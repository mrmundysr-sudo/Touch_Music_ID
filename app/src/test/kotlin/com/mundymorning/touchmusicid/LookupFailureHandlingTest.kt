package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Failure semantics end to end through KnowledgeRepository: a failed lookup is LOOKUP_FAILED and is never cached or shown as
 * "not found"; a completed lookup is VERIFIED / NOT_FOUND / AMBIGUOUS. (Replaces the earlier characterization of the old, wrong behaviour.)
 */
class LookupFailureHandlingTest {
    private val song = Song("Some Title", "Morgan Wallen")
    private val wiki = FakeWiki(
        db = mapOf("Q1" to entityJson("Q1", "Morgan Wallen", "American singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q488205", "Q177220"), "P434" to listOf("mb")))),
        entitySearch = mapOf("Morgan Wallen" to searchJson(Triple("Q1", "Morgan Wallen", "American singer"))),
    )
    /** When set, replaces Wikidata search/entity responses; returning null simulates an IO failure. */
    private var broken: ((String) -> HttpResponse?)? = null
    private var brokenFor: (String) -> Boolean = { "action=wbsearchentities" in it || "action=wbgetentities" in it }
    private val http = FakeHttp { url -> val b = broken; if (b != null && brokenFor(url)) b(url) else wiki.http.handler(url) }
    private var now = 1_000_000_000L
    private val cache = InMemoryKnowledgeCache()
    private val repo = KnowledgeRepository(KnowledgeResolver(WikiApi(http, pause = {})), cache, clock = { now })
    private fun cached() = cache.map.keys.count { it.startsWith("k2|") }
    private fun artistOf(r: KnowledgeResult) = (r as KnowledgeResult.Found).bundle.artist

    @Test fun healthyBaselineIsVerified() = runTest {
        assertEquals(MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
        assertEquals(1, cached())
    }

    // ---- total failures: Failed(kind), nothing cached, never "not found" ----
    private suspend fun assertFailsWithoutCaching(label: String, kind: KnowledgeResult.Kind, bad: (String) -> HttpResponse?) {
        cache.clear(); broken = bad
        val r = repo.load(song)
        assertEquals(label, KnowledgeResult.Failed(kind), r)
        assertEquals("$label must not be cached", 0, cached())
        broken = null
        assertEquals("$label: next lookup on a healthy network succeeds immediately", MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
    }

    @Test fun everyKindOfFailureIsLookupFailedAndNeverCached() = runTest {
        val s = KnowledgeResult.Kind.SERVER
        assertFailsWithoutCaching("api error body (too busy)", s) { ok("""{"error":{"code":"cirrussearch-too-busy-error","info":"busy"}}""") }
        assertFailsWithoutCaching("maxlag", s) { ok("""{"error":{"code":"maxlag","info":"lagged"}}""") }
        assertFailsWithoutCaching("html page with 200 (captive portal)", s) { ok("<html><body>Sign in</body></html>") }
        assertFailsWithoutCaching("empty body", s) { ok("") }
        assertFailsWithoutCaching("missing search field", s) { ok("""{"success":1}""") }
        assertFailsWithoutCaching("HTTP 500", s) { HttpResponse(500, "oops") }
        assertFailsWithoutCaching("HTTP 503", s) { HttpResponse(503, "") }
        assertFailsWithoutCaching("HTTP 429", KnowledgeResult.Kind.RATE_LIMITED) { HttpResponse(429, "", 30) }
        assertFailsWithoutCaching("network failure", KnowledgeResult.Kind.OFFLINE) { null }
    }

    @Test fun truncatedEntitiesResponseAfterAGoodSearchIsAFailureToo() = runTest {
        broken = { """{"entities":{"Q1":""".let(::ok) }
        brokenFor = { "action=wbgetentities" in it }
        val r = repo.load(song)
        assertEquals(MatchStatus.LOOKUP_FAILED, artistOf(r).status)   // the artist card fails; it is NOT "not found"
        assertEquals(KnowledgeResult.Kind.SERVER, artistOf(r).failure)
        assertEquals(0, cached())
    }

    // ---- genuine results ----
    @Test fun emptySearchIsAGenuineNotFoundAndIsCachedBriefly() = runTest {
        broken = { ok("""{"searchinfo":{"search":"x"},"search":[],"success":1}""") }
        val first = repo.load(song)
        assertEquals(MatchStatus.NOT_FOUND, artistOf(first).status)
        assertEquals(1, cached())
        broken = null
        now += 30 * 60 * 1000
        assertEquals("within the hour: served from cache", Freshness.CACHE_FRESH, (repo.load(song) as KnowledgeResult.Found).freshness)
        assertEquals("manual retry bypasses the cache", MatchStatus.VERIFIED, artistOf(repo.load(song, forceRefresh = true)).status)
    }

    @Test fun notFoundHealsAfterTheShortLifetimeWithoutAnyAction() = runTest {
        broken = { ok("""{"search":[]}""") }
        repo.load(song); broken = null
        now += KnowledgeRepository.NOT_FOUND_TTL + 1
        assertEquals(MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
    }

    // ---- partial failure, retry, legacy cache ----
    @Test fun oneFailedCardDoesNotBecomeNotFoundAndNothingIsCached() = runTest {
        broken = { ok("""{"error":{"code":"cirrussearch-too-busy-error","info":"busy"}}""") }
        brokenFor = { "action=wbsearchentities" in it && "search=Morgan" in it }   // only the artist search fails
        val r = repo.load(song) as KnowledgeResult.Found
        assertEquals(MatchStatus.LOOKUP_FAILED, r.bundle.artist.status)
        assertEquals(KnowledgeResult.Kind.SERVER, r.bundle.artist.failure)
        assertEquals("the other card still completes", MatchStatus.NOT_FOUND, r.bundle.song.status)
        assertEquals(0, cached())
        broken = null
        assertEquals(MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
    }

    @Test fun aSingleTransientErrorIsAbsorbedByTheRetry() = runTest {
        var n = 0
        broken = { url -> if (++n == 1) ok("""{"error":{"code":"cirrussearch-too-busy-error","info":"busy"}}""") else wiki.http.handler(url) }
        brokenFor = { "action=wbsearchentities" in it && "search=Morgan" in it }
        assertEquals(MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
    }

    @Test fun failureNeverReplacesGoodStaleData() = runTest {
        repo.load(song)
        now += 30 * KnowledgeRepository.DAY
        broken = { ok("""{"error":{"code":"maxlag","info":"lagged"}}""") }
        val r = repo.load(song) as KnowledgeResult.Found
        assertEquals(Freshness.CACHE_STALE_OFFLINE, r.freshness)
        assertEquals(MatchStatus.VERIFIED, r.bundle.artist.status)
    }

    @Test fun earlierCachedNoMatchResultsAreDiscardedByTheVersionBump() = runTest {
        // A poisoned entry exactly as the previous version stored it (key "k1|...", status "NONE").
        val legacy = """{"v":1,"at":1,"artist":{"status":"NONE","candidates":[]},"song":{"status":"NONE","candidates":[]}}"""
        cache.put("k1|" + song.key, legacy, now)
        assertNull("legacy payload is a cache miss, not a crash", BundleCodec.decode(legacy))
        assertEquals(MatchStatus.VERIFIED, artistOf(repo.load(song)).status)
    }

    @Test fun lookupFailedRoundTripsThroughTheCodecWithItsReason() {
        val b = KnowledgeBundle(Lookup(MatchStatus.LOOKUP_FAILED, failure = KnowledgeResult.Kind.RATE_LIMITED), Lookup(MatchStatus.NOT_FOUND), 5)
        assertEquals(b, BundleCodec.decode(BundleCodec.encode(b)))
    }
}
