package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.RecognitionSource
import com.mundymorning.touchmusicid.domain.Song

enum class UnavailableReason {
    /** Apple's ShazamKit Android AAR is not linked into this build (it is an Apple-licensed download). */
    SDK_NOT_LINKED,

    /** No HTTPS token-service URL configured, so no developer token can be obtained. */
    TOKEN_SERVICE_NOT_CONFIGURED,
}

sealed interface EngineAvailability {
    data object Ready : EngineAvailability
    data class Unavailable(val reason: UnavailableReason) : EngineAvailability
}

enum class FailureKind { NETWORK, TOKEN, AUDIO, SILENCE, TIMEOUT, UNKNOWN }

sealed interface EngineResult {
    data class Match(val song: Song) : EngineResult
    data object NoMatch : EngineResult
    data class Failure(val kind: FailureKind, val message: String) : EngineResult
}

enum class EnginePhase { LISTENING, PROCESSING }

/**
 * Adapter boundary between the app and any recognition backend.
 * The UI and state machine only know this interface; ShazamKit lives behind it (see docs/SHAZAMKIT_SETUP.md).
 * Implementations must be cancellable (coroutine cancellation stops capture and releases the microphone)
 * and must never retain raw audio.
 */
interface RecognitionEngine {
    val source: RecognitionSource
    val requiresMicrophone: Boolean
    val availability: EngineAvailability
    suspend fun recognize(onPhase: (EnginePhase) -> Unit): EngineResult
}

/** Used when no real engine can run. It can never produce a match. */
class UnavailableEngine(private val reason: UnavailableReason) : RecognitionEngine {
    override val source = RecognitionSource.SHAZAMKIT
    override val requiresMicrophone = true
    override val availability: EngineAvailability = EngineAvailability.Unavailable(reason)
    override suspend fun recognize(onPhase: (EnginePhase) -> Unit): EngineResult =
        EngineResult.Failure(FailureKind.UNKNOWN, "Recognition is not configured ($reason).")
}
