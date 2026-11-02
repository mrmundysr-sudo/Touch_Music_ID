package com.mundymorning.touchmusicid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String, val retryAfterSeconds: Long? = null)

interface HttpFetcher {
    /** Throws [IOException] when the network is unreachable. Non-2xx statuses are returned, not thrown. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/** Wikimedia asks API clients for a descriptive User-Agent with contact info. */
const val USER_AGENT =
    "TouchMusicID/0.1 (Android; https://github.com/mrmundysr-sudo/Touch_Music_ID; Mundy Morning Studios)"

/** HttpURLConnection fetcher: HTTPS only, timeouts, a response-size cap and a polite request spacing. */
class UrlConnectionFetcher(
    private val userAgent: String = USER_AGENT,
    private val minIntervalMillis: Long = 150,
    private val maxBytes: Int = 2_000_000,
) : HttpFetcher {
    private val gate = Mutex()
    private var last = 0L

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        require(url.startsWith("https://")) { "HTTPS required" }
        gate.withLock {
            val wait = minIntervalMillis - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
        }
        return withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 12_000
                c.setRequestProperty("User-Agent", userAgent)
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val raw = stream?.use { s ->
                    val input = if (c.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(s) else s
                    input.readNBytesCapped(maxBytes)
                } ?: ByteArray(0)
                HttpResponse(code, String(raw, Charsets.UTF_8), c.getHeaderField("Retry-After")?.toLongOrNull())
            } finally {
                c.disconnect()
            }
        }
    }
}

private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw IOException("Response too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
