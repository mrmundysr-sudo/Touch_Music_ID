package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * CHARACTERIZATION of CURRENT behaviour (diagnostic step, no production change). These tests document what the repository does
 * when Wikidata misbehaves. Several assertions describe behaviour we consider WRONG (a failure cached as NONE); when the fix lands
 * they are meant to be inverted. Output lines prefixed DIAGCHAR.
 */
class LookupFailureCharacterizationTest {
    private val song = Song("Some Title", "Morgan Wallen")
    private val wiki = FakeWiki(
        db = mapOf("Q1" to entityJson("Q1", "Morgan Wallen", "American singer", claims = mapOf("P31" to listOf("Q5"), "P106" to listOf("Q488205", "Q177220")))),
        entitySearch = mapOf("Morgan Wallen" to searchJson(Triple("Q1", "Morgan Wallen", "American singer"))),
    )
    private var broken: ((String) -> HttpResponse?)? = null
    private val http = FakeHttp { url -> broken?.let { b -> if (isSearchOrEntities(url)) return@FakeHttp b(url) }; wiki.http.handler(url) }
    private fun isSearchOrEntities(u: String) = "action=wbsearchentities" in u || "action=wbgetentities" in u
    private var now = 1_000_000_000L
    private val cache = InMemoryKnowledgeCache()
    private val repo = KnowledgeRepository(KnowledgeResolver(WikiApi(http)), cache, clock = { now })

    private fun describe(r: KnowledgeResult) = when (r) {
        is KnowledgeResult.Found -> "Found(${r.freshness}, artist=${r.bundle.artist.status})"
        is KnowledgeResult.Failed -> "Failed(${r.kind})"
    }
    private fun cachedEntries() = cache.map.keys.count { it.startsWith("k1|") }

    private suspend fun scenario(label: String, bad: (String) -> HttpResponse?): KnowledgeResult {
        broken = bad
        val r = repo.load(song)
        println("DIAGCHAR $label -> ${describe(r)} ; cachedEntries=${cachedEntries()}")
        return r
    }

    @Test fun healthyBaselineIsVerified() = runTest {
        val r = repo.load(song) as KnowledgeResult.Found
        assertEquals(MatchStatus.VERIFIED, r.bundle.artist.status)
    }

    // ---- Failures that are (correctly) NOT cached and surface as LOOKUP_FAILED ----
    @Test fun httpErrorsAndIoFailuresAreFailedAndNotCached() = runTest {
        for ((label, resp) in listOf<Pair<String, HttpResponse?>>("HTTP 429" to HttpResponse(429, "", 30), "HTTP 500" to HttpResponse(500, "oops"), "HTTP 503" to HttpResponse(503, ""), "IO/timeout" to null)) {
            cache.clear()
            val r = scenario(label) { resp }
            assertTrue("$label must be a failure, not a result", r is KnowledgeResult.Failed)
            assertEquals("$label must not be cached", 0, cachedEntries())
        }
    }

    // ---- Failures that the CURRENT code mistakes for "no match" and caches ----
    @Test fun currentBehaviour_emptySearchResponseIsCachedAsNone() = runTest {
        val r = scenario("200 {\"search\":[]}") { ok("""{"searchinfo":{"search":"Morgan Wallen"},"search":[],"success":1}""") }
        assertEquals(MatchStatus.NONE, (r as KnowledgeResult.Found).bundle.artist.status)
        assertEquals(1, cachedEntries())
    }

    @Test fun currentBehaviour_apiErrorObjectWithHttp200IsCachedAsNone() = runTest {
        val r = scenario("200 {error:{code:maxlag}}") { ok("""{"error":{"code":"maxlag","info":"Waiting for a database server: 6 seconds lagged."},"servedby":"mw"}""") }
        assertEquals(MatchStatus.NONE, (r as KnowledgeResult.Found).bundle.artist.status)
        assertEquals(1, cachedEntries())
    }

    @Test fun currentBehaviour_htmlBodyWith200FromCaptivePortalOrProxyIsCachedAsNone() = runTest {
        val r = scenario("200 <html> (captive portal / proxy page)") { ok("<html><body>Sign in to continue</body></html>") }
        assertEquals(MatchStatus.NONE, (r as KnowledgeResult.Found).bundle.artist.status)
        assertEquals(1, cachedEntries())
    }

    @Test fun currentBehaviour_garbledEntitiesResponseAfterGoodSearchIsCachedAsNone() = runTest {
        val r = scenario("search OK, entities body garbled") { url ->
            if ("action=wbsearchentities" in url) wiki.http.handler(url) else ok("""{"entities":{"Q1":""")  // truncated JSON
        }
        assertEquals(MatchStatus.NONE, (r as KnowledgeResult.Found).bundle.artist.status)
        assertEquals(1, cachedEntries())
    }

    @Test fun currentBehaviour_entitiesMissingFromAnOtherwiseValidResponseIsCachedAsNone() = runTest {
        val r = scenario("entities 200 but entity absent") { url ->
            if ("action=wbsearchentities" in url) wiki.http.handler(url) else ok("""{"entities":{"Q1":{"id":"Q1","missing":""}}}""")
        }
        assertEquals(MatchStatus.NONE, (r as KnowledgeResult.Found).bundle.artist.status)
    }

    // ---- Does an existing cached NONE block later successful lookups? ----
    @Test fun currentBehaviour_cachedNoneBlocksSuccessForADayUnlessForcedOrExpired() = runTest {
        scenario("poison: empty search") { ok("""{"search":[]}""") }
        broken = null // network is healthy again
        now += 60 * 60 * 1000 // one hour later
        val again = repo.load(song)
        println("DIAGCHAR healthy network 1h later, plain load -> ${describe(again)}")
        assertEquals("cached NONE is served instead of retrying", Freshness.CACHE_FRESH, (again as KnowledgeResult.Found).freshness)
        assertEquals(MatchStatus.NONE, again.bundle.artist.status)

        val forced = repo.load(song, forceRefresh = true)
        println("DIAGCHAR healthy network, forceRefresh -> ${describe(forced)}")
        assertEquals(MatchStatus.VERIFIED, (forced as KnowledgeResult.Found).bundle.artist.status)

        cache.clear(); scenario("poison again") { ok("""{"search":[]}""") }; broken = null
        now += 25 * 60 * 60 * 1000
        val expired = repo.load(song)
        println("DIAGCHAR healthy network 25h later -> ${describe(expired)}")
        assertEquals(MatchStatus.VERIFIED, (expired as KnowledgeResult.Found).bundle.artist.status)
    }

    @Test fun currentBehaviour_noneNegativeTtlIsOneDayAndVerifiedIsSevenDays() = runTest {
        assertEquals(24L * 60 * 60 * 1000, KnowledgeRepository.DAY)
    }
}
