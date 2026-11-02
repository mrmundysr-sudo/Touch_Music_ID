package com.mundymorning.touchmusicid.lyricsproto

import kotlin.math.abs

/**
 * When the first audio frame of a capture session was recorded. Everything the SDK receives is stamped from this:
 * ShazamKit takes WALL-CLOCK epoch milliseconds as the timestamp of each chunk (its predictedCurrentMatchOffset is
 * `matchOffset + (System.currentTimeMillis() - firstChunkTimestamp)`), so stream-relative timestamps (0, 100, 200...) make that
 * value meaningless. frame0EpochMs is therefore the epoch time of sample 0, derived from the monotonic capture clock.
 */
data class CaptureTimeline(val frame0EpochMs: Long, val frame0MonoMs: Double, val method: String) {
    /** Epoch timestamp for a chunk starting [streamMs] into the capture (never 0, which the SDK treats as "unset"). */
    fun epochForStream(streamMs: Long): Long = frame0EpochMs + streamMs
}

/** Everything ShazamKit reported for one match, plus our own clocks at the moment it arrived. */
data class RawFix(
    val sessionId: Int,
    val title: String,
    val artist: String,
    val isrc: String?,
    val appleMusicId: String?,
    val artworkUrl: String?,
    val matchOffsetMs: Float?,
    val predictedMs: Float?,
    val frequencySkew: Float?,
    val sigDurationMs: Long,
    val itemCount: Int,
    val readMonoMs: Double,
    val readEpochMs: Long,
    val lastFedEndMono: Double,
    val lastFedEndStreamMs: Long,
)

enum class Estimator { A, B, C }

/** Song position AT THE INSTANT THE MATCH WAS READ, by three independent assumptions. Null when not computable or implausible. */
data class Estimates(val a: Double?, val b: Double?, val c: Double?) {
    fun pick(e: Estimator): Double? = when (e) { Estimator.A -> a; Estimator.B -> b; Estimator.C -> c }
}

object Positioning {
    private const val MAX_PLAUSIBLE_MS = 30.0 * 60 * 1000

    /**
     * A  = the SDK's own predictedCurrentMatchOffset (needs correct epoch timestamps).
     * B  = matchOffset + signatureDuration + (time since the last chunk we fed): assumes the signature ended at the newest chunk,
     *      which overestimates by the network round trip.
     * C  = matchOffset + (read time - capture start): assumes the signature began at the first chunk we ever fed.
     * Comparing them on a real phone shows which assumption holds; B and C ignore frequencySkew by design.
     */
    fun estimate(raw: RawFix, tl: CaptureTimeline): Estimates {
        val offset = raw.matchOffsetMs?.toDouble()
        val a = raw.predictedMs?.toDouble()?.let { plausible(it) }
        val b = offset?.let { plausible(it + raw.sigDurationMs + (raw.readMonoMs - raw.lastFedEndMono)) }
        val c = offset?.let { plausible(it + (raw.readMonoMs - tl.frame0MonoMs)) }
        return Estimates(a, b, c)
    }

    private fun plausible(v: Double): Double? = if (v.isNaN() || v.isInfinite() || v < -2_000 || v > MAX_PLAUSIBLE_MS) null else maxOf(0.0, v)

    /** Playback-speed factor from ShazamKit's frequency skew (0.1 = 10% higher pitch/speed); ignored when tiny. */
    fun rate(skew: Float?): Double {
        val s = skew?.toDouble() ?: return 1.0
        if (s.isNaN() || abs(s) < 0.003 || abs(s) > 0.2) return 1.0
        return 1.0 + s
    }

    /** Position of a running estimate [nowMono] after it was read at [readMono]. */
    fun advance(atRead: Double, readMono: Double, nowMono: Double): Double = atRead + (nowMono - readMono)
}
