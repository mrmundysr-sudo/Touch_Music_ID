package com.mundymorning.touchmusicid.design

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Full-screen blurred + darkened album artwork background.
 * On API < 31 falls back to a strong dark overlay without blur (still readable).
 */
@Composable
fun AlbumArtBackground(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (bitmap != null) {
            val image = bitmap.asImageBitmap()
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(48.dp)
            } else {
                Modifier
            }
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(blurModifier),
                contentScale = ContentScale.Crop,
            )
            // Darken enough for readable text
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.85f),
                        )
                    )
                )
            )
        } else {
            // Branded fallback
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(TmiColors.BackgroundDeep, TmiColors.Background, Color(0xFF111B33))
                    )
                )
            )
        }
    }
}
