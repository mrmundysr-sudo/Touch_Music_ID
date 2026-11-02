package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.Song
import kotlinx.coroutines.flow.Flow

/** Backend-neutral match outcome. Mirrors the SDK's Match / NoMatch / Error without leaking its types. */
sealed interface BackendResult {
    data class Match(val song: Song) : BackendResult
    data object NoMatch : BackendResult
    data class Error(val kind: FailureKind, val message: String) : BackendResult
}

class BackendException(val kind: FailureKind, message: String) : Exception(message)

/** An open streaming match session. Feed contiguous PCM; collect [results]. */
interface StreamMatcher {
    val minSignatureMs: Long
    val maxSignatureMs: Long
    val results: Flow<BackendResult>
    fun feed(chunk: AudioChunk)
}

/** The only place SDK types may appear (see src/shazamkit). Token is the short-lived developer token from the token service. */
interface MatchBackend {
    /** Throws [BackendException] if the session can't be created. */
    suspend fun open(token: String, sampleRateHz: Int, readBufferSize: Int): StreamMatcher
}
