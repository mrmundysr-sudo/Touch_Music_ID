package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.UrlConnectionFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in smoke test against the REAL Wikipedia and Wikidata APIs. Skipped unless LIVE_WIKI=1 (CI runs it as an
 * informational job because some sandboxes, including the development sandbox, cannot reach those hosts).
 * It checks that real responses parse and that the conservative matcher behaves sensibly on well-known music.
 * Output lines are prefixed LIVE-WIKI so CI can surface them.
 */
class LiveWikiSmokeTest {
    private val enabled get() = System.getenv("LIVE_WIKI") == "1"

    private fun summarize(label: String, l: Lookup) {
        val e = l.entity
        println("LIVE-WIKI $label: status=${l.status} qid=${e?.qid} name=${e?.name} wiki=${e?.wikipediaUrl} facts=${e?.facts?.size} extractChars=${e?.extract?.length} candidates=${l.candidates.size}")
        e?.facts?.forEach { println("LIVE-WIKI   fact ${it.label}=${it.value}") }
    }

    @Test fun queenAndBohemianRhapsodyResolveAgainstRealApis() = runBlocking {
        assumeTrue("LIVE_WIKI not set", enabled)
        val resolver = KnowledgeResolver(WikiApi(UrlConnectionFetcher()))
        val (artist, song) = resolver.resolve(Song("Bohemian Rhapsody", "Queen"))
        summarize("artist Queen", artist); summarize("song Bohemian Rhapsody", song)
        assertEquals("Queen must resolve to exactly one verified musical entity", MatchStatus.VERIFIED, artist.status)
        assertNotNull(artist.entity!!.extract)
        assertTrue(artist.entity!!.wikipediaUrl!!.startsWith("https://en.wikipedia.org/wiki/"))
        assertTrue("expected at least one Wikidata fact", artist.entity!!.facts.isNotEmpty())
        assertEquals(MatchStatus.VERIFIED, song.status)
    }

    @Test fun nonsenseNamesAssertNothing() = runBlocking {
        assumeTrue("LIVE_WIKI not set", enabled)
        val resolver = KnowledgeResolver(WikiApi(UrlConnectionFetcher()))
        val (artist, song) = resolver.resolve(Song("Zzqx Vlorb Wmpt", "Qqxz Plorbnik Vwyt"))
        summarize("artist nonsense", artist); summarize("song nonsense", song)
        assertEquals(MatchStatus.NONE, artist.status)
        assertEquals(MatchStatus.NONE, song.status)
    }

    @Test fun commonNameIsNeverSilentlyGuessed() = runBlocking {
        assumeTrue("LIVE_WIKI not set", enabled)
        val resolver = KnowledgeResolver(WikiApi(UrlConnectionFetcher()))
        val artist = resolver.resolveArtist("Prince")
        summarize("artist Prince", artist)
        // Either exactly one verified musician, or the user is asked to choose. Never "guess" among several.
        assertTrue(artist.status == MatchStatus.VERIFIED || artist.status == MatchStatus.AMBIGUOUS)
        if (artist.status == MatchStatus.AMBIGUOUS) assertTrue(artist.candidates.size >= 2)
    }
}
