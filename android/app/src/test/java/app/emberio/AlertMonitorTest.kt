package app.emberio

import app.emberio.alerts.Alert
import app.emberio.alerts.AlertMonitor
import app.emberio.ember.LiquidState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMonitorTest {
    private fun readings(
        battery: Int = 80,
        charging: Boolean = false,
        state: LiquidState = LiquidState.PERFECT,
        current: Double = 57.0,
        target: Double = 57.0,
    ) = MugReadings(
        batteryPercent = battery,
        charging = charging,
        liquidState = state,
        currentTempC = current,
        targetTempC = target,
    )

    @Test fun `low battery alerts once when crossing the threshold`() {
        val m = AlertMonitor()
        assertTrue(m.update(readings(battery = 25), 30, 20, true).isEmpty())
        assertTrue(m.update(readings(battery = 21), 25, 20, true).isEmpty())
        assertEquals(listOf(Alert.LowBattery(20, 22)), m.update(readings(battery = 20), 22, 20, true))
        assertTrue(m.update(readings(battery = 19), 20, 20, true).isEmpty())
        assertTrue(m.update(readings(battery = 12), 12, 20, true).isEmpty())
    }

    @Test fun `connecting to an already-low mug doesn't alert, but charging re-arms`() {
        val m = AlertMonitor()
        assertTrue(m.update(readings(battery = 15), 15, 20, true).isEmpty())
        assertTrue(m.update(readings(battery = 14), 14, 20, true).isEmpty())
        m.update(readings(battery = 14, charging = true), 30, 20, true)
        m.update(readings(battery = 30, charging = true), 20, 20, true)
        assertTrue(m.update(readings(battery = 30), 25, 20, true).isEmpty())
        assertEquals(1, m.update(readings(battery = 20), 18, 20, true).size)
    }

    @Test fun `a threshold of 0 turns the battery alert off`() {
        val m = AlertMonitor()
        m.update(readings(battery = 50), 40, 0, true)
        assertTrue(m.update(readings(battery = 5), 4, 0, true).isEmpty())
    }

    @Test fun `drink ready fires when heating reaches the target`() {
        val m = AlertMonitor()
        m.update(readings(state = LiquidState.HEATING, current = 50.0), null, 20, true)
        m.update(readings(state = LiquidState.HEATING, current = 55.0), null, 20, true)
        assertEquals(listOf(Alert.DrinkReady(57.0)), m.update(readings(state = LiquidState.PERFECT, current = 57.0), null, 20, true))
        // Staying perfect doesn't repeat it.
        assertTrue(m.update(readings(state = LiquidState.PERFECT), null, 20, true).isEmpty())
    }

    @Test fun `cooling down from too hot also counts`() {
        val m = AlertMonitor()
        m.update(readings(state = LiquidState.COOLING, current = 70.0), null, 20, true)
        assertEquals(1, m.update(readings(state = LiquidState.PERFECT), null, 20, true).size)
    }

    @Test fun `hovering around the target doesn't nag`() {
        val m = AlertMonitor()
        m.update(readings(state = LiquidState.HEATING, current = 50.0), null, 20, true)
        assertEquals(1, m.update(readings(state = LiquidState.PERFECT), null, 20, true).size)
        m.update(readings(state = LiquidState.HEATING, current = 56.6), null, 20, true)
        assertTrue(m.update(readings(state = LiquidState.PERFECT), null, 20, true).isEmpty())
    }

    @Test fun `a fresh pour re-arms it`() {
        val m = AlertMonitor()
        m.update(readings(state = LiquidState.HEATING, current = 50.0), null, 20, true)
        m.update(readings(state = LiquidState.PERFECT), null, 20, true)
        m.update(readings(state = LiquidState.EMPTY), null, 20, true)
        m.update(readings(state = LiquidState.FILLING), null, 20, true)
        assertEquals(1, m.update(readings(state = LiquidState.PERFECT), null, 20, true).size)
    }

    @Test fun `connecting to a mug already at temperature doesn't alert`() {
        val m = AlertMonitor()
        assertTrue(m.update(readings(state = LiquidState.PERFECT), null, 20, true).isEmpty())
    }

    @Test fun `drink ready can be turned off`() {
        val m = AlertMonitor()
        m.update(readings(state = LiquidState.HEATING, current = 50.0), null, 20, false)
        assertTrue(m.update(readings(state = LiquidState.PERFECT), null, 20, false).isEmpty())
    }
}
