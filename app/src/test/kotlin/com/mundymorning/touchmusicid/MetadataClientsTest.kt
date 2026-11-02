package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MetadataClientsTest {
    private val itunesJson = """{"resultCount":1,"results":[{"wrapperType":"track","kind":"song","trackId":1440650428,"artistName":"Queen","trackName":"Bohemian Rhapsody","collectionName":"A Night at the Opera","trackTimeMillis":354320,"artworkUrl100":"https://is1-ssl.mzstatic.com/x/100x100bb.jpg"}]}"""

    @Test fun itunesLookupGivesDurationAlbumAndArtwork() = runTest {
        val http = FakeHttp { ok(itunesJson) }
        val r = ITunesClient(http, pause = {}).lookupById("1440650428") as MetaResult.Found
        assertEquals(354_320L, r.meta.durationMs); assertEquals("A Night at the Opera", r.meta.album); assertEquals("1440650428", r.meta.trackId)
        assertTrue(http.urls.single().startsWith("https://itunes.apple.com/lookup?id=1440650428"))
    }

    @Test fun itunesEmptyResultIsNotFoundButBrokenResponsesAreFailures() = runTest {
        assertEquals(MetaResult.NotFound, ITunesClient(FakeHttp { ok("""{"resultCount":0,"results":[]}""") }, pause = {}).lookupById("1"))
        assertEquals(MetaResult.NotFound, ITunesClient(FakeHttp { HttpResponse(404, "") }, pause = {}).lookupById("1"))
        for (body in listOf("<html/>", """{"resultCount":1}""", """{"results":[]}""")) {
            assertTrue(body, ITunesClient(FakeHttp { ok(body) }, pause = {}).lookupById("1") is MetaResult.Failed)
        }
    }

    @Test fun itunesRateLimitingIsAFailureAfterOneRetry() = runTest {
        val pauses = mutableListOf<Long>()
        val http = FakeHttp { HttpResponse(403, "") }
        val r = ITunesClient(http, pause = { pauses += it }).lookupById("1") as MetaResult.Failed
        assertEquals("RATE_LIMITED", r.kind); assertEquals(2, http.urls.size); assertEquals(1, pauses.size)
    }

    @Test fun musicBrainzIsrcLookupUsesTheMedianLengthAndFirstRelease() = runTest {
        val body = """{"isrc":"GBUM71029604","recordings":[
            {"id":"a","title":"Bohemian Rhapsody","length":354000,"releases":[{"title":"A Night at the Opera"}]},
            {"id":"b","title":"Bohemian Rhapsody","length":355000,"releases":[{"title":"Greatest Hits"}]},
            {"id":"c","title":"Bohemian Rhapsody","length":354000},
            {"id":"d","title":"Bohemian Rhapsody","length":null}]}"""
        val http = FakeHttp { ok(body) }
        val r = MusicBrainzClient(http, pause = {}).byIsrc("GBUM71029604") as MetaResult.Found
        assertEquals(354_000L, r.meta.durationMs); assertEquals("A Night at the Opera", r.meta.album)
        assertTrue(r.meta.note.contains("recordings=4"))
        assertTrue(http.urls.single().startsWith("https://musicbrainz.org/ws/2/isrc/GBUM71029604"))
    }

    @Test fun musicBrainzUnknownIsrcIsNotFoundAndGarbageIsAFailure() = runTest {
        assertEquals(MetaResult.NotFound, MusicBrainzClient(FakeHttp { HttpResponse(404, """{"error":"Not Found"}""") }, pause = {}).byIsrc("X"))
        assertEquals(MetaResult.NotFound, MusicBrainzClient(FakeHttp { ok("""{"recordings":[]}""") }, pause = {}).byIsrc("X"))
        assertTrue(MusicBrainzClient(FakeHttp { ok("""{"unexpected":1}""") }, pause = {}).byIsrc("X") is MetaResult.Failed)
        assertTrue(MusicBrainzClient(FakeHttp { HttpResponse(503, "") }, pause = {}).byIsrc("X") is MetaResult.Failed)
    }

    @Test fun everyClientIdentifiesItselfAndSpacesRequests() {
        assertTrue(LabHttp.USER_AGENT.contains("TouchMusicID") && LabHttp.USER_AGENT.contains("github.com"))
    }
}
