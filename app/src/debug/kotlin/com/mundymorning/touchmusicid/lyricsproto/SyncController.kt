package com.mundymorning.touchmusicid.lyricsproto

import kotlin.math.abs
import kotlin.math.exp

/*
 * Correction policy adapted from LiveLyrics (https://github.com/Nurzhigit-dev/LiveLyrics, MIT): ignore small differences,
 * halve medium ones, and require a second consistent measurement before a large jump (guards against a repeated chorus
 * or a false match). Silence holds the clock where the sound stopped and asks for a fresh fix on resume.
 * Silence is only a HEURISTIC for "paused": quiet passages also look like silence.
 */

enum class SyncState { SEARCHING, FOLLOWING, HOLD, UNRELIABLE }

/** A measurement of the song position at a monotonic instant. */
data class PositionFix(val songMs: Double, val atMono: Double, val rate: Double = 1.0, val tag: String = "")

data class SyncEvent(val atMono: Double, val kind: String, val diffMs: Double, val appliedMs: Double, val note: String = "")

class SyncController(val clock: LyricClock = LyricClock(), private val cfg: Config = Config()) {
    data class Config(
        val ignoreBelowMs: Double = 250.0,
        val halfBelowMs: Double = 2_500.0,
        val confirmToleranceMs: Double = 1_500.0,
        val pendingWindowMs: Double = 30_000.0,
        val staleMs: Double = 150_000.0,
        val maxContradictions: Int = 2,
        val confidenceTauMs: Double = 90_000.0,
    )

    private data class Pending(val diffMs: Double, val atMono: Double)

    var state: SyncState = SyncState.SEARCHING
        private set
    var lastVerifiedMono: Double? = null
        private set
    var agreeCount: Int = 0
        private set
    var contradictions: Int = 0
        private set
    val events = ArrayList<SyncEvent>()

    private var pending: Pending? = null
    private var resumePending = false

    val hasPending: Boolean get() = pending != null

    fun reset() {
        clock.clear()
        state = SyncState.SEARCHING
        lastVerifiedMono = null
        agreeCount = 0
        contradictions = 0
        pending = null
        resumePending = false
    }

    /** The next fix replaces the anchor outright (used by the Re-sync button and after a pause). */
    fun requireFreshFix() { resumePending = true }

    fun onFix(fix: PositionFix): SyncEvent {
        if (!clock.hasAnchor) {
            adopt(fix)
            state = SyncState.FOLLOWING
            agreeCount = 1
            contradictions = 0
            return log(fix.atMono, "INIT", 0.0, 0.0)
        }
        if (state == SyncState.HOLD) return log(fix.atMono, "IGNORED_HOLD", 0.0, 0.0)

        val running = clock.runningRawMs(fix.atMono) ?: fix.songMs
        val diff = fix.songMs - running

        if (resumePending || state == SyncState.UNRELIABLE) {
            val kind = if (state == SyncState.UNRELIABLE) "RECOVER" else "RESUME"
            adopt(fix)
            resumePending = false
            state = SyncState.FOLLOWING
            pending = null
            contradictions = 0
            agreeCount = 1
            return log(fix.atMono, kind, diff, diff)
        }

        val ad = abs(diff)
        return when {
            ad <= cfg.ignoreBelowMs -> {
                agreeCount++
                contradictions = 0
                pending = null
                lastVerifiedMono = fix.atMono
                log(fix.atMono, "AGREE", diff, 0.0)
            }
            ad <= cfg.halfBelowMs -> {
                val shift = diff / 2
                clock.setAnchor(running + shift, fix.atMono, fix.rate)
                contradictions = 0
                pending = null
                agreeCount++
                lastVerifiedMono = fix.atMono
                log(fix.atMono, "HALF", diff, shift)
            }
            else -> large(fix, diff)
        }
    }

    private fun large(fix: PositionFix, diff: Double): SyncEvent {
        var p = pending
        if (p != null && fix.atMono - p.atMono > cfg.pendingWindowMs) p = null
        if (p != null && abs(diff - p.diffMs) <= cfg.confirmToleranceMs) {
            adopt(fix)
            pending = null
            contradictions = 0
            agreeCount = 1
            return log(fix.atMono, "JUMP", diff, diff)
        }
        if (p != null) contradictions++
        pending = Pending(diff, fix.atMono)
        if (contradictions >= cfg.maxContradictions) state = SyncState.UNRELIABLE
        return log(fix.atMono, "PENDING", diff, 0.0, "contradictions=$contradictions")
    }

    private fun adopt(fix: PositionFix) {
        clock.holdRawMs = null
        clock.setAnchor(fix.songMs, fix.atMono, fix.rate)
        lastVerifiedMono = fix.atMono
    }

    /** Sustained quiet began at [sinceMono]. Park the clock there. */
    fun onSilenceStart(sinceMono: Double) {
        if (state != SyncState.FOLLOWING || !clock.hasAnchor) return
        clock.holdRawMs = clock.runningRawMs(sinceMono)
        state = SyncState.HOLD
        log(sinceMono, "HOLD", 0.0, 0.0, "heuristic: sustained quiet")
    }

    /** Sound is back. Continue from the parked position and ask for a fresh measurement. */
    fun onSoundResume(nowMono: Double) {
        if (state != SyncState.HOLD) return
        val held = clock.holdRawMs ?: return
        clock.holdRawMs = null
        clock.setAnchor(held, nowMono, clock.rate)
        resumePending = true
        state = SyncState.FOLLOWING
        log(nowMono, "RESUME_PENDING", 0.0, 0.0, "continuing from ${held.toLong()} ms")
    }

    /** Call regularly. Verification gets stale: with no recent fix the position can no longer be trusted. */
    fun evaluate(nowMono: Double) {
        val v = lastVerifiedMono ?: return
        if (state == SyncState.FOLLOWING && nowMono - v > cfg.staleMs) {
            state = SyncState.UNRELIABLE
            log(nowMono, "STALE", 0.0, 0.0, "no verification for ${((nowMono - v) / 1000).toLong()} s")
        }
    }

    fun confidence(nowMono: Double): Double {
        if (!clock.hasAnchor || state == SyncState.UNRELIABLE || state == SyncState.SEARCHING) return 0.0
        val base = minOf(1.0, 0.5 + 0.15 * agreeCount)
        val age = (nowMono - (lastVerifiedMono ?: nowMono)).coerceAtLeast(0.0)
        var c = base * exp(-age / cfg.confidenceTauMs)
        if (pending != null) c -= 0.25
        if (state == SyncState.HOLD) c = minOf(c, 0.5)
        return c.coerceIn(0.0, 1.0)
    }

    fun nudge(deltaMs: Double) {
        clock.nudgeMs = (clock.nudgeMs + deltaMs).coerceIn(-5_000.0, 5_000.0)
    }

    private fun log(at: Double, kind: String, diff: Double, applied: Double, note: String = ""): SyncEvent {
        val e = SyncEvent(at, kind, diff, applied, note)
        events += e
        if (events.size > 300) events.removeAt(0)
        return e
    }
}
