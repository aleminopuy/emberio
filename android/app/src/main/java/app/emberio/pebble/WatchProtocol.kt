package app.emberio.pebble

import app.emberio.AppState
import app.emberio.LinkStatus
import app.emberio.UnitPref
import app.emberio.ember.TempUnit
import app.emberio.ember.celsiusToFahrenheit
import app.emberio.ember.fahrenheitToCelsius
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The AppMessage protocol between the Emberio watchapp and this app.
 * Keys must match `messageKeys` in watch/package.json.
 */
object WatchProtocol {
    val WATCHAPP_UUID: UUID = UUID.fromString("5da00f2a-1e57-439b-b34a-a992d40e4f26")
    const val VERSION = 1

    object Key {
        const val STATUS = 1u
        const val UNIT = 2u
        const val TEMP_CUR = 3u
        const val TEMP_TARGET = 4u
        const val TEMP_MIN = 5u
        const val TEMP_MAX = 6u
        const val LIQUID_STATE = 7u
        const val LIQUID_LEVEL = 8u
        const val BATTERY = 9u
        const val CHARGING = 10u
        const val MINUTES_LEFT = 11u
        const val MUG_NAME = 12u
        const val ALERT_BATTERY = 13u
        const val ALERT_READY = 14u
        const val UNIT_PREF = 15u
        const val CMD = 20u
        const val PROTOCOL = 21u
    }

    object Cmd {
        const val HELLO = 1
        const val SET_TARGET = 2
        const val SET_SETTINGS = 3
        const val HEATER_ON = 4
    }

    /** The heater's range in tenths of the display unit, as the watch steps through it. */
    fun rangeTenths(unit: TempUnit): IntRange =
        if (unit == TempUnit.FAHRENHEIT) 1200..1450 else 500..625

    /** °C to tenths of the display unit: 135°F -> 1350, 57.5°C -> 575. */
    fun toTenths(celsius: Double, unit: TempUnit): Int = when (unit) {
        TempUnit.FAHRENHEIT -> (celsiusToFahrenheit(celsius) * 10).roundToInt()
        TempUnit.CELSIUS -> (celsius * 10).roundToInt()
    }

    fun fromTenths(tenths: Int, unit: TempUnit): Double = when (unit) {
        TempUnit.FAHRENHEIT -> fahrenheitToCelsius(tenths / 10.0)
        TempUnit.CELSIUS -> tenths / 10.0
    }

    private fun int(value: Int) = PebbleDictionaryItem.Int32(value)

    /** Everything the watch shows, in one message (well under the watch's 256-byte inbox). */
    fun stateMessage(state: AppState, fallbackUnit: TempUnit): PebbleDictionary {
        val unit = state.displayUnit(fallbackUnit)
        val r = state.readings
        val range = rangeTenths(unit)
        val dict = linkedMapOf<UInt, PebbleDictionaryItem>(
            Key.PROTOCOL to int(VERSION),
            Key.STATUS to int(state.link.code),
            Key.UNIT to int(if (unit == TempUnit.FAHRENHEIT) 1 else 0),
            Key.TEMP_MIN to int(range.first),
            Key.TEMP_MAX to int(range.last),
            Key.ALERT_BATTERY to int(state.settings.lowBatteryAlert),
            Key.ALERT_READY to int(if (state.settings.readyAlert) 1 else 0),
            Key.UNIT_PREF to int(state.settings.unitPref.code),
            Key.MUG_NAME to PebbleDictionaryItem.Text(state.displayName.take(23)),
        )
        if (state.link == LinkStatus.CONNECTED) {
            r.currentTempC?.let { dict[Key.TEMP_CUR] = int(toTenths(it, unit)) }
            r.targetTempC?.let { dict[Key.TEMP_TARGET] = int(if (it <= 0) 0 else toTenths(it, unit)) }
            r.liquidState?.let { dict[Key.LIQUID_STATE] = int(it.code) }
            r.liquidLevel?.let { dict[Key.LIQUID_LEVEL] = int(it) }
            r.batteryPercent?.let { dict[Key.BATTERY] = int(it) }
            r.charging?.let { dict[Key.CHARGING] = int(if (it) 1 else 0) }
            dict[Key.MINUTES_LEFT] = int(state.minutesLeft ?: -1)
        }
        return dict
    }

    sealed class Command {
        data object Hello : Command()
        /** [celsius] 0 means heater off. */
        data class SetTarget(val celsius: Double) : Command()
        data object HeaterOn : Command()
        data class SetSettings(val lowBatteryAlert: Int, val readyAlert: Boolean, val unitPref: UnitPref) : Command()
    }

    private fun PebbleDictionary.int(key: UInt): Int? = when (val item = this[key]) {
        is PebbleDictionaryItem.Int32 -> item.value
        is PebbleDictionaryItem.UInt32 -> item.value.toInt()
        is PebbleDictionaryItem.Int16 -> item.value.toInt()
        is PebbleDictionaryItem.UInt16 -> item.value.toInt()
        is PebbleDictionaryItem.Int8 -> item.value.toInt()
        is PebbleDictionaryItem.UInt8 -> item.value.toInt()
        else -> null
    }

    /** Null for anything we don't understand. */
    fun parse(data: PebbleDictionary): Command? = when (data.int(Key.CMD)) {
        Cmd.HELLO -> Command.Hello
        Cmd.SET_TARGET -> {
            val tenths = data.int(Key.TEMP_TARGET)
            val unit = if (data.int(Key.UNIT) == 0) TempUnit.CELSIUS else TempUnit.FAHRENHEIT
            tenths?.let { Command.SetTarget(if (it <= 0) 0.0 else fromTenths(it, unit)) }
        }
        Cmd.HEATER_ON -> Command.HeaterOn
        Cmd.SET_SETTINGS -> Command.SetSettings(
            lowBatteryAlert = (data.int(Key.ALERT_BATTERY) ?: 20).coerceIn(0, 100),
            readyAlert = (data.int(Key.ALERT_READY) ?: 1) != 0,
            unitPref = UnitPref.fromCode(data.int(Key.UNIT_PREF) ?: 0),
        )
        else -> null
    }
}
