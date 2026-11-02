package com.mundymorning.touchmusicid.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.lyricsproto.LyricsLabActivity

/** DEBUG BUILDS ONLY: a shortcut to the synchronized-lyrics prototype. The release source set provides an empty version of this. */
@Composable
fun DebugLabEntry() {
    val ctx = LocalContext.current
    TmiButton(
        "Open Lyrics Lab (debug)",
        { ctx.startActivity(Intent(ctx, LyricsLabActivity::class.java)) },
        Modifier.fillMaxWidth(), primary = false, compact = true,
    )
}
