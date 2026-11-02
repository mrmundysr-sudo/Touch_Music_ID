package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.HttpResponse
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A failed request. Never to be confused with "nothing found" (HTTP 404 / empty result). */
class ApiFailure(val kind: String, message: String) : IOException(message)

/** Polite, identified clients for the free metadata/lyrics services. One request at a time, spaced by [minIntervalMillis]. */
object LabHttp {
    const val USER_AGENT = "TouchMusicID-LyricsLab/0.1 (debug prototype; https://github.com/mrmundysr-sudo/Touch_Music_ID)"

    fun lrclib(): HttpFetcher = UrlConnectionFetcher(userAgent = USER_AGENT, minIntervalMillis = 600)
    fun musicBrainz(): HttpFetcher = UrlConnectionFetcher(userAgent = USER_AGENT, minIntervalMillis = 1_100)
    fun itunes(): HttpFetcher = UrlConnectionFetcher(userAgent = USER_AGENT, minIntervalMillis = 3_200)

    /**
     * GET with one polite retry (honouring a short Retry-After). Returns null for 404 ("not found", a completed answer).
     * Throws [ApiFailure] for everything else that is not a 2xx.
     */
    suspend fun get(http: HttpFetcher, url: String, pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }): HttpResponse? {
        var attempt = 0
        while (true) {
            val r: HttpResponse = try {
                http.get(url)
            } catch (e: IOException) {
                if (attempt == 0) { pause(800); attempt++; continue }
                throw ApiFailure("NETWORK", e.message ?: "network error")
            }
            when {
                r.code == 404 -> return null
                r.code in 200..299 -> return r
                (r.code == 429 || r.code == 403 || r.code in 500..599) && attempt == 0 -> {
                    pause(((r.retryAfterSeconds ?: 1L) * 1000).coerceIn(500L, 3_000L))
                    attempt++
                }
                r.code == 429 || r.code == 403 -> throw ApiFailure("RATE_LIMITED", "HTTP ${r.code}")
                else -> throw ApiFailure("SERVER", "HTTP ${r.code}")
            }
        }
    }

    fun obj(body: String): JSONObject = try { JSONObject(body) } catch (e: JSONException) { throw ApiFailure("MALFORMED", "response is not a JSON object") }
    fun arr(body: String): JSONArray = try { JSONArray(body) } catch (e: JSONException) { throw ApiFailure("MALFORMED", "response is not a JSON array") }
}

internal fun JSONObject.str(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONObject.dbl(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }
