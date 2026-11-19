package app.emberio.battery

import app.emberio.ember.MugModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the mug's battery percentage into "minutes of heat left" (or, on the coaster,
 * "minutes to full"). Ember doesn't report this, so we work it out:
 *
 * - Before we've seen the battery move, assume Ember's rated battery life for the model
 *   (or the rate learned from earlier sessions with this mug).
 * - Once the percentage starts dropping, time how fast, counting only time the mug was
 *   actually keeping a drink hot (an empty mug in standby barely drains), and blend that in,
 *   trusting it more the longer we've watched.
 * - Between 1% steps, count down smoothly instead of jumping.
 * - At the end of a long enough session, remember the rate for next time.
 */
class BatteryEstimator(
    private val model: MugModel,
    learnedDrainPerMin: Double? = null,
    learnedChargePerMin: Double? = null,
    private val onLearned: (drainPerMin: Double?, chargePerMin: Double?) -> Unit = { _, _ -> },
) {
    private var learnedDrain = learnedDrainPerMin?.takeIf { it in DRAIN_RANGE }
    private var learnedCharge = learnedChargePerMin?.takeIf { it in CHARGE_RANGE }

    private var charging: Boolean? = null
    private var lastTimeMs = 0L
    private var lastPercent = -1
    private var lastActive = false

    // Timing since the first step we saw in this session.
    private var anchorPercent = -1
    private var timedMs = 0L
    private var sinceStepMs = 0L

    /** The drain rate (%/min) assumed before observing anything. */
    val priorDrainPerMin: Double get() = learnedDrain ?: (100.0 / model.ratedHeatMinutes)
    val priorChargePerMin: Double get() = learnedCharge ?: (100.0 / model.ratedChargeMinutes)

    /**
     * Feed a battery reading.
     *
     * @param active whether the mug is holding a drink and keeping it hot
     * @return minutes of heat left (discharging) or to full (charging)
     */
    fun update(nowMs: Long, percent: Int, isCharging: Boolean, active: Boolean): Int {
        val pct = percent.coerceIn(0, 100)
        if (charging != isCharging || lastPercent < 0) {
            finishSession()
            charging = isCharging
            lastTimeMs = nowMs
            lastPercent = pct
            lastActive = active
            anchorPercent = -1
            timedMs = 0
            sinceStepMs = 0
            return estimate(pct, isCharging)
        }

        val dt = max(0L, nowMs - lastTimeMs)
        val counts = isCharging || lastActive
        if (counts) {
            sinceStepMs += dt
            if (anchorPercent >= 0) timedMs += dt
        }

        val stepped = if (isCharging) pct > lastPercent else pct < lastPercent
        val wrongWay = if (isCharging) pct < lastPercent else pct > lastPercent
        when {
            stepped -> {
                // The first step starts the clock: at that moment we know where the battery is.
                if (anchorPercent < 0) {
                    anchorPercent = pct
                    timedMs = 0
                }
                sinceStepMs = 0
            }
            wrongWay -> {
                // A reading bounced the other way (voltage settling); start timing over.
                anchorPercent = -1
                timedMs = 0
                sinceStepMs = 0
            }
        }

        lastTimeMs = nowMs
        lastPercent = pct
        lastActive = active
        return estimate(pct, isCharging)
    }

    /** Call when the mug disconnects, so a long session still teaches us its rate. */
    fun reset() {
        finishSession()
        charging = null
        lastPercent = -1
    }

    private fun observedSteps(): Int =
        if (anchorPercent < 0) 0 else kotlin.math.abs(lastPercent - anchorPercent)

    private fun rate(isCharging: Boolean): Double {
        val prior = if (isCharging) priorChargePerMin else priorDrainPerMin
        val steps = observedSteps()
        val minutes = timedMs / 60_000.0
        if (steps < 1 || minutes < MIN_OBSERVED_MINUTES) return prior
        val observed = steps / minutes
        // Weighted average: the prior counts as PRIOR_WEIGHT_MINUTES of watching.
        return (prior * PRIOR_WEIGHT_MINUTES + observed * minutes) / (PRIOR_WEIGHT_MINUTES + minutes)
    }

    private fun estimate(pct: Int, isCharging: Boolean): Int {
        val r = rate(isCharging)
        // Since the last step we've (probably) used some of the current percent.
        val used = min(0.95, (sinceStepMs / 60_000.0) * r)
        val remaining = if (isCharging) {
            if (pct >= 100) return 0
            (100 - pct) - used
        } else {
            pct - used
        }
        return (max(0.0, remaining) / r).roundToInt().coerceIn(0, MAX_MINUTES)
    }

    private fun finishSession() {
        val wasCharging = charging ?: return
        val steps = observedSteps()
        val minutes = timedMs / 60_000.0
        if (steps < LEARN_MIN_STEPS || minutes < LEARN_MIN_MINUTES) return
        val observed = steps / minutes
        if (wasCharging) {
            if (observed !in CHARGE_RANGE) return
            learnedCharge = learnedCharge?.let { it * (1 - LEARN_WEIGHT) + observed * LEARN_WEIGHT } ?: observed
        } else {
            if (observed !in DRAIN_RANGE) return
            learnedDrain = learnedDrain?.let { it * (1 - LEARN_WEIGHT) + observed * LEARN_WEIGHT } ?: observed
        }
        onLearned(learnedDrain, learnedCharge)
    }

    companion object {
        const val PRIOR_WEIGHT_MINUTES = 15.0
        const val MIN_OBSERVED_MINUTES = 2.0
        const val LEARN_MIN_STEPS = 5
        const val LEARN_MIN_MINUTES = 10.0
        const val LEARN_WEIGHT = 0.3
        const val MAX_MINUTES = 24 * 60

        /** Plausible rates, %/min: a full battery lasting 20 min to 10 h. */
        val DRAIN_RANGE = (100.0 / 600)..(100.0 / 20)
        val CHARGE_RANGE = (100.0 / 600)..(100.0 / 15)
    }
}
