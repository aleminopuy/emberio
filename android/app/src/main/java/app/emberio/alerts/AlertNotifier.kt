package app.emberio.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.emberio.Permissions
import app.emberio.R
import app.emberio.TempFormat
import app.emberio.ember.TempUnit
import app.emberio.ui.MainActivity

/**
 * Posts mug alerts as ordinary, non-ongoing notifications. The Pebble app forwards those to the
 * watch (it skips ongoing ones like our status notification), so they buzz the wrist the way
 * any Pebble notification does.
 */
class AlertNotifier(private val context: Context) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Mug alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Drink ready and low battery. Your Pebble shows these."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Mug connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Emberio stays connected to your mug."
                setShowBadge(false)
            },
        )
    }

    fun show(alert: Alert, mugName: String, unit: TempUnit) {
        if (!Permissions.canNotify(context)) return
        val (id, title, text) = when (alert) {
            is Alert.DrinkReady -> Triple(
                ID_READY,
                "Your drink is ready",
                "$mugName is at ${TempFormat.format(alert.targetC, unit)}.",
            )
            is Alert.LowBattery -> Triple(
                ID_BATTERY,
                "Mug battery at ${alert.percent}%",
                buildString {
                    alert.minutesLeft?.let { append("About ${TempFormat.duration(it)} of heat left. ") }
                    append("Put $mugName on its coaster.")
                },
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications turned off since we checked.
        }
    }

    fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_STATUS = "status"
        const val ID_STATUS = 1
        const val ID_READY = 1001
        const val ID_BATTERY = 1002
    }
}
