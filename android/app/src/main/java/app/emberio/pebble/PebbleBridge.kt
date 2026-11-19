package app.emberio.pebble

import android.content.Context
import android.util.Log
import app.emberio.MugRepository
import app.emberio.localeUnit
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Talks to the Emberio watchapp through PebbleKit 2. While the watchapp is open on a watch,
 * every change to the mug's state goes to it (AppMessages only reach the app that's open), and
 * commands from it (set temperature, settings) come back here.
 */
class PebbleBridge(
    private val context: Context,
    private val repository: MugRepository,
    private val scope: CoroutineScope,
) {
    private val _openOn = MutableStateFlow<Set<WatchIdentifier>>(emptySet())
    /** Watches the Emberio watchapp is open on right now. */
    val openOn: StateFlow<Set<WatchIdentifier>> = _openOn.asStateFlow()

    private var sender: PebbleSender? = null
    private val sendSignal = Channel<Unit>(Channel.CONFLATED)
    private var closeJob: Job? = null

    // Fallback for phones without a PebbleKit 2 app; see ClassicPebbleKit.
    private val classic = ClassicPebbleKit(
        context,
        onMessage = { data ->
            lastClassicMessageMs = System.currentTimeMillis()
            onMessage(data, CLASSIC_WATCH)
        },
        onNack = { onAppClosed(CLASSIC_WATCH) },
    )
    @Volatile private var lastClassicMessageMs = 0L
    private val _usingClassic = MutableStateFlow(false)
    /** True when talking to the watch through PebbleKit Classic (no PebbleKit 2 app installed). */
    val usingClassic: StateFlow<Boolean> = _usingClassic.asStateFlow()

    init {
        scope.launch {
            // Send on any change the watch would show.
            combine(repository.state.map { WatchProtocol.stateMessage(it, localeUnit()) }.distinctUntilChanged(), _openOn) { _, open -> open }
                .collect { open -> if (open.isNotEmpty()) sendSignal.trySend(Unit) }
        }
        scope.launch {
            for (signal in sendSignal) {
                sendState()
                // At most a few messages a second; the latest state wins.
                delay(SEND_SPACING_MS)
            }
        }
    }

    /**
     * Pick the link to the watch: PebbleKit 2 when a Pebble app supports it (Core's app), else
     * PebbleKit Classic. Called whenever Emberio starts talking to the mug.
     */
    fun refreshTransport() {
        val classicNeeded = ClassicPebbleKit.isNeeded(context)
        if (classicNeeded) {
            classic.start()
        } else {
            classic.stop()
            _openOn.update { it - CLASSIC_WATCH }
        }
        _usingClassic.value = classicNeeded
    }

    fun stopTransport() {
        classic.stop()
        _openOn.update { it - CLASSIC_WATCH }
    }

    fun onAppOpened(watch: WatchIdentifier) {
        closeJob?.cancel()
        _openOn.update { it + watch }
        sendSignal.trySend(Unit)
    }

    fun onAppClosed(watch: WatchIdentifier) {
        _openOn.update { it - watch }
        if (_openOn.value.isEmpty()) {
            closeJob?.cancel()
            closeJob = scope.launch {
                delay(30_000)
                if (_openOn.value.isEmpty()) {
                    sender?.close()
                    sender = null
                }
            }
        }
    }

    fun onMessage(data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watch !in _openOn.value) onAppOpened(watch)
        val command = WatchProtocol.parse(data) ?: return ReceiveResult.Nack
        Log.i(TAG, "watch: $command")
        when (command) {
            WatchProtocol.Command.Hello -> sendSignal.trySend(Unit)
            is WatchProtocol.Command.SetTarget -> repository.setTarget(command.celsius)
            WatchProtocol.Command.HeaterOn -> repository.heaterOn()
            is WatchProtocol.Command.SetSettings -> repository.updateSettings {
                it.copy(
                    lowBatteryAlert = command.lowBatteryAlert,
                    readyAlert = command.readyAlert,
                    unitPref = command.unitPref,
                )
            }
        }
        // Settings and units change what the watch shows; send it back either way.
        sendSignal.trySend(Unit)
        return ReceiveResult.Ack
    }

    /** Open the watchapp on every connected watch. */
    suspend fun openOnWatch(): Boolean {
        if (classic.running) {
            classic.startAppOnWatch()
            return true
        }
        val results = senderOrCreate().startAppOnTheWatch(WatchProtocol.WATCHAPP_UUID) ?: return false
        return results.values.any { it == TransmissionResult.Success }
    }

    private fun senderOrCreate(): PebbleSender =
        sender ?: DefaultPebbleSender(context).also { sender = it }

    private suspend fun sendState() {
        val open = _openOn.value
        if (open.isEmpty()) return
        val message = WatchProtocol.stateMessage(repository.state.value, localeUnit())

        if (CLASSIC_WATCH in open) {
            // Classic has no "app closed" event; a refused message (NACK) or a long silence ends it.
            if (!classic.running || System.currentTimeMillis() - lastClassicMessageMs > CLASSIC_IDLE_MS) {
                _openOn.update { it - CLASSIC_WATCH }
            } else {
                classic.send(message)
            }
        }

        val watches = open.filter { it != CLASSIC_WATCH }
        if (watches.isEmpty()) return
        val results = try {
            senderOrCreate().sendDataToPebble(WatchProtocol.WATCHAPP_UUID, message, watches)
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            null
        } ?: return
        for ((watch, result) in results) {
            when (result) {
                TransmissionResult.Success -> Unit
                TransmissionResult.FailedDifferentAppOpen, TransmissionResult.FailedWatchNotConnected ->
                    _openOn.update { it - watch }
                else -> Log.w(TAG, "send to watch: $result")
            }
        }
    }

    companion object {
        private const val TAG = "PebbleBridge"
        private const val SEND_SPACING_MS = 300L
        private val CLASSIC_WATCH = WatchIdentifier("pebblekit-classic")
        private const val CLASSIC_IDLE_MS = 60 * 60_000L
    }
}
