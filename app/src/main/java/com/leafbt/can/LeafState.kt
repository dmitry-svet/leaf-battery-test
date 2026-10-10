package com.leafbt.can

/**
 * Latest known vehicle values. Fields are null until the matching frame has
 * been seen. Immutable; [CanDecoder] returns an updated copy per frame.
 */
data class LeafState(
    val socPercent: Double? = null,
    val gids: Int? = null,
    val packVolts: Double? = null,
    val packAmps: Double? = null,      // + = discharge, - = regen/charge
    val batteryTempC: Double? = null,
    val ambientTempC: Double? = null,
    val aux12V: Double? = null,        // 12V battery, measured by the ELM327 (ATRV)
    val speedKmh: Double? = null,
    // from active LBC group polling
    val sohPercent: Double? = null,
    /** True once [sohPercent] comes from LBC group 2161 (not the Ah/66 estimate). */
    val sohFromLbc: Boolean = false,
    val ahCapacity: Double? = null,
    val hx: Double? = null,
    val batteryTempsC: List<Double> = emptyList(),
    /** Weakest / strongest cell voltage (V), from LBC group 2 (96 cells). */
    val cellMinV: Double? = null,
    val cellMaxV: Double? = null,
    /** All 96 cell pair voltages (mV), cell 1 first; empty until group 2 read. */
    val cellsMv: List<Int> = emptyList(),
    /** Balancing shunt active per cell pair (LBC group 6), cell 1 first. */
    val shunts: List<Boolean> = emptyList(),
    // VCM (0x797) UDS reads; units as in the LeafSpy log where it has a column
    val vin: String? = null,
    val qcCount: Int? = null,
    val l1l2Count: Int? = null,
    val aux12A: Double? = null,           // 12V battery current, - = drain
    val motorPowerW: Int? = null,
    val auxPower100W: Int? = null,
    val acPower250W: Int? = null,         // A/C incl. PTC heater
    val estAcPower50W: Int? = null,
    val estHeaterPower250W: Int? = null,
    val plugState: Int? = null,           // 0 unplugged, 1 partial, 2 plugged
    val chargeMode: Int? = null,          // 0 none, 1 L1, 2 L2, 3 QC
    val chargePowerW: Int? = null,
    val gear: Int? = null,                // 1 P, 2 R, 3 N, 4 D, 7 B/Eco
    val powerSwitch: Boolean? = null,     // VCM 1304: car power switch on
    val motorRpm: Int? = null,            // VCM 1255
    val torqueNm: Double? = null,         // VCM 1254, /64
    /** Outside temp from the VCM (115D), degrees C; the 0x510 one feeds the tile. */
    val ambientTempVcmC: Double? = null,
    /** Odometer in km from the meter ECU (0x743 group 1): the dash value, no unit guess. */
    val meterOdoKm: Int? = null,
    /** Tire pressures PSI: FL, FR, RR, RL (0x385); null = no data. */
    val tiresPsi: List<Double?> = emptyList(),
) {
    /** Instant DC power at the pack, kW. + discharge, - charge/regen. */
    val powerKw: Double?
        get() = if (packVolts != null && packAmps != null) packVolts * packAmps / 1000.0 else null

    /**
     * Usable energy remaining, kWh = SOC × capacity × NOMINAL voltage. Uses a
     * fixed nominal voltage (not live pack V) so it tracks SOC cleanly — live V
     * sags under load and would inject phantom consumption into economy.
     */
    val kwhRemaining: Double?
        get() = when {
            socPercent != null && ahCapacity != null ->
                socPercent / 100.0 * ahCapacity * NOMINAL_V / 1000.0
            gids != null -> gids * GID_WH / 1000.0
            else -> null
        }

    /** gids: broadcast value if present, else derived from kWh remaining. */
    val gidsValue: Int?
        get() = gids ?: kwhRemaining?.let { Math.round(it * 1000.0 / GID_WH).toInt() }

    companion object {
        /** Wh per gid (LeafSpy default 77.5). */
        const val GID_WH = 77.5

        /** Nominal AZE0 pack voltage for stable energy estimation. */
        const val NOMINAL_V = 360.0
    }
}
