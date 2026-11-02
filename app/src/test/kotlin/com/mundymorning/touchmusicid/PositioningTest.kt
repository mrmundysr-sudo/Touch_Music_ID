package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import org.junit.Assert.*
import org.junit.Test

class PositioningTest {
    private val tl = CaptureTimeline(frame0EpochMs = 1_700_000_000_000L, frame0MonoMs = 90_000.0, method = "test")

    private fun raw(offset: Float? = 83_000f, predicted: Float? = 85_000f, skew: Float? = 0f) = RawFix(
        sessionId = 1, title = "T", artist = "A", isrc = null, appleMusicId = null, artworkUrl = null,
        matchOffsetMs = offset, predictedMs = predicted, frequencySkew = skew, sigDurationMs = 9_000, itemCount = 1,
        readMonoMs = 100_000.0, readEpochMs = 1_700_000_010_000L, lastFedEndMono = 99_900.0, lastFedEndStreamMs = 9_900,
    )

    @Test fun chunkTimestampsAreWallClockEpochAndNeverZero() {
        assertEquals(1_700_000_000_000L, tl.epochForStream(0))
        assertEquals(1_700_000_000_250L, tl.epochForStream(250))
        assertTrue(tl.epochForStream(0) > 0)
    }

    @Test fun threeEstimatorsUseTheirDocumentedAssumptions() {
        val e = Positioning.estimate(raw(), tl)
        assertEquals("A is the SDK's own predicted value", 85_000.0, e.a!!, 0.001)
        assertEquals("B = offset + signature + time since last chunk", 83_000.0 + 9_000.0 + 100.0, e.b!!, 0.001)
        assertEquals("C = offset + time since capture start", 83_000.0 + 10_000.0, e.c!!, 0.001)
        assertEquals(e.a, e.pick(Estimator.A)); assertEquals(e.b, e.pick(Estimator.B)); assertEquals(e.c, e.pick(Estimator.C))
    }

    @Test fun predictedValueFromStreamRelativeTimestampsIsRejectedAsImplausible() {
        // With timestamps 0,100,200... the SDK computes matchOffset + (System.currentTimeMillis() - ~0): around 1.8e12 ms.
        val e = Positioning.estimate(raw(predicted = 1.79e12f), tl)
        assertNull("the bug signature must not become a lyric position", e.a)
        assertNotNull("B and C do not depend on the SDK's wall clock", e.b); assertNotNull(e.c)
    }

    @Test fun missingOrInvalidInputsGiveNullNotGarbage() {
        val e = Positioning.estimate(raw(offset = null, predicted = Float.NaN), tl)
        assertNull(e.a); assertNull(e.b); assertNull(e.c)
        assertNull(Positioning.estimate(raw(predicted = -5_000f), tl).a)
        assertEquals("small negatives clamp to the start", 0.0, Positioning.estimate(raw(predicted = -500f), tl).a!!, 0.0)
    }

    @Test fun frequencySkewBecomesAPlaybackRateOnlyWhenMeaningful() {
        assertEquals(1.0, Positioning.rate(null), 0.0)
        assertEquals(1.0, Positioning.rate(0.001f), 0.0)
        assertEquals(1.01, Positioning.rate(0.01f), 1e-6)
        assertEquals(0.98, Positioning.rate(-0.02f), 1e-6)
        assertEquals("absurd skew is ignored", 1.0, Positioning.rate(0.5f), 0.0)
    }

    @Test fun advancingAnEstimateByElapsedTime() {
        assertEquals(87_500.0, Positioning.advance(85_000.0, 100_000.0, 102_500.0), 0.001)
    }
}
