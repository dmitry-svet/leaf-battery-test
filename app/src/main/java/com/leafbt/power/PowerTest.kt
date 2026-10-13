package com.leafbt.power

import java.util.Locale

/**
 * Guided full-throttle step test: hold a steady 30-60 A cruise, count down,
 * floor the pedal, release after [HOLD] high-current samples. The peak is
 * the [HOLD]th high sample (a fixed ~3 s hold, so runs are comparable: R
 * keeps growing with hold time), never the first one, whose pack voltage
 * and cell table are still partly pre-step. From the steady and peak
 * (A, V) pairs the pack's DC internal resistance follows as dV/dI.
 * "Floored" is judged by peak power, not amps: the motor is power-limited
 * (~80 kW), so full throttle is ~210-250 A depending on pack voltage.
 * Pure state machine: feed [onSample] per poll cycle (+ = discharge) and
 * [onTick] once a second; read [phase], [countdown], [message], [detail].
 */
class PowerTest {

    enum class Phase { READY, IDLE, COUNTDOWN, FLOOR, RELEASE, RESULT }

    /** READY = not armed yet: samples are ignored until [start]. */
    var phase = Phase.READY
        private set
    var countdown = COUNTDOWN_S
        private set
    var message = READY_MSG
        private set
    /** Small line under [message]: live amps / what is still needed. */
    var detail: String? = null
        private set
    var ok = false
        private set
    var packMilliOhm: Double? = null
        private set
    var peakAmps: Double? = null
        private set
    var steadyAmps: Double? = null
        private set

    /** Cell with the biggest voltage drop cruise -> peak (1-based), and that drop in mV. */
    var worstCell: Int? = null
        private set
    var worstDropMv: Int? = null
        private set

    private var steadyCount = 0
    private var steadyV = 0.0
    private var steadyCells: List<Int> = emptyList()
    private var peakCells: List<Int> = emptyList()
    private var intermediates = 0     // samples between cruise and the >= STEP_A step
    private var sawRegen = false
    private var floorSamples = 0
    private var highSamples = 0       // consecutive samples >= STEP_A
    private var peakV = 0.0

    fun onSample(amps: Double, volts: Double, cellsMv: List<Int> = emptyList()) {
        when (phase) {
            Phase.READY -> {}
            Phase.IDLE -> if (amps in CRUISE) startCountdown(amps, volts, cellsMv)
            Phase.COUNTDOWN -> if (amps in CRUISE) {
                steadyCount++; steadyAmps = amps; steadyV = volts; steadyCells = cellsMv
            } else {
                reset("Струм вийшов за 30–60 А. Тримайте рівний газ і спробуйте ще.")
            }
            Phase.FLOOR -> when {
                amps >= STEP_A -> {
                    highSamples++
                    if (highSamples > 1) { peakAmps = amps; peakV = volts; peakCells = cellsMv }
                    if (highSamples >= HOLD) {
                        phase = Phase.RELEASE; message = "ВІДПУСКАЙ"; detail = null
                    } else {
                        message = "ТРИМАЙ"
                        detail = String.format(Locale.US, "%.0f A · ще %d", amps, HOLD - highSamples)
                    }
                }
                highSamples > 0 -> finish()          // let go before HOLD samples: grade what we have
                else -> {
                    floorSamples++
                    if (amps < 0) sawRegen = true
                    else if (amps > CRUISE.endInclusive) intermediates++
                    val left = FLOOR_TIMEOUT - floorSamples
                    detail = String.format(Locale.US, "%.0f A · треба ≥%.0f A · %d с", amps, STEP_A, left)
                    if (left <= 0) finish()
                }
            }
            Phase.RELEASE -> if (amps < STEP_A) finish()      // longer holds do not move the peak
            Phase.RESULT -> {}
        }
    }

    /** Call once a second. */
    fun onTick() {
        if (phase != Phase.COUNTDOWN) return
        countdown--
        message = if (countdown > 0) "$countdown" else "ВТОПИ В ПІДЛОГУ"
        if (countdown <= 0) { phase = Phase.FLOOR; detail = String.format(Locale.US, "треба ≥%.0f A", STEP_A) }
    }

    /** Arm the test (from READY or after a RESULT): start listening for the cruise. */
    fun start() = reset(IDLE_MSG)

    /** Same as [start]; kept for the mid-run "restart" button. */
    fun restart() = reset(IDLE_MSG)

    private fun startCountdown(amps: Double, volts: Double, cellsMv: List<Int>) {
        phase = Phase.COUNTDOWN
        countdown = COUNTDOWN_S
        steadyCount = 1; steadyAmps = amps; steadyV = volts; steadyCells = cellsMv
        peakCells = emptyList(); worstCell = null; worstDropMv = null
        intermediates = 0; sawRegen = false; floorSamples = 0; highSamples = 0
        peakAmps = null; packMilliOhm = null; ok = false
        message = "$countdown"; detail = null
    }

    private fun finish() {
        phase = Phase.RESULT
        detail = null
        val peak = peakAmps
        val peakKw = if (peak == null) 0.0 else peak * peakV / 1000.0
        val fail = when {
            sawRegen -> "Розгін не зараховано: був реген"
            intermediates > 1 -> "Останній розгін некоректний: не різко натиснули газ"
            steadyCount < MIN_STEADY -> "Мало часу перед розгоном"
            peak == null && highSamples == 1 -> "Рано відпустили: після ступеня тримайте ще 2 с"
            peak == null -> "Не втопили в підлогу"
            peakKw < PEAK_KW -> String.format(Locale.US, "Не втопили в підлогу: пік %.0f кВт, треба ≥%.0f", peakKw, PEAK_KW)
            else -> null
        }
        if (fail != null) { ok = false; message = fail; return }
        val r = (steadyV - peakV) / (peak!! - steadyAmps!!) * 1000.0
        packMilliOhm = r
        ok = true
        message = String.format(
            Locale.US, "OK: %.0f A / %.1f V → %.0f A / %.1f V (%.0f кВт, %d с)\nR = %.0f мОм (%.2f мОм/ячейку)",
            steadyAmps, steadyV, peak, peakV, peakKw, minOf(highSamples, HOLD), r, r / 96.0,
        )
        if (steadyCells.size == 96 && peakCells.size == 96) {
            val drops = steadyCells.indices.map { steadyCells[it] - peakCells[it] }
            val worst = drops.indices.maxByOrNull { drops[it] }!!
            worstCell = worst + 1
            worstDropMv = drops[worst]
            val minPeak = peakCells.indices.minByOrNull { peakCells[it] }!!
            message += String.format(
                Locale.US, "\nНайбільша просадка: #%d −%d мВ (%.3f V на піку)\nМін. на піку: #%d %.3f V",
                worst + 1, drops[worst], peakCells[worst] / 1000.0, minPeak + 1, peakCells[minPeak] / 1000.0,
            )
        }
    }

    private fun reset(msg: String) {
        phase = Phase.IDLE
        countdown = COUNTDOWN_S
        message = msg
        detail = null
    }

    companion object {
        val CRUISE = 30.0..60.0
        const val STEP_A = 150.0        // a sample at/above this = the step has happened
        const val HOLD = 3              // high samples to hold; the HOLDth one is the peak (first has stale V)
        const val PEAK_KW = 70.0        // peak A*V below this = not floored (motor limit ~80 kW)
        const val COUNTDOWN_S = 10
        const val MIN_STEADY = 4        // cruise samples needed before the step
        const val FLOOR_TIMEOUT = 8     // samples after the floor command
        const val IDLE_MSG = "Розженіться на круїзі до ~60 км/год і тримайте рівний газ (30–60 А)"
        const val READY_MSG = "Натисніть Start test, потім виїжджайте на рівну дорогу"
    }
}
