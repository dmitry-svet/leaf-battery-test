package com.leafbt.transport

/**
 * Byte-stream link to the ELM327 dongle.
 *
 * Deliberately minimal: the ELM327 speaks line-oriented ASCII (commands end
 * with '\r', responses end with '>'), so higher layers only need to write
 * bytes and read bytes. Real impl = Bluetooth RFCOMM; test impl = replay.
 */
interface Transport {
    /** Open the link. Throws on failure. */
    fun open()

    /** Write raw bytes (a command, already terminated with '\r'). */
    fun write(bytes: ByteArray)

    /**
     * Read up to [max] bytes, blocking until at least one is available.
     * Returns the number read, or -1 at end of stream.
     */
    fun read(buffer: ByteArray, max: Int = buffer.size): Int

    /** Bytes available to read without blocking (best-effort; 0 if unknown). */
    fun available(): Int = 0

    /** Close the link. Idempotent. */
    fun close()
}
