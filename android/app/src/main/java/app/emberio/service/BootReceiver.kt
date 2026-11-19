package app.emberio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnect to the mug after the phone restarts or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> MugService.startIfReady(context)
        }
    }
}
