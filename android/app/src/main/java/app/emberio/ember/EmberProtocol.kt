package app.emberio.ember

import java.util.UUID
import kotlin.math.roundToInt

/**
 * Ember's Bluetooth LE protocol, as reverse-engineered by the community
 * (python-ember-mug, mugctl). Every characteristic lives under one service and shares
 * the base UUID `fc54xxxx-236c-4c94-8fa9-944a3e5353fa`.
 */
object EmberProtocol {
    private fun uuid(id: Int): UUID = UUID.fromString("fc54%04x-236c-4c94-8fa9-944a3e5353fa".format(id))

    val SERVICE: UUID = uuid(0x3622)
    val SERVICE_TRAVEL_MUG: UUID = uuid(0x3621)
    val SERVICE_TRAVEL_MUG_OTHER: UUID = uuid(0x21A1)
    val SERVICES = listOf(SERVICE, SERVICE_TRAVEL_MUG, SERVICE_TRAVEL_MUG_OTHER)

    val NAME: UUID = uuid(0x0001)
    val CURRENT_TEMP: UUID = uuid(0x0002)
    val TARGET_TEMP: UUID = uuid(0x0003)
    val TEMP_UNIT: UUID = uuid(0x0004)
    val LIQUID_LEVEL: UUID = uuid(0x0005)
    val BATTERY: UUID = uuid(0x0007)
    val LIQUID_STATE: UUID = uuid(0x0008)
    val FIRMWARE: UUID = uuid(0x000C)
    val UDSK: UUID = uuid(0x000F)
    val PUSH_EVENT: UUID = uuid(0x0012)

    /** Client Characteristic Configuration descriptor, to turn on notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Ember Technologies' Bluetooth SIG company ID, in the advertisement's manufacturer data. */
    const val MANUFACTURER_ID = 0x03C1

    /** The mug accepts targets in this range; 0 turns the heater off. */
    const val MIN_TARGET_C = 49.0
    const val MAX_TARGET_C = 63.0

    fun u16(bytes: ByteArray, offset: Int = 0): Int? {
        if (bytes.size < offset + 2) return null
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun u8(bytes: ByteArray, offset: Int = 0): Int? = bytes.getOrNull(offset)?.toInt()?.and(0xFF)

    /** Temperatures are little-endian hundredths of a degree Celsius. */
    fun decodeTemp(bytes: ByteArray): Double? = u16(bytes)?.let { it / 100.0 }

    fun encodeTemp(celsius: Double): ByteArray {
        val hundredths = (celsius * 100).roundToInt().coerceIn(0, 0xFFFF)
        return byteArrayOf((hundredths and 0xFF).toByte(), (hundredths shr 8 and 0xFF).toByte())
    }

    /** A target the mug will take: 0 (off), or clamped to the heater's range. */
    fun clampTarget(celsius: Double): Double =
        if (celsius <= 0.0) 0.0 else celsius.coerceIn(MIN_TARGET_C, MAX_TARGET_C)

    data class Battery(val percent: Int, val charging: Boolean)

    fun decodeBattery(bytes: ByteArray): Battery? {
        val percent = u8(bytes, 0) ?: return null
        return Battery(percent.coerceIn(0, 100), u8(bytes, 1) == 1)
    }

    fun decodeUnit(bytes: ByteArray): TempUnit? = when (u8(bytes)) {
        0 -> TempUnit.CELSIUS
        1 -> TempUnit.FAHRENHEIT
        else -> null
    }

    fun decodeLiquidState(bytes: ByteArray): LiquidState? = u8(bytes)?.let(LiquidState::fromCode)

    /** Mugs and cups report 0-30; travel mugs and tumblers 0-100. */
    fun decodeLiquidLevel(bytes: ByteArray, model: MugModel): Int? {
        val raw = u8(bytes) ?: return null
        val full = if (model.levelIsPercent) 100 else 30
        return (raw * 100 / full).coerceIn(0, 100)
    }

    fun decodeName(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).trim { it <= ' ' }

    fun decodeFirmware(bytes: ByteArray): String? {
        val version = u16(bytes, 0) ?: return null
        val hardware = u16(bytes, 2)
        return if (hardware != null) "$version (hw $hardware)" else "$version"
    }

    /**
     * Writes are refused until the mug has a UDSK. The Ember app sets one when it pairs; a mug
     * that was reset (or never set up) reads back all zeros, and any value we write makes it
     * writable. We never overwrite one the Ember app set.
     */
    fun udskIsEmpty(bytes: ByteArray?): Boolean = bytes == null || bytes.isEmpty() || bytes.all { it.toInt() == 0 }

    /** 20 printable bytes, like the base64 text the community libraries write. */
    fun newUdsk(random: java.util.Random = java.security.SecureRandom()): ByteArray {
        val raw = ByteArray(15).also(random::nextBytes)
        return java.util.Base64.getEncoder().encode(raw)
    }

    /** IDs the mug pushes on [PUSH_EVENT] to say what changed. */
    object PushEvent {
        const val BATTERY_CHANGED = 1
        const val CHARGER_CONNECTED = 2
        const val CHARGER_DISCONNECTED = 3
        const val TARGET_TEMP_CHANGED = 4
        const val DRINK_TEMP_CHANGED = 5
        const val AUTH_INFO_NOT_FOUND = 6
        const val LIQUID_LEVEL_CHANGED = 7
        const val LIQUID_STATE_CHANGED = 8
        const val BATTERY_VOLTAGE_STATE_CHANGED = 9
    }
}

enum class TempUnit { CELSIUS, FAHRENHEIT }

fun celsiusToFahrenheit(c: Double): Double = c * 9.0 / 5.0 + 32.0
fun fahrenheitToCelsius(f: Double): Double = (f - 32.0) * 5.0 / 9.0

/** What the mug says it's doing with the drink (characteristic 0x0008). */
enum class LiquidState(val code: Int, val label: String, val key: String) {
    STANDBY(0, "Standby", "standby"),
    EMPTY(1, "Empty", "empty"),
    FILLING(2, "Filling", "filling"),
    COLD(3, "Cold", "cold"),
    COOLING(4, "Cooling", "cooling"),
    HEATING(5, "Heating", "heating"),
    PERFECT(6, "Perfect", "perfect"),
    WARM(7, "Warm", "warm");

    /** The mug holds a drink and is working on it. */
    val active: Boolean get() = this == COOLING || this == HEATING || this == PERFECT

    companion object {
        fun fromCode(code: Int): LiquidState? = entries.firstOrNull { it.code == code }
    }
}
