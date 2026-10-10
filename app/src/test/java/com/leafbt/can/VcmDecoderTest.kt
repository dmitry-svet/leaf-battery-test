package com.leafbt.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VcmDecoderTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    // UDS positive reply: 62 DID_hi DID_lo data...
    @Test fun decodesQuickChargeCount() {
        val s = VcmDecoder.apply(LeafState(), 0x1203, bytes(0x62, 0x12, 0x03, 0x00, 0xBA))
        assertEquals(186, s.qcCount)
    }

    @Test fun invalidCountIgnored() {
        val s = VcmDecoder.apply(LeafState(), 0x1205, bytes(0x62, 0x12, 0x05, 0xFF, 0xFF))
        assertNull(s.l1l2Count)
    }

    @Test fun wrongDidOrNegativeReplyIgnored() {
        assertEquals(LeafState(), VcmDecoder.apply(LeafState(), 0x1203, bytes(0x62, 0x12, 0x05, 0, 1)))
        assertEquals(LeafState(), VcmDecoder.apply(LeafState(), 0x1203, bytes(0x7F, 0x22, 0x31)))
    }

    @Test fun decodesPowersInLeafSpyUnits() {
        var s = VcmDecoder.apply(LeafState(), 0x1146, bytes(0x62, 0x11, 0x46, 0x03, 0xB1)) // 945 x 40 W
        assertEquals(37800, s.motorPowerW)
        s = VcmDecoder.apply(s, 0x1146, bytes(0x62, 0x11, 0x46, 0xFF, 0xF6))                // regen: -10 x 40
        assertEquals(-400, s.motorPowerW)
        s = VcmDecoder.apply(s, 0x1152, bytes(0x62, 0x11, 0x52, 0x02))
        assertEquals(2, s.auxPower100W)
        s = VcmDecoder.apply(s, 0x1151, bytes(0x62, 0x11, 0x51, 0x03))
        assertEquals(3, s.acPower250W)
        s = VcmDecoder.apply(s, 0x1261, bytes(0x62, 0x12, 0x61, 0x11))
        assertEquals(17, s.estAcPower50W)
        s = VcmDecoder.apply(s, 0x1262, bytes(0x62, 0x12, 0x62, 0x08))
        assertEquals(8, s.estHeaterPower250W)
    }

    @Test fun decodesChargeAndGear() {
        var s = VcmDecoder.apply(LeafState(), 0x1234, bytes(0x62, 0x12, 0x34, 0x02))
        assertEquals(2, s.plugState)
        s = VcmDecoder.apply(s, 0x114E, bytes(0x62, 0x11, 0x4E, 0x02))
        assertEquals(2, s.chargeMode)
        s = VcmDecoder.apply(s, 0x1236, bytes(0x62, 0x12, 0x36, 0x00, 0x21))              // 33 x 100 W
        assertEquals(3300, s.chargePowerW)
        s = VcmDecoder.apply(s, 0x1156, bytes(0x62, 0x11, 0x56, 0x04))
        assertEquals(4, s.gear)
    }

    @Test fun decodesCurrents() {
        val s = VcmDecoder.apply(LeafState(), 0x1183, bytes(0x62, 0x11, 0x83, 0xF8, 0x47)) // s16 / 256
        assertEquals(-7.72, s.aux12A!!, 0.01)
    }

    @Test fun decodes12vVoltsAmbientPowerSwitch() {
        var s = VcmDecoder.apply(LeafState(), 0x1103, bytes(0x62, 0x11, 0x03, 0xA2))    // 162 / 12.5
        assertEquals(12.96, s.aux12V!!, 1e-9)
        s = VcmDecoder.apply(s, 0x115D, bytes(0x62, 0x11, 0x5D, 0x6E))                 // 110*0.9-40.9 F
        assertEquals((58.1 - 32) / 1.8, s.ambientTempVcmC!!, 0.01)
        s = VcmDecoder.apply(s, 0x1304, bytes(0x62, 0x13, 0x04, 0x80, 0xBC))
        assertEquals(true, s.powerSwitch)
    }

    @Test fun decodesRpmAndTorque() {
        var s = VcmDecoder.apply(LeafState(), 0x1255, bytes(0x62, 0x12, 0x55, 0x0B, 0xF7))   // 3063
        assertEquals(3063, s.motorRpm)
        s = VcmDecoder.apply(s, 0x1254, bytes(0x62, 0x12, 0x54, 0x16, 0x20))                 // 5664 / 64
        assertEquals(88.5, s.torqueNm!!, 1e-9)
        s = VcmDecoder.apply(s, 0x1254, bytes(0x62, 0x12, 0x54, 0xFF, 0xB0))                 // -80 / 64
        assertEquals(-1.25, s.torqueNm!!, 1e-9)
    }

    @Test fun decodesVinStrippingPadding() {
        val vin = "1N4AZ0CP2DC401434"
        val p = bytes(0x61, 0x81) + vin.toByteArray(Charsets.US_ASCII) + bytes(0, 0)
        assertEquals(vin, VcmDecoder.vin(p))
        assertNull(VcmDecoder.vin(bytes(0x7F, 0x21, 0x12)))
    }

    @Test fun decodesTiresGidsShunts() {
        val tires = CanDecoder.tires(CanFrame(0x385, bytes(0, 0, 157, 156, 155, 0, 0, 0)))
        assertEquals(listOf(39.25, 39.0, 38.75, null), tires)            // 0 = no data
        assertEquals(185, CanDecoder.gids5b3(CanFrame(0x5B3, bytes(0, 0, 0, 0, 0x00, 185, 0, 0))))
        assertEquals(300, CanDecoder.gids5b3(CanFrame(0x5B3, bytes(0, 0, 0, 0, 0x01, 44, 0, 0))))

        // 2106: one nibble per byte, 4 cells each, cell 4i+0 = bit 3 (OVMS order)
        val p = ByteArray(26)
        p[0] = 0x61; p[1] = 0x06
        p[2] = 0x08          // cell 1 active
        p[3] = 0x01          // cell 8 active
        val shunts = GroupDecoder.apply(LeafState(), p).shunts
        assertEquals(96, shunts.size)
        assertEquals(listOf(0, 7), shunts.withIndex().filter { it.value }.map { it.index })
    }
}
