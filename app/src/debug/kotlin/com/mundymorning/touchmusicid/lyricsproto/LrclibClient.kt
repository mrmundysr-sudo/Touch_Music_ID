package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.net.HttpFetcher
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

sealed interface LyricsLookup {
    data class Found(val best: ScoredCandidate, val ranked: List<ScoredCandidate>, val requests: Int) : LyricsLookup
    data class NotFound(val ranked: List<ScoredCandidate>, val requests: Int) : LyricsLookup
    data class Failed(val kind: String, val message: String) : LyricsLookup
}

/**
 * LRCLIB (https://lrclib.net) client. Requests are strictly sequential (no parallel calls: LRCLIB is reported to restrict them),
 * identified by User-Agent, and spaced by the injected fetcher. Debug-only: lyrics are copyrighted and commercial use is unlicensed.
 * Round 1: exact /get (when album and duration are known), then /search by fields, then /search by free text.
 * Round 2 only when nothing good (score >= 0.8 with synchronized lyrics) was found.
 */
class LrclibClient(
    private val http: HttpFetcher,
    private val base: String = "https://lrclib.net/api",
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun lookup(q: TrackQuery): LyricsLookup {
        val gathered = ArrayList<LrclibRecord>()
        var requests = 0
        var failures = 0
        var lastFailure: ApiFailure? = null

        suspend fun attempt(block: suspend () -> List<LrclibRecord>) {
            requests++
            try { gathered += block() } catch (e: ApiFailure) { failures++; lastFailure = e }
        }

        val cleaned = LyricsMatcher.cleanTitle(q.title)
        val primary = LyricsMatcher.primaryArtist(q.artist)
        val searchArtists = LyricsMatcher.searchArtists(q.artist)

        if (q.durationSec != null && !q.album.isNullOrBlank()) attempt { listOfNotNull(getExact(cleaned, primary, q.album, q.durationSec)) }
        attempt { search("track_name=${enc(cleaned)}&artist_name=${enc(primary)}") }
        for (a in searchArtists) attempt { search("q=${enc("$a $cleaned")}") }

        var ranked = LyricsMatcher.rank(q, gathered)
        var best = LyricsMatcher.best(ranked)
        if (best == null || best.score < 0.8 || best.record.synced.isNullOrBlank()) {
            attempt { search("q=${enc(cleaned)}") }
            ranked = LyricsMatcher.rank(q, gathered)
            best = LyricsMatcher.best(ranked)
        }
        return when {
            best != null -> LyricsLookup.Found(best, ranked, requests)
            // Some requests failed and none of the rest produced a match: we cannot claim the lyrics don't exist.
            failures > 0 -> LyricsLookup.Failed(lastFailure?.kind ?: "UNKNOWN", lastFailure?.message ?: "lookup failed")
            else -> LyricsLookup.NotFound(ranked, requests)
        }
    }

    private suspend fun getExact(title: String, artist: String, album: String, durationSec: Double): LrclibRecord? {
        val url = "$base/get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(album)}&duration=${Math.round(durationSec)}"
        val r = LabHttp.get(http, url, pause) ?: return null
        return parseRecord(LabHttp.obj(r.body))
    }

    private suspend fun search(query: String): List<LrclibRecord> {
        val r = LabHttp.get(http, "$base/search?$query", pause) ?: return emptyList()
        val arr = LabHttp.arr(r.body)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseRecord) }
    }

    companion object {
        fun parseRecord(o: JSONObject): LrclibRecord? {
            val id = o.optLong("id", -1L)
            if (id < 0) return null
            return LrclibRecord(
                id = id,
                trackName = o.str("trackName") ?: o.str("name"),
                artistName = o.str("artistName"),
                albumName = o.str("albumName"),
                durationSec = o.dbl("duration"),
                instrumental = o.optBoolean("instrumental", false),
                plain = o.str("plainLyrics"),
                synced = o.str("syncedLyrics"),
            )
        }
    }
}
