package app.emberio

import app.emberio.ember.LiquidState
import app.emberio.ember.MugModel
import app.emberio.ember.TempUnit
import app.emberio.ember.celsiusToFahrenheit
import java.util.Locale
import kotlin.math.roundToInt

/** Everything we've read from the mug. Temperatures are °C; a target of 0 means heater off. */
data class MugReadings(
    val name: String? = null,
    val currentTempC: Double? = null,
    val targetTempC: Double? = null,
    val mugUnit: TempUnit? = null,
    val liquidState: LiquidState? = null,
    val liquidLevel: Int? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
) {
    val heaterOff: Boolean get() = targetTempC != null && targetTempC <= 0.0
}

/** The phone's link to the mug. The codes are what the watch app expects (key STATUS). */
enum class LinkStatus(val code: Int, val key: String) {
    NO_MUG(1, "no_mug"),
    SEARCHING(2, "searching"),
    CONNECTED(3, "connected"),
    BLUETOOTH_OFF(4, "bluetooth_off"),
    NEEDS_SETUP(5, "needs_setup"),
}

enum class UnitPref(val code: Int) {
    MUG(0),
    CELSIUS(1),
    FAHRENHEIT(2);

    companion object {
        fun fromCode(code: Int): UnitPref = entries.firstOrNull { it.code == code } ?: MUG
    }
}

data class Settings(
    val mugAddress: String? = null,
    val mugName: String? = null,
    val mugModel: MugModel = MugModel.UNKNOWN,
    /** Alert when the battery drops to this percent; 0 turns the alert off. */
    val lowBatteryAlert: Int = 20,
    val readyAlert: Boolean = true,
    val unitPref: UnitPref = UnitPref.MUG,
    /** Where "heater on" goes back to. */
    val lastTargetC: Double = 57.0,
    /** Learned from watching this mug: battery percent per minute while keeping a drink hot. */
    val learnedDrainPerMin: Double? = null,
    val learnedChargePerMin: Double? = null,
)

data class AppState(
    val link: LinkStatus = LinkStatus.NO_MUG,
    val readings: MugReadings = MugReadings(),
    /** Minutes of heat left on battery, or minutes to full while charging. Null while unknown. */
    val minutesLeft: Int? = null,
    val settings: Settings = Settings(),
    /** When the last reading arrived (epoch ms), 0 if never. */
    val updatedAtMs: Long = 0,
) {
    val connected: Boolean get() = link == LinkStatus.CONNECTED

    fun displayUnit(fallback: TempUnit = localeUnit()): TempUnit = when (settings.unitPref) {
        UnitPref.CELSIUS -> TempUnit.CELSIUS
        UnitPref.FAHRENHEIT -> TempUnit.FAHRENHEIT
        UnitPref.MUG -> readings.mugUnit ?: fallback
    }

    val displayName: String
        get() = settings.mugName?.takeIf { it.isNotBlank() }
            ?: readings.name?.takeIf { it.isNotBlank() }
            ?: settings.mugModel.displayName
}

fun localeUnit(locale: Locale = Locale.getDefault()): TempUnit =
    if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) TempUnit.FAHRENHEIT else TempUnit.CELSIUS

object TempFormat {
    /** °C to the display unit: whole degrees in °F, half degrees in °C. */
    fun value(celsius: Double, unit: TempUnit): Double = when (unit) {
        TempUnit.FAHRENHEIT -> celsiusToFahrenheit(celsius).roundToInt().toDouble()
        TempUnit.CELSIUS -> (celsius * 2).roundToInt() / 2.0
    }

    fun symbol(unit: TempUnit): String = if (unit == TempUnit.FAHRENHEIT) "°F" else "°C"

    /** "135°F", "57.5°C", "57°C". */
    fun format(celsius: Double, unit: TempUnit, withUnit: Boolean = true): String {
        val v = value(celsius, unit)
        val number = if (v % 1.0 == 0.0) v.roundToInt().toString() else "%.1f".format(Locale.US, v)
        return number + if (withUnit) symbol(unit) else "°"
    }

    /** "58 min", "1 h 05 min". */
    fun duration(minutes: Int): String =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h %02d min".format(Locale.US, minutes % 60)
}
