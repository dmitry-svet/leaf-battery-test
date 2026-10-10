package com.leafbt.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth-Classic (SPP / RFCOMM) link to an ELM327 dongle.
 *
 * Caller MUST hold BLUETOOTH_CONNECT (API 31+) before [open]. Uses the
 * standard Serial Port Profile UUID.
 */
@SuppressLint("MissingPermission") // permission checked by caller before open()
class BtSppTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter,
) : Transport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        runCatching { adapter.cancelDiscovery() } // best-effort; needs SCAN perm
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()                    // blocks until connected / throws
        } catch (e: Exception) {
            runCatching { s.close() }      // not ours yet - don't leak it
            throw e
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val o = output ?: error("write before open")
        o.write(bytes)
        o.flush()
    }

    override fun read(buffer: ByteArray, max: Int): Int {
        val i = input ?: return -1
        return i.read(buffer, 0, max)
    }

    override fun available(): Int = runCatching { input?.available() ?: 0 }.getOrDefault(0)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
