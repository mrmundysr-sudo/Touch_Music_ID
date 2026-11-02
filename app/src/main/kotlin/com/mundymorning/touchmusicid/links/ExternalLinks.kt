package com.mundymorning.touchmusicid.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mundymorning.touchmusicid.domain.Song
import java.net.URLEncoder

enum class Service(val label: String) { APPLE_MUSIC("Apple Music"), SPOTIFY("Spotify"), YOUTUBE("YouTube") }

/**
 * Outbound discovery links. A canonical link is used only if the recognition engine supplied one AND its host is on
 * the allow-list for that service over HTTPS; otherwise a plain search URL is built. We never guess track URLs.
 */
object ExternalLinks {
    private val allowedHosts = mapOf(
        Service.APPLE_MUSIC to setOf("music.apple.com", "itunes.apple.com"),
        Service.SPOTIFY to setOf("open.spotify.com"),
        Service.YOUTUBE to setOf("www.youtube.com", "youtube.com", "music.youtube.com", "youtu.be"),
    )

    fun url(service: Service, song: Song): String {
        val canonical = if (service == Service.APPLE_MUSIC) song.appleMusicUrl else null
        if (canonical != null && isAllowed(service, canonical)) return canonical
        val q = enc("${song.title} ${song.artist}")
        return when (service) {
            Service.APPLE_MUSIC -> "https://music.apple.com/search?term=$q"
            Service.SPOTIFY -> "https://open.spotify.com/search/$q"
            Service.YOUTUBE -> "https://www.youtube.com/results?search_query=$q"
        }
    }

    fun isAllowed(service: Service, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in allowedHosts.getValue(service)
    }

    /** Only https links to Wikipedia/Wikidata (or the services above) may be opened from content we fetched. */
    fun isSafeWebUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host.endsWith(".wikipedia.org") || host == "www.wikidata.org" ||
            allowedHosts.values.any { host in it })
    }

    fun wikipediaSearchUrl(query: String) = "https://en.wikipedia.org/w/index.php?search=${enc(query)}"

    fun shareText(song: Song): String =
        "I just identified \"${song.title}\" by ${song.artist} with Touch Music ID.\n${url(Service.APPLE_MUSIC, song)}"

    /** Opens a URL in the user's browser/app. Returns false (never crashes) if nothing can handle it. */
    fun open(context: Context, url: String): Boolean {
        if (!isSafeWebUrl(url)) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun share(context: Context, song: Song): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(song))
        context.startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
