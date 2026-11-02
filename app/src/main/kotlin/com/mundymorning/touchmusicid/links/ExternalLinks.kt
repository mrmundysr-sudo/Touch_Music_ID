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
        val q = enc(searchText(song))
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

    // ---- Spotify ----
    // Why: the Spotify Android app claims https://open.spotify.com/search/<query> links, opens its search screen, and
    // drops the query that is carried in the URL *path*. Spotify's own URI scheme (spotify:search:<query>) carries it in a form the app
    // reads, so the app is tried that way first; the https search page (forced into a browser so the app cannot claim it
    // again) is the fallback when the app isn't installed.

    const val SPOTIFY_PACKAGE = "com.spotify.music"

    /** One way of opening Spotify. [browserOnly] bypasses app-link handling so the web search page really loads. */
    data class LaunchStep(val uri: String, val packageName: String? = null, val browserOnly: Boolean = false)

    /** Plain "title artist" text used for searches: trimmed, whitespace collapsed. Punctuation is kept (encoding handles it). */
    fun searchText(song: Song): String = "${song.title} ${song.artist}".trim().replace(Regex("\\s+"), " ")

    /** Returns the canonical https track URL only for a genuine open.spotify.com/track/<22 chars> link (or spotify:track:<id>); else null. */
    fun spotifyTrackUrl(verified: String?): String? {
        val v = verified?.trim().orEmpty()
        Regex("^spotify:track:([A-Za-z0-9]{22})$").matchEntire(v)?.let { return "https://open.spotify.com/track/${it.groupValues[1]}" }
        val uri = runCatching { Uri.parse(v) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase() != "open.spotify.com") return null
        val seg = uri.pathSegments
        // Allow a locale prefix such as /intl-de/track/<id>.
        val i = seg.indexOf("track").takeIf { it in 0..1 } ?: return null
        val id = seg.getOrNull(i + 1) ?: return null
        return if (Regex("[A-Za-z0-9]{22}").matches(id)) "https://open.spotify.com/track/$id" else null
    }

    /**
     * Ordered ways to open Spotify for [song]. [verifiedTrackUrl] must come from a trustworthy source (none supplies one yet:
     * ShazamKit returns Apple/Shazam links only); when valid it is preferred because it opens the exact recording.
     */
    fun spotifyPlan(song: Song, verifiedTrackUrl: String? = null): List<LaunchStep> {
        val q = enc(searchText(song))
        return buildList {
            spotifyTrackUrl(verifiedTrackUrl)?.let { add(LaunchStep(it)) }
            add(LaunchStep("spotify:search:$q", packageName = SPOTIFY_PACKAGE))
            add(LaunchStep("https://open.spotify.com/search/$q", browserOnly = true))
        }
    }

    /** Safety gate for plan steps: only our own spotify:search: URIs or https links on the Spotify host. */
    internal fun isSafeSpotifyStep(step: LaunchStep): Boolean =
        (step.uri.startsWith("spotify:search:") && step.packageName == SPOTIFY_PACKAGE) ||
            (step.packageName == null && isAllowed(Service.SPOTIFY, step.uri))

    /**
     * Spotify offers no supported way to prefill its search from outside (the app and the mobile web page both ignore or drop the
     * query on many devices), so the text is also put on the clipboard. The user can paste it. Returns the copied text, or null if
     * the clipboard is unavailable. Written only on an explicit tap, never read.
     */
    fun copySearchText(context: Context, song: Song): String? {
        val text = searchText(song)
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Song search", text))
            text
        } catch (e: RuntimeException) { null }
    }

    /** Tries each step in order and stops at the first the device can handle. Returns false only if none could. */
    fun openSpotify(context: Context, song: Song, verifiedTrackUrl: String? = null): Boolean {
        for (step in spotifyPlan(song, verifiedTrackUrl)) {
            if (!isSafeSpotifyStep(step)) continue
            val uri = Uri.parse(step.uri)
            val intent = if (step.browserOnly) {
                // Forces a real browser; avoids Spotify's app-link claim on open.spotify.com.
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).setData(uri)
            } else {
                Intent(Intent.ACTION_VIEW, uri).apply { step.packageName?.let { setPackage(it) } }
            }
            if (start(context, intent)) return true
            if (step.browserOnly && start(context, Intent(Intent.ACTION_VIEW, uri))) return true
        }
        return false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

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
