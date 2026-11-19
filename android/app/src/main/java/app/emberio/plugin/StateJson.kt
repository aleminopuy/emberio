package app.emberio.plugin

import app.emberio.AppState
import app.emberio.LinkStatus
import app.emberio.TempFormat
import app.emberio.ember.TempUnit
import app.emberio.ember.celsiusToFahrenheit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The JSON the watchface plugin (watch/src/plugin/index.js) reads from
 * `http://127.0.0.1:47811/v1/state`. Hand-written to avoid a JSON dependency.
 */
object StateJson {
    fun build(state: AppState, fallbackUnit: TempUnit): String {
        val unit = state.displayUnit(fallbackUnit)
        val r = state.readings
        val mug = linkedMapOf<String, Any?>(
            "name" to state.displayName,
            "unit" to if (unit == TempUnit.FAHRENHEIT) "F" else "C",
        )
        if (state.link == LinkStatus.CONNECTED) {
            mug["temperature"] = r.currentTempC?.let { round1(convert(it, unit)) }
            mug["target"] = r.targetTempC?.let { if (it <= 0) 0 else TempFormat.value(it, unit) }
            mug["targetRange"] = if (unit == TempUnit.FAHRENHEIT) mapOf("min" to 120, "max" to 145) else mapOf("min" to 50, "max" to 62.5)
            mug["state"] = r.liquidState?.key
            mug["battery"] = r.batteryPercent
            mug["charging"] = r.charging
            mug["minutesLeft"] = state.minutesLeft
            mug["liquidLevel"] = r.liquidLevel
            mug["updatedAt"] = if (state.updatedAtMs > 0) state.updatedAtMs / 1000 else null
        }
        return encode(mapOf("app" to "emberio", "version" to 1, "status" to state.link.key, "mug" to mug))
    }

    private fun convert(c: Double, unit: TempUnit) = if (unit == TempUnit.FAHRENHEIT) celsiusToFahrenheit(c) else c

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> if (value.isFinite()) {
            if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%s", value)
        } else "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + encode(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> quote(value.toString())
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
