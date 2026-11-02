package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import com.mundymorning.touchmusicid.net.HttpResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The whole Lab pipeline on virtual time with a scripted probe: no phone, no network, no ShazamKit. */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsLabControllerTest {
    // L0 at 0 s, L1 at 10 s ... L10 at 100 s; recording is 110 s long.
    private val synced = (0..10).joinToString("\n") { "[%02d:%02d.00]L%d".format(it * 10 / 60, it * 10 % 60, it) }
    private val tl = CaptureTimeline(1_700_000_000_000L, 0.0, "test")

    private fun record(id: Long = 1, syncedLyrics: String? = synced, plain: String? = "P0\nP1", dur: Double = 110.0, title: String = "Song") =
        JSONObject().put("id", id).put("trackName", title).put("artistName", "Artist").put("albumName", "Album").put("duration", dur)
            .put("instrumental", false).put("plainLyrics", plain ?: JSONObject.NULL).put("syncedLyrics", syncedLyrics ?: JSONObject.NULL)

    private class Net(var lrclibBody: () -> HttpResponse?) {
        val lrclibUrls = ArrayList<String>()
        val lrclib = FakeHttp { url -> lrclibUrls += url; if ("/get?" in url) HttpResponse(404, "{}") else lrclibBody() }
        val itunes = FakeHttp { ok("""{"resultCount":1,"results":[{"trackId":123,"trackName":"Song","artistName":"Artist","collectionName":"Album","trackTimeMillis":110000}]}""") }
        val mb = FakeHttp { HttpResponse(404, "{}") }
    }

    private fun raw(sid: Int, predicted: Float?, readMono: Double, title: String = "Song", offset: Float? = 40_000f) = RawFix(
        sessionId = sid, title = title, artist = "Artist", isrc = "ISRC1", appleMusicId = "123", artworkUrl = null,
        matchOffsetMs = offset, predictedMs = predicted, frequencySkew = 0f, sigDurationMs = 5_000, itemCount = 1,
        readMonoMs = readMono, readEpochMs = 0, lastFedEndMono = readMono - 100, lastFedEndStreamMs = 0,
    )

    private class ScriptProbe(val script: suspend (Int, (ProbeEvent) -> Unit) -> Unit) : MatchProbe {
        override suspend fun run(sessionId: Int, emit: (ProbeEvent) -> Unit) = script(sessionId, emit)
    }

    private fun TestScope.controller(net: Net, script: suspend (Int, (ProbeEvent) -> Unit) -> Unit) = LyricsLabController(
        scope = backgroundScope, probe = ScriptProbe(script), tokensConfigured = true,
        lrclib = LrclibClient(net.lrclib, pause = {}), itunes = ITunesClient(net.itunes, pause = {}), musicBrainz = MusicBrainzClient(net.mb, pause = {}),
        clock = { testScheduler.currentTime.toDouble() }, epochClock = { 1_700_000_000_000L + testScheduler.currentTime },
    )

    private fun TestScope.now() = testScheduler.currentTime.toDouble()

    @Test fun identifiedHalfwayThroughJumpsToTheRightLineThenFollows() = runTest {
        val net = Net { ok(JSONArray(listOf(record())).toString()) }
        val c = controller(net) { sid, emit ->
            emit(ProbeEvent.Started(sid, tl))
            emit(ProbeEvent.Fix(raw(sid, predicted = 45_000f, readMono = now())))   // the song is 45 s in
            awaitCancellation()
        }
        c.start(); runCurrent(); advanceTimeBy(100)
        assertEquals("song identified from the fix", "Song", c.state.value.song!!.title)
        val ready = c.state.value.lyrics as LyricsState.Ready
        assertTrue("synchronized lyrics trusted", ready.synced)
        assertEquals(110_000L, c.state.value.song!!.durationMs)
        assertEquals("not the start of the song", 4, c.tick.value.activeIdx)          // 45.1 s -> L4 (40 s)
        advanceTimeBy(5_000)
        assertEquals(5, c.tick.value.activeIdx)                                        // 50.1 s -> L5
        assertEquals(SyncState.FOLLOWING, c.tick.value.syncState)
        val p = c.tick.value.positionMs
        assertTrue("position ~50.1 s but was $p", p in 50_000..50_300)
    }

    @Test fun lyricsWithoutTimestampsBecomeAPlainSheetThatIsNeverHighlighted() = runTest {
        val net = Net { ok(JSONArray(listOf(record(syncedLyrics = null, plain = "Line one\nLine two\nLine three"))).toString()) }
        val c = controller(net) { sid, emit -> emit(ProbeEvent.Started(sid, tl)); emit(ProbeEvent.Fix(raw(sid, 45_000f, now()))); awaitCancellation() }
        c.start(); runCurrent(); advanceTimeBy(200)
        val ready = c.state.value.lyrics as LyricsState.Ready
        assertFalse(ready.synced); assertEquals(listOf("Line one", "Line two", "Line three"), ready.lines)
        assertEquals(-1, c.tick.value.activeIdx)
    }

    @Test fun timestampsThatRunPastTheTrackEndFallBackToPlain() = runTest {
        val tooLong = "[00:05.00]a\n[05:00.00]b" // recording is 110 s
        val net = Net { ok(JSONArray(listOf(record(syncedLyrics = tooLong, plain = "a\nb"))).toString()) }
        val c = controller(net) { sid, emit -> emit(ProbeEvent.Started(sid, tl)); emit(ProbeEvent.Fix(raw(sid, 45_000f, now()))); awaitCancellation() }
        c.start(); runCurrent(); advanceTimeBy(200)
        val ready = c.state.value.lyrics as LyricsState.Ready
        assertFalse("unreliable synchronization must not be shown", ready.synced)
        assertTrue(ready.info.contains("rejected"))
    }

    @Test fun noLrclibMatchIsNotFoundAndAFailureIsRetryable() = runTest {
        var healthy = false
        val net = Net { if (healthy) ok(JSONArray(listOf(record())).toString()) else HttpResponse(500, "oops") }
        val c = controller(net) { sid, emit -> emit(ProbeEvent.Started(sid, tl)); emit(ProbeEvent.Fix(raw(sid, 45_000f, now()))); awaitCancellation() }
        c.start(); runCurrent(); advanceTimeBy(200)
        assertTrue("a failed lookup is not 'no lyrics': ${c.state.value.lyrics}", c.state.value.lyrics is LyricsState.Failed)
        assertEquals("position tracking is independent of lyrics", SyncState.FOLLOWING, c.tick.value.syncState)
        healthy = true
        c.retryLyrics(); runCurrent(); advanceTimeBy(200)
        assertTrue(c.state.value.lyrics is LyricsState.Ready)
    }

    @Test fun sustainedQuietHoldsTheLyricsAndSoundResumesThenAFreshFixIsAdopted() = runTest {
        val net = Net { ok(JSONArray(listOf(record())).toString()) }
        val c = controller(net) { sid, emit ->
            emit(ProbeEvent.Started(sid, tl))
            emit(ProbeEvent.Fix(raw(sid, 45_000f, now())))
            repeat(20) { delay(100); emit(ProbeEvent.Audio(sid, now(), 0.0, 0, 0)) }          // 2 s of silence
            repeat(5) { delay(100); emit(ProbeEvent.Audio(sid, now(), 0.05, 9_000, 0)) }       // sound is back
            emit(ProbeEvent.Fix(raw(sid, predicted = 100_000f, readMono = now())))              // the user had sought ahead
            awaitCancellation()
        }
        c.start(); runCurrent()
        advanceTimeBy(2_050); runCurrent()
        assertEquals("quiet is a heuristic, but it parks the clock", SyncState.HOLD, c.tick.value.syncState)
        val frozen = c.tick.value.positionMs
        advanceTimeBy(100)
        assertEquals("position does not advance while held", frozen, c.tick.value.positionMs)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(SyncState.FOLLOWING, c.tick.value.syncState)
        val kinds = c.sync.events.map { it.kind }
        assertTrue(kinds.toString(), kinds.containsAll(listOf("INIT", "HOLD", "RESUME_PENDING", "RESUME")))
        val p = c.tick.value.positionMs
        assertTrue("adopted the fresh fix (~100 s) but was $p", p in 100_000..101_500)
        assertEquals(10, c.tick.value.activeIdx)
    }

    @Test fun anImplausibleSdkPositionIsNotUsedUntilAnotherEstimatorIsChosen() = runTest {
        val net = Net { ok(JSONArray(listOf(record())).toString()) }
        val c = controller(net) { sid, emit ->
            emit(ProbeEvent.Started(sid, tl))
            emit(ProbeEvent.Fix(raw(sid, predicted = 1.79e12f, readMono = now(), offset = 40_000f))) // stream-relative timestamps: garbage
            awaitCancellation()
        }
        c.start(); runCurrent(); advanceTimeBy(500)
        assertEquals("estimator A is rejected, so there is no position", -1L, c.tick.value.positionMs)
        c.setEstimator(Estimator.C); runCurrent(); advanceTimeBy(100)
        assertTrue("C = offset + time since capture start (~40 s)", c.tick.value.positionMs in 40_000..41_000)
    }

    @Test fun aDifferentSongNeedsTwoConsecutiveIdentificationsBeforeSwitching() = runTest {
        val net = Net { ok(JSONArray(listOf(record())).toString()) }
        val c = controller(net) { sid, emit ->
            emit(ProbeEvent.Started(sid, tl))
            emit(ProbeEvent.Fix(raw(sid, 45_000f, now())))
            delay(1_000); emit(ProbeEvent.Fix(raw(sid, 10_000f, now(), title = "Other Song")))
            delay(1_000); emit(ProbeEvent.Fix(raw(sid, 46_000f, now())))                              // back to the first: one-off dismissed
            delay(1_000); emit(ProbeEvent.Fix(raw(sid, 12_000f, now(), title = "Other Song")))
            delay(1_000); emit(ProbeEvent.Fix(raw(sid, 13_000f, now(), title = "Other Song")))
            awaitCancellation()
        }
        c.start(); runCurrent(); advanceTimeBy(2_500); runCurrent()
        assertEquals("one contradicting fix must not switch songs", "Song", c.state.value.song!!.title)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("two in a row switch", "Other Song", c.state.value.song!!.title)
    }

    @Test fun manualNudgeMovesTheHighlightAndIsRemembered() = runTest {
        val net = Net { ok(JSONArray(listOf(record())).toString()) }
        val c = controller(net) { sid, emit -> emit(ProbeEvent.Started(sid, tl)); emit(ProbeEvent.Fix(raw(sid, 49_900f, now()))); awaitCancellation() }
        c.start(); runCurrent(); advanceTimeBy(100)
        assertEquals(4, c.tick.value.activeIdx)          // 49.9 s still in L4
        c.nudge(250); advanceTimeBy(100)
        assertEquals("a +250 ms nudge pushes it into L5", 5, c.tick.value.activeIdx)
        assertEquals(250, c.state.value.nudgeMs)
        c.resetNudge(); assertEquals(0, c.state.value.nudgeMs)
    }

    @Test fun startingWithoutTheSdkOrTokenServiceSaysSoInsteadOfPretending() = runTest {
        val net = Net { ok("[]") }
        val noSdk = LyricsLabController(backgroundScope, null, true, LrclibClient(net.lrclib), ITunesClient(net.itunes), MusicBrainzClient(net.mb))
        noSdk.start(); assertFalse(noSdk.state.value.running); assertTrue(noSdk.state.value.status.contains("SDK"))
        val noToken = LyricsLabController(backgroundScope, ScriptProbe { _, _ -> }, false, LrclibClient(net.lrclib), ITunesClient(net.itunes), MusicBrainzClient(net.mb))
        noToken.start(); assertFalse(noToken.state.value.running); assertTrue(noToken.state.value.status.contains("Token"))
    }
}
