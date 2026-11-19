package app.emberio

import app.emberio.battery.BatteryEstimator
import app.emberio.ember.MugModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEstimatorTest {
    private val minute = 60_000L

    @Test fun `before seeing the battery move it uses the rated battery life`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ) // 90 min from full
        assertEquals(45, e.update(0, 50, isCharging = false, active = true))
        assertEquals(90, e.update(0, 100, isCharging = true, active = true).let { e.update(1, 100, false, true) })
    }

    @Test fun `a learned rate replaces the rated one`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ, learnedDrainPerMin = 2.0)
        assertEquals(30, e.update(0, 60, isCharging = false, active = true))
    }

    @Test fun `a faster drain than rated pulls the estimate down`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ)
        var t = 0L
        var pct = 80
        var estimate = e.update(t, pct, false, true)
        assertEquals(72, estimate) // 80% at 90 min per 100%
        // 2% a minute for 20 minutes (twice the rated drain).
        repeat(40) {
            t += minute / 2
            pct -= 1
            estimate = e.update(t, pct, false, true)
        }
        // 40% left: rated would say 36 min, observed 20; blended in between, closer to observed.
        assertTrue("estimate $estimate", estimate in 20..30)
    }

    @Test fun `time in standby doesn't count as drain`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ)
        var t = 0L
        e.update(t, 60, false, true)
        // A step down starts the clock...
        t += minute
        e.update(t, 59, false, true)
        // ...then the mug sits empty for an hour without dropping.
        repeat(60) {
            t += minute
            e.update(t, 59, false, false)
        }
        // Still the rated estimate (less the one active minute), not inflated by the idle hour.
        assertEquals(52, e.update(t, 59, false, false))
    }

    @Test fun `it counts down between percent steps`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ)
        e.update(0, 50, false, true)
        e.update(minute, 49, false, true)
        val atStep = e.update(minute, 49, false, true)
        val later = e.update(minute + 2 * minute, 49, false, true)
        assertTrue("$later < $atStep", later < atStep)
    }

    @Test fun `on the coaster it estimates time to full`() {
        val e = BatteryEstimator(MugModel.MUG_2_10OZ) // 120 min to charge fully
        assertEquals(72, e.update(0, 40, isCharging = true, active = false))
        assertEquals(0, e.update(minute, 100, isCharging = true, active = false))
    }

    @Test fun `a long session teaches the drain rate`() {
        var learned: Double? = null
        val e = BatteryEstimator(MugModel.MUG_2_10OZ, onLearned = { drain, _ -> learned = drain })
        var t = 0L
        var pct = 90
        e.update(t, pct, false, true)
        repeat(20) {
            t += minute
            pct -= 1
            e.update(t, pct, false, true)
        }
        assertNull(learned)
        // Putting it on the coaster ends the session.
        e.update(t + minute, pct, true, true)
        assertNotNull(learned)
        assertEquals(1.0, learned!!, 0.1)
    }

    @Test fun `implausible rates are not learned`() {
        var learned: Double? = null
        val e = BatteryEstimator(MugModel.MUG_2_10OZ, onLearned = { drain, _ -> learned = drain })
        var t = 0L
        e.update(t, 90, false, true)
        // 50% in 5 minutes: too short a session to learn from (and an implausible rate).
        for (pct in 89 downTo 40) {
            t += 6_000
            e.update(t, pct, false, true)
        }
        e.update(t, 40, true, true)
        assertNull(learned)
    }
}
