package app.emberio.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.emberio.AppState
import app.emberio.EmberioApp
import app.emberio.LinkStatus
import app.emberio.Permissions
import app.emberio.R
import app.emberio.TempFormat
import app.emberio.alerts.AlertNotifier
import app.emberio.localeUnit
import app.emberio.plugin.LocalStateServer
import app.emberio.plugin.StateJson
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Emberio connected to the mug in the background, so alerts fire and the watch and
 * watchface plugin have fresh readings. Android requires a visible notification for that; it's
 * a quiet one, and the Pebble app doesn't forward it.
 */
class MugService : LifecycleService() {
    private val repository get() = (application as EmberioApp).repository
    private var server: LocalStateServer? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, AlertNotifier.ID_STATUS, statusNotification(repository.state.value), type)
        } catch (e: Exception) {
            // Missing Bluetooth permission (Android 14 checks it for this service type).
            Log.w(TAG, "can't run in the foreground: ${e.message}")
            stopSelf()
            return
        }
        repository.start()
        server = LocalStateServer { StateJson.build(repository.state.value, localeUnit()) }.also { it.start() }

        lifecycleScope.launch {
            repository.state
                .map { statusText(it) }
                .distinctUntilChanged()
                .collect {
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(AlertNotifier.ID_STATUS, statusNotification(repository.state.value))
                }
        }
        lifecycleScope.launch {
            // Nothing to stay connected to: stop.
            repository.state.map { it.link == LinkStatus.NO_MUG }.distinctUntilChanged().collect { noMug ->
                if (noMug) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        repository.start()
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        repository.stop()
        super.onDestroy()
    }

    private fun statusText(state: AppState): Pair<String, String> {
        val name = state.displayName
        val r = state.readings
        return when (state.link) {
            LinkStatus.CONNECTED -> {
                val unit = state.displayUnit()
                val temp = r.currentTempC?.let { TempFormat.format(it, unit) } ?: "--"
                val title = "$name · $temp" + (r.liquidState?.let { " · ${it.label}" } ?: "")
                val battery = r.batteryPercent?.let { pct ->
                    val detail = state.minutesLeft?.let { m ->
                        if (r.charging == true) "full in ${TempFormat.duration(m)}" else "${TempFormat.duration(m)} left"
                    }
                    "Battery $pct%" + (detail?.let { ", $it" } ?: "")
                } ?: "Connected"
                title to battery
            }
            LinkStatus.SEARCHING -> name to "Looking for your mug. Pick it up to wake it."
            LinkStatus.BLUETOOTH_OFF -> name to "Bluetooth is off"
            LinkStatus.NEEDS_SETUP -> name to "Open Emberio to finish setting up"
            LinkStatus.NO_MUG -> "Emberio" to "No mug paired"
        }
    }

    private fun statusNotification(state: AppState) = statusText(state).let { (title, text) ->
        NotificationCompat.Builder(this, AlertNotifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(repository.notifier.openApp())
            .build()
    }

    companion object {
        private const val TAG = "MugService"

        /** Start if there's a mug to connect to and we're allowed to; safe to call any time. */
        fun startIfReady(context: Context) {
            val repository = (context.applicationContext as EmberioApp).repository
            if (repository.settingsStore.settings.value.mugAddress == null) return
            if (!Permissions.canConnect(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, MugService::class.java))
            } catch (e: IllegalStateException) {
                // Android 12+ can refuse from the background. While the Pebble app is bound to us
                // our process is alive anyway, so connect without the service for now.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "not allowed to start from the background; connecting directly")
                    repository.start()
                } else {
                    throw e
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MugService::class.java))
        }
    }
}
