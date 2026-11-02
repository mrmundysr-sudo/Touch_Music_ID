package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.knowledge.*
import com.mundymorning.touchmusicid.net.HttpFetcher
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** How WikiApi tells a FAILURE (retry, then LOOKUP_FAILED) apart from a genuine empty answer (NOT_FOUND). */
class WikiApiResilienceTest {
    private val pauses = mutableListOf<Long>()
    private fun api(http: HttpFetcher) = WikiApi(http, pause = { pauses += it })
    private val busy get() = ok("""{"error":{"code":"cirrussearch-too-busy-error","info":"Search is currently too busy. Please try again later."},"servedby":"mw-api-ext"}""")
    private val good get() = ok(searchJson(Triple("Q1", "Morgan Wallen", "American singer")))
    private fun sequence(vararg responses: HttpResponse?): FakeHttp { var n = 0; return FakeHttp { responses[minOf(n++, responses.size - 1)] } }

    // ---- recovery ----
    @Test fun transientBusyErrorIsRetriedAndRecovers() = runTest {
        val http = sequence(busy, good)
        assertEquals(1, api(http).searchEntities("Morgan Wallen").size)
        assertEquals(2, http.urls.size); assertEquals(listOf(400L), pauses)
    }

    @Test fun persistentErrorStopsAfterTwoRetriesWithBoundedBackoffAndIsAFailureNotAnEmptyResult() = runTest {
        val http = sequence(busy)
        val e = runCatching { api(http).searchEntities("Morgan Wallen") }.exceptionOrNull()
        assertTrue("was $e", e is ApiErrorException)
        assertEquals("cirrussearch-too-busy-error", (e as ApiErrorException).code)
        assertEquals(3, http.urls.size)                // 1 try + 2 retries, never more
        assertEquals(listOf(400L, 1000L), pauses)      // short, bounded
        assertEquals(KnowledgeResult.Kind.SERVER, e.lookupFailureKind())
    }

    // ---- genuine empty vs malformed ----
    @Test fun wellFormedEmptyResultIsTheOnlyThingReportedAsNothingFound() = runTest {
        val http = sequence(ok("""{"searchinfo":{"search":"zzz"},"search":[],"success":1}"""))
        assertTrue(api(http).searchEntities("zzz").isEmpty())
        assertEquals(1, http.urls.size); assertTrue(pauses.isEmpty())
        assertTrue(api(sequence(ok("""{"batchcomplete":true,"query":{"search":[]}}"""))).searchArticles("zzz").isEmpty())
        assertTrue(api(sequence(ok("""{"entities":{}}"""))).entities(listOf("Q1")).isEmpty())
    }

    private suspend fun assertMalformed(label: String, body: String, call: suspend (WikiApi) -> Any?) {
        val e = runCatching { call(api(sequence(ok(body)))) }.exceptionOrNull()
        assertTrue("$label: $e", e is MalformedResponseException)
    }

    @Test fun missingRequiredFieldIsMalformedNotEmpty() = runTest {
        assertMalformed("search", """{"success":1}""") { it.searchEntities("x") }
        assertMalformed("entities", """{"success":1}""") { it.entities(listOf("Q1")) }
        assertMalformed("query", """{"batchcomplete":""}""") { it.searchArticles("x") }
        assertMalformed("summary", """{"type":"standard"}""") { it.summary("Queen") }
    }

    @Test fun nonJsonAndTruncatedBodiesAreMalformed() = runTest {
        for (body in listOf("<html><body>Sign in to Wi-Fi</body></html>", "", """{"search":[{"id":"Q1","lab""", "null", "[]")) {
            val e = runCatching { api(sequence(ok(body))).searchEntities("x") }.exceptionOrNull()
            assertTrue("body=$body -> $e", e is MalformedResponseException)
        }
    }

    @Test fun errorObjectThatIsNotTransientFailsImmediately() = runTest {
        val http = sequence(ok("""{"error":{"code":"badvalue","info":"Unrecognized value"}}"""))
        assertTrue(runCatching { api(http).searchEntities("x") }.exceptionOrNull() is ApiErrorException)
        assertEquals(1, http.urls.size); assertTrue(pauses.isEmpty())
    }

    // ---- HTTP level ----
    @Test fun retryAfterIsRespectedWhenShort() = runTest {
        val http = sequence(HttpResponse(429, "", 2), good)
        assertEquals(1, api(http).searchEntities("x").size)
        assertEquals(listOf(2000L), pauses)
    }

    @Test fun retryAfterBeyondTheCapIsNotWaitedOut() = runTest {
        val http = sequence(HttpResponse(429, "", 120))
        val e = runCatching { api(http).searchEntities("x") }.exceptionOrNull()
        assertTrue(e is RateLimitedException); assertEquals(1, http.urls.size); assertTrue(pauses.isEmpty())
    }

    @Test fun retryAfterOnAnApiErrorBodyIsHonouredToo() = runTest {
        val http = sequence(HttpResponse(200, """{"error":{"code":"maxlag","info":"lagged"}}""", 3), good)
        assertEquals(1, api(http).searchEntities("x").size); assertEquals(listOf(3000L), pauses)
    }

    @Test fun serverErrorsAreRetriedButClientErrorsAreNot() = runTest {
        assertEquals(1, api(sequence(HttpResponse(503, ""), good)).searchEntities("x").size)
        val http = sequence(HttpResponse(400, "bad request"))
        assertTrue(runCatching { api(http).searchEntities("x") }.exceptionOrNull() is ServerException)
        assertEquals(1, http.urls.size)
    }

    @Test fun connectionFailuresAreRetriedButTimeoutsAndOfflineAreNot() = runTest {
        val flaky = sequence(null, good) // generic IOException, then success
        assertEquals(1, api(flaky).searchEntities("x").size)
        for (ex in listOf(SocketTimeoutException("slow"), UnknownHostException("offline"))) {
            var calls = 0
            val http = object : HttpFetcher { override suspend fun get(url: String, headers: Map<String, String>): HttpResponse { calls++; throw ex } }
            assertTrue(runCatching { api(http).searchEntities("x") }.exceptionOrNull() is IOException)
            assertEquals("no retry for ${ex.javaClass.simpleName}", 1, calls)
        }
    }

    @Test fun summaryNotFoundIsNullButAnErrorBodyIsAFailure() = runTest {
        assertNull(api(sequence(HttpResponse(404, "{}"))).summary("No Such Page"))
        assertTrue(runCatching { api(sequence(busy)).summary("Queen") }.exceptionOrNull() is ApiErrorException)
    }

    // ---- language labels ----
    @Test fun entityRequestsAskForEnglishAndLanguageNeutralLabels() = runTest {
        val http = sequence(ok("""{"entities":{}}"""))
        api(http).entities(listOf("Q1", "Q2"))
        assertTrue(http.urls.single().contains("languages=en%7Cmul"))
    }
}
