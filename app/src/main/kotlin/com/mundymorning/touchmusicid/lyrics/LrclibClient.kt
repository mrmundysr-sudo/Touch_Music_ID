package com.mundymorning.touchmusicid.lyrics

import com.mundymorning.touchmusicid.net.HttpFetcher
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URLEncoder

/**
 * On-demand LRCLIB client. Sequential requests only, proper User-Agent, no permanent storage.
 * Used only when the user has enabled online lyrics and the build allows it.
 */
class LrclibClient(
    private val http: HttpFetcher,
    private val base: String = "https://lrclib.net/api",
    private val userAgent: String = "TouchMusicID/0.1 (https://github.com/mrmundysr-sudo/Touch_Music_ID)",
) {
    suspend fun lookup(q: TrackQuery): LyricsResult? {
        val cleanedTitle = q.title.trim()
        val primaryArtist = q.artist.split(",", "&", "feat.", "ft.").firstOrNull()?.trim() ?: q.artist

        // Prefer exact when possible
        if (q.durationSec != null && !q.album.isNullOrBlank()) {
            getExact(cleanedTitle, primaryArtist, q.album, q.durationSec)?.let { return it }
            delay(300) // respect rate
        }

        // Search by fields
        search("track_name=${enc(cleanedTitle)}&artist_name=${enc(primaryArtist)}")
            .firstOrNull()?.let { return it }

        delay(300)
        // Free-text fallback
        return search("q=${enc("$primaryArtist $cleanedTitle")}").firstOrNull()
    }

    private suspend fun getExact(title: String, artist: String, album: String, duration: Double): LyricsResult? {
        val url = "$base/get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(album)}&duration=${duration.toInt()}"
        val body = http.get(url, userAgent) ?: return null
        return parse(body)
    }

    private suspend fun search(query: String): List<LyricsResult> {
        val body = http.get("$base/search?$query", userAgent) ?: return emptyList()
        val arr = try { org.json.JSONArray(body) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseObject) }
    }

    private fun parse(body: String): LyricsResult? = try { parseObject(JSONObject(body)) } catch (_: Exception) { null }

    private fun parseObject(o: JSONObject): LyricsResult? {
        val plain = o.optString("plainLyrics", null)
        val synced = o.optString("syncedLyrics", null)
        val instrumental = o.optBoolean("instrumental", false)
        if (plain.isNullOrBlank() && synced.isNullOrBlank() && !instrumental) return null
        return LyricsResult(
            plain = plain,
            syncedLines = if (!synced.isNullOrBlank()) LrcParser.parse(synced) else emptyList(),
            source = "online",
            instrumental = instrumental,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
