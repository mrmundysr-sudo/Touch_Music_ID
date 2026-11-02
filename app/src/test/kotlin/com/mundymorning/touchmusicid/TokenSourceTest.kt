package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.net.HttpResponse
import com.mundymorning.touchmusicid.recognition.RemoteTokenSource
import com.mundymorning.touchmusicid.recognition.TokenResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TokenSourceTest {
    private var now = 1_000L
    private fun source(url: String, http: FakeHttp) = RemoteTokenSource(url, http, nowSeconds = { now }, refreshMarginSeconds = 60)

    @Test fun emptyOrNonHttpsUrlIsNotConfiguredAndMakesNoRequest() = runTest {
        val http = FakeHttp { ok("{}") }
        for (u in listOf("", "http://insecure.example/token")) {
            val s = source(u, http)
            assertFalse(s.isConfigured)
            assertEquals(TokenResult.NotConfigured, s.token())
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test fun parsesAndCachesTokenUntilNearExpiry() = runTest {
        val http = FakeHttp { ok("""{"token":"h.p.s1","expiresAt":2000}""") }
        val s = source("https://tokens.example/apple", http)
        assertEquals("h.p.s1", (s.token() as TokenResult.Ok).token.value)
        s.token()
        assertEquals("cached while valid", 1, http.urls.size)
        now = 1_950 // within the 60 s refresh margin
        s.token()
        assertEquals(2, http.urls.size)
    }

    @Test fun rejectsExpiredOrBlankTokens() = runTest {
        assertTrue(source("https://t.example", FakeHttp { ok("""{"token":"x","expiresAt":10}""") }).token() is TokenResult.Failed)
        assertTrue(source("https://t.example", FakeHttp { ok("""{"token":"","expiresAt":9999}""") }).token() is TokenResult.Failed)
    }

    @Test fun failuresAreReportedNotThrown() = runTest {
        assertTrue(source("https://t.example", FakeHttp { HttpResponse(500, "") }).token() is TokenResult.Failed)
        assertTrue(source("https://t.example", FakeHttp { ok("not json") }).token() is TokenResult.Failed)
        assertTrue(source("https://t.example", FakeHttp { null }).token() is TokenResult.Failed)
    }

    @Test fun rejectsNonJwtTokensSuchAsErrorPagesOrRawKeys() = runTest {
        for (bad in listOf("<html>oops</html>", "-----BEGIN PRIVATE KEY-----", "onlyone", "a.b")) {
            val body = org.json.JSONObject().put("token", bad).put("expiresAt", 9999).toString()
            assertTrue(bad, source("https://t.example", FakeHttp { ok(body) }).token() is TokenResult.Failed)
        }
    }

    @Test fun rateLimitedOrUnconfiguredServiceIsAFailureNotAToken() = runTest {
        for (code in listOf(429, 503, 404)) {
            val r = source("https://t.example", FakeHttp { HttpResponse(code, """{"error":"x"}""") }).token()
            assertTrue("HTTP $code", r is TokenResult.Failed)
            assertFalse("a service error is not a network outage", (r as TokenResult.Failed).network)
        }
        assertTrue((source("https://t.example", FakeHttp { null }).token() as TokenResult.Failed).network)
    }

    @Test fun expiredTokenIsRefetchedAndInvalidateForcesRefetch() = runTest {
        var n = 0
        val http = FakeHttp { ok("""{"token":"h.p.s${++n}","expiresAt":${now + 600}}""") }
        val s = source("https://t.example", http)
        assertEquals("h.p.s1", (s.token() as TokenResult.Ok).token.value)
        now += 599 // inside the refresh margin: the old token is treated as expired
        assertEquals("h.p.s2", (s.token() as TokenResult.Ok).token.value)
        s.invalidate() // Apple rejected it
        assertEquals("h.p.s3", (s.token() as TokenResult.Ok).token.value)
    }
}
