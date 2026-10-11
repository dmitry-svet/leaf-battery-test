package com.leafbt.log

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LeafSpy-format data log files, one per logging session (Start log .. Stop
 * log): LOG_FILES/Log_<VIN last 8>_YYMMDD_HHmmss.csv, named like LeafSpy's
 * own files (VIN tail + start time); the cells probe uses the prefix Probe_. Blocking file IO - call off the main
 * thread.
 */
class DataLogStore(root: File) {
    private val dir = File(root, "LOG_FILES")

    /** File of the running session, null when stopped. */
    @Volatile var current: File? = null
        private set

    fun start(nowMs: Long, vin: String?, prefix: String = "Log") {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val tag = vin?.takeLast(8) ?: "NOVIN"
        val f = File(dir, "${prefix}_${tag}_$stamp.csv")
        f.writeText(csvLine(LeafSpyLog.HEADER))
        current = f
    }

    fun stop() {
        current = null
    }

    /** Appends to the running session, starting one if needed. */
    fun append(nowMs: Long, vin: String?, row: List<String>) {
        if (current == null) start(nowMs, vin)
        current!!.appendText(csvLine(row))
    }

    /** Running session's file, else the newest one on disk. */
    fun latest(): File? =
        current ?: dir.listFiles { f -> f.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }

    /** All sessions merged oldest first, header once. */
    fun exportAll(out: OutputStream) {
        val files = dir.listFiles { f -> f.name.endsWith(".csv") }?.sortedBy { it.lastModified() } ?: emptyList()
        out.bufferedWriter().use { w ->
            w.write(csvLine(LeafSpyLog.HEADER))
            for (f in files) {
                f.bufferedReader().useLines { lines -> lines.drop(1).forEach { w.write(it); w.write("\n") } }
            }
        }
    }

    val path: String get() = dir.absolutePath

    private fun csvLine(cells: List<String>) = cells.joinToString(",") + "\n"
}
