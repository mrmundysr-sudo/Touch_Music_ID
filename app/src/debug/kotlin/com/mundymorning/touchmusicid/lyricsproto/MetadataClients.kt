package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.net.HttpFetcher
import kotlinx.coroutines.delay
import java.net.URLEncoder

/** Free track metadata used only to make lyric matching safer: duration (to tell edits and versions apart) and album. */
data class TrackMeta(
    val durationMs: Long?,
    val album: String?,
    val source: String,
    val trackId: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val artworkUrl: String? = null,
    val note: String = "",
)

sealed interface MetaResult {
    data class Found(val meta: TrackMeta) : MetaResult
    data object NotFound : MetaResult
    data class Failed(val kind: String, val message: String) : MetaResult
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** Apple's free iTunes lookup. The Apple Music ID ShazamKit returns is the iTunes track id of the exact release Shazam matched. */
class ITunesClient(private val http: HttpFetcher, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun lookupById(appleMusicId: String): MetaResult = try {
        val r = LabHttp.get(http, "https://itunes.apple.com/lookup?id=${enc(appleMusicId)}", pause)
        if (r == null) MetaResult.NotFound else parse(r.body, "iTunes lookup")
    } catch (e: ApiFailure) {
        MetaResult.Failed(e.kind, e.message ?: "")
    }

    /** Used only by the live CI catalogue test to discover real ids. */
    suspend fun searchFirst(term: String): MetaResult = try {
        val r = LabHttp.get(http, "https://itunes.apple.com/search?term=${enc(term)}&entity=song&limit=1", pause)
        if (r == null) MetaResult.NotFound else parse(r.body, "iTunes search")
    } catch (e: ApiFailure) {
        MetaResult.Failed(e.kind, e.message ?: "")
    }

    companion object {
        fun parse(body: String, source: String): MetaResult {
            val o = try { LabHttp.obj(body) } catch (e: ApiFailure) { return MetaResult.Failed(e.kind, e.message ?: "") }
            val results = o.optJSONArray("results") ?: return MetaResult.Failed("MALFORMED", "missing results")
            if (!o.has("resultCount")) return MetaResult.Failed("MALFORMED", "missing resultCount")
            val first = results.optJSONObject(0) ?: return MetaResult.NotFound
            val dur = if (first.has("trackTimeMillis") && !first.isNull("trackTimeMillis")) first.optLong("trackTimeMillis", -1L).takeIf { it > 0 } else null
            return MetaResult.Found(
                TrackMeta(
                    durationMs = dur, album = first.str("collectionName"), source = source,
                    trackId = if (first.has("trackId")) first.optLong("trackId").toString() else null,
                    trackName = first.str("trackName"), artistName = first.str("artistName"), artworkUrl = first.str("artworkUrl100"),
                ),
            )
        }
    }
}

/** MusicBrainz (free, open data). ISRC lookup returns the recordings sharing that ISRC, with their lengths and releases. */
class MusicBrainzClient(private val http: HttpFetcher, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    suspend fun byIsrc(isrc: String): MetaResult = try {
        val r = LabHttp.get(http, "https://musicbrainz.org/ws/2/isrc/${enc(isrc)}?fmt=json&inc=releases", pause)
        if (r == null) MetaResult.NotFound else parseIsrc(r.body)
    } catch (e: ApiFailure) {
        MetaResult.Failed(e.kind, e.message ?: "")
    }

    /** Returns the first ISRC found for a title/artist (live CI catalogue test only). */
    suspend fun searchIsrc(title: String, artist: String): String? = try {
        val q = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        val r = LabHttp.get(http, "https://musicbrainz.org/ws/2/recording?query=${enc(q)}&fmt=json&limit=3", pause)
        val recs = r?.let { LabHttp.obj(it.body).optJSONArray("recordings") }
        var found: String? = null
        if (recs != null) {
            for (i in 0 until recs.length()) {
                val isrcs = recs.optJSONObject(i)?.optJSONArray("isrcs")
                if (isrcs != null && isrcs.length() > 0) { found = isrcs.optString(0); break }
            }
        }
        found
    } catch (e: ApiFailure) {
        null
    }

    companion object {
        fun parseIsrc(body: String): MetaResult {
            val o = try { LabHttp.obj(body) } catch (e: ApiFailure) { return MetaResult.Failed(e.kind, e.message ?: "") }
            val recs = o.optJSONArray("recordings") ?: return MetaResult.Failed("MALFORMED", "missing recordings")
            val lengths = ArrayList<Long>()
            var album: String? = null
            for (i in 0 until recs.length()) {
                val rec = recs.optJSONObject(i) ?: continue
                val len = if (rec.has("length") && !rec.isNull("length")) rec.optLong("length", -1L) else -1L
                if (len > 0) lengths += len
                if (album == null) album = rec.optJSONArray("releases")?.optJSONObject(0)?.str("title")
            }
            if (recs.length() == 0) return MetaResult.NotFound
            val sorted = lengths.sorted()
            val median = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
            val note = "recordings=${recs.length()} lengths(ms)=${sorted.joinToString("/")}"
            return MetaResult.Found(TrackMeta(durationMs = median, album = album, source = "MusicBrainz ISRC", note = note))
        }
    }
}
