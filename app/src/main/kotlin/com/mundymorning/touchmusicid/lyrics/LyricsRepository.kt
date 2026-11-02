package com.mundymorning.touchmusicid.lyrics

import com.mundymorning.touchmusicid.BuildConfig
import com.mundymorning.touchmusicid.data.SettingsStore
import com.mundymorning.touchmusicid.net.HttpFetcher

/**
 * Coordinates local imported LRC (always available) and optional on-demand online lyrics.
 * Online is disabled in public release builds pending licensing review.
 * Independently removable: delete this package and the calls in the UI; recognition and wiki stay intact.
 */
class LyricsRepository(
    private val settings: SettingsStore,
    private val local: LocalLrcStore,
    http: HttpFetcher,
) {
    private val client = LrclibClient(http)

    val onlineAllowedInThisBuild: Boolean
        get() = BuildConfig.DEBUG // public release keeps online disabled

    suspend fun load(songKey: String, query: TrackQuery): LyricsUiState {
        local.get(songKey)?.let { return LyricsUiState.Ready(it) }

        if (!onlineAllowedInThisBuild || !settings.enableOnlineLyrics.value) {
            return LyricsUiState.Disabled
        }

        return try {
            val result = client.lookup(query)
            if (result != null) LyricsUiState.Ready(result)
            else LyricsUiState.Unavailable("No lyrics found for this track")
        } catch (e: Exception) {
            LyricsUiState.Unavailable(e.message ?: "Could not load lyrics")
        }
    }

    fun importLocal(songKey: String, lrcText: String) = local.put(songKey, lrcText)
}
