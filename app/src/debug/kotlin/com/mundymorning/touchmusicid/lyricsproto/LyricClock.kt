package com.mundymorning.touchmusicid.lyricsproto

/**
 * Song position as a function of a monotonic clock. Position is DERIVED from the anchor on every read, never accumulated
 * (so a stalled UI thread cannot make it drift). All times are monotonic milliseconds (System.nanoTime() / 1e6).
 * Idea credit: LiveLyrics (MIT), "anchor = where the matched audio began; position = anchor + elapsed".
 */
class LyricClock {
    var hasAnchor: Boolean = false
        private set
    var anchorSongMs: Double = 0.0
        private set
    var anchorAtMono: Double = 0.0
        private set
    var rate: Double = 1.0
        private set

    /** Persistent user timing correction (positive = lyrics later in the song, i.e. highlight lines sooner). */
    var nudgeMs: Double = 0.0

    /** While set, the clock is parked here (raw position, without nudge). */
    var holdRawMs: Double? = null

    fun setAnchor(songMs: Double, atMono: Double, rate: Double) {
        hasAnchor = true
        anchorSongMs = songMs
        anchorAtMono = atMono
        this.rate = rate
    }

    fun clear() {
        hasAnchor = false
        holdRawMs = null
        anchorSongMs = 0.0
        anchorAtMono = 0.0
        rate = 1.0
    }

    /** Position the anchor says we are at, ignoring any hold. */
    fun runningRawMs(nowMono: Double): Double? = if (!hasAnchor) null else anchorSongMs + (nowMono - anchorAtMono) * rate

    /** Raw position honouring a hold. */
    fun rawMs(nowMono: Double): Double? = holdRawMs ?: runningRawMs(nowMono)

    /** What the UI shows: raw position plus the user's nudge, never negative. */
    fun positionMs(nowMono: Double): Double? = rawMs(nowMono)?.let { maxOf(0.0, it + nudgeMs) }
}
