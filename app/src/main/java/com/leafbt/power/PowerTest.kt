package com.leafbt.power

import java.util.Locale

/**
 * Guided full-throttle step test: hold a steady 30-60 A cruise, count down,
 * floor the pedal, release at the first high-current sample. From the
 * steady and peak (A, V) pairs the pack's DC internal resistance follows as
 * dV/dI. Pure state machine: feed [onSample] per poll cycle (+ = discharge)
 * and [onTick] once a second; read [phase], [countdown], [message].
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
    private var intermediates = 0     // samples between cruise and the >= 250 A step
    private var sawRegen = false
    private var floorSamples = 0
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
            Phase.FLOOR -> {
                floorSamples++
                when {
                    amps >= STEP_A -> {
                        phase = Phase.RELEASE; message = "ВІДПУСКАЙ"
                        peakAmps = amps; peakV = volts; peakCells = cellsMv
                    }
                    amps < 0 -> sawRegen = true
                    amps > CRUISE.endInclusive -> intermediates++
                }
                if (phase == Phase.FLOOR && floorSamples >= FLOOR_TIMEOUT) finish()
            }
            Phase.RELEASE -> if (amps >= STEP_A) {
                if (amps > peakAmps!!) { peakAmps = amps; peakV = volts; peakCells = cellsMv }
            } else finish()
            Phase.RESULT -> {}
        }
    }

    /** Call once a second. */
    fun onTick() {
        if (phase != Phase.COUNTDOWN) return
        countdown--
        message = if (countdown > 0) "$countdown" else "ВТОПИ В ПІДЛОГУ"
        if (countdown <= 0) phase = Phase.FLOOR
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
        intermediates = 0; sawRegen = false; floorSamples = 0
        peakAmps = null; packMilliOhm = null; ok = false
        message = "$countdown"
    }

    private fun finish() {
        phase = Phase.RESULT
        val peak = peakAmps
        val fail = when {
            sawRegen -> "Розгін не зараховано: був реген"
            intermediates > 1 -> "Останній розгін некоректний: не різко натиснули газ"
            steadyCount < MIN_STEADY -> "Мало часу перед розгоном"
            peak == null || peak < PEAK_A -> "Не втопили в підлогу"
            else -> null
        }
        if (fail != null) { ok = false; message = fail; return }
        val r = (steadyV - peakV) / (peak!! - steadyAmps!!) * 1000.0
        packMilliOhm = r
        ok = true
        message = String.format(
            Locale.US, "OK: %.0f A / %.1f V → %.0f A / %.1f V\nR = %.0f мОм (%.2f мОм/ячейку)",
            steadyAmps, steadyV, peak, peakV, r, r / 96.0,
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
    }

    companion object {
        val CRUISE = 30.0..60.0
        const val STEP_A = 250.0        // "release" threshold
        const val PEAK_A = 270.0        // below this = not floored
        const val COUNTDOWN_S = 10
        const val MIN_STEADY = 4        // cruise samples needed before the step
        const val FLOOR_TIMEOUT = 8     // samples after the floor command
        const val IDLE_MSG = "Розженіться на круїзі до ~60 км/год і тримайте рівний газ (30–60 А)"
        const val READY_MSG = "Натисніть Start test, потім виїжджайте на рівну дорогу"
    }
}
