package com.leafbt.obd

import com.leafbt.can.CanFrame
import com.leafbt.transport.Transport

/**
 * Minimal ELM327 driver for passive CAN monitoring on the Leaf.
 *
 * Init sequence configures the chip for deterministic parsing (echo/spaces
 * off, headers on, protocol 6 = ISO 15765 500 kbps 11-bit), then [startMonitor]
 * issues `ATMA` and the chip streams every frame. [nextFrame] reads and parses
 * one frame at a time. Frames we don't care about are filtered later by the
 * decoder (unknown ids ignored).
 */
class Elm327(private val transport: Transport) {

    private val buf = ByteArray(4096)
    private var bufLen = 0          // valid bytes in buf
    private var bufPos = 0          // next unread index
    private val line = StringBuilder(64)

    /** Return the next byte from the buffered stream, or -1 at end. */
    private fun nextByte(): Int {
        if (bufPos >= bufLen) {
            val n = transport.read(buf)
            if (n < 0) return -1
            bufLen = n
            bufPos = 0
        }
        return buf[bufPos++].toInt() and 0xFF
    }

    /** Commands run once at startup. Each waits for the '>' prompt. */
    private val initCommands = listOf(
        "ATZ",    // reset
        "ATE0",   // echo off
        "ATL0",   // linefeeds off
        "ATS0",   // spaces off -> tight hex lines, easy to parse
        "ATH1",   // headers on -> we need the CAN id
        "ATSP6",  // protocol 6: ISO 15765-4 CAN 11-bit 500k
    )

    fun init() {
        for (cmd in initCommands) {
            sendCommand(cmd)
            drainToPrompt()
        }
    }

    /** Begin the continuous frame stream. */
    fun startMonitor() {
        sendCommand("ATMA")
    }

    /** Stop ATMA (any input halts it) and drain back to the prompt. */
    fun stopMonitor() {
        transport.write("\r".toByteArray(Charsets.US_ASCII))
        drainToPrompt()
    }

    /**
     * Read one broadcast frame with the given receive id via a hardware filter
     * (this is how LeafSpy catches 0x5C5 that blind ATMA drops). Blocks until a
     * matching frame arrives; when the car is on, target frames stream at ~10 Hz
     * so this returns promptly. Recoverable via transport close.
     */
    fun readBroadcast(rxId: String): CanFrame? = readBroadcastN(rxId, 1).firstOrNull()

    /** Read up to [n] broadcast frames with the given receive id (filtered). */
    fun readBroadcastN(rxId: String, n: Int): List<CanFrame> {
        sendCommand("ATCAF0")   // raw frames for monitoring (no ISO-TP reassembly)
        drainToPrompt()
        setRxAddr(rxId)
        startMonitor()
        val out = ArrayList<CanFrame>(n)
        repeat(n) { nextFrame()?.let { out.add(it) } }
        stopMonitor()
        sendCommand("ATCAF1")   // restore auto-format for ISO-TP battery polls
        drainToPrompt()
        return out
    }

    /**
     * Like [readBroadcast] but gives up after [timeoutMs] when the frame never
     * comes (not every car sends every id). Any input halts ATMA, so a timer
     * sends a CR to break the blocked read; the ELM then prints "STOPPED"
     * and/or the prompt. Exactly one CR is sent (timer or normal stop, whichever
     * wins), and never at the prompt: a bare CR there repeats the last command.
     */
    fun readBroadcastTimed(rxId: String, timeoutMs: Long): CanFrame? {
        sendCommand("ATCAF0")
        drainToPrompt()
        setRxAddr(rxId)
        startMonitor()
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val timer = Thread {
            try {
                Thread.sleep(timeoutMs)
                if (stopped.compareAndSet(false, true)) {
                    runCatching { transport.write("\r".toByteArray(Charsets.US_ASCII)) }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        var frame: CanFrame? = null
        var atPrompt = false
        while (true) {
            val raw = readLineOrPrompt() ?: break
            if (raw == PROMPT) { atPrompt = true; break }
            if (raw.contains("STOPPED")) break
            frame = parseMonitorLine(raw) ?: continue
            break
        }
        timer.interrupt()
        val won = stopped.compareAndSet(false, true)
        when {
            // monitor already ended by itself: send nothing, unless the timer's CR
            // landed at the prompt and restarted ATMA - then stop that one
            atPrompt -> if (!won) {
                transport.write("\r".toByteArray(Charsets.US_ASCII))
                drainToPrompt()
            }
            else -> {
                if (won) transport.write("\r".toByteArray(Charsets.US_ASCII))
                drainToPrompt()
            }
        }
        sendCommand("ATCAF1")
        drainToPrompt()
        return frame
    }

    /** One line, or [PROMPT] when the '>' prompt arrives on an empty line; null at end. */
    private fun readLineOrPrompt(): String? {
        line.setLength(0)
        while (true) {
            val b = nextByte()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            val c = b.toChar()
            when {
                c == '>' -> return if (line.isEmpty()) PROMPT else line.toString().also { bufPos-- }
                c == '\r' || c == '\n' -> if (line.isNotEmpty()) return line.toString()
                else -> line.append(c)
            }
        }
    }

    /**
     * Point requests at ECU [tx] and accept replies only from [rx], e.g.
     * "797"/"79A". [fcsd] = flow-control data (block size / separation time);
     * the meter wants block size 1.
     */
    fun useEcu(tx: String, rx: String, fcsd: String = "300000") {
        for (cmd in listOf("ATSH$tx", "ATFCSH$tx", "ATFCSD$fcsd", "ATCRA$rx")) {
            sendCommand(cmd)
            drainToPrompt()
        }
    }

    /**
     * Active-diagnostic setup: the Leaf battery data is NOT broadcast on the
     * car-CAN pins an ELM327 sees; it must be requested from the battery
     * controller (LBC) via ISO-TP. Point requests at 0x79B, receive 0x7BB,
     * let the ELM327 auto-assemble multi-frame replies (CAF on) and handle
     * flow control.
     */
    private val activeSetup = listOf(
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6", "ATCAF1",
        "ATSH79B",        // request header -> LBC
        "ATFCSH79B",      // flow-control header
        "ATFCSD300000",   // flow-control data: 30 00 00
        "ATFCSM1",        // flow-control mode 1
        "ATCRA7BB",       // accept replies only from 0x7BB
    )

    /** ELM327 identity + negotiated protocol, captured during initActive(). */
    var elmId: String = ""
        private set
    var protocol: String = ""
        private set

    fun initActive(onStep: (String) -> Unit = {}) {
        for (cmd in activeSetup) {
            onStep(cmd)
            sendCommand(cmd)
            drainToPrompt()
        }
        onStep("ATI")
        elmId = queryRaw("ATI").trim()      // e.g. "ELM327 v1.5"
        onStep("ATDP")
        protocol = queryRaw("ATDP").trim()  // e.g. "ISO 15765-4 (CAN 11/500)"
    }

    /**
     * Passive bus scan: collect the distinct CAN ids seen on the monitored
     * bus. Stops after [maxFrames], or early once no new id appears for
     * [settle] consecutive frames. Requires startMonitor() first.
     */
    fun scanIds(maxFrames: Int = 300, settle: Int = 40): Set<Int> {
        val ids = LinkedHashSet<Int>()
        var n = 0
        var since = 0
        while (n < maxFrames && since < settle) {
            val f = nextFrame() ?: break
            if (ids.add(f.id)) since = 0 else since++
            n++
        }
        return ids
    }

    /** Set the request header (target ECU), e.g. "79B" (LBC) or "743" (meter). */
    fun setHeader(h: String) {
        sendCommand("ATSH$h")
        drainToPrompt()
    }

    /** Restrict accepted responses to this id (ATCRA), e.g. "7BB" / "763". */
    fun setRxAddr(h: String) {
        sendCommand("ATCRA$h")
        drainToPrompt()
    }

    /** Send a diagnostic request (e.g. "2101") and return the raw reply text. */
    fun queryRaw(pid: String): String {
        sendCommand(pid)
        return readUntilPrompt()
    }

    /** Read everything up to the '>' prompt as text. */
    private fun readUntilPrompt(): String {
        val sb = StringBuilder()
        while (true) {
            val b = nextByte()
            if (b < 0 || b == '>'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    private fun sendCommand(cmd: String) {
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
    }

    /** Read and discard bytes until the ELM327 prompt '>'. */
    private fun drainToPrompt() {
        while (true) {
            val b = nextByte()
            if (b < 0 || b == '>'.code) return
        }
    }

    /**
     * Read the next parseable CAN frame from the monitor stream, or null at
     * end of stream. Non-frame lines (OK, SEARCHING..., blank) are skipped.
     */
    fun nextFrame(): CanFrame? {
        while (true) {
            val raw = readLine() ?: return null
            val frame = parseMonitorLine(raw)
            if (frame != null) return frame
        }
    }

    /** Read one line terminated by CR/LF (or the prompt). Null at end. */
    private fun readLine(): String? {
        line.setLength(0)
        while (true) {
            val b = nextByte()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            val c = b.toChar()
            if (c == '\r' || c == '\n' || c == '>') {
                if (line.isNotEmpty()) return line.toString()
            } else {
                line.append(c)
            }
        }
    }

    companion object {
        private const val PROMPT = ">"

        /**
         * Parse one monitor line into a [CanFrame], or null if it isn't a
         * frame. With ATS0 the line is contiguous hex: 3 hex chars of 11-bit
         * id followed by pairs of data-byte hex.
         */
        fun parseMonitorLine(raw: String): CanFrame? {
            val s = raw.trim().filterNot { it == ' ' }
            if (s.length < 3) return null
            if (!s.all { it.isHex() }) return null
            if ((s.length - 3) % 2 != 0) return null   // id(3) + N byte pairs

            val id = s.substring(0, 3).toInt(16)
            val nBytes = (s.length - 3) / 2
            val data = ByteArray(nBytes) { i ->
                s.substring(3 + i * 2, 5 + i * 2).toInt(16).toByte()
            }
            return CanFrame(id, data)
        }

        private fun Char.isHex(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
