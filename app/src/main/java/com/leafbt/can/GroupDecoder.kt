package com.leafbt.can

/**
 * Reassembles ISO-TP multi-frame ELM327 responses and decodes Nissan Leaf
 * AZE0 battery-controller (LBC) group data into [LeafState].
 *
 * Offsets/formulas from OVMS (`vehicle_nissanleaf.cpp`) and dalathegreat's
 * Battery-Emulator, verified against real captures from a 24 kWh car.
 * Payload index 0 = the 0x61 service echo, index 1 = group id.
 */
object GroupDecoder {

    /** New-car capacity for a 24 kWh pack (Ah), for the SOH baseline. */
    const val NEW_CAR_AH = 66.0

    fun apply(state: LeafState, payload: ByteArray): LeafState {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != 0x61) return state
        return when (payload[1].toInt() and 0xFF) {
            0x01 -> group1(state, payload)
            0x02 -> group2(state, payload)
            0x04 -> group4(state, payload)
            0x06 -> group6(state, payload)
            0x61 -> group61(state, payload)
            else -> state
        }
    }

    /** Group 61 (2161): Hx p2-3 / 102.4, SOH% p4-5 / 100 (LBC's own, matches the dash tools). */
    private fun group61(s: LeafState, p: ByteArray): LeafState {
        if (p.size < 6) return s
        fun u(i: Int) = p[i].toInt() and 0xFF
        val hx = ((u(2) shl 8) or u(3)) / 102.4
        val soh = ((u(4) shl 8) or u(5)) / 100.0
        return s.copy(
            hx = hx.takeIf { it in 0.0..200.0 } ?: s.hx,
            sohPercent = soh.takeIf { it in 0.0..150.0 } ?: s.sohPercent,
            sohFromLbc = s.sohFromLbc || soh in 0.0..150.0,
        )
    }

    /**
     * Meter ECU (0x743) group 1: odometer km at p9-11 (OBDb: bit 56, 24 bits,
     * counted after the 61 01 echo). The first frame declares far more bytes
     * than the two frames carry, so reassemble with trim = false.
     */
    fun meterOdometerKm(p: ByteArray): Int? {
        if (p.size < 12 || (p[0].toInt() and 0xFF) != 0x61 || (p[1].toInt() and 0xFF) != 0x01) return null
        fun u(i: Int) = p[i].toInt() and 0xFF
        return ((u(9) shl 16) or (u(10) shl 8) or u(11)).takeIf { it in 1..2_000_000 }
    }

    /**
     * Group 6 (2106): balancing shunts, 24 bytes with 4 cells in each low
     * nibble, cell 4i+0 = bit 3 ... 4i+3 = bit 0 (OVMS "8421" order). Bit
     * order differs between public sources - verify against LeafSpy.
     */
    private fun group6(s: LeafState, p: ByteArray): LeafState {
        if (p.size < 2 + 24) return s
        val bits = (0 until 96).map { c ->
            val nib = p[2 + c / 4].toInt() and 0x0F
            (nib shr (3 - c % 4)) and 1 == 1
        }
        return s.copy(shunts = bits)
    }

    /** Group 2 (2102): 96 cell voltages, 2 bytes each, big-endian mV. */
    private fun group2(s: LeafState, p: ByteArray): LeafState {
        if (p.size < 2 + 96 * 2) return s
        fun u(i: Int) = p[i].toInt() and 0xFF
        val all = (0 until 96).map { i -> (u(2 + 2 * i) shl 8) or u(3 + 2 * i) }
        val mv = all.filter { it in 1000..4500 }
        if (mv.isEmpty()) return s
        return s.copy(cellMinV = mv.min() / 1000.0, cellMaxV = mv.max() / 1000.0, cellsMv = all)
    }

    /** Group 1 (2101): capacity Ah, Hx, derived SOH (until 2161 arrives), pack V, pack A. */
    private fun group1(s: LeafState, p: ByteArray): LeafState {
        if (p.size < 38) return s
        fun u(i: Int) = p[i].toInt() and 0xFF
        // /102.4, not /100: matches LeafSpy side by side (raw 4910 -> 47.95 vs
        // LeafSpy 47.93, raw 5050 -> 49.32 vs 49.27)
        val hx = ((u(28) shl 8) or u(29)) / 102.4
        // p8-11: signed current /1024 A; raw is negative while driving (-29 A
        // at 20 kW on the car), so flip to our + = discharge convention. p2-5
        // is a noisier second reading.
        val amps = -(((u(8) shl 24) or (u(9) shl 16) or (u(10) shl 8) or u(11))) / 1024.0
        val soc = ((u(31) shl 16) or (u(32) shl 8) or u(33)) / 10000.0
        val ah = ((u(35) shl 16) or (u(36) shl 8) or u(37)) / 10000.0
        val soh = if (ah > 0) ah / NEW_CAR_AH * 100.0 else null
        val packV = ((u(20) shl 8) or u(21)) / 100.0
        return s.copy(
            hx = hx.takeIf { it in 0.0..200.0 },
            socPercent = soc.takeIf { it in 0.0..100.0 } ?: s.socPercent,
            ahCapacity = ah.takeIf { it in 0.0..100.0 },
            // Ah/66 estimate only until group 2161 supplies the LBC's own SOH
            sohPercent = s.sohPercent ?: soh?.takeIf { it in 0.0..150.0 },
            packVolts = packV.takeIf { it in 100.0..500.0 } ?: s.packVolts,
            packAmps = amps.takeIf { it in -400.0..400.0 } ?: s.packAmps,
        )
    }

    /**
     * Group 4 (2104): four temp triplets [ADC_hi, ADC_lo, °C]. The LBC already
     * computes °C in the 3rd byte, so use those directly. Sensor 3 is unused
     * on AZE0 (0xFF). Reports the average of the valid sensors.
     */
    private fun group4(s: LeafState, p: ByteArray): LeafState {
        if (p.size < 14) return s
        val cBytes = listOf(4, 7, 13).mapNotNull { i ->
            val v = p[i].toInt() and 0xFF
            if (v == 0xFF) null else p[i].toInt() // signed byte -> °C
        }
        if (cBytes.isEmpty()) return s
        val temps = cBytes.map { it.toDouble() }
        return s.copy(batteryTempC = temps.average(), batteryTempsC = temps)
    }
}

/** ISO-TP reassembly of ELM327 monitor/response text into the payload bytes. */
object IsoTp {

    /**
     * Join the data bytes of the ELM327 frame lines (id + PCI + data) into the
     * ISO-TP payload. Handles single, first, and consecutive frames; trims to
     * the length declared in the first frame (drops padding).
     */
    fun reassemble(raw: String, trim: Boolean = true): ByteArray {
        val out = ArrayList<Int>()
        var declared = -1
        var sawCf = false
        for (tok in raw.trim().split(Regex("\\s+"))) {
            if (tok.length < 5 || !tok.all { it.isHex() }) continue
            val d = tok.substring(3)                 // strip 3-char 11-bit id
            val b = ArrayList<Int>()
            var i = 0
            while (i + 1 < d.length) {
                b.add(d.substring(i, i + 2).toInt(16)); i += 2
            }
            if (b.isEmpty()) continue
            when (b[0] shr 4) {
                0x1 -> {                              // first frame: 2 PCI bytes
                    if (b.size >= 2) declared = ((b[0] and 0xF) shl 8) or b[1]
                    for (j in 2 until b.size) out.add(b[j])
                }
                0x2 -> {                              // consecutive frame: 1 PCI byte
                    sawCf = true
                    for (j in 1 until b.size) out.add(b[j])
                }
                0x0 -> for (j in 1 until b.size) out.add(b[j]) // single frame: 1 PCI byte
            }
        }
        // CFs without their first frame = partial capture; no declared length to
        // trim padding by, so the payload can't be trusted
        if (sawCf && declared < 0) return ByteArray(0)
        // some ECUs declare fewer bytes than they send (the meter's odometer
        // sits past the declared length): trim = false keeps everything
        val res = if (trim && declared in 0..out.size) out.subList(0, declared) else out
        return ByteArray(res.size) { res[it].toByte() }
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
