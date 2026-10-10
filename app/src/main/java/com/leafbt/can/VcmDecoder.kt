package com.leafbt.can

/**
 * Decodes VCM (request 0x797 / reply 0x79A) UDS ReadDataByIdentifier replies
 * (`62 DID_hi DID_lo data...`). DIDs and scaling from the public decodes in
 * OVMS vehicle_nissanleaf.cpp and OBDb Nissan-Leaf (2013 support list);
 * several are single-source and still need checking on the car.
 */
object VcmDecoder {

    /** DIDs polled every cycle (live values). */
    val FAST = listOf(0x1146, 0x1152, 0x1151, 0x1156, 0x1183, 0x1255, 0x1254)

    /** DIDs that change slowly (every 10th cycle): keeps the poll cycle short. */
    val SLOW = listOf(0x1261, 0x1262, 0x1103, 0x1304, 0x1203, 0x1205, 0x1234, 0x114E, 0x1236, 0x115D)

    fun apply(s: LeafState, did: Int, p: ByteArray): LeafState {
        if (p.size < 4 || u(p, 0) != 0x62 || ((u(p, 1) shl 8) or u(p, 2)) != did) return s
        fun d(i: Int) = u(p, 3 + i)
        fun u16() = (d(0) shl 8) or d(1)
        fun s16() = u16().toShort().toInt()
        val two = p.size >= 5
        return when (did) {
            0x1203 -> if (two && u16() != 0xFFFF) s.copy(qcCount = u16()) else s
            0x1205 -> if (two && u16() != 0xFFFF) s.copy(l1l2Count = u16()) else s
            0x1183 -> if (two) s.copy(aux12A = s16() / 256.0) else s
            0x1146 -> if (two) s.copy(motorPowerW = s16() * 40) else s
            0x1152 -> s.copy(auxPower100W = d(0))
            0x1151 -> s.copy(acPower250W = d(0))
            0x1261 -> s.copy(estAcPower50W = d(0))
            0x1262 -> s.copy(estHeaterPower250W = d(0))
            0x1234 -> s.copy(plugState = d(0))
            0x114E -> s.copy(chargeMode = d(0))
            0x1236 -> if (two) s.copy(chargePowerW = u16() * 100) else s
            0x1156 -> s.copy(gear = d(0))
            0x1103 -> s.copy(aux12V = (d(0) / 12.5).takeIf { it in 5.0..20.0 } ?: s.aux12V)
            0x115D -> s.copy(ambientTempVcmC = ((d(0) * 0.9 - 40.9) - 32.0) / 1.8)   // OBDb: F
            0x1304 -> s.copy(powerSwitch = d(0) and 0x80 != 0)
            0x1255 -> if (two) s.copy(motorRpm = s16()) else s
            0x1254 -> if (two) s.copy(torqueNm = s16() / 64.0) else s
            else -> s
        }
    }

    /** VIN from the `21 81` reply: 17 ASCII chars after `61 81`, padding dropped. */
    fun vin(p: ByteArray): String? {
        if (p.size < 19 || u(p, 0) != 0x61 || u(p, 1) != 0x81) return null
        // AZE0 may pad with ESC (0x1B) instead of NUL: keep only VIN characters
        val v = String(p, 2, p.size - 2, Charsets.US_ASCII).filter { it.isLetterOrDigit() }
        return v.takeIf { it.length == 17 }
    }

    private fun u(p: ByteArray, i: Int) = if (i < p.size) p[i].toInt() and 0xFF else 0
}
