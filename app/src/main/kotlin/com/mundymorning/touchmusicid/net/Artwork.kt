package com.mundymorning.touchmusicid.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Album-art URL rules. Pure, so it is unit-tested. Only HTTPS images from Apple/Shazam CDNs are fetched. */
object ArtworkUrl {
    private val allowedSuffixes = listOf(".mzstatic.com", ".apple.com", ".shazam.com", ".shazamcdn.com")

    /** Returns a fetchable https URL (size templates filled in) or null if the URL is missing or not trusted. */
    fun normalize(raw: String?, px: Int = 600): String? {
        if (raw.isNullOrBlank()) return null
        val filled = raw.trim().replace("{w}", "$px").replace("{h}", "$px").replace("{f}", "jpg").replace("{c}", "bb")
        val uri = runCatching { java.net.URI(filled) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme != "https") return null
        return if (allowedSuffixes.any { host.endsWith(it) }) filled else null
    }
}

/** Small in-memory cached loader (no image-library dependency). Downsamples; never writes to disk. */
object ArtworkLoader {
    private const val MAX_BYTES = 3_000_000
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Null on any failure (offline, 404, bad image). The caller shows the fallback. */
    suspend fun load(url: String, targetPx: Int = 600): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 8_000
                    c.readTimeout = 10_000
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    if (c.responseCode !in 200..299) return@runCatching null
                    val bytes = c.inputStream.use { it.readBytesCapped(MAX_BYTES) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.also { cache.put(url, it) }
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
        }
    }
}

private fun java.io.InputStream.readBytesCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw java.io.IOException("Image too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
