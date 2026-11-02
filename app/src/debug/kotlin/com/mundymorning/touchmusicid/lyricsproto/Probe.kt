package com.mundymorning.touchmusicid.lyricsproto

/** Events produced by a recognition session. The probe never interprets them; the controller does. */
sealed interface ProbeEvent {
    data class Started(val sessionId: Int, val timeline: CaptureTimeline) : ProbeEvent
    data class Audio(val sessionId: Int, val monoMs: Double, val rms: Double, val peak: Int, val streamMs: Long, val driftMs: Double? = null) : ProbeEvent
    data class Fix(val raw: RawFix) : ProbeEvent
    data class NoMatch(val sessionId: Int, val monoMs: Double, val sigDurationMs: Long) : ProbeEvent
    data class Failed(val sessionId: Int, val message: String) : ProbeEvent
    data class Info(val sessionId: Int, val message: String) : ProbeEvent
}

/** One microphone + ShazamKit streaming session. Cancelling the coroutine stops capture and releases the microphone. */
interface MatchProbe {
    suspend fun run(sessionId: Int, emit: (ProbeEvent) -> Unit)
}
