package com.mundymorning.touchmusicid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.links.ExternalLinks
import com.mundymorning.touchmusicid.links.Service
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalLinksTest {
    private val song = Song("Smells Like Teen Spirit", "Nirvana")

    @Test fun buildsEncodedSearchUrlsWhenNoCanonicalLinkExists() {
        assertEquals("https://music.apple.com/search?term=Smells%20Like%20Teen%20Spirit%20Nirvana", ExternalLinks.url(Service.APPLE_MUSIC, song))
        assertEquals("https://open.spotify.com/search/Smells%20Like%20Teen%20Spirit%20Nirvana", ExternalLinks.url(Service.SPOTIFY, song))
        assertEquals("https://www.youtube.com/results?search_query=Smells%20Like%20Teen%20Spirit%20Nirvana", ExternalLinks.url(Service.YOUTUBE, song))
    }

    @Test fun specialCharactersCannotBreakOutOfTheQuery() {
        val u = ExternalLinks.url(Service.SPOTIFY, Song("A&b=c#d/e?f", "X"))
        assertFalse(u.substringAfter("search/").contains(Regex("[&=#/?]")))
    }

    @Test fun usesCanonicalAppleLinkOnlyIfHostAllowedAndHttps() {
        val good = song.copy(appleMusicUrl = "https://music.apple.com/us/album/x/1?i=2")
        assertEquals(good.appleMusicUrl, ExternalLinks.url(Service.APPLE_MUSIC, good))
        for (bad in listOf("http://music.apple.com/x", "https://evil.example/music.apple.com", "javascript:alert(1)", "intent://x#Intent;end", "https://music.apple.com.evil.example/x")) {
            assertTrue("fallback to search for $bad", ExternalLinks.url(Service.APPLE_MUSIC, song.copy(appleMusicUrl = bad)).startsWith("https://music.apple.com/search?term="))
        }
    }

    @Test fun contentFetchedFromTheWebCanOnlyOpenKnownHttpsHosts() {
        assertTrue(ExternalLinks.isSafeWebUrl("https://en.wikipedia.org/wiki/Queen_(band)"))
        assertTrue(ExternalLinks.isSafeWebUrl("https://www.wikidata.org/wiki/Q15862"))
        assertFalse(ExternalLinks.isSafeWebUrl("http://en.wikipedia.org/wiki/X"))
        assertFalse(ExternalLinks.isSafeWebUrl("https://wikipedia.org.evil.example/"))
        assertFalse(ExternalLinks.isSafeWebUrl("file:///sdcard/x"))
        assertFalse(ExternalLinks.isSafeWebUrl("not a url"))
    }

    @Test fun openLaunchesViewIntentAndRefusesUnsafeUrls() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(ExternalLinks.open(ctx, "https://en.wikipedia.org/wiki/Queen_(band)"))
        val started = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertFalse(ExternalLinks.open(ctx, "https://evil.example/"))
        assertNull(Shadows.shadowOf(ctx).nextStartedActivity)
    }

    @Test fun shareTextNamesSongAndArtist() {
        val t = ExternalLinks.shareText(song)
        assertTrue(t.contains("Smells Like Teen Spirit") && t.contains("Nirvana") && t.contains("https://"))
    }

    // ---- Spotify: the query must reach Spotify's search ----
    private val tricky = listOf(
        Song("Don't Stop Believin'", "Journey"),
        Song("(I Can't Get No) Satisfaction", "The Rolling Stones"),
        Song("Uptown Funk", "Mark Ronson feat. Bruno Mars"),
        Song("Rock & Roll, Pt. 2", "Gary Glitter"),
        Song("Back in Black", "AC/DC"),
        Song("Halo", "Beyoncé"),
        Song("  Spaced   Out  ", "  Some   Band "),
        Song("100% #1 = ?best", "A+B"),
    )

    @Test fun spotifyAppUriCarriesTheEncodedTitleAndArtist() {
        for (t in tricky) {
            val step = ExternalLinks.spotifyPlan(t).first()
            assertEquals(ExternalLinks.SPOTIFY_PACKAGE, step.packageName)
            assertTrue(step.uri.startsWith("spotify:search:"))
            val encoded = step.uri.removePrefix("spotify:search:")
            assertFalse("no raw reserved characters in $encoded", encoded.contains(Regex("[ '&=#/?+()]")))
            assertFalse("every % starts a valid escape in $encoded", encoded.contains(Regex("%(?![0-9A-F]{2})")))
            assertEquals(ExternalLinks.searchText(t), java.net.URLDecoder.decode(encoded, "UTF-8"))
            // Android's URI parser hands the Spotify app the same still-encoded text.
            assertEquals(encoded, android.net.Uri.parse(step.uri).encodedSchemeSpecificPart.removePrefix("search:"))
        }
        assertEquals("spotify:search:Don%27t%20Stop%20Believin%27%20Journey", ExternalLinks.spotifyPlan(tricky[0]).first().uri)
        assertEquals("Spaced Out Some Band", ExternalLinks.searchText(tricky[6]))
    }

    @Test fun spotifyWebFallbackIsBrowserOnlyAndEncoded() {
        for (t in tricky) {
            val last = ExternalLinks.spotifyPlan(t).last()
            assertTrue(last.browserOnly)
            assertNull(last.packageName)
            assertTrue(ExternalLinks.isAllowed(Service.SPOTIFY, last.uri))
            assertEquals(ExternalLinks.url(Service.SPOTIFY, t), last.uri)
            assertEquals(ExternalLinks.searchText(t), java.net.URLDecoder.decode(last.uri.substringAfter("/search/"), "UTF-8"))
        }
    }

    @Test fun planWithoutVerifiedTrackIsAppSearchThenBrowser() {
        val plan = ExternalLinks.spotifyPlan(song)
        assertEquals(2, plan.size)
        assertEquals("spotify:search:Smells%20Like%20Teen%20Spirit%20Nirvana", plan[0].uri)
        assertEquals("https://open.spotify.com/search/Smells%20Like%20Teen%20Spirit%20Nirvana", plan[1].uri)
    }

    @Test fun verifiedSpotifyTrackIsPreferredWhenGenuine() {
        val id = "4cOdK2wGLETKBW3PvgPWqT"
        for (v in listOf("https://open.spotify.com/track/$id", "https://open.spotify.com/track/$id?si=abc123", "https://open.spotify.com/intl-de/track/$id", "spotify:track:$id")) {
            val plan = ExternalLinks.spotifyPlan(song, v)
            assertEquals(v, "https://open.spotify.com/track/$id", plan.first().uri)
            assertEquals(3, plan.size)
        }
    }

    @Test fun unverifiedOrLookAlikeSpotifyTrackLinksAreIgnored() {
        val id = "4cOdK2wGLETKBW3PvgPWqT"
        for (bad in listOf(null, "", "http://open.spotify.com/track/$id", "https://evil.example/track/$id", "https://open.spotify.com.evil.example/track/$id",
            "https://open.spotify.com/track/short", "https://open.spotify.com/album/$id", "javascript:alert(1)", "spotify:track:short", "spotify:search:x", "intent://x#Intent;end")) {
            assertNull("rejected: $bad", ExternalLinks.spotifyTrackUrl(bad))
            assertEquals("falls back to search for $bad", 2, ExternalLinks.spotifyPlan(song, bad).size)
        }
    }

    @Test fun onlyOurOwnSpotifyStepsPassTheSafetyGate() {
        assertTrue(ExternalLinks.spotifyPlan(song, "spotify:track:4cOdK2wGLETKBW3PvgPWqT").all { ExternalLinks.isSafeSpotifyStep(it) })
        assertFalse(ExternalLinks.isSafeSpotifyStep(ExternalLinks.LaunchStep("spotify:search:x")))            // no package pin
        assertFalse(ExternalLinks.isSafeSpotifyStep(ExternalLinks.LaunchStep("file:///sdcard/x", ExternalLinks.SPOTIFY_PACKAGE)))
        assertFalse(ExternalLinks.isSafeSpotifyStep(ExternalLinks.LaunchStep("https://evil.example/search/x")))
    }

    @Test fun spotifyAppInstalledGetsTheSearchUriPinnedToItsPackage() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(ExternalLinks.openSpotify(ctx, tricky[0]))
        val started = Shadows.shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, started.action)
        assertEquals(ExternalLinks.SPOTIFY_PACKAGE, started.`package`)
        assertEquals("spotify:search:Don%27t%20Stop%20Believin%27%20Journey", started.dataString)
    }

    @Test fun spotifyMissingFallsBackToTheWebSearchInABrowser() {
        val ctx = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        val app = Shadows.shadowOf(ctx)
        app.checkActivities(true) // behave like a real device: unresolved intents throw ActivityNotFoundException
        val web = android.net.Uri.parse(ExternalLinks.url(Service.SPOTIFY, tricky[0]))
        val browser = android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_BROWSER).setData(web)
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.chrome"; name = "Main"; exported = true }
        }
        Shadows.shadowOf(ctx.packageManager).addResolveInfoForIntent(browser, info)
        assertTrue(ExternalLinks.openSpotify(ctx, tricky[0]))
        val started = app.nextStartedActivity
        assertEquals(web, started.data)
        assertTrue(started.selector?.hasCategory(android.content.Intent.CATEGORY_APP_BROWSER) == true)
    }

    @Test fun nothingAbleToOpenSpotifyReturnsFalseWithoutCrashing() {
        val ctx = ApplicationProvider.getApplicationContext<Context>() as android.app.Application
        Shadows.shadowOf(ctx).checkActivities(true)
        assertFalse(ExternalLinks.openSpotify(ctx, song))
    }

    // ---- Regression: Apple Music and YouTube are unchanged ----
    @Test fun appleMusicAndYouTubeKeepTheirSearchUrlsForTrickyTitles() {
        val t = tricky[0]
        assertEquals("https://music.apple.com/search?term=Don%27t%20Stop%20Believin%27%20Journey", ExternalLinks.url(Service.APPLE_MUSIC, t))
        assertEquals("https://www.youtube.com/results?search_query=Don%27t%20Stop%20Believin%27%20Journey", ExternalLinks.url(Service.YOUTUBE, t))
        val multi = tricky[2]
        assertEquals("https://music.apple.com/search?term=Uptown%20Funk%20Mark%20Ronson%20feat.%20Bruno%20Mars", ExternalLinks.url(Service.APPLE_MUSIC, multi))
        assertEquals("https://www.youtube.com/results?search_query=Uptown%20Funk%20Mark%20Ronson%20feat.%20Bruno%20Mars", ExternalLinks.url(Service.YOUTUBE, multi))
    }
}
