package com.mundymorning.touchmusicid.lyricsproto

import com.mundymorning.touchmusicid.recognition.TokenResult
import com.mundymorning.touchmusicid.recognition.TokenSource
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object ProbeFactory {
    fun create(tokens: TokenSource, capture: LabAudioCapture): MatchProbe? = ShazamProbe(tokens, capture)
}

/**
 * DEBUG-ONLY ShazamKit probe. Separate from the production engine, which is untouched.
 *
 * Differences from production, on purpose:
 *  - Every chunk is stamped with WALL-CLOCK epoch milliseconds derived from the capture timeline (the SDK computes
 *    predictedCurrentMatchOffset against System.currentTimeMillis(), so stream-relative stamps would make it meaningless).
 *  - The session keeps running after the first match and EVERY result is reported, to learn how often matches arrive.
 *  - All raw fields (matchOffsetInMs, predictedCurrentMatchOffset, frequencySkew, signature duration) are passed through untouched.
 */
class ShazamProbe(private val tokens: TokenSource, private val capture: LabAudioCapture) : MatchProbe {
    override suspend fun run(sessionId: Int, emit: (ProbeEvent) -> Unit) {
        val token = when (val t = tokens.token()) {
            is TokenResult.Ok -> t.token.value
            TokenResult.NotConfigured -> { emit(ProbeEvent.Failed(sessionId, "Token service is not configured")); return }
            is TokenResult.Failed -> { emit(ProbeEvent.Failed(sessionId, "Token: ${t.message}")); return }
        }
        val provider = object : DeveloperTokenProvider {
            override fun provideDeveloperToken() = DeveloperToken(token)
        }
        val catalog = ShazamKit.createShazamCatalog(provider)
        val session = when (val r = ShazamKit.createStreamingSession(catalog, AudioSampleRateInHz.SAMPLE_RATE_48000, capture.chunkBytes)) {
            is ShazamKitResult.Success -> r.data
            is ShazamKitResult.Failure -> { emit(ProbeEvent.Failed(sessionId, "ShazamKit couldn't start (${r.reason.internalError})")); return }
        }

        var timeline: CaptureTimeline? = null
        var lastEndMono = 0.0
        var lastEndStream = 0L

        coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                session.recognitionResults().collect { result ->
                    val readMono = System.nanoTime() / 1e6
                    val readEpoch = System.currentTimeMillis()
                    when (result) {
                        is MatchResult.Match -> {
                            val item = result.matchedMediaItems.firstOrNull()
                            if (item == null) {
                                emit(ProbeEvent.NoMatch(sessionId, readMono, result.querySignature.durationInMs))
                            } else {
                                emit(
                                    ProbeEvent.Fix(
                                        RawFix(
                                            sessionId = sessionId,
                                            title = item.title.orEmpty(),
                                            artist = item.artist.orEmpty().ifBlank { item.subtitle.orEmpty() },
                                            isrc = item.isrc,
                                            appleMusicId = item.appleMusicID,
                                            artworkUrl = item.artworkURL?.toString(),
                                            matchOffsetMs = item.matchOffsetInMs,
                                            predictedMs = item.predictedCurrentMatchOffset,
                                            frequencySkew = item.frequencySkew,
                                            sigDurationMs = result.querySignature.durationInMs,
                                            itemCount = result.matchedMediaItems.size,
                                            readMonoMs = readMono,
                                            readEpochMs = readEpoch,
                                            lastFedEndMono = lastEndMono,
                                            lastFedEndStreamMs = lastEndStream,
                                        ),
                                    ),
                                )
                            }
                        }
                        is MatchResult.NoMatch -> emit(ProbeEvent.NoMatch(sessionId, readMono, result.querySignature.durationInMs))
                        is MatchResult.Error -> emit(ProbeEvent.Failed(sessionId, "ShazamKit error ${result.exception.matchError}"))
                        else -> Unit
                    }
                }
            }
            try {
                capture.chunks { tl -> timeline = tl; emit(ProbeEvent.Started(sessionId, tl)) }.collect { c ->
                    val tl = timeline ?: return@collect
                    session.matchStream(c.data, c.len, tl.epochForStream(c.streamStartMs))
                    lastEndMono = c.readMonoMs
                    lastEndStream = c.streamStartMs + (c.len / 2) * 1000L / capture.sampleRate
                    emit(ProbeEvent.Audio(sessionId, c.readMonoMs, c.rms, c.peak, c.streamStartMs, c.measuredFrame0MonoMs?.let { it - tl.frame0MonoMs }))
                }
            } catch (e: LabAudioException) {
                emit(ProbeEvent.Failed(sessionId, "Microphone: ${e.message}"))
            } catch (e: SecurityException) {
                emit(ProbeEvent.Failed(sessionId, "Microphone permission was revoked"))
            } finally {
                collector.cancel()
            }
        }
    }
}
