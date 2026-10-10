package com.leafbt.transport

/**
 * Replays a canned response script for tests and off-car development.
 *
 * The ELM327 protocol is request/response: after each [write] we expect the
 * caller to [read] the matching reply. [script] maps the trimmed command
 * (without the trailing '\r') to the bytes returned on the following reads.
 * A "*" key is the default reply for any unmatched command (useful to stream
 * the same monitor output repeatedly).
 *
 * Every reply automatically gets the ELM327 prompt '>' appended if absent, so
 * the line reader's end-of-response detection works exactly as on hardware.
 */
class MockTransport(
    private val script: Map<String, String>,
) : Transport {

    private var pending = ArrayDeque<Byte>()
    private var opened = false

    override fun open() {
        opened = true
    }

    override fun write(bytes: ByteArray) {
        check(opened) { "write before open" }
        val cmd = String(bytes, Charsets.US_ASCII).trim().trimEnd('\r')
        val reply = script[cmd] ?: script["*"] ?: ""
        val withPrompt = if (reply.endsWith(">")) reply else reply + "\r>"
        withPrompt.toByteArray(Charsets.US_ASCII).forEach { pending.addLast(it) }
    }

    override fun read(buffer: ByteArray, max: Int): Int {
        if (pending.isEmpty()) return -1
        var n = 0
        while (n < max && pending.isNotEmpty()) {
            buffer[n++] = pending.removeFirst()
        }
        return n
    }

    override fun available(): Int = pending.size

    override fun close() {
        opened = false
        pending.clear()
    }
}
