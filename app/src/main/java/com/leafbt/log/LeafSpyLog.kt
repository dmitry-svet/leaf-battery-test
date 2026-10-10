package com.leafbt.log

import com.leafbt.can.LeafState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-sample data log in LeafSpy's published CSV layout (LeafSpy Help,
 * "Log File Format" plus the trailing columns a real v0.52 log has, 161 in
 * all, same labels and order) so tools that read LeafSpy logs read ours.
 * Value formats follow a real log (plain numbers, "none" for the missing
 * sensor). Values LeafDash cannot read (GPS, regen Wh, A/C pressure, motor
 * and inverter temps...) are left blank.
 */
object LeafSpyLog {

    val HEADER: List<String> = listOf(
        "Date/Time", "Lat", "Long", "Elv", "Speed", "Gids", "SOC", "AHr",
        "Pack Volts", "Pack Amps", "Max CP mV", "Min CP mV", "Avg CP mV",
        "CP mV Diff", "Judgment Value",
        "Pack T1 F", "Pack T1 C", "Pack T2 F", "Pack T2 C",
        "Pack T3 F", "Pack T3 C", "Pack T4 F", "Pack T4 C",
    ) + (1..96).map { "CP$it" } + listOf(
        "12v Bat Amps", "VIN", "Hx", "12v Bat Volts", "Odo(km)", "QC", "L1/L2",
        "TP-FL", "TP-FR", "TP-RR", "TP-RL", "Ambient", "SOH", "RegenWh",
        "BLevel", "epoch time", "Motor Pwr(w)", "Aux Pwr(100w)",
        "A/C Pwr(250w)", "A/C Comp(0.1MPa)", "Est Pwr A/C(50w)",
        "Est Pwr Htr(250w)", "Plug State", "Charge Mode", "OBC Out Pwr", "Gear",
        "HVolt1", "HVolt2", "GPS Status", "Power SW", "BMS", "OBC",
        // trailing columns of a real v0.52 log (not in the help PDF); the
        // leading space in " Speed1"/" Speed2" and the second "Debug" are
        // LeafSpy's own quirks, kept so headers match byte for byte
        "Debug", "Motor Temp", "Inverter 2 Temp", "Inverter 4 Temp",
        " Speed1", " Speed2", "Wiper Status", "Torque Nm", "RPM", "Debug",
    )

    fun row(tMs: Long, leaf: LeafState, odoKm: Double?, phoneBattery: Int?): List<String> {
        val cells = leaf.cellsMv
        val valid = cells.filter { it in 1000..4500 }        // raw cells may hold junk reads
        val max = valid.maxOrNull()
        val min = valid.minOrNull()
        val avg = if (valid.isEmpty()) null else valid.average()
        // AZE0 has no sensor 3 ("none" in LeafSpy logs): readings are sensors 1, 2 and 4
        val t = leaf.batteryTempsC
        val (t1, t2, t4) = if (t.size == 3) Triple(t[0], t[1], t[2])
            else Triple(t.getOrNull(0), t.getOrNull(1), t.getOrNull(2))
        val packV = avg?.let { it * 96 / 1000.0 } ?: leaf.packVolts
        return listOf(
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date(tMs)),
            "", "", "",                                        // Lat, Long, Elv (no GPS)
            f(leaf.speedKmh, 1),                               // car speed (LeafSpy: GPS)
            leaf.gids?.toString() ?: "",                       // 0x5B3 (sent only in READY)
            leaf.socPercent?.let { Math.round(it * 10000).toString() } ?: "",
            leaf.ahCapacity?.let { Math.round(it * 10000).toString() } ?: "",
            f(packV, 2),
            f(leaf.packAmps, 3),                               // LBC 2101, + = discharge
            max?.toString() ?: "", min?.toString() ?: "",
            avg?.let { Math.round(it).toString() } ?: "",
            if (max != null && min != null) (max - min).toString() else "",
            "0",                                               // Judgment Value: not computed
            f(t1?.let(::toF), 1), f(t1, 1),
            f(t2?.let(::toF), 1), f(t2, 1),
            "none", "none",
            f(t4?.let(::toF), 1), f(t4, 1),
        ) + (0 until 96).map { cells.getOrNull(it)?.toString() ?: "" } + listOf(
            f(leaf.aux12A, 2),
            leaf.vin ?: "",
            f(leaf.hx, 2),
            f(leaf.aux12V, 2),
            f(odoKm, 0),
            i(leaf.qcCount), i(leaf.l1l2Count),
        ) + (0 until 4).map { f(leaf.tiresPsi.getOrNull(it), 2) } + listOf(  // FL FR RR RL
            f(leaf.ambientTempC?.let(::toF), 1),
            f(leaf.sohPercent, 2),
            "",                                                // RegenWh: no counter
            phoneBattery?.toString() ?: "",
            String.format(Locale.US, "%d.%03d", tMs / 1000, tMs % 1000),
            i(leaf.motorPowerW), i(leaf.auxPower100W), i(leaf.acPower250W),
            "",                                                // A/C compressor pressure
            i(leaf.estAcPower50W), i(leaf.estHeaterPower250W),
            i(leaf.plugState), i(leaf.chargeMode), i(leaf.chargePowerW), i(leaf.gear),
            f(leaf.packVolts, 2), f(leaf.packVolts, 2),       // HVolt1/2: LBC pack V (equal in LeafSpy logs too)
            "",                                                // GPS Status
            leaf.powerSwitch?.let { if (it) "1" else "0" } ?: "",
            "1", "0",                                          // BMS read, OBC not read
            "",                                                // Debug
            "", "", "",                                        // motor / inverter temps: not read
            leaf.speedKmh?.let { Math.round(it * 100).toString() } ?: "",   // Speed1 = raw 0x284 count
            "",                                                // Speed2
            "",                                                // Wiper Status
            f(leaf.torqueNm, 2),
            i(leaf.motorRpm),
            "",                                                // Debug
        )
    }

    private fun i(v: Int?) = v?.toString() ?: ""

    private fun toF(c: Double) = c * 1.8 + 32.0

    private fun f(v: Double?, digits: Int): String =
        if (v == null) "" else String.format(Locale.US, "%.${digits}f", v)
}
