package com.mundymorning.touchmusicid.lyricsproto

import java.io.Writer

/**
 * Plain CSV event log for calibration analysis: `kind,monoMs,epochMs,"k=v;k=v"`. Raw ShazamKit offsets and every calculated
 * position are recorded side by side. No audio and no personal data is written.
 */
class CsvLog(private val writer: Writer?) {
    init { writer?.append(HEADER)?.append('\n') }

    fun row(kind: String, monoMs: Double, epochMs: Long, fields: Map<String, Any?>) {
        val w = writer ?: return
        try { w.append(format(kind, monoMs, epochMs, fields)).append('\n') } catch (e: java.io.IOException) { /* logging must never crash the lab */ }
    }

    fun flush() { try { writer?.flush() } catch (e: java.io.IOException) { } }
    fun close() { try { writer?.close() } catch (e: java.io.IOException) { } }

    companion object {
        const val HEADER = "kind,monoMs,epochMs,data"

        fun format(kind: String, monoMs: Double, epochMs: Long, fields: Map<String, Any?>): String {
            val data = fields.entries.joinToString(";") { (k, v) -> "$k=${v.toString().replace(';', ',').replace('\n', ' ')}" }
            return "$kind,${"%.1f".format(java.util.Locale.ROOT, monoMs)},$epochMs,\"${data.replace("\"", "\"\"")}\""
        }
    }
}
