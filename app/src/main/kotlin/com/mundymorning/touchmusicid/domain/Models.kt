package com.mundymorning.touchmusicid.domain

import java.text.Normalizer
import java.util.Locale

/** Where a recognition result came from. DEMO results are synthetic and must always be labelled as such. */
enum class RecognitionSource { SHAZAMKIT, DEMO }

/** Pure data about an identified track. Never carries decorative-art references. */
data class Song(
    val title: String,
    val artist: String,
    val album: String? = null,
    val appleMusicUrl: String? = null,
    val webUrl: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
) {
    /** Stable identity used for favorites and knowledge-cache lookups. */
    val key: String get() = songKey(title, artist)
}

data class RecognizedSong(
    val song: Song,
    val source: RecognitionSource,
    val recognizedAtMillis: Long,
)

/** Lower-cases, strips diacritics and punctuation noise, collapses whitespace. */
fun normalizeText(input: String): String {
    val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
    return decomposed.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

fun songKey(title: String, artist: String): String = normalizeText(title) + "||" + normalizeText(artist)
