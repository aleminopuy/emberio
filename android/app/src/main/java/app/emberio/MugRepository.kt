package app.emberio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import app.emberio.alerts.AlertMonitor
import app.emberio.alerts.AlertNotifier
import app.emberio.battery.BatteryEstimator
import app.emberio.ember.EmberMugClient
import app.emberio.ember.FoundMug
import app.emberio.ember.MugModel
import app.emberio.pebble.PebbleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The hub: owns the connection to the mug, works out battery time left and alerts, and
 * publishes one [AppState] that the UI, the watch and the watchface plugin all read.
 */
class MugRepository(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore(context)
    val notifier = AlertNotifier(context)
    private val client = EmberMugClient(context, scope)
    private val alerts = AlertMonitor()
    private var estimator: BatteryEstimator? = null
    private var estimatorMug: String? = null

    private val bluetoothOn = MutableStateFlow(isBluetoothOn())
    private val canConnect = MutableStateFlow(Permissions.canConnect(context))
    private val minutesLeft = MutableStateFlow<Int?>(null)

    val state: StateFlow<AppState> = combine(
        client.state,
        settingsStore.settings,
        bluetoothOn,
        canConnect,
        minutesLeft,
    ) { client, settings, btOn, permitted, minutes ->
        val link = when {
            settings.mugAddress == null -> LinkStatus.NO_MUG
            !permitted -> LinkStatus.NEEDS_SETUP
            !btOn -> LinkStatus.BLUETOOTH_OFF
            client.link == EmberMugClient.Link.CONNECTED -> LinkStatus.CONNECTED
            else -> LinkStatus.SEARCHING
        }
        AppState(
            link = link,
            readings = client.readings,
            minutesLeft = minutes,
            settings = settings,
            updatedAtMs = client.lastReadingMs,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppState(settings = settingsStore.settings.value))

    val writeFailed: Boolean get() = client.state.value.writeFailed

    val bridge = PebbleBridge(context, this, scope)

    private var started = false
    private var tickJob: Job? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            bluetoothOn.value = on
            if (on) connectIfPaired(reconnect = true)
        }
    }

    init {
        scope.launch {
            client.state.collect { onClientState(it) }
        }
    }

    /** Start talking to the mug. Called by [app.emberio.service.MugService]. */
    @Synchronized
    fun start() {
        refreshPermissions()
        bridge.refreshTransport()
        if (!started) {
            started = true
            ContextCompat.registerReceiver(
                context,
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Re-run the estimate every minute so "minutes left" counts down between readings.
            tickJob = scope.launch {
                while (true) {
                    delay(60_000)
                    onClientState(client.state.value)
                }
            }
        }
        connectIfPaired()
    }

    @Synchronized
    fun stop() {
        if (started) {
            started = false
            runCatching { context.unregisterReceiver(bluetoothReceiver) }
            tickJob?.cancel()
        }
        estimator?.reset()
        client.disconnect()
        bridge.stopTransport()
    }

    fun refreshPermissions() {
        canConnect.value = Permissions.canConnect(context)
        bluetoothOn.value = isBluetoothOn()
    }

    private fun isBluetoothOn(): Boolean =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
            .getOrDefault(false)

    private fun connectIfPaired(reconnect: Boolean = false) {
        val s = settingsStore.settings.value
        val address = s.mugAddress ?: return
        if (!Permissions.canConnect(context) || !isBluetoothOn()) return
        if (reconnect) client.reconnect() else client.connect(address, s.mugModel)
    }

    // -----------------------------------------------------------------------------------------
    // Readings -> estimate -> alerts

    @Synchronized
    private fun onClientState(client: EmberMugClient.State) {
        val settings = settingsStore.settings.value
        val r = client.readings
        if (client.link != EmberMugClient.Link.CONNECTED) {
            estimator?.reset()
            alerts.reset()
            minutesLeft.value = null
            return
        }
        val est = estimatorFor(settings)
        val percent = r.batteryPercent
        val charging = r.charging
        if (percent != null && charging != null) {
            val active = r.liquidState?.active == true && !r.heaterOff
            minutesLeft.value = est.update(System.currentTimeMillis(), percent, charging, active)
        }
        val fired = alerts.update(r, minutesLeft.value, settings.lowBatteryAlert, settings.readyAlert)
        if (fired.isNotEmpty()) {
            val current = state.value
            val unit = current.displayUnit()
            fired.forEach {
                Log.i(TAG, "alert: $it")
                notifier.show(it, current.displayName, unit)
            }
        }
    }

    private fun estimatorFor(settings: Settings): BatteryEstimator {
        val existing = estimator
        if (existing != null && estimatorMug == settings.mugAddress) return existing
        return BatteryEstimator(
            model = settings.mugModel,
            learnedDrainPerMin = settings.learnedDrainPerMin,
            learnedChargePerMin = settings.learnedChargePerMin,
            onLearned = { drain, charge ->
                settingsStore.update { it.copy(learnedDrainPerMin = drain, learnedChargePerMin = charge) }
            },
        ).also {
            estimator = it
            estimatorMug = settings.mugAddress
        }
    }

    // -----------------------------------------------------------------------------------------
    // Commands, from the UI and the watch

    /** Set the target in °C; 0 turns the heater off. */
    fun setTarget(celsius: Double) {
        if (celsius > 0) settingsStore.update { it.copy(lastTargetC = celsius) }
        scope.launch { client.setTarget(celsius) }
    }

    fun heaterOn() = setTarget(settingsStore.settings.value.lastTargetC)

    fun heaterOff() = setTarget(0.0)

    fun updateSettings(transform: (Settings) -> Settings) = settingsStore.update(transform)

    fun pair(mug: FoundMug) {
        settingsStore.update {
            it.copy(
                mugAddress = mug.address,
                mugName = null,
                mugModel = mug.model,
                learnedDrainPerMin = null,
                learnedChargePerMin = null,
            )
        }
        estimator = null
        alerts.reset()
        client.connect(mug.address, mug.model)
    }

    fun forget() {
        client.disconnect()
        estimator = null
        alerts.reset()
        settingsStore.update {
            it.copy(mugAddress = null, mugName = null, mugModel = MugModel.UNKNOWN, learnedDrainPerMin = null, learnedChargePerMin = null)
        }
    }

    companion object {
        private const val TAG = "MugRepository"
    }
}
