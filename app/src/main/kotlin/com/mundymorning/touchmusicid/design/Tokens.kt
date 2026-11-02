package com.mundymorning.touchmusicid.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Central design tokens. Change visuals here, never inside screens. */
object TmiColors {
    val Background = Color(0xFF0B0F1A)
    val BackgroundDeep = Color(0xFF070A12)
    val Surface = Color(0xFF141A2B)
    val SurfaceHigh = Color(0xFF1B2338)
    val Outline = Color(0xFF2B3552)
    val Primary = Color(0xFF5EEAD4)        // teal
    val OnPrimary = Color(0xFF04201B)
    val Accent = Color(0xFF8B7CFF)         // violet
    val Warm = Color(0xFFFFB86B)
    val Success = Color(0xFF6EE7A0)
    val Error = Color(0xFFFF6B6B)
    val TextHigh = Color(0xFFF3F6FF)
    val TextMedium = Color(0xFFB4BED8)
    val TextLow = Color(0xFF7D88A8)
    /** Scrim laid over every background slot so text stays legible whatever artwork is dropped in. */
    val ScrimTop = Color(0x66070A12)
    val ScrimBottom = Color(0xCC070A12)
}

object TmiDims {
    val MinTouch = 48.dp
    val ScreenPadding = 20.dp
    val CardRadius = 22.dp
    val TileRadius = 18.dp
    val Gap = 12.dp
    val GapLarge = 20.dp
    val ListenButton = 168.dp
    val ListenHalo = 270.dp
    val AlbumArt = 200.dp
}

object TmiMotion {
    const val Fast = 160
    const val Normal = 280
    const val PulseIdleMs = 3200
    const val PulseListeningMs = 1500
}
