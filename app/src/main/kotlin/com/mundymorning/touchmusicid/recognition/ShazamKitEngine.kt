package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.RecognitionSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real recognition: microphone -> streaming ShazamKit session -> first Match wins.
 * SDK-free (talks to [MatchBackend] / [AudioCapture]) so every state transition is unit-tested without the AAR.
 *
 * Lifecycle: capture is a cold flow collected inside this coroutine, so cancelling recognize() (cancel button,
 * app backgrounded, controller timeout) cancels the collector and releases the microphone. Returning, for any reason,
 * does the same. Raw audio is never stored.
 */
class ShazamKitEngine(
    private val tokens: TokenSource,
    private val backend: MatchBackend,
    private val capture: AudioCapture,
    /** Total time the mic stays open before giving up. Must be below the controller's hard timeout. */
    private val listenBudgetMs: Long = 12_000,
    /** After the mic closes, how long to wait for a late answer from the service. */
    private val answerGraceMs: Long = 4_000,
    private val silencePeak: Int = 150,
) : RecognitionEngine {
    override val source = RecognitionSource.SHAZAMKIT
    override val requiresMicrophone = true
    override val availability: EngineAvailability = EngineAvailability.Ready

    override suspend fun recognize(onPhase: (EnginePhase) -> Unit): EngineResult {
        val token = when (val t = tokens.token()) {
            is TokenResult.Ok -> t.token.value
            TokenResult.NotConfigured -> return EngineResult.Failure(FailureKind.TOKEN, "The token service isn't configured.")
            is TokenResult.Failed -> return EngineResult.Failure(
                if (t.network) FailureKind.NETWORK else FailureKind.TOKEN, t.message,
            )
        }
        val matcher = try {
            backend.open(token, capture.sampleRateHz, capture.readBufferSize)
        } catch (e: BackendException) {
            return EngineResult.Failure(e.kind, e.message ?: "Couldn't start recognition.")
        }

        onPhase(EnginePhase.LISTENING)
        return coroutineScope {
            val outcome = CompletableDeferred<EngineResult>()
            // Subscribe before any audio is fed so no early result can be missed.
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                matcher.results.collect { r ->
                    when (r) {
                        is BackendResult.Match -> outcome.complete(EngineResult.Match(r.song))
                        is BackendResult.Error -> outcome.complete(EngineResult.Failure(r.kind, r.message))
                        BackendResult.NoMatch -> Unit // more audio may still match; decided at the end of the budget
                    }
                }
            }
            var peak = 0
            var processing = false
            val budget = minOf(listenBudgetMs, matcher.maxSignatureMs.coerceAtLeast(matcher.minSignatureMs) + 3_000)
            try {
                capture.chunks()
                    .takeWhile { !outcome.isCompleted && it.timestampMs < budget }
                    .collect { chunk ->
                        peak = maxOf(peak, peakAmplitude(chunk.data, chunk.length))
                        matcher.feed(chunk)
                        if (!processing && chunk.timestampMs >= matcher.minSignatureMs) {
                            processing = true
                            onPhase(EnginePhase.PROCESSING)
                        }
                    }
            } catch (e: AudioUnavailable) {
                collector.cancel()
                return@coroutineScope EngineResult.Failure(FailureKind.AUDIO, e.message ?: "Microphone unavailable.")
            } catch (e: SecurityException) {
                collector.cancel()
                return@coroutineScope EngineResult.Failure(FailureKind.AUDIO, "Microphone permission was revoked.")
            }
            if (!processing) onPhase(EnginePhase.PROCESSING)
            val result = if (outcome.isCompleted) outcome.await() else withTimeoutOrNull(answerGraceMs) { outcome.await() }
            collector.cancel()
            when {
                result != null -> result
                peak < silencePeak -> EngineResult.Failure(FailureKind.SILENCE, "I couldn't hear any music. Check the volume and hold the phone near the speaker.")
                else -> EngineResult.NoMatch
            }
        }
    }
}
