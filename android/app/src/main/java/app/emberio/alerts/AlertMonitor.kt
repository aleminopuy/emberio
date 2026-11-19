package app.emberio.alerts

import app.emberio.MugReadings
import app.emberio.ember.LiquidState
import kotlin.math.abs

sealed class Alert {
    data class LowBattery(val percent: Int, val minutesLeft: Int?) : Alert()
    data class DrinkReady(val targetC: Double) : Alert()
}

/**
 * Decides when to notify, from the stream of readings. Pure logic, so it can be tested;
 * [app.emberio.alerts.AlertNotifier] turns the alerts into notifications, which the Pebble
 * app forwards to the watch.
 *
 * - Low battery: once when the battery drops to the threshold while off the coaster. Re-arms
 *   after charging, or once the battery is a few percent above the threshold again.
 * - Drink ready: when the mug reaches its target after working towards it (heating or
 *   cooling from at least [READY_ARM_DELTA_C] away, or a fresh pour). Hovering around the
 *   target doesn't re-arm it, so it won't nag.
 */
class AlertMonitor {
    private var batteryArmed: Boolean? = null
    private var readyArmed = false
    private var lastState: LiquidState? = null

    fun reset() {
        batteryArmed = null
        readyArmed = false
        lastState = null
    }

    fun update(
        readings: MugReadings,
        minutesLeft: Int?,
        lowBatteryThreshold: Int,
        readyAlert: Boolean,
    ): List<Alert> {
        val alerts = mutableListOf<Alert>()
        checkBattery(readings, minutesLeft, lowBatteryThreshold)?.let(alerts::add)
        checkReady(readings, readyAlert)?.let(alerts::add)
        return alerts
    }

    private fun checkBattery(readings: MugReadings, minutesLeft: Int?, threshold: Int): Alert? {
        val percent = readings.batteryPercent ?: return null
        val charging = readings.charging ?: return null
        if (threshold <= 0) {
            batteryArmed = null
            return null
        }
        val armed = batteryArmed
        if (armed == null) {
            // First reading: only alert on a crossing we see, not on connecting to a low mug.
            batteryArmed = charging || percent > threshold
            return null
        }
        if (charging || percent >= threshold + REARM_MARGIN) {
            batteryArmed = true
            return null
        }
        if (armed && percent <= threshold) {
            batteryArmed = false
            return Alert.LowBattery(percent, minutesLeft)
        }
        return null
    }

    private fun checkReady(readings: MugReadings, enabled: Boolean): Alert? {
        val state = readings.liquidState ?: return null
        val previous = lastState
        lastState = state
        val target = readings.targetTempC
        val current = readings.currentTempC

        when (state) {
            LiquidState.FILLING -> readyArmed = true
            LiquidState.EMPTY, LiquidState.STANDBY -> readyArmed = false
            LiquidState.HEATING, LiquidState.COOLING -> {
                if (target != null && target > 0 && current != null && abs(current - target) >= READY_ARM_DELTA_C) {
                    readyArmed = true
                }
            }
            LiquidState.PERFECT -> {
                if (previous != null && previous != LiquidState.PERFECT && readyArmed) {
                    readyArmed = false
                    if (enabled && target != null && target > 0) return Alert.DrinkReady(target)
                }
            }
            else -> Unit
        }
        return null
    }

    companion object {
        const val REARM_MARGIN = 3
        const val READY_ARM_DELTA_C = 1.5
    }
}
