package com.mundymorning.touchmusicid.lyricsproto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TmiColors
import kotlinx.coroutines.delay

object LabFormat {
    /** m:ss.mmm, or a placeholder when there is no position yet. */
    fun position(ms: Long): String {
        if (ms < 0) return "--:--.---"
        return "%d:%02d.%03d".format(java.util.Locale.ROOT, ms / 60_000, (ms / 1000) % 60, ms % 1000)
    }
}

/**
 * The lyric list. Synchronized mode highlights [activeIndex] and, when [followActive], scrolls to keep it in view.
 * Plain mode (synced = false) is an ordinary, manually scrollable sheet. Touching the list reports [onUserTouch] so the
 * caller can pause auto-follow while the user reads elsewhere.
 */
@Composable
fun LyricSheet(
    lines: List<String>,
    activeIndex: Int,
    followActive: Boolean,
    synced: Boolean,
    onUserTouch: () -> Unit,
    modifier: Modifier = Modifier,
    textSizeSp: Int = 20,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, followActive) {
        if (followActive && activeIndex in lines.indices) {
            val offset = -(listState.layoutInfo.viewportSize.height / 3)
            if (kotlin.math.abs(listState.firstVisibleItemIndex - activeIndex) > 6) listState.scrollToItem(activeIndex, offset)
            else listState.animateScrollToItem(activeIndex, offset)
        }
    }
    LazyColumn(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    onUserTouch()
                }
            }
        },
        state = listState,
        contentPadding = PaddingValues(vertical = 80.dp, horizontal = 12.dp),
    ) {
        itemsIndexed(lines) { i, text ->
            val active = synced && i == activeIndex
            val shown = text.ifBlank { "♪" }
            Text(
                shown,
                fontSize = textSizeSp.sp,
                lineHeight = (textSizeSp * 1.3f).sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    active -> TmiColors.Primary
                    synced && i < activeIndex -> TmiColors.TextLow
                    else -> TmiColors.TextMedium
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { if (active) contentDescription = "Current line: $shown" },
            )
        }
    }
}

@Composable
fun LyricsLabScreen(c: LyricsLabController, onStart: () -> Unit) {
    val st by c.state.collectAsStateWithLifecycle()
    val tick by c.tick.collectAsStateWithLifecycle()
    val diag by c.diag.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var textSize by remember { mutableIntStateOf(20) }
    var paused by remember { mutableStateOf(false) }
    var touchToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchToken) {
        if (touchToken > 0) { paused = true; delay(5_000); paused = false }
    }

    Column(Modifier.fillMaxSize().background(TmiColors.Background).systemBarsPadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lyrics Lab · debug prototype", style = MaterialTheme.typography.titleMedium, color = TmiColors.TextHigh)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TmiButton(if (st.running) "Stop" else "Start", { if (st.running) c.stop() else onStart() }, Modifier.weight(1f), compact = true)
            TmiButton("Re-sync", { c.resyncNow() }, Modifier.weight(1f), primary = false, compact = true, enabled = st.running)
            TmiButton("Mark", { c.mark() }, Modifier.weight(1f), primary = false, compact = true, enabled = st.running)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ProbeMode.entries) TmiButton(m.name.lowercase().replaceFirstChar { it.uppercase() }, { c.setMode(m) }, Modifier.weight(1f), primary = st.mode == m, compact = true)
            for (e in Estimator.entries) TmiButton("Est ${e.name}", { c.setEstimator(e) }, Modifier.weight(0.7f), primary = st.estimator == e, compact = true)
        }
        Text(
            st.song?.let { "${it.title} — ${it.artist}" } ?: st.status,
            style = MaterialTheme.typography.titleSmall, color = TmiColors.TextHigh, maxLines = 2,
        )
        Text(
            listOfNotNull(st.song?.let { st.status }, st.song?.metaNote?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = TmiColors.TextLow, maxLines = 3,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(LabFormat.position(tick.positionMs), fontSize = 30.sp, fontFamily = FontFamily.Monospace, color = TmiColors.Primary, fontWeight = FontWeight.Bold)
            Text("${tick.syncState}  conf ${"%.2f".format(java.util.Locale.ROOT, tick.confidence)}", style = MaterialTheme.typography.bodySmall, color = TmiColors.TextMedium)
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(TmiColors.Surface)) {
            when (val l = st.lyrics) {
                LyricsState.Idle -> Text("Press Start, then play a song near the phone (it can be halfway through).", Modifier.padding(16.dp), color = TmiColors.TextMedium)
                is LyricsState.Loading -> Text("Looking up ${l.step}…", Modifier.padding(16.dp), color = TmiColors.TextMedium)
                is LyricsState.NotFound -> Text("No lyrics found. ${l.info}", Modifier.padding(16.dp), color = TmiColors.TextMedium)
                is LyricsState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't check for lyrics (${l.message}). This does not mean none exist.", color = TmiColors.TextMedium)
                    TmiButton("Retry", { c.retryLyrics() }, compact = true)
                }
                LyricsState.Instrumental -> Text("LRCLIB lists this recording as instrumental.", Modifier.padding(16.dp), color = TmiColors.TextMedium)
                is LyricsState.Ready -> {
                    val followActive = st.follow && l.synced && !paused && (tick.syncState == SyncState.FOLLOWING || tick.syncState == SyncState.HOLD)
                    Column {
                        Text(
                            (if (l.synced) (if (followActive) "Synchronized · following" else "Synchronized · not following") else "Plain lyrics · scroll freely") + " · " + l.info,
                            style = MaterialTheme.typography.labelSmall, color = TmiColors.TextLow, modifier = Modifier.padding(8.dp), maxLines = 2,
                        )
                        LyricSheet(l.lines, tick.activeIdx, followActive, l.synced, { touchToken++ }, Modifier.weight(1f).fillMaxWidth(), textSize)
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TmiButton("−250", { c.nudge(-250) }, Modifier.weight(1f), primary = false, compact = true)
            TmiButton("−100", { c.nudge(-100) }, Modifier.weight(1f), primary = false, compact = true)
            Text("${if (st.nudgeMs >= 0) "+" else ""}${st.nudgeMs} ms", color = TmiColors.TextHigh, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            TmiButton("+100", { c.nudge(100) }, Modifier.weight(1f), primary = false, compact = true)
            TmiButton("+250", { c.nudge(250) }, Modifier.weight(1f), primary = false, compact = true)
            TmiButton("0", { c.resetNudge() }, Modifier.weight(0.6f), primary = false, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TmiButton(if (st.follow) "Follow: on" else "Follow: off", { c.toggleFollow() }, Modifier.weight(1f), primary = st.follow, compact = true)
            TmiButton("A−", { textSize = (textSize - 2).coerceAtLeast(14) }, Modifier.weight(0.5f), primary = false, compact = true)
            TmiButton("A+", { textSize = (textSize + 2).coerceAtMost(34) }, Modifier.weight(0.5f), primary = false, compact = true)
            TmiButton("Copy diag", {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lyrics Lab diagnostics", c.diagText()))
            }, Modifier.weight(1f), primary = false, compact = true)
        }
        Column(Modifier.fillMaxWidth().height(96.dp)) {
            for (line in diag) Text(line, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp, color = TmiColors.TextMedium, maxLines = 1)
            st.logPath?.let { Text("log: $it", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = TmiColors.TextLow, maxLines = 2) }
        }
    }
}
