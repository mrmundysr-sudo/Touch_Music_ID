package com.mundymorning.touchmusicid.design

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette

/** Accent colors extracted from album artwork. Falls back to brand tokens when art is unavailable. */
data class ArtworkAccents(
    val primary: Color = TmiColors.Primary,
    val secondary: Color = TmiColors.Accent,
    val background: Color = TmiColors.Background,
    val onPrimary: Color = TmiColors.OnPrimary,
    val vibrant: Color = TmiColors.Primary,
    val muted: Color = TmiColors.Accent,
)

fun extractAccents(bitmap: Bitmap?): ArtworkAccents {
    if (bitmap == null) return ArtworkAccents()
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    val vibrant = palette.vibrantSwatch?.rgb?.let { Color(it) } ?: TmiColors.Primary
    val muted = palette.mutedSwatch?.rgb?.let { Color(it) } ?: TmiColors.Accent
    val dark = palette.darkVibrantSwatch?.rgb?.let { Color(it) } ?: TmiColors.Background
    val onPrimary = if (vibrant.luminance() > 0.5f) Color.Black else Color.White
    return ArtworkAccents(
        primary = vibrant,
        secondary = muted,
        background = dark.copy(alpha = 0.92f),
        onPrimary = onPrimary,
        vibrant = vibrant,
        muted = muted,
    )
}

private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.299f * r + 0.587f * g + 0.114f * b
}

val LocalArtworkAccents = compositionLocalOf { ArtworkAccents() }

@Composable
fun DynamicArtworkTheme(
    bitmap: Bitmap?,
    content: @Composable () -> Unit,
) {
    val accents = remember(bitmap) { extractAccents(bitmap) }
    val animatedPrimary by animateColorAsState(accents.primary, tween(TmiMotion.Normal), label = "primary")
    val animatedSecondary by animateColorAsState(accents.secondary, tween(TmiMotion.Normal), label = "secondary")
    val animatedBg by animateColorAsState(accents.background, tween(TmiMotion.Normal), label = "bg")

    val scheme = darkColorScheme(
        primary = animatedPrimary,
        onPrimary = accents.onPrimary,
        secondary = animatedSecondary,
        onSecondary = TmiColors.TextHigh,
        background = animatedBg,
        onBackground = TmiColors.TextHigh,
        surface = animatedBg.copy(alpha = 0.75f),
        onSurface = TmiColors.TextHigh,
        surfaceVariant = animatedBg.copy(alpha = 0.85f),
        onSurfaceVariant = TmiColors.TextMedium,
        outline = TmiColors.Outline,
        error = TmiColors.Error,
    )

    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}
