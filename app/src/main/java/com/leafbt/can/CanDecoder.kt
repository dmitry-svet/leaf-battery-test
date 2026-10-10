package com.leafbt.can

/**
 * Decodes Nissan Leaf AZE0 (24/30 kWh) broadcast CAN frames into [LeafState].
 *
 * Pure: [apply] folds one frame into the previous state and returns a new one.
 * Unknown ids return the state unchanged.
 *
 * IMPORTANT: the byte-level formulas here are the *documented / community*
 * decodings and are APPROXIMATE. Bit offsets, scaling and current sign are
 * verified and tuned against the real car in the on-car step. Unit tests only
 * assert that this code implements the formulas below correctly.
 */
object CanDecoder {

    /** 0x385 tire pressures, PSI = byte / 4, bytes 2..5 = FL, FR, RR, RL; 0 = no data (OVMS). */
    fun tires(f: CanFrame): List<Double?> = (2..5).map { i -> f.u(i).takeIf { it > 0 }?.let { it / 4.0 } }

    /**
     * 0x5B3 (VCM, car-CAN) gids = ((d4 & 3) << 8) | d5, per the AZE0 car-CAN DBC.
     * OVMS reports not seeing this frame on AZE0 - to be confirmed on the car.
     */
    fun gids5b3(f: CanFrame): Int? = (((f.u(4) and 0x03) shl 8) or f.u(5)).takeIf { it in 1..1022 }

    // Frame ids we care about (MVP, passive broadcast).
    const val ID_PACK = 0x1DB   // pack volts + current
    const val ID_SOC = 0x55B    // state of charge
    const val ID_GIDS = 0x5BC   // gids (capacity/energy)
    const val ID_TEMP = 0x5C0   // battery temperature (muxed)
    const val ID_SPEED = 0x284  // vehicle speed  (TBD - verify on car)

    fun apply(state: LeafState, f: CanFrame): LeafState = when (f.id) {
        ID_PACK -> state.copy(
            packAmps = decodeAmps(f),
            packVolts = decodeVolts(f),
        )
        ID_SOC -> state.copy(socPercent = decodeSoc(f))
        ID_GIDS -> state.copy(gids = decodeGids(f))
        ID_TEMP -> decodeTemp(f)?.let { state.copy(batteryTempC = it) } ?: state
        ID_SPEED -> state.copy(speedKmh = decodeSpeed(f))
        else -> state
    }

    // --- 0x1DB : pack current (bytes 0..1) and voltage (bytes 2..3) ---

    /** Current, A. 11-bit signed, 0.5 A/LSB. + = discharge, - = charge/regen. */
    fun decodeAmps(f: CanFrame): Double {
        val raw = (f.u(0) shl 3) or (f.u(1) shr 5)      // 11 bits
        return signExtend(raw, 11) / 2.0
    }

    /** Pack voltage, V. 10-bit, 0.5 V/LSB. */
    fun decodeVolts(f: CanFrame): Double {
        val raw = (f.u(2) shl 2) or (f.u(3) shr 6)      // 10 bits
        return raw / 2.0
    }

    // --- 0x55B : SOC (bytes 0..1, 10-bit, 0.1 %/LSB) ---
    fun decodeSoc(f: CanFrame): Double {
        val raw = (f.u(0) shl 2) or (f.u(1) shr 6)
        return raw / 10.0
    }

    // --- 0x5BC : gids (bytes 0..1, high 10 bits) ---
    fun decodeGids(f: CanFrame): Int =
        (f.u(0) shl 2) or (f.u(1) shr 6)

    // --- 0x5C0 : battery temperature, muxed on top 2 bits of byte 0 ---
    /** Returns °C when this frame carries the temperature group, else null. */
    fun decodeTemp(f: CanFrame): Double? {
        val group = f.u(0) shr 6
        if (group != 1) return null            // only group 1 carries pack temp
        return (f.u(2) / 2.0) - 40.0
    }

    // --- 0x284 : vehicle speed (bytes 4..5). Scaling UNCONFIRMED. ---
    fun decodeSpeed(f: CanFrame): Double {
        val raw = (f.u(4) shl 8) or f.u(5)
        return raw / 100.0                     // km/h  (verify on car)
    }

    /** Two's-complement sign-extend the low [bits] of [value]. */
    private fun signExtend(value: Int, bits: Int): Int {
        val sign = 1 shl (bits - 1)
        return (value and (sign - 1)) - (value and sign)
    }
}
