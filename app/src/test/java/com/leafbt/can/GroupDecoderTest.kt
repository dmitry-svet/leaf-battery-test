package com.leafbt.can

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupDecoderTest {

    // Real captures from a 24 kWh AZE0 (car sitting in READY).
    private val group1 =
        "7BB10296101FFFFF7F6 7BB210286FFFFF9C1FF 7BB22FFFFFF170E2AF8 " +
            "7BB238E9532A0037700 7BB24731412000451CE 7BB250006C28B800005"
    private val group4 =
        "7BB1010610401A11E01 7BB21B11CFFFFFF01B6 7BB221C1C00FFFFFFFF"

    @Test fun reassemblesGroup1ToDeclaredLength() {
        val p = IsoTp.reassemble(group1)
        assertEquals(41, p.size)                 // 0x29
        assertEquals(0x61, p[0].toInt() and 0xFF)
        assertEquals(0x01, p[1].toInt() and 0xFF)
    }

    @Test fun decodesGroup1CapacityHxSohVolts() {
        val s = GroupDecoder.apply(LeafState(), IsoTp.reassemble(group1))
        assertEquals(44.30, s.ahCapacity!!, 0.01)
        assertEquals(50.18, s.hx!!, 0.01)        // raw 5138 / 102.4 (LeafSpy scale)
        assertEquals(67.1, s.sohPercent!!, 0.1)   // 44.30 / 66 * 100
        assertEquals(365.0, s.packVolts!!, 0.1)
        assertEquals(28.31, s.socPercent!!, 0.01) // idx31-33 / 10000
    }

    @Test fun decodesGroup1PackAmps() {
        // real capture at rest: second current word p8-11 = 0x0000016A -> 362/1024
        val s = GroupDecoder.apply(LeafState(), IsoTp.reassemble(
            "7BB102961010000034A 7BB2102860000016AFF 7BB22FFFFFF073A2AF8 " +
                "7BB23978F3263038500 7BB245C132C000CE117 7BB2500069B75800005"))
        assertEquals(-0.354, s.packAmps!!, 0.001)    // raw +362 at rest = slight charge/noise
        assertEquals(47.93, s.hx!!, 0.01)          // 0x132C / 102.4
    }

    @Test fun decodesGroup61SohAndHx() {
        // real capture: 61 61 13 2C 19 DD ... -> Hx 4908/102.4, SOH 6621/100
        val s = GroupDecoder.apply(LeafState(), IsoTp.reassemble(
            "7BB100C6161132C19DD 7BB21FF19DC19DD03FF"))
        assertEquals(66.21, s.sohPercent!!, 0.001)
        assertEquals(true, s.sohFromLbc)
        assertEquals(47.93, s.hx!!, 0.01)
        // a later group 1 must not overwrite it with the Ah/66 estimate
        val after = GroupDecoder.apply(s, IsoTp.reassemble(group1))
        assertEquals(66.21, after.sohPercent!!, 0.001)
    }

    @Test fun decodesMeterOdometerBeyondDeclaredLength() {
        // meter (0x743) group 1 declares 8 bytes but the km count is in the
        // consecutive frame: 0x02C06A = 180330 km
        val p = IsoTp.reassemble("7631082610100000000 7632100000002C06A00", trim = false)
        assertEquals(180330, GroupDecoder.meterOdometerKm(p))
    }

    @Test fun decodesGroup4TempAverage() {
        val s = GroupDecoder.apply(LeafState(), IsoTp.reassemble(group4))
        assertEquals(28.67, s.batteryTempC!!, 0.01) // (30+28+28)/3
    }

    @Test fun decodesGroup2CellMinMax() {
        val p = ByteArray(2 + 96 * 2)
        p[0] = 0x61; p[1] = 0x02
        for (i in 0 until 96) {
            val mv = when (i) {
                17 -> 3672
                50 -> 3777
                else -> 3749
            }
            p[2 + 2 * i] = (mv shr 8).toByte()
            p[3 + 2 * i] = (mv and 0xFF).toByte()
        }
        val s = GroupDecoder.apply(LeafState(), p)
        assertEquals(3.672, s.cellMinV!!, 0.0005)
        assertEquals(3.777, s.cellMaxV!!, 0.0005)
    }

    @Test fun group2IgnoresImplausibleCells() {
        val p = ByteArray(2 + 96 * 2)
        p[0] = 0x61; p[1] = 0x02
        for (i in 0 until 96) {
            // cell 0 = 0x0000 (dead read), cell 1 = 0xFFFF (padding), rest 3700
            val mv = when (i) {
                0 -> 0
                1 -> 0xFFFF
                else -> 3700
            }
            p[2 + 2 * i] = (mv shr 8).toByte()
            p[3 + 2 * i] = (mv and 0xFF).toByte()
        }
        val s = GroupDecoder.apply(LeafState(), p)
        assertEquals(3.700, s.cellMinV!!, 0.0005)
        assertEquals(3.700, s.cellMaxV!!, 0.0005)
    }

    @Test fun consecutiveFramesWithoutFirstFrameRejected() {
        // CF-only garbage (missed first frame): no declared length -> no payload
        assertEquals(0, IsoTp.reassemble("7BB2101020304 7BB2205060708").size)
    }

    @Test fun ignoresNonReplyPayload() {
        val s = GroupDecoder.apply(LeafState(), byteArrayOf(0x7F, 0x21)) // negative resp
        assertEquals(LeafState(), s)
    }
}
