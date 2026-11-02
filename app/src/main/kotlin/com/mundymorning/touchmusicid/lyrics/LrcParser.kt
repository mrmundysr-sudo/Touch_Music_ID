package com.mundymorning.touchmusicid.lyrics

object LrcParser {
    private val LINE = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]\s*(.*)""")

    fun parse(lrc: String): List<LrcLine> = lrc.lineSequence()
        .mapNotNull { line ->
            val m = LINE.find(line) ?: return@mapNotNull null
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val frac = m.groupValues[3].let { if (it.isEmpty()) 0L else it.padEnd(3, '0').take(3).toLong() }
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) return@mapNotNull null
            LrcLine(min * 60_000 + sec * 1_000 + frac, text)
        }
        .sortedBy { it.timeMs }
        .toList()
}
