package com.mundymorning.touchmusicid.lyrics

/** Minimal models for on-demand lyrics. No permanent library. */
data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Double? = null,
)

data class LrcLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val plain: String?,
    val syncedLines: List<LrcLine>,
    val source: String, // "online" or "local"
    val instrumental: Boolean = false,
)

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data class Ready(val result: LyricsResult) : LyricsUiState
    data class Unavailable(val message: String) : LyricsUiState
    data object Disabled : LyricsUiState // online disabled and no local
}
