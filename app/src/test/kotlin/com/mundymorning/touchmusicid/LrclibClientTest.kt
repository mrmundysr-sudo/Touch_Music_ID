package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LrclibClientTest {
    private val pauses = mutableListOf<Long>()
    private fun client(http: FakeHttp) = LrclibClient(http, pause = { pauses += it })
    private val q = TrackQuery("Bohemian Rhapsody - Remastered 2011", "Queen", "A Night at the Opera", 354.0)

    private fun record(id: Long, title: String = "Bohemian Rhapsody", artist: String = "Queen", dur: Double = 354.0, synced: String? = "[00:01.00]a\n[00:05.00]b", plain: String? = "a\nb") =
        JSONObject().put("id", id).put("trackName", title).put("artistName", artist).put("albumName", "A Night at the Opera").put("duration", dur)
            .put("instrumental", false).put("plainLyrics", plain ?: JSONObject.NULL).put("syncedLyrics", synced ?: JSONObject.NULL)

    private fun arr(vararg r: JSONObject) = JSONArray(r.toList()).toString()

    @Test fun findsTheRecordAndCallsAreMadeOneAtATimeInDocumentedOrder() = runTest {
        val http = FakeHttp { url ->
            when {
                "/get?" in url -> HttpResponse(404, """{"code":404,"name":"TrackNotFound"}""")
                "/search?track_name=" in url -> ok(arr(record(7)))
                else -> ok("[]")
            }
        }
        val res = client(http).lookup(q) as LyricsLookup.Found
        assertEquals(7L, res.best.record.id)
        assertTrue(http.urls[0].contains("/api/get?track_name=Bohemian+Rhapsody&artist_name=Queen&album_name=A+Night+at+the+Opera&duration=354"))
        assertTrue(http.urls[1].contains("/api/search?track_name=Bohemian+Rhapsody&artist_name=Queen"))
        assertTrue(http.urls[2].contains("/api/search?q=Queen+Bohemian+Rhapsody"))
        assertEquals("a good synchronized match needs no second round", 3, http.urls.size)
        assertEquals(3, res.requests)
    }

    @Test fun exactGetIsSkippedWhenAlbumOrDurationIsUnknown() = runTest {
        val http = FakeHttp { ok("[]") }
        client(http).lookup(TrackQuery("Song", "Artist"))
        assertTrue(http.urls.none { "/api/get?" in it })
    }

    @Test fun aSecondRoundRunsOnlyWhenNothingGoodWithSyncedLyricsWasFound() = runTest {
        val http = FakeHttp { url ->
            when {
                "/get?" in url -> HttpResponse(404, "{}")
                url.endsWith("/search?q=Bohemian+Rhapsody") -> ok(arr(record(9)))      // round 2, synced
                "/search?" in url -> ok(arr(record(8, synced = null)))                  // round 1, plain only
                else -> ok("[]")
            }
        }
        val res = client(http).lookup(q) as LyricsLookup.Found
        assertEquals(9L, res.best.record.id)
        assertTrue(http.urls.last().endsWith("/search?q=Bohemian+Rhapsody"))
    }

    @Test fun nothingFoundIsNotFoundOnlyWhenEveryRequestCompleted() = runTest {
        val http = FakeHttp { url -> if ("/get?" in url) HttpResponse(404, "{}") else ok("[]") }
        assertTrue(client(http).lookup(q) is LyricsLookup.NotFound)
    }

    @Test fun aFailedRequestWithoutAMatchIsAFailureNeverNotFound() = runTest {
        val http = FakeHttp { url -> if ("/search?track_name=" in url) HttpResponse(500, "oops") else if ("/get?" in url) HttpResponse(404, "{}") else ok("[]") }
        val res = client(http).lookup(q)
        assertTrue("was $res", res is LyricsLookup.Failed)
        assertEquals("SERVER", (res as LyricsLookup.Failed).kind)
    }

    @Test fun malformedAndNonJsonResponsesAreFailures() = runTest {
        for (body in listOf("<html>blocked</html>", "", "{\"not\":\"an array\"}")) {
            val res = client(FakeHttp { url -> if ("/get?" in url) HttpResponse(404, "{}") else ok(body) }).lookup(q)
            assertTrue("body=$body -> $res", res is LyricsLookup.Failed)
        }
    }

    @Test fun aThrottledRequestIsRetriedOnceAfterTheRequestedPause() = runTest {
        var n = 0
        val http = FakeHttp { url ->
            when {
                "/get?" in url -> HttpResponse(404, "{}")
                "/search?track_name=" in url -> if (n++ == 0) HttpResponse(429, "", 2) else ok(arr(record(5)))
                else -> ok("[]")
            }
        }
        val res = client(http).lookup(q)
        assertTrue(res is LyricsLookup.Found)
        assertEquals(listOf(2000L), pauses)
    }

    @Test fun aRecordOfTheWrongLengthIsRejectedByTheDurationGuard() = runTest {
        val http = FakeHttp { url -> if ("/get?" in url) HttpResponse(404, "{}") else ok(arr(record(1, dur = 120.0))) }
        val res = client(http).lookup(q)
        assertTrue(res is LyricsLookup.NotFound)
        assertTrue((res as LyricsLookup.NotFound).ranked.first().rejectedReason!!.contains("duration"))
    }

    @Test fun aLiveRecordIsNotUsedForAStudioQuery() = runTest {
        val http = FakeHttp { url -> if ("/get?" in url) HttpResponse(404, "{}") else ok(arr(record(1, title = "Bohemian Rhapsody (Live)"))) }
        assertTrue(client(http).lookup(q) is LyricsLookup.NotFound)
    }

    @Test fun parseRecordHandlesJsonNullLyricsAndMissingFields() {
        val r = LrclibClient.parseRecord(JSONObject("""{"id":3,"trackName":"X","artistName":"Y","duration":200.5,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""))!!
        assertNull(r.plain); assertNull(r.synced); assertTrue(r.instrumental); assertEquals(200.5, r.durationSec!!, 0.0)
        assertNull("no id -> not a record", LrclibClient.parseRecord(JSONObject("""{"trackName":"X"}""")))
    }
}
