package com.mundymorning.touchmusicid.lyricsproto

/*
 * DEBUG-ONLY synchronized-lyrics prototype ("Lyrics Lab"). Not part of release builds.
 *
 * Attribution: the approach (parse every timestamp on a line, apply an [offset:] tag, sort because repeated choruses can
 * be out of order, binary-search the active line) follows the open-source LiveLyrics project
 * (https://github.com/Nurzhigit-dev/LiveLyrics, MIT licence). This is an independent Kotlin implementation of those ideas.
 */

data class LrcLine(val timeMs: Long, val text: String)

data class LrcDoc(val lines: List<LrcLine>, val offsetMs: Long = 0, val meta: Map<String, String> = emptyMap()) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,4}):([0-5]?\d)(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[([A-Za-z#]+):([^\]]*)]\s*$""")
    private val WORD_TAG = Regex("""<\d{1,4}:[0-5]?\d(?:[.:]\d{1,3})?>""")

    /** Never throws: empty or malformed input yields an empty document. */
    fun parse(raw: String?): LrcDoc {
        if (raw.isNullOrBlank()) return LrcDoc(emptyList())
        val lines = ArrayList<LrcLine>()
        val meta = LinkedHashMap<String, String>()
        var offset = 0L
        for (row in raw.lineSequence()) {
            val s = row.trim()
            if (s.isEmpty()) continue
            val tags = TIME_TAG.findAll(s).toList()
            if (tags.isEmpty()) {
                val m = META_TAG.matchEntire(s)
                if (m != null) {
                    val key = m.groupValues[1].lowercase()
                    val value = m.groupValues[2].trim()
                    if (key == "offset") offset = value.toLongOrNull() ?: 0L else meta[key] = value
                }
                continue
            }
            val text = WORD_TAG.replace(s.substring(tags.last().range.last + 1), "").trim()
            for (t in tags) {
                val minutes = t.groupValues[1].toLong()
                val seconds = t.groupValues[2].toLong()
                val frac = t.groupValues[3]
                val ms = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                lines += LrcLine(minutes * 60_000 + seconds * 1000 + ms, text)
            }
        }
        // A positive [offset:] makes lyrics appear earlier (same convention as LiveLyrics).
        val shifted = lines.map { it.copy(timeMs = maxOf(0L, it.timeMs - offset)) }.sortedBy { it.timeMs }
        return LrcDoc(shifted, offset, meta)
    }

    /** Index of the last line whose start is at or before [posMs]; -1 before the first line. */
    fun findActiveIndex(lines: List<LrcLine>, posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/** Sanity checks that decide whether synchronized lyrics are trustworthy enough to follow, or a plain sheet should be shown. */
object LyricsSanity {
    /**
     * False when the timestamps cannot belong to this recording: no lines, or the last line starts well after the track ends.
     * Unknown duration gives the benefit of the doubt.
     */
    fun syncedLooksPlausible(doc: LrcDoc, durationMs: Long?): Boolean {
        if (doc.isEmpty) return false
        if (durationMs == null || durationMs <= 0) return true
        return doc.lines.last().timeMs <= durationMs + 5_000
    }
}
