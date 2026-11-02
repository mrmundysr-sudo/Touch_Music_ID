package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.Song
import com.mundymorning.touchmusicid.net.ArtworkUrl
import com.mundymorning.touchmusicid.recognition.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShazamKitEngineTest {
    private val song = Song("Real Song", "Real Artist", artworkUrl = "https://is1-ssl.mzstatic.com/image/{w}x{h}bb.jpg")

    private class Tokens(val result: TokenResult) : TokenSource {
        override val isConfigured = true
        override suspend fun token() = result
    }
    private val okToken = Tokens(TokenResult.Ok(DeveloperToken("jwt", Long.MAX_VALUE)))

    /** Emits 250 ms chunks forever (like the mic) until the collector is cancelled; tracks mic open/closed. */
    private class FakeCapture(val amplitude: Short = 4000, val failWith: Throwable? = null) : AudioCapture {
        override val sampleRateHz = 48_000
        override val readBufferSize = 24_000
        var open = false
        var opened = 0
        override fun chunks(): Flow<AudioChunk> = flow {
            failWith?.let { throw it }
            open = true; opened++
            try {
                var t = 0L
                while (true) {
                    delay(250)
                    val b = ByteArray(24_000)
                    for (i in b.indices step 2) { b[i] = (amplitude.toInt() and 0xFF).toByte(); b[i + 1] = (amplitude.toInt() shr 8).toByte() }
                    emit(AudioChunk(b, b.size, t)); t += 250
                }
            } finally { open = false }
        }
    }

    private class FakeMatcher(val flow: MutableSharedFlow<BackendResult> = MutableSharedFlow(extraBufferCapacity = 16)) : StreamMatcher {
        override val minSignatureMs = 3_000L
        override val maxSignatureMs = 12_000L
        override val results: Flow<BackendResult> = flow
        var fed = 0
        var onFeed: (Int) -> Unit = {}
        override fun feed(chunk: AudioChunk) { fed++; onFeed(fed) }
    }

    private class FakeBackend(val matcher: FakeMatcher? = null, val error: BackendException? = null) : MatchBackend {
        var tokenSeen: String? = null
        override suspend fun open(token: String, sampleRateHz: Int, readBufferSize: Int): StreamMatcher {
            tokenSeen = token; error?.let { throw it }; return matcher!!
        }
    }

    private fun engine(b: MatchBackend, c: AudioCapture, t: TokenSource = okToken) = ShazamKitEngine(t, b, c)

    @Test fun matchEndsListeningAndReleasesMic() = runTest {
        val m = FakeMatcher(); val cap = FakeCapture()
        m.onFeed = { if (it == 8) m.flow.tryEmit(BackendResult.Match(song)) }
        val phases = mutableListOf<EnginePhase>()
        val r = engine(FakeBackend(m), cap).recognize { phases += it }
        assertEquals(EngineResult.Match(song), r)
        assertFalse("mic must be released after a match", cap.open)
        assertEquals(EnginePhase.LISTENING, phases.first())
        assertTrue(EnginePhase.PROCESSING in phases)
        assertTrue("stops capturing soon after the match", m.fed < 12)
    }

    @Test fun tokenIsPassedToBackendAndNeverFakeMatch() = runTest {
        val b = FakeBackend(FakeMatcher())
        engine(b, FakeCapture()).recognize { }
        assertEquals("jwt", b.tokenSeen)
    }

    @Test fun noMatchAfterBudgetWhenAudioHadSound() = runTest {
        val m = FakeMatcher(); val cap = FakeCapture()
        m.onFeed = { if (it == 6) m.flow.tryEmit(BackendResult.NoMatch) }
        val r = engine(FakeBackend(m), cap).recognize { }
        assertEquals(EngineResult.NoMatch, r)
        assertFalse(cap.open)
        assertTrue("listens for the whole budget before giving up", m.fed >= 40)
    }

    @Test fun silenceIsReportedDistinctly() = runTest {
        val r = engine(FakeBackend(FakeMatcher()), FakeCapture(amplitude = 3)).recognize { }
        assertEquals(FailureKind.SILENCE, (r as EngineResult.Failure).kind)
    }

    @Test fun networkErrorFromServiceIsReported() = runTest {
        val m = FakeMatcher(); val cap = FakeCapture()
        m.onFeed = { if (it == 2) m.flow.tryEmit(BackendResult.Error(FailureKind.NETWORK, "offline")) }
        val r = engine(FakeBackend(m), cap).recognize { }
        assertEquals(EngineResult.Failure(FailureKind.NETWORK, "offline"), r)
        assertFalse(cap.open)
    }

    @Test fun unauthorizedMapsToTokenFailure() = runTest {
        val m = FakeMatcher(); m.onFeed = { m.flow.tryEmit(BackendResult.Error(FailureKind.TOKEN, "rejected")) }
        assertEquals(FailureKind.TOKEN, (engine(FakeBackend(m), FakeCapture()).recognize { } as EngineResult.Failure).kind)
    }

    @Test fun tokenServiceFailureNeverOpensMic() = runTest {
        val cap = FakeCapture()
        val r = engine(FakeBackend(FakeMatcher()), cap, Tokens(TokenResult.Failed("down", network = true))).recognize { }
        assertEquals(FailureKind.NETWORK, (r as EngineResult.Failure).kind)
        assertEquals(0, cap.opened)
        val r2 = engine(FakeBackend(FakeMatcher()), cap, Tokens(TokenResult.Failed("bad"))).recognize { }
        assertEquals(FailureKind.TOKEN, (r2 as EngineResult.Failure).kind)
        assertEquals(0, cap.opened)
    }

    @Test fun sessionCreationFailureNeverOpensMic() = runTest {
        val cap = FakeCapture()
        val r = engine(FakeBackend(error = BackendException(FailureKind.UNKNOWN, "no")), cap).recognize { }
        assertTrue(r is EngineResult.Failure); assertEquals(0, cap.opened)
    }

    @Test fun micFailureIsAnAudioError() = runTest {
        val r = engine(FakeBackend(FakeMatcher()), FakeCapture(failWith = AudioUnavailable("busy"))).recognize { }
        assertEquals(EngineResult.Failure(FailureKind.AUDIO, "busy"), r)
        val r2 = engine(FakeBackend(FakeMatcher()), FakeCapture(failWith = SecurityException())).recognize { }
        assertEquals(FailureKind.AUDIO, (r2 as EngineResult.Failure).kind)
    }

    @Test fun cancellingReleasesMic() = runTest {
        val cap = FakeCapture(); val m = FakeMatcher()
        val job = launch { engine(FakeBackend(m), cap).recognize { } }
        advanceTimeBy(2_000)
        assertTrue(cap.open)
        job.cancel(); advanceUntilIdle()
        assertFalse("cancel must release the microphone", cap.open)
    }

    @Test fun peakAmplitudeReadsPcm16() {
        val b = byteArrayOf(0, 0, 0x10, 0x00, 0x00, 0x80.toByte()) // 0, 16, -32768
        assertEquals(32768, peakAmplitude(b, 6))
        assertEquals(16, peakAmplitude(b, 4))
        assertEquals(0, peakAmplitude(ByteArray(0), 0))
    }

    @Test fun artworkUrlOnlyTrustedHttps() {
        assertEquals("https://is1-ssl.mzstatic.com/image/600x600bb.jpg", ArtworkUrl.normalize(song.artworkUrl))
        assertNull(ArtworkUrl.normalize("http://is1-ssl.mzstatic.com/a.jpg"))
        assertNull(ArtworkUrl.normalize("https://evil.example.com/a.jpg"))
        assertNull(ArtworkUrl.normalize("https://mzstatic.com.evil.com/a.jpg"))
        assertNull(ArtworkUrl.normalize(null)); assertNull(ArtworkUrl.normalize(" "))
    }
}
