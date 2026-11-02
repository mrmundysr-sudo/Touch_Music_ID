package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.domain.normalizeText

/*
 * Candidate scoring follows the shape used by LiveLyrics (https://github.com/Nurzhigit-dev/LiveLyrics, MIT):
 * title similarity >= 0.78 and artist similarity >= 0.6 or reject; reject duration drift above 10 s; weights 0.40/0.35/0.25;
 * +0.2 for synchronized lyrics; -0.3 for instrumental records. Version-mismatch rejection (live/remix/...) is new here,
 * because LiveLyrics only excludes those indirectly.
 */

data class TrackQuery(val title: String, val artist: String, val album: String? = null, val durationSec: Double? = null)

data class LrclibRecord(
    val id: Long,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val durationSec: Double?,
    val instrumental: Boolean,
    val plain: String?,
    val synced: String?,
)

data class ScoredCandidate(
    val record: LrclibRecord,
    val score: Double,
    val rejectedReason: String?,
    val titleSim: Double,
    val artistSim: Double,
    val durationGapSec: Double?,
)

object LyricsMatcher {
    const val MIN_TITLE = 0.78
    const val MIN_ARTIST = 0.6
    const val MAX_DURATION_DRIFT_SEC = 10.0

    private val VERSION_WORDS = setOf(
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover", "demo", "slowed", "sped", "reverb", "nightcore",
        "rework", "bootleg", "unplugged", "piano", "orchestral", "stripped", "reprise",
    )

    private val NOISE_GROUP = Regex(
        """\s*[(\[][^)\]]*(remaster\w*|\bversion\b|\bmix\b|\bedit\b|\bdeluxe\b|\bfeat\b|\bft\b|\bfeaturing\b|\bfrom\b|\bbonus\b|\bmono\b|\bstereo\b|\banniversary\b|\b(19|20)\d{2}\b)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_SUFFIX = Regex("""\s+-\s+[^-]*\b(remaster\w*|version|mono|stereo|edit|mix)\b.*$""", RegexOption.IGNORE_CASE)
    /** Separators that introduce a secondary artist. "AC/DC", "Jay-Z" and "Florence + the Machine" must stay intact. */
    private val ARTIST_STRONG = Regex("""\s*(?:,|;|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
    /** Separators that may join two artists OR belong to one name ("Simon & Garfunkel"): used for matching only. */
    private val ARTIST_WEAK = Regex("""\s*(?:&|\band\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)

    /** Words that make a recording a different VERSION (live, remix...), taken from the raw, uncleaned title. */
    fun versionWords(raw: String): Set<String> = normalizeText(raw).split(' ').filter { it in VERSION_WORDS }.toSet()

    /** Removes remaster/feature/year decorations but keeps version words such as "Live" and "Remix". */
    fun cleanTitle(raw: String): String {
        var s = NOISE_GROUP.replace(raw, "")
        s = NOISE_SUFFIX.replace(s, "")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.ifBlank { raw.trim() }
    }

    /** The lead artist for LRCLIB field searches (strong separators only). */
    fun primaryArtist(raw: String): String = raw.split(ARTIST_STRONG).firstOrNull { it.isNotBlank() }?.trim() ?: raw.trim()

    /** Artist strings worth searching with: the lead artist and, when the name contains "&"/"and"/"x", its first part too. */
    fun searchArtists(raw: String): List<String> {
        val lead = primaryArtist(raw)
        val firstWeak = lead.split(ARTIST_WEAK).firstOrNull { it.isNotBlank() }?.trim()
        return listOfNotNull(lead, firstWeak).distinct()
    }

    private fun artistNames(raw: String): List<String> {
        val strong = raw.split(ARTIST_STRONG)
        val parts = strong + strong.flatMap { it.split(ARTIST_WEAK) }
        return (listOf(raw) + parts).map { normalizeText(it) }.filter { it.isNotEmpty() }.distinct()
    }

    private fun bigrams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until s.length - 1) m.merge(s.substring(i, i + 2), 1, Int::plus)
        return m
    }

    /** Sorensen-Dice similarity of two already-normalised strings, 0..1. */
    fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val ma = bigrams(a)
        val mb = bigrams(b)
        var inter = 0
        for ((k, v) in ma) inter += minOf(v, mb[k] ?: 0)
        return 2.0 * inter / ((a.length - 1) + (b.length - 1))
    }

    fun titleSimilarity(a: String, b: String): Double = dice(normalizeText(cleanTitle(a)), normalizeText(cleanTitle(b)))

    fun artistSimilarity(query: String, candidate: String): Double {
        var best = 0.0
        for (q in artistNames(query)) for (c in artistNames(candidate)) best = maxOf(best, dice(q, c))
        return best
    }

    fun score(q: TrackQuery, r: LrclibRecord): ScoredCandidate {
        val name = r.trackName.orEmpty()
        val t = titleSimilarity(q.title, name)
        val a = artistSimilarity(q.artist, r.artistName.orEmpty())
        val gap = if (q.durationSec != null && r.durationSec != null) kotlin.math.abs(q.durationSec - r.durationSec) else null
        fun reject(why: String) = ScoredCandidate(r, -1.0, why, t, a, gap)

        val qv = versionWords(q.title)
        val cv = versionWords(name)
        if (qv != cv) return reject("version mismatch: ${(qv - cv) + (cv - qv)}")
        if (t < MIN_TITLE) return reject("title similarity ${"%.2f".format(t)} < $MIN_TITLE")
        if (a < MIN_ARTIST) return reject("artist similarity ${"%.2f".format(a)} < $MIN_ARTIST")
        if (gap != null && gap > MAX_DURATION_DRIFT_SEC) return reject("duration differs by ${"%.1f".format(gap)} s")

        val d = when {
            gap == null -> 0.5
            gap <= 2.0 -> 1.0
            gap <= 5.0 -> 0.6
            else -> 0.25
        }
        var score = 0.4 * t + 0.35 * a + 0.25 * d
        if (!r.synced.isNullOrBlank()) score += 0.2
        if (!r.plain.isNullOrBlank()) score += 0.02
        if (r.instrumental) score -= 0.3
        if (q.album != null && r.albumName != null && titleSimilarity(q.album, r.albumName) >= 0.8) score += 0.05
        return ScoredCandidate(r, score, null, t, a, gap)
    }

    /** Distinct candidates, best accepted first, rejected ones last (kept for diagnostics). */
    fun rank(q: TrackQuery, records: List<LrclibRecord>): List<ScoredCandidate> =
        records.distinctBy { it.id }.map { score(q, it) }
            .sortedWith(compareBy<ScoredCandidate> { it.rejectedReason != null }.thenByDescending { it.score })

    fun best(ranked: List<ScoredCandidate>): ScoredCandidate? = ranked.firstOrNull { it.rejectedReason == null }
}
