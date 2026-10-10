package com.leafbt.obd

import com.leafbt.can.CanDecoder
import com.leafbt.can.LeafState
import com.leafbt.transport.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Elm327Test {

    @Test fun parsesTightHexLine() {
        val f = Elm327.parseMonitorLine("55B8AC0")!!
        assertEquals(0x55B, f.id)
        assertEquals(0x8A, f.u(0))
        assertEquals(0xC0, f.u(1))
    }

    @Test fun toleratesSpaces() {
        val f = Elm327.parseMonitorLine("  55B 8A C0 ")!!
        assertEquals(0x55B, f.id)
        assertEquals(0xC0, f.u(1))
    }

    @Test fun rejectsNonFrameLines() {
        assertNull(Elm327.parseMonitorLine("OK"))
        assertNull(Elm327.parseMonitorLine("SEARCHING..."))
        assertNull(Elm327.parseMonitorLine(">"))
        assertNull(Elm327.parseMonitorLine(""))
        assertNull(Elm327.parseMonitorLine("1DB0")) // odd byte nibbles
    }

    @Test fun streamsFramesThroughMockThenDecodes() {
        val ok = "OK"
        val mock = MockTransport(
            mapOf(
                "ATZ" to ok, "ATE0" to ok, "ATL0" to ok,
                "ATS0" to ok, "ATH1" to ok, "ATSP6" to ok,
                // SOC frame then pack volts/amps frame
                "ATMA" to "55B8AC0\r1DB0C806400",
            )
        )
        mock.open()
        val elm = Elm327(mock)
        elm.init()
        elm.startMonitor()

        var state = LeafState()
        var f = elm.nextFrame()
        while (f != null) {
            state = CanDecoder.apply(state, f)
            f = elm.nextFrame()
        }

        assertEquals(55.5, state.socPercent!!, 1e-9)
        assertEquals(200.0, state.packVolts!!, 1e-9)
        assertEquals(50.0, state.packAmps!!, 1e-9)
        assertEquals(10.0, state.powerKw!!, 1e-9)
    }

    /**
     * ELM327 stand-in with real timing semantics: reads block until bytes
     * arrive; ATMA streams [frames] with no prompt until any input stops it
     * (then "STOPPED" + prompt, or just the prompt if [bareStop]); a bare CR
     * at the prompt repeats the last command, like the chip.
     */
    private class FakeElm(
        private val frames: List<String>,
        private val replies: Map<String, String>,
        private val bareStop: Boolean = false,
    ) : com.leafbt.transport.Transport {
        private val q = java.util.concurrent.LinkedBlockingQueue<Byte>()
        @Volatile private var monitoring = false
        private var last = ""
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())

        override fun open() {}
        override fun close() {}
        override fun available() = q.size
        override fun read(buffer: ByteArray, max: Int): Int {
            buffer[0] = q.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: return -1
            var n = 1
            while (n < max) {
                val b = q.poll() ?: break
                buffer[n++] = b
            }
            return n
        }
        @Synchronized override fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trimEnd('\r')
            sent.add(cmd)
            if (monitoring) {                       // any input halts ATMA
                monitoring = false
                emit(if (bareStop) "\r>" else "STOPPED\r\r>")
                return
            }
            val c = cmd.ifEmpty { last }           // bare CR repeats last command
            last = c
            if (c == "ATMA") {
                monitoring = true
                frames.forEach { emit(it + "\r") }
            } else {
                emit((replies[c] ?: "OK") + "\r\r>")
            }
        }
        private fun emit(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { q.put(it) }
    }

    @Test fun timedBroadcastReturnsFrameAndKeepsSync() {
        val t = FakeElm(listOf("5B300000000B9000000"), mapOf("2101" to "7BB0461010203"))
        val elm = Elm327(t)
        val f = elm.readBroadcastTimed("5B3", 1000)
        assertEquals(0x5B3, f!!.id)
        assertEquals("7BB0461010203", elm.queryRaw("2101"))      // next reply not shifted
        assertEquals(1, t.sent.count { it.isEmpty() })            // exactly one stop CR
    }

    @Test fun timedBroadcastTimesOutAndKeepsSync() {
        val t = FakeElm(emptyList(), mapOf("2101" to "7BB0461010203"))
        val elm = Elm327(t)
        assertNull(elm.readBroadcastTimed("5B3", 100))           // no frame: timer stops it
        assertEquals("7BB0461010203", elm.queryRaw("2101"))
        assertEquals(1, t.sent.count { it.isEmpty() })
    }

    @Test fun timedBroadcastHandlesPromptOnlyStop() {
        // some clones answer the stop with just '>' (no STOPPED line)
        val t = FakeElm(emptyList(), mapOf("2101" to "7BB0461010203"), bareStop = true)
        val elm = Elm327(t)
        assertNull(elm.readBroadcastTimed("5B3", 100))
        assertEquals("7BB0461010203", elm.queryRaw("2101"))
    }
}
