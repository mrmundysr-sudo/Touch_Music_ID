package com.mundymorning.touchmusicid.design

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mundymorning.touchmusicid.domain.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** True when the user has turned system animations off; infinite decorative motion is then skipped. */
@Composable
fun rememberReduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TmiDims.CardRadius))
            .background(TmiColors.SurfaceHigh.copy(alpha = 0.88f))
            .border(1.dp, TmiColors.Outline, RoundedCornerShape(TmiDims.CardRadius))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun TmiButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, primary: Boolean = true, enabled: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.defaultMinSize(minHeight = TmiDims.MinTouch)
    if (primary) {
        Button(onClick, m, enabled, colors = ButtonDefaults.buttonColors(containerColor = TmiColors.Primary, contentColor = TmiColors.OnPrimary), content = { content() })
    } else {
        OutlinedButton(
            onClick, m, enabled, border = BorderStroke(1.dp, TmiColors.Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TmiColors.TextHigh), content = { content() },
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh, modifier = modifier.semantics { heading() })
}

enum class ButtonPhase { IDLE, LISTENING, PROCESSING }

/** The round, one-tap Listen control. Artwork comes from the LISTEN_IDLE/LISTEN_ACTIVE slots; the pulse is drawn in code. */
@Composable
fun ListenButton(phase: ButtonPhase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val active = phase != ButtonPhase.IDLE
    val t = if (reduce) 0.35f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (phase == ButtonPhase.LISTENING) TmiMotion.PulseListeningMs else TmiMotion.PulseIdleMs, easing = LinearEasing)),
        label = "pulseT",
    ).value
    val desc = when (phase) {
        ButtonPhase.IDLE -> "Listen. Double tap to identify the music playing nearby."
        ButtonPhase.LISTENING -> "Listening. Double tap to cancel."
        ButtonPhase.PROCESSING -> "Identifying. Double tap to cancel."
    }
    Box(modifier.size(TmiDims.ListenHalo), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2
            val minR = TmiDims.ListenButton.toPx() / 2
            for (i in 0 until 3) {
                val p = (t + i / 3f) % 1f
                val r = minR + (maxR - minR) * p
                val a = (1f - p) * if (active) 0.55f else 0.22f
                drawCircle(TmiColors.Primary.copy(alpha = a), r, style = Stroke(2.dp.toPx()))
            }
        }
        Box(
            Modifier
                .size(TmiDims.ListenButton)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (active) "Cancel" else "Start listening", onClick = onClick)
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) {
            SlotImage(if (active) AssetSlot.LISTEN_ACTIVE else AssetSlot.LISTEN_IDLE, Modifier.fillMaxSize(), ContentScale.Fit) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            if (active) listOf(TmiColors.Accent, TmiColors.Primary) else listOf(TmiColors.Primary, TmiColors.Accent),
                        ),
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TmiIcons.Mic, null, Modifier.size(68.dp), tint = TmiColors.OnPrimary)
                }
            }
            if (phase == ButtonPhase.PROCESSING) {
                CircularProgressIndicator(Modifier.fillMaxSize().padding(4.dp), color = TmiColors.TextHigh, strokeWidth = 3.dp)
            }
        }
    }
}

/** Animated bars while listening. If a WAVEFORM slot image is supplied it is shown instead. */
@Composable
fun Waveform(active: Boolean, modifier: Modifier = Modifier) {
    val slotId = rememberSlotResId(AssetSlot.WAVEFORM)
    if (slotId != 0) { SlotImage(AssetSlot.WAVEFORM, modifier, ContentScale.FillWidth); return }
    val reduce = rememberReduceMotion()
    val phase = if (active && !reduce) rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "waveT",
    ).value else 0f
    Canvas(modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "" }) {
        val n = 29
        val gap = size.width / n
        val bar = gap * 0.46f
        for (i in 0 until n) {
            val envelope = 1f - abs(i - n / 2f) / (n / 2f) * 0.75f
            val level = if (active) 0.25f + 0.75f * abs(sin(phase * 2 * PI + i * 0.5)).toFloat() else 0.12f
            val h = size.height * level * envelope
            drawLine(
                if (active) TmiColors.Primary else TmiColors.Outline,
                Offset(gap * (i + 0.5f), (size.height - h) / 2), Offset(gap * (i + 0.5f), (size.height + h) / 2),
                strokeWidth = bar, cap = StrokeCap.Round,
            )
        }
    }
}

/** Album-art container. No artwork is bundled: shows a generated monogram unless the slot is replaced. */
@Composable
fun AlbumArtBox(song: Song, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = TmiDims.AlbumArt) {
    val hue = remember(song.key) { (song.key.hashCode().toLong().let { if (it < 0) -it else it } % 360).toFloat() }
    val a = Color.hsv(hue, 0.55f, 0.55f)
    val b = Color.hsv((hue + 50f) % 360f, 0.6f, 0.32f)
    Box(modifier.size(size).clip(RoundedCornerShape(TmiDims.TileRadius)), contentAlignment = Alignment.Center) {
        SlotImage(AssetSlot.ALBUM_PLACEHOLDER, Modifier.fillMaxSize(), ContentScale.Crop) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
                Text(
                    song.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
        SlotImage(AssetSlot.RESULTS_FRAME, Modifier.fillMaxSize(), ContentScale.FillBounds)
    }
}

@Composable
fun EmptyState(slot: AssetSlot, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SlotImage(slot, Modifier.size(160.dp), ContentScale.Fit) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(TmiColors.Surface), contentAlignment = Alignment.Center) {
                Icon(TmiIcons.MusicNote, null, Modifier.size(64.dp), tint = TmiColors.TextLow)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = TmiColors.TextHigh, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = TmiColors.TextMedium, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun TileBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(TmiDims.TileRadius)).border(1.dp, TmiColors.Outline.copy(alpha = 0.7f), RoundedCornerShape(TmiDims.TileRadius))) {
        SlotImage(AssetSlot.HISTORY_TILE, Modifier.matchParentSize(), ContentScale.FillBounds) {
            Box(Modifier.fillMaxSize().background(TmiColors.Surface.copy(alpha = 0.92f)))
        }
        content()
    }
}
