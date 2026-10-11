package com.leafbt.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTestTest {

    private fun PowerTest.cruise(n: Int, a: Double = 50.0, v: Double = 385.0) =
        repeat(n) { onSample(a, v) }

    /** Cruise, let the countdown run out, then floor it with the given samples. */
    private fun run(steady: Int, vararg accel: Double): PowerTest {
        val t = PowerTest().apply { start() }
        t.cruise(steady, v = 385.0 - 50.0 * 0.12)        // same 120 mOhm model as below
        repeat(PowerTest.COUNTDOWN_S) { t.onTick() }
        assertEquals(PowerTest.Phase.FLOOR, t.phase)
        for (a in accel) t.onSample(a, 385.0 - a * 0.12)   // ~120 mOhm pack
        return t
    }

    @Test fun ignoresSamplesUntilStarted() {
        val t = PowerTest()
        assertEquals(PowerTest.Phase.READY, t.phase)
        t.onSample(45.0, 385.0)
        assertEquals(PowerTest.Phase.READY, t.phase)
        t.start()
        assertEquals(PowerTest.Phase.IDLE, t.phase)
    }

    @Test fun idleUntilCruiseCurrent() {
        val t = PowerTest().apply { start() }
        assertEquals(PowerTest.Phase.IDLE, t.phase)
        t.onSample(5.0, 390.0)
        assertEquals(PowerTest.Phase.IDLE, t.phase)
        t.onSample(45.0, 385.0)
        assertEquals(PowerTest.Phase.COUNTDOWN, t.phase)
        assertEquals(PowerTest.COUNTDOWN_S, t.countdown)
    }

    @Test fun leavingCruiseWindowResets() {
        val t = PowerTest().apply { start() }
        t.cruise(2)
        t.onTick()
        t.onSample(-12.0, 390.0)          // lifted off: regen
        assertEquals(PowerTest.Phase.IDLE, t.phase)
        assertTrue(t.message.contains("30"))
    }

    @Test fun countdownReachesFloorCommand() {
        val t = PowerTest().apply { start() }
        t.cruise(1)
        repeat(PowerTest.COUNTDOWN_S - 1) { t.onTick() }
        assertEquals(PowerTest.Phase.COUNTDOWN, t.phase)
        assertEquals(1, t.countdown)
        t.onTick()
        assertEquals(PowerTest.Phase.FLOOR, t.phase)
    }

    @Test fun cleanStepGivesResistance() {
        val t = run(6, 290.0, 300.0, 120.0)        // one step to peak, then released
        assertEquals(PowerTest.Phase.RESULT, t.phase)
        assertTrue(t.ok)
        // (385-0.12*50) -> (385-0.12*300): dV/dI = 0.12 ohm = 120 mOhm
        assertEquals(120.0, t.packMilliOhm!!, 0.5)
        assertEquals(300.0, t.peakAmps!!, 1e-9)
        assertTrue(t.message.contains("120"))
    }

    @Test fun releaseShownAfterHoldSamples() {
        val t = PowerTest().apply { start() }
        t.cruise(5)
        repeat(PowerTest.COUNTDOWN_S) { t.onTick() }
        t.onSample(280.0, 350.0)
        assertEquals(PowerTest.Phase.FLOOR, t.phase)
        assertEquals("ТРИМАЙ", t.message)
        t.onSample(280.0, 350.0)
        assertEquals(PowerTest.Phase.RELEASE, t.phase)
    }

    @Test fun twoIntermediateStepsIsNotAbrupt() {
        val t = run(6, 80.0, 120.0, 290.0, 300.0, 50.0)
        assertEquals(PowerTest.Phase.RESULT, t.phase)
        assertTrue(!t.ok)
        assertTrue(t.message, t.message.contains("різко"))
    }

    @Test fun oneIntermediateStepIsFine() {
        val t = run(6, 100.0, 290.0, 300.0, 50.0)
        assertTrue(t.ok)
    }

    @Test fun regenBeforeStepVoidsRun() {
        val t = run(6, -20.0, 290.0, 300.0, 50.0)
        assertTrue(!t.ok)
        assertTrue(t.message, t.message.contains("реген"))
    }

    @Test fun tooFewCruiseSamples() {
        val t = run(3, 290.0, 300.0, 50.0)
        assertTrue(!t.ok)
        assertTrue(t.message, t.message.contains("Мало часу"))
    }

    @Test fun notFlooredWhenPeakPowerBelow70kW() {
        val t = run(6, 160.0, 170.0, 50.0)       // 170 A * 365 V = 62 kW
        assertTrue(!t.ok)
        assertTrue(t.message, t.message.contains("підлогу"))
        assertTrue(t.message, t.message.contains("62 кВт"))
    }

    @Test fun realRunFloorsAt245AmpsAndFirstSampleHasStaleVolts() {
        // from the 2026-10-11 log: cruise ~55 A / 371.4 V, step 234 A (V still 366.6),
        // 243 A / 347.4 V, 246 A / 345.5 V, then released
        val t = PowerTest()
        t.start()
        repeat(6) { t.onSample(55.0, 371.4) }
        repeat(PowerTest.COUNTDOWN_S) { t.onTick() }
        t.onSample(234.0, 366.6)
        assertEquals(PowerTest.Phase.FLOOR, t.phase)
        t.onSample(243.2, 347.4)
        assertEquals(PowerTest.Phase.RELEASE, t.phase)
        t.onSample(245.7, 345.5)
        t.onSample(10.0, 374.0)
        assertTrue(t.message, t.ok)
        assertEquals(136.0, t.packMilliOhm!!, 1.0)
        assertTrue(t.message, t.message.contains("85 кВт"))
    }

    @Test fun peakIsTheDeepestSagNotTheFirstHighSample() {
        // 2026-10-11 second run: row 18 = 253 A but pack V (361.9) and most
        // cells still pre-step; row 19 = 248 A / 340.1 V fully settled
        val t = PowerTest()
        t.start()
        val cruise = List(96) { 3790 }
        val mixed = List(96) { i -> if (i < 12) 3540 else 3790 }     // only cells 1-12 refreshed
        val settled = List(96) { 3530 }
        repeat(6) { t.onSample(45.2, 364.9, cruise) }
        repeat(PowerTest.COUNTDOWN_S) { t.onTick() }
        t.onSample(253.2, 361.9, mixed)
        t.onSample(248.4, 340.1, settled)
        t.onSample(8.0, 370.0, cruise)
        assertTrue(t.message, t.ok)
        assertEquals(248.4, t.peakAmps!!, 1e-9)
        assertEquals(122.0, t.packMilliOhm!!, 1.0)
        assertEquals(260, t.worstDropMv)
    }

    @Test fun floorPhaseShowsLiveAmpsAndSecondsLeft() {
        val t = run(6, 58.0)
        assertEquals("58 A · треба ≥150 A · 7 с", t.detail)
    }

    @Test fun releasingBeforeHoldStillGrades() {
        val t = run(6, 290.0, 50.0)
        assertEquals(PowerTest.Phase.RESULT, t.phase)
        assertTrue(t.ok)
    }

    @Test fun noStepAtAllTimesOut() {
        val t = run(6, 55.0, 60.0, 58.0, 57.0, 55.0, 56.0, 54.0, 55.0)
        assertEquals(PowerTest.Phase.RESULT, t.phase)
        assertTrue(!t.ok)
        assertTrue(t.message, t.message.contains("підлогу"))
    }

    @Test fun reportsWorstCellDrop() {
        val t = PowerTest().apply { start() }
        val cruiseCells = List(96) { 3950 }
        val peakCells = List(96) { i -> if (i == 16) 3400 else 3700 }   // cell #17 sags most
        repeat(5) { t.onSample(50.0, 379.0, cruiseCells) }
        repeat(PowerTest.COUNTDOWN_S) { t.onTick() }
        t.onSample(290.0, 349.0, peakCells)
        t.onSample(290.0, 349.0, peakCells)
        t.onSample(40.0, 380.0, cruiseCells)
        assertTrue(t.ok)
        assertEquals(17, t.worstCell)
        assertEquals(550, t.worstDropMv)
        assertTrue(t.message, t.message.contains("#17"))
    }

    @Test fun restartGoesIdle() {
        val t = run(6, 290.0, 50.0)
        t.restart()
        assertEquals(PowerTest.Phase.IDLE, t.phase)
    }
}
