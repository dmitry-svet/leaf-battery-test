package com.leafbt.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanDecoderTest {

    private fun frame(id: Int, vararg b: Int) =
        CanFrame(id, ByteArray(b.size) { b[it].toByte() })

    private val empty = LeafState()

    @Test fun decodesPackVoltage() {
        // raw10 = (0x64<<2)|(0x00>>6) = 400 -> 200.0 V
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_PACK, 0, 0, 0x64, 0x00))
        assertEquals(200.0, s.packVolts!!, 1e-9)
    }

    @Test fun decodesPositiveCurrentAsDischarge() {
        // raw11 = (12<<3)|(0x80>>5) = 100 -> +50.0 A
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_PACK, 12, 0x80, 0, 0))
        assertEquals(50.0, s.packAmps!!, 1e-9)
    }

    @Test fun decodesNegativeCurrentAsCharge() {
        // raw11 = 1948 -> sign-extended -100 -> -50.0 A
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_PACK, 243, 0x80, 0, 0))
        assertEquals(-50.0, s.packAmps!!, 1e-9)
    }

    @Test fun computesPowerFromVoltsAndAmps() {
        var s = CanDecoder.apply(empty, frame(CanDecoder.ID_PACK, 12, 0x80, 0x64, 0x00))
        assertEquals(10.0, s.powerKw!!, 1e-9) // 200V * 50A / 1000
    }

    @Test fun decodesSoc() {
        // raw10 = (138<<2)|(0xC0>>6) = 555 -> 55.5 %
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_SOC, 138, 0xC0))
        assertEquals(55.5, s.socPercent!!, 1e-9)
    }

    @Test fun decodesGidsAndEnergy() {
        // raw10 = (62<<2)|(0x80>>6) = 250 gids -> 250 * 77.5 Wh = 19.375 kWh
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_GIDS, 62, 0x80))
        assertEquals(250, s.gids)
        assertEquals(19.375, s.kwhRemaining!!, 1e-9)
    }

    @Test fun decodesTempOnlyForItsGroup() {
        // group 1 (u0 top bits = 01), u2=120 -> (120/2)-40 = 20.0 C
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_TEMP, 64, 0, 120))
        assertEquals(20.0, s.batteryTempC!!, 1e-9)

        // other group -> unchanged (null)
        val s2 = CanDecoder.apply(empty, frame(CanDecoder.ID_TEMP, 0, 0, 120))
        assertNull(s2.batteryTempC)
    }

    @Test fun decodesSpeed() {
        // raw = (0x17<<8)|0x70 = 6000 -> 60.0 km/h
        val s = CanDecoder.apply(empty, frame(CanDecoder.ID_SPEED, 0, 0, 0, 0, 0x17, 0x70))
        assertEquals(60.0, s.speedKmh!!, 1e-9)
    }

    @Test fun unknownIdLeavesStateUnchanged() {
        val s = CanDecoder.apply(empty.copy(socPercent = 42.0), frame(0x123, 1, 2, 3))
        assertEquals(42.0, s.socPercent!!, 1e-9)
    }
}
