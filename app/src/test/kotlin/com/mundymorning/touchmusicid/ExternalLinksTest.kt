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
}
