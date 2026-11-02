package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KnowledgeRepositoryTest {
    private val song = Song("Bohemian Rhapsody", "Queen")
    private var now = 1_000_000L

    private fun world(): FakeWiki {
        val queen = entityJson("Q15862", "Queen", "British rock band", claims = mapOf("P31" to listOf("Q5741069"), "P434" to listOf("m")), enwiki = "Queen (band)")
        val bo = entityJson("Q187655", "Bohemian Rhapsody", null, claims = mapOf("P31" to listOf("Q7366"), "P175" to listOf("Q15862")), enwiki = "Bohemian Rhapsody")
        return FakeWiki(
            db = mapOf("Q15862" to queen, "Q187655" to bo),
            entitySearch = mapOf("Queen" to searchJson(Triple("Q15862", "Queen", null)), "Bohemian Rhapsody" to searchJson(Triple("Q187655", "Bohemian Rhapsody", null))),
            summaries = mapOf("Queen_(band)" to summaryJson("Queen (band)", "Queen are a band."), "Bohemian_Rhapsody" to summaryJson("Bohemian Rhapsody", "A song by Queen.")),
        )
    }

    private fun repo(w: FakeWiki, cache: KnowledgeCache = InMemoryKnowledgeCache()) =
        KnowledgeRepository(KnowledgeResolver(WikiApi(w.http)), cache, clock = { now }) to cache

    @Test fun firstLoadIsLiveAndSecondComesFromCacheWithoutNetwork() = runTest {
        val w = world(); val (r, _) = repo(w)
        val first = r.load(song) as KnowledgeResult.Found
        assertEquals(Freshness.LIVE, first.freshness)
        val calls = w.http.urls.size
        val second = r.load(song) as KnowledgeResult.Found
        assertEquals(Freshness.CACHE_FRESH, second.freshness)
        assertEquals("no network on a fresh cache hit", calls, w.http.urls.size)
        assertEquals(first.bundle, second.bundle)
    }

    @Test fun expiredCacheRefetches() = runTest {
        val w = world(); val (r, _) = repo(w)
        r.load(song)
        now += 8 * KnowledgeRepository.DAY
        assertEquals(Freshness.LIVE, (r.load(song) as KnowledgeResult.Found).freshness)
    }

    @Test fun offlineWithStaleCacheServesStaleAndSaysSo() = runTest {
        val w = world(); val (r, _) = repo(w)
        r.load(song)
        now += 30 * KnowledgeRepository.DAY
        w.http.handler = { null }
        val res = r.load(song) as KnowledgeResult.Found
        assertEquals(Freshness.CACHE_STALE_OFFLINE, res.freshness)
        assertEquals(MatchStatus.VERIFIED, res.bundle.artist.status)
    }

    @Test fun offlineWithNoCacheIsAnOfflineError() = runTest {
        val w = world(); w.http.handler = { null }
        val res = repo(w).first.load(song)
        assertEquals(KnowledgeResult.Failed(KnowledgeResult.Kind.OFFLINE), res)
    }

    @Test fun rateLimitAndServerErrorsAreDistinguished() = runTest {
        val w = world(); w.http.handler = { HttpResponse(429, "", 30) }
        assertEquals(KnowledgeResult.Failed(KnowledgeResult.Kind.RATE_LIMITED), repo(w).first.load(song))
        val w2 = world(); w2.http.handler = { HttpResponse(503, "") }
        assertEquals(KnowledgeResult.Failed(KnowledgeResult.Kind.SERVER), repo(w2).first.load(song))
    }

    @Test fun forceRefreshBypassesFreshCache() = runTest {
        val w = world(); val (r, _) = repo(w)
        r.load(song); val n = w.http.urls.size
        assertEquals(Freshness.LIVE, (r.load(song, forceRefresh = true) as KnowledgeResult.Found).freshness)
        assertTrue(w.http.urls.size > n)
    }

    @Test fun negativeResultsExpireSooner() = runTest {
        val w = FakeWiki(emptyMap(), emptyMap()); val (r, _) = repo(w)
        val first = r.load(Song("Nothing", "Nobody")) as KnowledgeResult.Found
        assertEquals(MatchStatus.NOT_FOUND, first.bundle.artist.status)
        now += 30 * 60 * 1000 // within the 1 h NOT_FOUND lifetime: served from cache
        assertEquals(Freshness.CACHE_FRESH, (r.load(Song("Nothing", "Nobody")) as KnowledgeResult.Found).freshness)
        now += 31 * 60 * 1000 // past 1 h: looked up again
        assertEquals(Freshness.LIVE, (r.load(Song("Nothing", "Nobody")) as KnowledgeResult.Found).freshness)
    }

    @Test fun ambiguousArtistChoiceIsRememberedAndApplied() = runTest {
        val a = entityJson("Q1", "Prince", "American musician", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q639669")))
        val b = entityJson("Q2", "Prince", "Dutch singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q177220")))
        val w = FakeWiki(mapOf("Q1" to a, "Q2" to b), mapOf("Prince" to searchJson(Triple("Q1", "Prince", null), Triple("Q2", "Prince", null))))
        val (r, _) = repo(w)
        val s = Song("Purple Rain", "Prince")
        assertEquals(MatchStatus.AMBIGUOUS, (r.load(s) as KnowledgeResult.Found).bundle.artist.status)
        val chosen = r.chooseArtist(s, "Q1") as KnowledgeResult.Found
        assertEquals("Q1", chosen.bundle.artist.entity!!.qid)
        now += 30 * KnowledgeRepository.DAY
        assertEquals("choice survives cache expiry", "Q1", (r.load(s) as KnowledgeResult.Found).bundle.artist.entity!!.qid)
    }

    @Test fun codecRoundTripsEverything() {
        val bundle = KnowledgeBundle(
            Lookup(MatchStatus.VERIFIED, EntityKnowledge("Q1", "Queen", "band", "ex", "https://w", "https://d", listOf(Fact("Born", "1970", FactSource.WIKIDATA, "https://d")), "why")),
            Lookup(MatchStatus.AMBIGUOUS, null, listOf(Candidate("Q2", "X", null))), 123,
        )
        assertEquals(bundle, BundleCodec.decode(BundleCodec.encode(bundle)))
        assertNull(BundleCodec.decode("garbage"))
        assertNull(BundleCodec.decode("""{"v":99}"""))
    }

    @Test fun sourcesListAttributesEveryEntity() {
        val e = EntityKnowledge("Q1", "Queen", null, null, "https://en.wikipedia.org/wiki/Queen", "https://www.wikidata.org/wiki/Q1", emptyList(), "b")
        val s = KnowledgeBundle(Lookup(MatchStatus.VERIFIED, e), Lookup(MatchStatus.NOT_FOUND), 0).sources()
        assertEquals(listOf("Wikipedia: Queen", "Wikidata: Queen"), s.map { it.label })
    }
}
