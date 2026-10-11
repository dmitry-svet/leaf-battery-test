package com.leafbt.poll

import com.leafbt.can.GroupDecoder
import com.leafbt.can.IsoTp
import com.leafbt.can.LeafState
import com.leafbt.can.VcmDecoder
import com.leafbt.obd.Elm327
import com.leafbt.transport.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One dongle session tuned for the step test: every cycle reads pack A/V
 * (LBC 2101), all 96 cell voltages (2102) and speed (0x284) - about 1.5 s
 * per sample. Identity and slow values (VIN, SOH/Hx from 2161, pack temps
 * 2104, meter odometer, ambient 0x510) are read until known and then every
 * [SLOW_EVERY] cycles. With [probe] set only 2102 is read, as fast as the
 * dongle allows (~0.4 s), to measure how often the LBC refreshes its cell
 * table. [runBlocking] blocks: call it on an IO thread.
 */
class BatteryPoller(
    private val transport: Transport,
    private val stallTimeoutMs: Long = 30_000,
) {
    private val elm = Elm327(transport)

    private val _state = MutableStateFlow(BatteryState(connecting = true))
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    @Volatile private var running = false
    /** Cells probe: read only 2102 back to back (no 2101/speed/slow reads, no sleep). */
    @Volatile var probe = false
    private var leaf = LeafState()
    private var cycle = 0
    private val fails = HashMap<String, Int>()      // consecutive failures per slow read
    @Volatile private var lastProgressMs = 0L

    private fun status(msg: String) {
        lastProgressMs = System.currentTimeMillis()
        _state.value = _state.value.copy(connecting = true, connectMsg = msg)
    }

    fun runBlocking() {
        running = true
        lastProgressMs = System.currentTimeMillis()
        val watchdog = startWatchdog()
        try {
            status("Opening Bluetooth...")
            transport.open()
            elm.initActive { cmd -> status("Init ELM: $cmd") }
            status("Reading battery...")
            while (running) {
                val debug = ArrayList<String>()
                debug.add("ELM: ${elm.elmId.ifBlank { "?" }}  ${elm.protocol}")
                if (probe) {
                    leaf = GroupDecoder.apply(leaf, IsoTp.reassemble(elm.queryRaw("2102")))
                    cycle++
                    lastProgressMs = System.currentTimeMillis()
                    _state.value = BatteryState(leaf = leaf, connected = true, cycle = cycle, debug = debug)
                    continue
                }
                for (g in listOf("2101", "2102")) {
                    if (!running) break
                    val payload = IsoTp.reassemble(elm.queryRaw(g))
                    leaf = GroupDecoder.apply(leaf, payload)
                    debug.add("$g: ${if (payload.isEmpty()) "no data" else "${payload.size}B"}")
                }
                if (!running) break
                val sf = elm.readBroadcast(SPEED_ID)
                leaf = leaf.copy(speedKmh = sf?.let { (((it.u(4) shl 8) or it.u(5)) / 100.0).takeIf { s -> s in 0.0..300.0 } })
                elm.setRxAddr(LBC_RX)

                if (cycle % SLOW_EVERY == 0 || !identityKnown()) pollSlow(debug)

                cycle++
                lastProgressMs = System.currentTimeMillis()
                _state.value = BatteryState(leaf = leaf, connected = true, cycle = cycle, debug = debug)
                Thread.sleep(100)
            }
            _state.value = _state.value.copy(connected = false, connecting = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(connected = false, connecting = false, error = e.message ?: "error")
        } finally {
            runCatching { transport.close() }
            running = false
            watchdog.interrupt()
        }
    }

    private fun identityKnown() =
        leaf.vin != null && leaf.sohFromLbc && leaf.batteryTempsC.isNotEmpty() &&
            leaf.meterOdoKm != null && leaf.ambientTempC != null

    /** Slow reads; each is skipped for the session after [MAX_FAILS] failures in a row. */
    private fun pollSlow(debug: MutableList<String>) {
        fun attempt(key: String, block: () -> Boolean) {
            if (!running || (fails[key] ?: 0) >= MAX_FAILS) return
            val ok = runCatching(block).getOrDefault(false)
            fails[key] = if (ok) 0 else (fails[key] ?: 0) + 1
        }
        attempt("2161") {
            leaf = GroupDecoder.apply(leaf, IsoTp.reassemble(elm.queryRaw("2161"))); leaf.sohFromLbc
        }
        attempt("2104") {
            leaf = GroupDecoder.apply(leaf, IsoTp.reassemble(elm.queryRaw("2104"))); leaf.batteryTempsC.isNotEmpty()
        }
        if (leaf.vin == null) attempt("vin") {
            elm.useEcu("797", "79A")
            val v = VcmDecoder.vin(IsoTp.reassemble(elm.queryRaw("2181")))
            elm.useEcu("79B", LBC_RX)
            if (v != null) leaf = leaf.copy(vin = v)
            v != null
        }
        attempt("meter") {
            elm.useEcu("743", "763", fcsd = "300100")
            val km = GroupDecoder.meterOdometerKm(IsoTp.reassemble(elm.queryRaw("2101"), trim = false))
            elm.useEcu("79B", LBC_RX)
            if (km != null) leaf = leaf.copy(meterOdoKm = km)
            km != null
        }
        attempt("ambient") {
            val af = elm.readBroadcast(AMBIENT_ID)
            elm.setRxAddr(LBC_RX)
            val c = af?.u(7)?.takeIf { it != 0xFF }?.let { it * 0.5 - 40.0 }?.takeIf { it in -50.0..90.0 }
            if (c != null) leaf = leaf.copy(ambientTempC = c)
            c != null
        }
        debug.add("VIN ${leaf.vin ?: "-"}  SOH ${leaf.sohPercent ?: "-"}  odo ${leaf.meterOdoKm ?: "-"}  amb ${leaf.ambientTempC ?: "-"}")
    }

    /**
     * ELM327 reads block with no timeout; if the session makes no progress
     * for [stallTimeoutMs], close the transport so the read unblocks.
     */
    private fun startWatchdog() = Thread {
        try {
            while (running) {
                Thread.sleep((stallTimeoutMs / 4).coerceAtLeast(10))
                if (running && System.currentTimeMillis() - lastProgressMs > stallTimeoutMs) {
                    stop(); break
                }
            }
        } catch (_: InterruptedException) {
        }
    }.apply { isDaemon = true; start() }

    fun stop() {
        running = false
        runCatching { transport.close() }
    }

    private companion object {
        const val LBC_RX = "7BB"
        const val SPEED_ID = "284"
        const val AMBIENT_ID = "510"
        const val SLOW_EVERY = 20
        const val MAX_FAILS = 3
    }
}
