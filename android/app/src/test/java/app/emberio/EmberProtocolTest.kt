package app.emberio

import app.emberio.ember.EmberProtocol
import app.emberio.ember.LiquidState
import app.emberio.ember.MugModel
import app.emberio.ember.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EmberProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun `uuids follow the Ember template`() {
        assertEquals(UUID.fromString("fc543622-236c-4c94-8fa9-944a3e5353fa"), EmberProtocol.SERVICE)
        assertEquals(UUID.fromString("fc540003-236c-4c94-8fa9-944a3e5353fa"), EmberProtocol.TARGET_TEMP)
        assertEquals(UUID.fromString("fc540012-236c-4c94-8fa9-944a3e5353fa"), EmberProtocol.PUSH_EVENT)
    }

    @Test fun `temperatures are little-endian hundredths of a degree`() {
        // 0x1644 = 5700 -> 57.00 °C
        assertEquals(57.0, EmberProtocol.decodeTemp(bytes(0x44, 0x16))!!, 0.001)
        assertEquals(62.5, EmberProtocol.decodeTemp(EmberProtocol.encodeTemp(62.5))!!, 0.001)
        assertTrue(EmberProtocol.encodeTemp(54.44).contentEquals(bytes(0x44, 0x15)))
        assertNull(EmberProtocol.decodeTemp(bytes(0x01)))
    }

    @Test fun `targets are clamped to the heater range, and 0 turns it off`() {
        assertEquals(0.0, EmberProtocol.clampTarget(0.0), 0.0)
        assertEquals(0.0, EmberProtocol.clampTarget(-3.0), 0.0)
        assertEquals(49.0, EmberProtocol.clampTarget(30.0), 0.0)
        assertEquals(63.0, EmberProtocol.clampTarget(80.0), 0.0)
        assertEquals(57.5, EmberProtocol.clampTarget(57.5), 0.0)
    }

    @Test fun `battery is percent then charging flag`() {
        assertEquals(EmberProtocol.Battery(72, false), EmberProtocol.decodeBattery(bytes(72, 0, 0x10, 0x0E, 0x01)))
        assertEquals(EmberProtocol.Battery(100, true), EmberProtocol.decodeBattery(bytes(100, 1)))
        assertNull(EmberProtocol.decodeBattery(ByteArray(0)))
    }

    @Test fun `unit, state and level decode`() {
        assertEquals(TempUnit.CELSIUS, EmberProtocol.decodeUnit(bytes(0)))
        assertEquals(TempUnit.FAHRENHEIT, EmberProtocol.decodeUnit(bytes(1)))
        assertEquals(LiquidState.PERFECT, EmberProtocol.decodeLiquidState(bytes(6)))
        assertNull(EmberProtocol.decodeLiquidState(bytes(42)))
        assertEquals(100, EmberProtocol.decodeLiquidLevel(bytes(30), MugModel.MUG_2_10OZ))
        assertEquals(50, EmberProtocol.decodeLiquidLevel(bytes(15), MugModel.MUG_2_10OZ))
        assertEquals(15, EmberProtocol.decodeLiquidLevel(bytes(15), MugModel.TRAVEL_MUG))
    }

    @Test fun `an all-zero UDSK means the mug won't accept writes yet`() {
        assertTrue(EmberProtocol.udskIsEmpty(ByteArray(20)))
        assertTrue(EmberProtocol.udskIsEmpty(null))
        assertFalse(EmberProtocol.udskIsEmpty(bytes(0, 0, 3)))
        val udsk = EmberProtocol.newUdsk(java.util.Random(1))
        assertEquals(20, udsk.size)
        assertTrue(udsk.all { it in 0x2B..0x7A })
        assertFalse(EmberProtocol.udskIsEmpty(udsk))
    }

    @Test fun `models are told apart by advertisement`() {
        // Newer firmware: [?, model, generation, colour]
        assertEquals(MugModel.MUG_2_10OZ, MugModel.detect(bytes(0x81, 1, 2, 0xC7), emptyList(), null))
        assertEquals(MugModel.MUG_1_10OZ, MugModel.detect(bytes(0x81, 1, 1, 0xC7), emptyList(), null))
        assertEquals(MugModel.MUG_2_14OZ, MugModel.detect(bytes(0x81, 2, 2, 0xC7), emptyList(), null))
        assertEquals(MugModel.CUP, MugModel.detect(bytes(0x81, 8, 2, 0), emptyList(), null))
        assertEquals(MugModel.TUMBLER, MugModel.detect(bytes(0x81, 9, 2, 0), emptyList(), null))
        // Older firmware: one signed byte
        assertEquals(MugModel.MUG_2_10OZ, MugModel.detect(bytes(-127), emptyList(), null))
        assertEquals(MugModel.MUG_2_14OZ, MugModel.detect(bytes(-51), emptyList(), null))
        assertEquals(MugModel.CUP, MugModel.detect(bytes(-60), emptyList(), null))
        // Travel mugs have their own service
        assertEquals(MugModel.TRAVEL_MUG, MugModel.detect(null, listOf(EmberProtocol.SERVICE_TRAVEL_MUG), null))
        // Name as a last resort
        assertEquals(MugModel.MUG_2_10OZ, MugModel.detect(null, emptyList(), "Ember Ceramic Mug"))
        assertEquals(MugModel.UNKNOWN, MugModel.detect(null, emptyList(), "Something"))
    }

    @Test fun `Ember devices are recognised`() {
        assertTrue(MugModel.isEmber(bytes(1), emptyList(), null))
        assertTrue(MugModel.isEmber(null, listOf(EmberProtocol.SERVICE), null))
        assertTrue(MugModel.isEmber(null, emptyList(), "Ember Ceramic Mug"))
        assertFalse(MugModel.isEmber(null, emptyList(), "Pebble Time 1A2B"))
    }
}
