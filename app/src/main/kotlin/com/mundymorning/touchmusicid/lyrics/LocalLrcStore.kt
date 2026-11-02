package com.mundymorning.touchmusicid.lyrics

import java.util.concurrent.ConcurrentHashMap

/**
 * Holds user-imported LRC content for the current session only.
 * No permanent downloaded lyrics library.
 */
class LocalLrcStore {
    private val byKey = ConcurrentHashMap<String, LyricsResult>()

    fun put(songKey: String, lrcText: String) {
        val lines = LrcParser.parse(lrcText)
        val plain = lines.joinToString("\n") { it.text }
        byKey[songKey] = LyricsResult(plain = plain, syncedLines = lines, source = "local")
    }

    fun get(songKey: String): LyricsResult? = byKey[songKey]

    fun clear() = byKey.clear()
}
