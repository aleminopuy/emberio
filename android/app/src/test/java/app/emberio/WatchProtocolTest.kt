package app.emberio

import app.emberio.ember.LiquidState
import app.emberio.ember.MugModel
import app.emberio.ember.TempUnit
import app.emberio.pebble.WatchProtocol
import app.emberio.pebble.WatchProtocol.Key
import app.emberio.plugin.StateJson
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProtocolTest {
    private val connected = AppState(
        link = LinkStatus.CONNECTED,
        readings = MugReadings(
            name = "Ember Mug 2",
            currentTempC = 53.6,
            targetTempC = 60.0,
            mugUnit = TempUnit.FAHRENHEIT,
            liquidState = LiquidState.HEATING,
            liquidLevel = 80,
            batteryPercent = 72,
            charging = false,
        ),
        minutesLeft = 58,
        settings = Settings(mugAddress = "AA:BB", mugModel = MugModel.MUG_2_10OZ),
        updatedAtMs = 1_795_000_000_000,
    )

    private fun Map<UInt, PebbleDictionaryItem>.int(key: UInt) = (this[key] as PebbleDictionaryItem.Int32).value

    @Test fun `temperatures go to the watch as tenths of the display unit`() {
        assertEquals(1350, WatchProtocol.toTenths(57.2222, TempUnit.FAHRENHEIT))
        assertEquals(575, WatchProtocol.toTenths(57.5, TempUnit.CELSIUS))
        assertEquals(57.5, WatchProtocol.fromTenths(575, TempUnit.CELSIUS), 0.001)
        assertEquals(60.0, WatchProtocol.fromTenths(1400, TempUnit.FAHRENHEIT), 0.001)
    }

    @Test fun `the state message carries everything the watch draws`() {
        val m = WatchProtocol.stateMessage(connected, TempUnit.CELSIUS)
        assertEquals(LinkStatus.CONNECTED.code, m.int(Key.STATUS))
        assertEquals(1, m.int(Key.UNIT)) // the mug's own unit
        assertEquals(1285, m.int(Key.TEMP_CUR))
        assertEquals(1400, m.int(Key.TEMP_TARGET))
        assertEquals(1200, m.int(Key.TEMP_MIN))
        assertEquals(1450, m.int(Key.TEMP_MAX))
        assertEquals(LiquidState.HEATING.code, m.int(Key.LIQUID_STATE))
        assertEquals(72, m.int(Key.BATTERY))
        assertEquals(0, m.int(Key.CHARGING))
        assertEquals(58, m.int(Key.MINUTES_LEFT))
        assertEquals(PebbleDictionaryItem.Text("Ember Mug 2"), m[Key.MUG_NAME])
        // Comfortably inside the watch's 256-byte inbox.
        val size = 1 + m.values.sumOf { 7 + it.size }
        assertTrue("$size bytes", size < 200)
    }

    @Test fun `unit preference wins over the mug`() {
        val celsius = connected.copy(settings = connected.settings.copy(unitPref = UnitPref.CELSIUS))
        val m = WatchProtocol.stateMessage(celsius, TempUnit.FAHRENHEIT)
        assertEquals(0, m.int(Key.UNIT))
        assertEquals(536, m.int(Key.TEMP_CUR))
        assertEquals(500, m.int(Key.TEMP_MIN))
    }

    @Test fun `heater off is a target of 0, and a disconnected mug sends no readings`() {
        val off = connected.copy(readings = connected.readings.copy(targetTempC = 0.0))
        assertEquals(0, WatchProtocol.stateMessage(off, TempUnit.CELSIUS).int(Key.TEMP_TARGET))
        val searching = connected.copy(link = LinkStatus.SEARCHING)
        val m = WatchProtocol.stateMessage(searching, TempUnit.CELSIUS)
        assertEquals(LinkStatus.SEARCHING.code, m.int(Key.STATUS))
        assertNull(m[Key.TEMP_CUR])
        assertNull(m[Key.BATTERY])
    }

    @Test fun `commands from the watch are understood`() {
        fun dict(vararg pairs: Pair<UInt, Int>) = pairs.associate { (k, v) -> k to PebbleDictionaryItem.Int32(v) }
        assertEquals(WatchProtocol.Command.Hello, WatchProtocol.parse(dict(Key.CMD to 1)))
        val f = WatchProtocol.parse(dict(Key.CMD to 2, Key.TEMP_TARGET to 1400, Key.UNIT to 1)) as WatchProtocol.Command.SetTarget
        assertEquals(60.0, f.celsius, 0.001)
        val c = WatchProtocol.parse(dict(Key.CMD to 2, Key.TEMP_TARGET to 575, Key.UNIT to 0)) as WatchProtocol.Command.SetTarget
        assertEquals(57.5, c.celsius, 0.001)
        assertEquals(WatchProtocol.Command.SetTarget(0.0), WatchProtocol.parse(dict(Key.CMD to 2, Key.TEMP_TARGET to 0, Key.UNIT to 1)))
        assertEquals(WatchProtocol.Command.HeaterOn, WatchProtocol.parse(dict(Key.CMD to 4)))
        assertEquals(
            WatchProtocol.Command.SetSettings(15, false, UnitPref.FAHRENHEIT),
            WatchProtocol.parse(dict(Key.CMD to 3, Key.ALERT_BATTERY to 15, Key.ALERT_READY to 0, Key.UNIT_PREF to 2)),
        )
        // The watch sends small ints as UInt8; PebbleKit may hand them over as UInt32.
        assertEquals(
            WatchProtocol.Command.Hello,
            WatchProtocol.parse(mapOf(Key.CMD to PebbleDictionaryItem.UInt32(1L))),
        )
        assertNull(WatchProtocol.parse(dict(Key.CMD to 99)))
        assertNull(WatchProtocol.parse(emptyMap()))
    }

    @Test fun `the plugin JSON has the readings in the display unit`() {
        val json = StateJson.build(connected, TempUnit.CELSIUS)
        assertTrue(json, json.startsWith("""{"app":"emberio","version":1,"status":"connected","mug":{"name":"Ember Mug 2","unit":"F","""))
        assertTrue(json, json.contains(""""temperature":128.5,"target":140,"""))
        assertTrue(json, json.contains(""""state":"heating","battery":72,"charging":false,"minutesLeft":58,"liquidLevel":80,"updatedAt":1795000000"""))
        val searching = StateJson.build(connected.copy(link = LinkStatus.SEARCHING), TempUnit.CELSIUS)
        assertFalse(searching, searching.contains("temperature"))
        assertEquals(""""a\"b\\c\n"""", StateJson.encode("a\"b\\c\n"))
    }

    @Test fun `display formatting`() {
        assertEquals("135°F", TempFormat.format(57.2, TempUnit.FAHRENHEIT))
        assertEquals("57.5°C", TempFormat.format(57.4, TempUnit.CELSIUS))
        assertEquals("57°C", TempFormat.format(57.1, TempUnit.CELSIUS))
        assertEquals("58 min", TempFormat.duration(58))
        assertEquals("1 h 05 min", TempFormat.duration(65))
    }
}
