package app.emberio.ember

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import app.emberio.MugReadings
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps a Bluetooth LE connection to one Ember mug and mirrors its characteristics into
 * [state]. Android allows one GATT operation at a time, so every read and write goes through
 * [gattOp], one after another.
 *
 * The mug sends a "push event" when something changes (temperature, battery, state...); we
 * read the matching characteristic in response, and also poll the temperature now and then.
 * When the mug goes to sleep or out of range we keep an auto-connect request open, so Android
 * reconnects as soon as it wakes up.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before connect().
class EmberMugClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    enum class Link { IDLE, CONNECTING, CONNECTED }

    data class State(
        val link: Link = Link.IDLE,
        val readings: MugReadings = MugReadings(),
        val lastReadingMs: Long = 0,
        /** The mug refused a write (it may need setting up in the Ember app once). */
        val writeFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var address: String? = null
    @Volatile private var model: MugModel = MugModel.UNKNOWN
    @Volatile private var wanted = false
    private var attempts = 0

    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<GattResult>? = null
    @Volatile private var servicesDiscovered: CompletableDeferred<Int>? = null

    private val dirty: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private var sessionJob: Job? = null

    private class GattResult(val status: Int, val value: ByteArray?)

    fun connect(address: String, model: MugModel) {
        if (wanted && this.address == address && gatt != null) return
        disconnect()
        this.address = address
        this.model = model
        wanted = true
        attempts = 0
        open(autoConnect = false)
    }

    fun disconnect() {
        wanted = false
        sessionJob?.cancel()
        sessionJob = null
        closeGatt()
        _state.value = State()
    }

    /** Re-open the connection, e.g. when Bluetooth comes back on. */
    fun reconnect() {
        val a = address ?: return
        if (!wanted) return
        closeGatt()
        attempts = 0
        this.address = a
        open(autoConnect = false)
    }

    private fun open(autoConnect: Boolean) {
        val a = address ?: return
        val device = try {
            adapter?.getRemoteDevice(a)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        _state.update { it.copy(link = Link.CONNECTING) }
        Log.i(TAG, "connecting to mug (autoConnect=$autoConnect)")
        gatt = try {
            device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "no Bluetooth permission", e)
            null
        }
        if (!autoConnect) {
            // A direct connect gives up after ~30 s; fall back to waiting for the mug to show up.
            val thisGatt = gatt
            scope.launch {
                delay(DIRECT_CONNECT_TIMEOUT_MS)
                if (wanted && gatt === thisGatt && _state.value.link != Link.CONNECTED) {
                    closeGatt()
                    open(autoConnect = true)
                }
            }
        }
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        pending?.cancel()
        servicesDiscovered?.cancel()
        try {
            g?.disconnect()
            g?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close failed", e)
        }
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        val backoff = RECONNECT_BACKOFF_MS[attempts.coerceAtMost(RECONNECT_BACKOFF_MS.size - 1)]
        attempts++
        scope.launch {
            delay(backoff)
            if (wanted && gatt == null) open(autoConnect = true)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Session: runs while connected

    private fun startSession(g: BluetoothGatt) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            servicesDiscovered = CompletableDeferred()
            if (!g.discoverServices()) {
                Log.w(TAG, "discoverServices refused")
                g.disconnect()
                return@launch
            }
            val discovered = withTimeoutOrNull(OP_TIMEOUT_MS * 2) { servicesDiscovered?.await() }
            if (discovered != BluetoothGatt.GATT_SUCCESS || service(g) == null) {
                Log.w(TAG, "Ember service not found (status $discovered)")
                g.disconnect()
                return@launch
            }

            ensureWritable()
            readAll()
            enableNotifications(g)
            attempts = 0
            _state.update { it.copy(link = Link.CONNECTED) }
            Log.i(TAG, "mug ready: ${_state.value.readings}")

            launch { refreshLoop() }
            var tick = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                tick++
                markDirty(EmberProtocol.CURRENT_TEMP)
                if (tick % 4 == 0) markDirty(EmberProtocol.BATTERY, EmberProtocol.LIQUID_STATE, EmberProtocol.TARGET_TEMP)
            }
        }
    }

    /**
     * A mug that was never set up in the Ember app (or was reset) has an all-zero UDSK and
     * refuses writes until it gets one. Only fill in an empty one, so we never replace the key
     * the Ember app set, unless [force]d because the mug is refusing writes anyway.
     */
    private suspend fun ensureWritable(force: Boolean = false) {
        val udsk = read(EmberProtocol.UDSK)
        val empty = udsk != null && EmberProtocol.udskIsEmpty(udsk)
        if (empty || (force && udsk == null)) {
            Log.i(TAG, "setting the mug's UDSK so it accepts writes")
            write(EmberProtocol.UDSK, EmberProtocol.newUdsk())
        }
    }

    private suspend fun readAll() {
        for (uuid in listOf(
            EmberProtocol.NAME,
            EmberProtocol.TEMP_UNIT,
            EmberProtocol.TARGET_TEMP,
            EmberProtocol.CURRENT_TEMP,
            EmberProtocol.BATTERY,
            EmberProtocol.LIQUID_STATE,
            EmberProtocol.LIQUID_LEVEL,
            EmberProtocol.FIRMWARE,
        )) {
            refresh(uuid)
        }
    }

    private suspend fun refresh(uuid: UUID) {
        val bytes = read(uuid) ?: return
        val now = System.currentTimeMillis()
        _state.update { s ->
            val r = s.readings
            val next = when (uuid) {
                EmberProtocol.NAME -> r.copy(name = EmberProtocol.decodeName(bytes).ifBlank { null })
                EmberProtocol.TEMP_UNIT -> r.copy(mugUnit = EmberProtocol.decodeUnit(bytes) ?: r.mugUnit)
                EmberProtocol.TARGET_TEMP -> r.copy(targetTempC = EmberProtocol.decodeTemp(bytes))
                EmberProtocol.CURRENT_TEMP -> r.copy(currentTempC = EmberProtocol.decodeTemp(bytes))
                EmberProtocol.BATTERY -> EmberProtocol.decodeBattery(bytes)
                    ?.let { r.copy(batteryPercent = it.percent, charging = it.charging) } ?: r
                EmberProtocol.LIQUID_STATE -> r.copy(liquidState = EmberProtocol.decodeLiquidState(bytes) ?: r.liquidState)
                EmberProtocol.LIQUID_LEVEL -> r.copy(liquidLevel = EmberProtocol.decodeLiquidLevel(bytes, model))
                EmberProtocol.FIRMWARE -> r.copy(firmware = EmberProtocol.decodeFirmware(bytes))
                else -> r
            }
            s.copy(readings = next, lastReadingMs = now)
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt) {
        val c = characteristic(EmberProtocol.PUSH_EVENT) ?: return
        try {
            g.setCharacteristicNotification(c, true)
        } catch (e: SecurityException) {
            return
        }
        val d = c.getDescriptor(EmberProtocol.CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gattOp { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(d)
            }
        }
    }

    private fun markDirty(vararg uuids: UUID) {
        dirty.addAll(uuids)
        refreshSignal.trySend(Unit)
    }

    private suspend fun refreshLoop() {
        for (signal in refreshSignal) {
            // Let a burst of push events settle into one round of reads.
            delay(250)
            val batch = dirty.toList()
            dirty.removeAll(batch.toSet())
            for (uuid in batch) refresh(uuid)
        }
    }

    private fun onPushEvent(event: Int) {
        when (event) {
            EmberProtocol.PushEvent.BATTERY_CHANGED -> markDirty(EmberProtocol.BATTERY)
            EmberProtocol.PushEvent.CHARGER_CONNECTED, EmberProtocol.PushEvent.CHARGER_DISCONNECTED -> {
                val charging = event == EmberProtocol.PushEvent.CHARGER_CONNECTED
                _state.update { it.copy(readings = it.readings.copy(charging = charging)) }
                markDirty(EmberProtocol.BATTERY)
            }
            EmberProtocol.PushEvent.TARGET_TEMP_CHANGED -> markDirty(EmberProtocol.TARGET_TEMP)
            EmberProtocol.PushEvent.DRINK_TEMP_CHANGED -> markDirty(EmberProtocol.CURRENT_TEMP)
            EmberProtocol.PushEvent.LIQUID_LEVEL_CHANGED -> markDirty(EmberProtocol.LIQUID_LEVEL)
            EmberProtocol.PushEvent.LIQUID_STATE_CHANGED -> markDirty(EmberProtocol.LIQUID_STATE, EmberProtocol.CURRENT_TEMP)
            EmberProtocol.PushEvent.AUTH_INFO_NOT_FOUND -> Log.w(TAG, "mug says auth info not found")
            else -> Unit
        }
    }

    // -----------------------------------------------------------------------------------------
    // Commands

    /** Set the target in °C; 0 turns the heater off. Returns false if the mug refused. */
    suspend fun setTarget(celsius: Double): Boolean {
        val target = EmberProtocol.clampTarget(celsius)
        var ok = write(EmberProtocol.TARGET_TEMP, EmberProtocol.encodeTemp(target))
        if (!ok) {
            // Maybe it has no UDSK (or we couldn't read it): try making it writable once more.
            ensureWritable(force = true)
            ok = write(EmberProtocol.TARGET_TEMP, EmberProtocol.encodeTemp(target))
        }
        _state.update { it.copy(writeFailed = !ok) }
        if (ok) {
            _state.update { it.copy(readings = it.readings.copy(targetTempC = target)) }
            markDirty(EmberProtocol.TARGET_TEMP, EmberProtocol.LIQUID_STATE)
        }
        return ok
    }

    // -----------------------------------------------------------------------------------------
    // GATT plumbing

    private fun service(g: BluetoothGatt? = gatt) =
        EmberProtocol.SERVICES.firstNotNullOfOrNull { g?.getService(it) }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? = service()?.getCharacteristic(uuid)

    private suspend fun gattOp(start: (BluetoothGatt) -> Boolean): GattResult? = opMutex.withLock {
        val g = gatt ?: return@withLock null
        val deferred = CompletableDeferred<GattResult>()
        pending = deferred
        val started = try {
            start(g)
        } catch (e: SecurityException) {
            false
        }
        if (!started) {
            pending = null
            return@withLock null
        }
        val result = withTimeoutOrNull(OP_TIMEOUT_MS) {
            runCatching { deferred.await() }.getOrNull()
        }
        pending = null
        result
    }

    private suspend fun read(uuid: UUID): ByteArray? {
        val c = characteristic(uuid) ?: return null
        var result = gattOp { it.readCharacteristic(c) } ?: return null
        if (result.status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            result.status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
        ) {
            // Android pairs with the mug on its own when it asks for this; try again after.
            Log.i(TAG, "mug wants pairing for $uuid; retrying")
            delay(AUTH_RETRY_DELAY_MS)
            result = gattOp { it.readCharacteristic(c) } ?: return null
        }
        return if (result.status == BluetoothGatt.GATT_SUCCESS) result.value else null
    }

    private suspend fun write(uuid: UUID, value: ByteArray): Boolean {
        val c = characteristic(uuid) ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val result = gattOp { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        return result?.status == BluetoothGatt.GATT_SUCCESS
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // A fast callback can beat connectGatt() returning; closed handles never call back.
            if (gatt == null && wanted) gatt = g
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "connected")
                startSession(g)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "disconnected (status $status)")
                sessionJob?.cancel()
                sessionJob = null
                closeGatt()
                _state.update { it.copy(link = if (wanted) Link.CONNECTING else Link.IDLE) }
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered?.complete(status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            pending?.complete(GattResult(status, value))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pending?.complete(GattResult(status, characteristic.value))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(GattResult(status, null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(GattResult(status, null))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == EmberProtocol.PUSH_EVENT) {
                EmberProtocol.u8(value)?.let(::onPushEvent)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == EmberProtocol.PUSH_EVENT) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { EmberProtocol.u8(it) }?.let(::onPushEvent)
            }
        }
    }

    companion object {
        private const val TAG = "EmberMugClient"
        private const val OP_TIMEOUT_MS = 5_000L
        private const val AUTH_RETRY_DELAY_MS = 4_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 15_000L
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 3_000, 10_000, 30_000)
    }
}
