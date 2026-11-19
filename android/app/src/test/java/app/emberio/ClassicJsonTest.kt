package app.emberio

import app.emberio.pebble.ClassicJson
import app.emberio.pebble.WatchProtocol
import app.emberio.pebble.WatchProtocol.Key
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicJsonTest {
    @Test fun `encodes tuples the way PebbleKit Classic does`() {
        val json = ClassicJson.encode(
            linkedMapOf(
                1u to PebbleDictionaryItem.UInt8(3),
                3u to PebbleDictionaryItem.Int32(-1284),
                12u to PebbleDictionaryItem.Text("Ember \"Mug\" 2"),
                30u to PebbleDictionaryItem.Bytes(byteArrayOf(1, 2, 3)),
            ),
        )
        assertEquals(
            """[{"key":1,"type":"uint","length":1,"value":3},""" +
                """{"key":3,"type":"int","length":4,"value":-1284},""" +
                """{"key":12,"type":"string","length":0,"value":"Ember \"Mug\" 2"},""" +
                """{"key":30,"type":"bytes","length":0,"value":"AQID"}]""",
            json,
        )
    }

    @Test fun `reads a watch command as the Pebble app broadcasts it`() {
        // org.json's output from the Pebble app: spaces and key order vary.
        val json = """[ {"key":21,"type":"uint","length":1,"value":1},
            {"value":2,"length":1,"type":"uint","key":20},
            {"key":4,"type":"int","length":4,"value":1420},
            {"key":2,"type":"uint","length":1,"value":1} ]"""
        val dict = ClassicJson.decode(json)
        assertEquals(WatchProtocol.Command.SetTarget(WatchProtocol.fromTenths(1420, app.emberio.ember.TempUnit.FAHRENHEIT)), WatchProtocol.parse(dict))
        assertEquals(PebbleDictionaryItem.UInt8(2), dict[Key.CMD])
    }

    @Test fun `the full state message survives a round trip`() {
        val state = AppState(
            link = LinkStatus.CONNECTED,
            readings = MugReadings(name = "Ember Mug 2", currentTempC = 53.6, targetTempC = 60.0, batteryPercent = 72, charging = false),
            minutesLeft = 58,
        )
        val message = WatchProtocol.stateMessage(state, app.emberio.ember.TempUnit.FAHRENHEIT)
        assertEquals(message, ClassicJson.decode(ClassicJson.encode(message)))
    }

    @Test fun `unknown tuples are skipped and bad JSON is refused`() {
        val dict = ClassicJson.decode("""[{"key":1,"type":"float","length":4,"value":1.5},{"key":2,"type":"uint","length":4,"value":4294967295},{"nokey":1}]""")
        assertEquals(mapOf(2u to PebbleDictionaryItem.UInt32(4294967295L)), dict)
        assertTrue(ClassicJson.decode("[]").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ClassicJson.decode("""[{"key":1""") }
        assertThrows(IllegalArgumentException::class.java) { ClassicJson.decode("""{"key":1}""") }
        assertThrows(IllegalArgumentException::class.java) { ClassicJson.decode("""[1,2] trailing""") }
    }
}
