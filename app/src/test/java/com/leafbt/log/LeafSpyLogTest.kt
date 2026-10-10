package com.leafbt.log

import com.leafbt.can.LeafState
import org.junit.Assert.assertEquals
import org.junit.Test

class LeafSpyLogTest {

    private val cells = List(96) { 4040 + it % 20 }     // 4040..4059 mV
    private val leaf = LeafState(
        socPercent = 86.5123,
        ahCapacity = 43.30,
        packVolts = 389.17,
        aux12V = 12.96,
        ambientTempC = 20.0,
        speedKmh = 42.0,
        sohPercent = 66.21,
        hx = 47.93,
        batteryTempsC = listOf(21.3, 21.4, 20.6),
        cellMinV = 4.040,
        cellMaxV = 4.059,
        cellsMv = cells,
    )
    private val row = LeafSpyLog.row(
        tMs = 1_791_000_000_000, leaf = leaf, odoKm = 180328.4, phoneBattery = 67,
    )
    private fun col(name: String) = row[LeafSpyLog.HEADER.indexOf(name)]

    @Test fun headerMatchesLeafSpyLayout() {
        assertEquals(161, LeafSpyLog.HEADER.size)              // columns A..EU + v0.52 extras
        assertEquals("Date/Time", LeafSpyLog.HEADER[0])
        assertEquals("CP1", LeafSpyLog.HEADER[23])             // column X
        assertEquals("CP96", LeafSpyLog.HEADER[118])           // column DO
        assertEquals("OBC", LeafSpyLog.HEADER[150])            // column EU
        assertEquals("Judgment Value", LeafSpyLog.HEADER[14])
        assertEquals("OBC Out Pwr", LeafSpyLog.HEADER[143])
        assertEquals(listOf("Debug", "Motor Temp", "Inverter 2 Temp", "Inverter 4 Temp",
            " Speed1", " Speed2", "Wiper Status", "Torque Nm", "RPM", "Debug"), LeafSpyLog.HEADER.drop(151))
    }

    @Test fun rowHasOneValuePerColumn() {
        assertEquals(LeafSpyLog.HEADER.size, row.size)
    }

    @Test fun socAndAhAreScaledIntegers() {
        assertEquals("865123", col("SOC"))
        assertEquals("433000", col("AHr"))
    }

    @Test fun cellStatsAndAllCells() {
        assertEquals("4059", col("Max CP mV"))
        assertEquals("4040", col("Min CP mV"))
        assertEquals("19", col("CP mV Diff"))
        assertEquals("4040", col("CP1"))
        assertEquals("4055", col("CP96"))                      // 4040 + 95 % 20
        // pack volts = average cell pair x 96, like LeafSpy
        assertEquals(String.format(java.util.Locale.US, "%.2f", cells.average() * 96 / 1000.0), col("Pack Volts"))
    }

    @Test fun temperaturesMapToSensors124() {
        // AZE0 has no sensor 3: our three readings are sensors 1, 2 and 4
        assertEquals("21.3", col("Pack T1 C"))
        assertEquals("70.3", col("Pack T1 F"))
        assertEquals("21.4", col("Pack T2 C"))
        assertEquals("none", col("Pack T3 C"))
        assertEquals("20.6", col("Pack T4 C"))
    }

    @Test fun vcmAndBroadcastFields() {
        val l = leaf.copy(
            gids = 185, packAmps = -10.0, aux12A = -7.72, vin = "1N4AZ0CP2DC401434",
            qcCount = 186, l1l2Count = 4725, tiresPsi = listOf(39.25, 39.0, 38.75, null),
            motorPowerW = 37800, auxPower100W = 2, acPower250W = 3,
            estAcPower50W = 17, estHeaterPower250W = 8,
            plugState = 2, chargeMode = 2, chargePowerW = 3300, gear = 4,
            motorRpm = 3063, torqueNm = 88.5,
        )
        val r = LeafSpyLog.row(1_791_000_000_000, l, 180328.4, 67)
        fun c(name: String) = r[LeafSpyLog.HEADER.indexOf(name)]
        assertEquals(LeafSpyLog.HEADER.size, r.size)
        assertEquals("185", c("Gids"))
        assertEquals("-10.000", c("Pack Amps"))
        assertEquals("-7.72", c("12v Bat Amps"))
        assertEquals("1N4AZ0CP2DC401434", c("VIN"))
        assertEquals("186", c("QC"))
        assertEquals("4725", c("L1/L2"))
        assertEquals("39.25", c("TP-FL"))
        assertEquals("38.75", c("TP-RR"))
        assertEquals("", c("TP-RL"))
        assertEquals("37800", c("Motor Pwr(w)"))
        assertEquals("2", c("Aux Pwr(100w)"))
        assertEquals("3", c("A/C Pwr(250w)"))
        assertEquals("17", c("Est Pwr A/C(50w)"))
        assertEquals("8", c("Est Pwr Htr(250w)"))
        assertEquals("2", c("Plug State"))
        assertEquals("2", c("Charge Mode"))
        assertEquals("3300", c("OBC Out Pwr"))
        assertEquals("4", c("Gear"))
        assertEquals("3063", c("RPM"))
        assertEquals("88.50", c("Torque Nm"))
    }

    @Test fun otherFields() {
        assertEquals("47.93", col("Hx"))
        assertEquals("66.21", col("SOH"))
        assertEquals("12.96", col("12v Bat Volts"))
        assertEquals("180328", col("Odo(km)"))
        assertEquals("68.0", col("Ambient"))                   // Fahrenheit, one decimal like LeafSpy
        assertEquals("67", col("BLevel"))
        assertEquals("1791000000.000", col("epoch time"))
        assertEquals("389.17", col("HVolt2"))
        assertEquals("4200", col(" Speed1"))                   // 42 km/h x 100
        assertEquals("0", col("Judgment Value"))
        assertEquals("", col("Gids"))
        assertEquals("389.17", col("HVolt1"))
    }
}
