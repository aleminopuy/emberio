package app.emberio.pebble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.common.model.PebbleDictionary
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * PebbleKit Classic: the broadcast intents the original Pebble app (with Rebble) and
 * Gadgetbridge use to pass AppMessages between a watchapp and its Android companion.
 *
 * Emberio only uses this when no PebbleKit 2 app is installed (see [isNeeded]). With Core's
 * Pebble app, which speaks PebbleKit 2, none of this runs: the receiver isn't registered and
 * nothing is broadcast. (Core's app would also stop to ask the user about Classic messages.)
 */
class ClassicPebbleKit(
    private val context: Context,
    private val onMessage: (PebbleDictionary) -> Unit,
    /** The watch refused a message we sent: the watchapp isn't open. */
    private val onNack: () -> Unit,
) {
    private val nextTransaction = AtomicInteger(0)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_RECEIVE -> receive(intent)
                ACTION_RECEIVE_NACK -> onNack()
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_RECEIVE)
            addAction(ACTION_RECEIVE_NACK)
        }
        // The Pebble app is another app, so the receiver has to be exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        Log.i(TAG, "listening for PebbleKit Classic messages")
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    val running: Boolean get() = registered

    private fun receive(intent: Intent) {
        val uuid = IntentCompat.getSerializableExtra(intent, EXTRA_UUID, UUID::class.java)
            ?: intent.getStringExtra(EXTRA_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uuid != WatchProtocol.WATCHAPP_UUID) return
        val transactionId = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1)
        val data = intent.getStringExtra(EXTRA_MSG_DATA)?.let {
            runCatching { ClassicJson.decode(it) }.getOrNull()
        }
        // Acknowledge, or the watch keeps retrying.
        context.sendBroadcast(
            Intent(if (data != null) ACTION_ACK else ACTION_NACK).putExtra(EXTRA_TRANSACTION_ID, transactionId),
        )
        if (data != null) onMessage(data)
    }

    fun send(data: PebbleDictionary) {
        val intent = Intent(ACTION_SEND)
            .putExtra(EXTRA_UUID, WatchProtocol.WATCHAPP_UUID)
            .putExtra(EXTRA_TRANSACTION_ID, nextTransaction.getAndIncrement() and 0xFF)
            .putExtra(EXTRA_MSG_DATA, ClassicJson.encode(data))
        context.sendBroadcast(intent)
    }

    fun startAppOnWatch() {
        context.sendBroadcast(Intent(ACTION_START).putExtra(EXTRA_UUID, WatchProtocol.WATCHAPP_UUID))
    }

    companion object {
        private const val TAG = "ClassicPebbleKit"

        const val ACTION_SEND = "com.getpebble.action.app.SEND"
        const val ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE"
        const val ACTION_ACK = "com.getpebble.action.app.ACK"
        const val ACTION_NACK = "com.getpebble.action.app.NACK"
        const val ACTION_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"
        const val ACTION_START = "com.getpebble.action.app.START"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_TRANSACTION_ID = "transaction_id"
        const val EXTRA_MSG_DATA = "msg_data"

        /**
         * Classic is only for phones without a PebbleKit 2 app (like Core's Pebble app). Where
         * one is installed, Emberio behaves exactly as it does without this class.
         */
        fun isNeeded(context: Context): Boolean =
            runCatching { DefaultPebbleAndroidAppPicker.getInstance(context).getAllEligibleApps().isEmpty() }
                .getOrDefault(false)
    }
}
