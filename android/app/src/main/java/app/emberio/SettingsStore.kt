package app.emberio

import android.content.Context
import app.emberio.ember.MugModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [Settings], kept in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("emberio", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load(): Settings = Settings(
        mugAddress = prefs.getString(MUG_ADDRESS, null),
        mugName = prefs.getString(MUG_NAME, null),
        mugModel = MugModel.fromCode(prefs.getString(MUG_MODEL, null)),
        lowBatteryAlert = prefs.getInt(LOW_BATTERY, 20),
        readyAlert = prefs.getBoolean(READY, true),
        unitPref = UnitPref.fromCode(prefs.getInt(UNIT_PREF, 0)),
        lastTargetC = prefs.getFloat(LAST_TARGET, 57f).toDouble(),
        learnedDrainPerMin = prefs.getFloat(LEARNED_DRAIN, -1f).takeIf { it > 0 }?.toDouble(),
        learnedChargePerMin = prefs.getFloat(LEARNED_CHARGE, -1f).takeIf { it > 0 }?.toDouble(),
    )

    private fun save(s: Settings) {
        prefs.edit().apply {
            putString(MUG_ADDRESS, s.mugAddress)
            putString(MUG_NAME, s.mugName)
            putString(MUG_MODEL, s.mugModel.name)
            putInt(LOW_BATTERY, s.lowBatteryAlert)
            putBoolean(READY, s.readyAlert)
            putInt(UNIT_PREF, s.unitPref.code)
            putFloat(LAST_TARGET, s.lastTargetC.toFloat())
            putFloat(LEARNED_DRAIN, s.learnedDrainPerMin?.toFloat() ?: -1f)
            putFloat(LEARNED_CHARGE, s.learnedChargePerMin?.toFloat() ?: -1f)
        }.apply()
    }

    private companion object {
        const val MUG_ADDRESS = "mug_address"
        const val MUG_NAME = "mug_name"
        const val MUG_MODEL = "mug_model"
        const val LOW_BATTERY = "low_battery_alert"
        const val READY = "ready_alert"
        const val UNIT_PREF = "unit_pref"
        const val LAST_TARGET = "last_target_c"
        const val LEARNED_DRAIN = "learned_drain"
        const val LEARNED_CHARGE = "learned_charge"
    }
}
