package com.mundymorning.touchmusicid.recognition

import com.mundymorning.touchmusicid.domain.Song
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.MatchError
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.MatchedMediaItem
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitResult
import com.shazam.shazamkit.StreamingSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Compiled only when app/libs/shazamkit-android-release.aar is present (see app/build.gradle.kts). */
object ShazamKitBindings {
    fun backend(): MatchBackend? = ShazamBackend
}

internal object ShazamBackend : MatchBackend {
    override suspend fun open(token: String, sampleRateHz: Int, readBufferSize: Int): StreamMatcher {
        val rate = AudioSampleRateInHz.entries.firstOrNull { it.name == "SAMPLE_RATE_$sampleRateHz" }
            ?: throw BackendException(FailureKind.AUDIO, "Unsupported sample rate $sampleRateHz Hz.")
        // The SDK asks for the token synchronously; ours was fetched (and cached in memory) just before this call.
        val provider = object : DeveloperTokenProvider {
            override fun provideDeveloperToken() = DeveloperToken(token)
        }
        val catalog = ShazamKit.createShazamCatalog(provider)
        return when (val r = ShazamKit.createStreamingSession(catalog, rate, readBufferSize)) {
            is ShazamKitResult.Success -> Matcher(r.data)
            is ShazamKitResult.Failure -> throw BackendException(FailureKind.UNKNOWN, "ShazamKit couldn't start (${r.reason.internalError}).")
        }
    }

    private class Matcher(private val session: StreamingSession) : StreamMatcher {
        override val minSignatureMs = session.catalog.minimumQuerySignatureDurationInMs
        override val maxSignatureMs = session.catalog.maximumQuerySignatureDurationInMs
        override val results: Flow<BackendResult> = session.recognitionResults().map { it.toBackend() }
        override fun feed(chunk: AudioChunk) = session.matchStream(chunk.data, chunk.length, chunk.timestampMs)
    }

    private fun MatchResult.toBackend(): BackendResult = when (this) {
        is MatchResult.Match -> matchedMediaItems.firstOrNull()?.let { BackendResult.Match(it.toSong()) } ?: BackendResult.NoMatch
        is MatchResult.NoMatch -> BackendResult.NoMatch
        is MatchResult.Error -> {
            val cause = generateSequence<Throwable>(exception) { it.cause }.toList()
            val network = cause.any { it is IOException }
            when {
                exception.matchError == MatchError.UNAUTHORIZED ->
                    BackendResult.Error(FailureKind.TOKEN, "Apple rejected the developer token. The token service or Apple credentials need attention.")
                network ->
                    BackendResult.Error(FailureKind.NETWORK, "Couldn't reach the music service. Check your connection and try again.")
                exception.matchError == MatchError.PROVIDED_EMPTY_AUDIO_DATA ->
                    BackendResult.Error(FailureKind.AUDIO, "No audio was captured.")
                else -> BackendResult.Error(FailureKind.UNKNOWN, "Recognition failed (${exception.matchError}). Please try again.")
            }
        }
        else -> BackendResult.NoMatch
    }

    private fun MatchedMediaItem.toSong() = Song(
        title = title.orEmpty().ifBlank { "Unknown title" },
        artist = artist.orEmpty().ifBlank { subtitle.orEmpty() },
        album = null,
        appleMusicUrl = appleMusicURL?.toString(),
        webUrl = webURL?.toString(),
        isrc = isrc,
        artworkUrl = artworkURL?.toString(),
    )
}
