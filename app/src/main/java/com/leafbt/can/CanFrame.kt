package com.leafbt.can

/** A single received CAN frame: 11-bit id + up to 8 data bytes. */
data class CanFrame(val id: Int, val data: ByteArray) {
    /** Unsigned byte value at [i], or 0 if out of range. */
    fun u(i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0

    override fun equals(other: Any?): Boolean =
        other is CanFrame && id == other.id && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * id + data.contentHashCode()
}
