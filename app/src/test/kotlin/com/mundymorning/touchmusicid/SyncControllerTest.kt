package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.*
import org.junit.Assert.*
import org.junit.Test

class SyncControllerTest {
    private fun fix(song: Double, at: Double, rate: Double = 1.0) = PositionFix(song, at, rate)
    private fun ctl() = SyncController()
    private fun SyncController.pos(at: Double) = clock.positionMs(at)

    private fun SyncController.exitEarlyPhase() {
        // Perform enough agreements to leave the earlyLockAgrees window
        onFix(fix(10_000.0, 0.0))
        onFix(fix(20_050.0, 10_000.0))
        onFix(fix(30_050.0, 20_000.0))
        onFix(fix(40_050.0, 30_000.0))
    }

    @Test fun identifiedHalfwayThroughStartsAtTheMeasuredPositionNotAtZero() {
        val c = ctl()
        assertEquals("INIT", c.onFix(fix(83_412.0, 1_000.0)).kind)
        assertEquals(SyncState.FOLLOWING, c.state)
        assertEquals(83_412.0, c.pos(1_000.0)!!, 0.001)
        assertEquals(85_412.0, c.pos(3_000.0)!!, 0.001)
        val lines = LrcParser.parse("[01:20.00]a\n[01:24.00]b\n[01:28.00]c").lines
        assertEquals(1, LrcParser.findActiveIndex(lines, c.pos(3_000.0)!!.toLong()))
    }

    @Test fun smallDisagreementsAreIgnored() {
        val c = ctl(); c.exitEarlyPhase()
        val e = c.onFix(fix(50_100.0, 40_000.0)) // running says 50_000, diff +100
        assertEquals("AGREE", e.kind)
        assertEquals(50_000.0, c.pos(40_000.0)!!, 0.001)
    }

    @Test fun mediumDisagreementsAreCorrectedByHalf() {
        val c = ctl(); c.exitEarlyPhase()
        val e = c.onFix(fix(51_000.0, 40_000.0)) // diff +1000
        assertEquals("HALF", e.kind); assertEquals(500.0, e.appliedMs, 0.001)
        assertEquals(50_500.0, c.pos(40_000.0)!!, 0.001)
    }

    @Test fun aLargeJumpNeedsASecondConsistentMeasurement() {
        val c = ctl(); c.exitEarlyPhase()
        val first = c.onFix(fix(70_000.0, 40_000.0)) // diff +20_000
        assertEquals("PENDING", first.kind)
        assertEquals("position must not move on a single large outlier", 50_000.0, c.pos(40_000.0)!!, 0.001)
        val second = c.onFix(fix(70_000.0 + 8_300.0, 48_000.0))
        assertEquals("JUMP", second.kind)
        assertEquals(78_300.0, c.pos(48_000.0)!!, 0.001)
        assertEquals(SyncState.FOLLOWING, c.state)
    }

    @Test fun aSingleFalseMatchFromARepeatedChorusIsDiscardedOnceTheNextFixAgrees() {
        val c = ctl(); c.exitEarlyPhase()
        assertEquals("PENDING", c.onFix(fix(90_000.0, 40_000.0)).kind)
        assertEquals("AGREE", c.onFix(fix(60_050.0, 50_000.0)).kind)
        assertFalse(c.hasPending)
        assertEquals(60_000.0, c.pos(50_000.0)!!, 0.001)
    }

    @Test fun repeatedContradictionsEndInUnreliableAndALaterGoodFixRecovers() {
        val c = ctl(); c.exitEarlyPhase()
        c.onFix(fix(90_000.0, 40_000.0))
        c.onFix(fix(130_000.0, 50_000.0))
        c.onFix(fix(60_000.0, 60_000.0))
        assertEquals(SyncState.UNRELIABLE, c.state)
        assertEquals(0.0, c.confidence(60_000.0), 0.0)
        val e = c.onFix(fix(71_000.0, 70_000.0))
        assertEquals("RECOVER", e.kind); assertEquals(SyncState.FOLLOWING, c.state)
        assertEquals(71_000.0, c.pos(70_000.0)!!, 0.001)
    }

    @Test fun sustainedSilenceHoldsThePositionAndSoundResumesFromThere() {
        val c = ctl(); c.onFix(fix(10_000.0, 0.0))
        c.onSilenceStart(5_000.0)
        assertEquals(SyncState.HOLD, c.state)
        assertEquals(15_000.0, c.pos(9_000.0)!!, 0.001)
        assertEquals(15_000.0, c.pos(60_000.0)!!, 0.001)
        assertEquals("a fix during a hold is ignored", "IGNORED_HOLD", c.onFix(fix(99_000.0, 9_500.0)).kind)
        c.onSoundResume(60_000.0)
        assertEquals(SyncState.FOLLOWING, c.state)
        assertEquals("resumes from the parked position", 15_500.0, c.pos(60_500.0)!!, 0.001)
        val e = c.onFix(fix(120_000.0, 62_000.0))
        assertEquals("RESUME", e.kind); assertEquals(120_000.0, c.pos(62_000.0)!!, 0.001)
    }

    @Test fun silenceIsIgnoredBeforeAnythingIsTracked() {
        val c = ctl(); c.onSilenceStart(1_000.0)
        assertEquals(SyncState.SEARCHING, c.state)
    }

    @Test fun verificationGoesStaleAndTheLabelDropsToUnreliable() {
        val c = ctl(); c.onFix(fix(10_000.0, 0.0))
        c.evaluate(100_000.0); assertEquals(SyncState.FOLLOWING, c.state)
        c.evaluate(151_000.0); assertEquals(SyncState.UNRELIABLE, c.state)
        assertEquals(0.0, c.confidence(151_000.0), 0.0)
    }

    @Test fun confidenceGrowsWithAgreementDecaysWithAgeAndDropsWithADisputedFix() {
        val c = ctl(); c.onFix(fix(0.0, 0.0))
        val c1 = c.confidence(0.0)
        c.onFix(fix(10_000.0, 10_000.0)); c.onFix(fix(20_000.0, 20_000.0))
        val c3 = c.confidence(20_000.0)
        assertTrue("$c1 < $c3", c1 < c3)
        assertTrue("decays with age", c.confidence(100_000.0) < c3)
        c.onFix(fix(80_000.0, 21_000.0))
        assertTrue("a pending dispute lowers confidence", c.confidence(21_000.0) < c3)
    }

    @Test fun playbackSpeedFromFrequencySkewAdvancesTheClockFasterOrSlower() {
        val c = ctl(); c.onFix(fix(0.0, 0.0, rate = 1.05))
        assertEquals(10_500.0, c.pos(10_000.0)!!, 0.001)
    }

    @Test fun manualNudgeShiftsThePositionAndIsClamped() {
        val c = ctl(); c.onFix(fix(10_000.0, 0.0))
        c.nudge(250.0); assertEquals(10_250.0, c.pos(0.0)!!, 0.001)
        c.nudge(-100_000.0); assertEquals(-5_000.0, c.clock.nudgeMs, 0.001)
        assertEquals("never negative", 5_000.0, c.pos(0.0)!!, 0.001)
    }

    @Test fun requireFreshFixReplacesTheAnchorWithoutDamping() {
        val c = ctl(); c.onFix(fix(10_000.0, 0.0))
        c.requireFreshFix()
        assertEquals("RESUME", c.onFix(fix(31_000.0, 20_000.0)).kind)
        assertEquals(31_000.0, c.pos(20_000.0)!!, 0.001)
    }

    @Test fun resetReturnsToSearching() {
        val c = ctl(); c.onFix(fix(10_000.0, 0.0)); c.reset()
        assertEquals(SyncState.SEARCHING, c.state); assertNull(c.pos(1.0))
    }

    @Test fun clockPositionIsDerivedNotAccumulated() {
        val k = LyricClock(); k.setAnchor(1_000.0, 500.0, 1.0)
        assertEquals(k.positionMs(10_500.0), k.positionMs(10_500.0))
        assertEquals(11_000.0, k.positionMs(10_500.0)!!, 0.001)
    }
}
