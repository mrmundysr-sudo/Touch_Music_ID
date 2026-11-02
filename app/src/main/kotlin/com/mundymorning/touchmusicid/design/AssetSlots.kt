package com.mundymorning.touchmusicid.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource

/**
 * Every piece of replaceable artwork is a named slot. To replace a default, drop a file named `<resName>.png`
 * (or .webp/.xml) into `app/src/main/res/drawable-nodpi/`. No code change is needed: if the drawable exists it is used,
 * otherwise the built-in default is drawn. Nothing here carries behaviour: buttons, navigation, labels and
 * accessibility text always come from code. See docs/VISUAL_ASSET_MANIFEST.md.
 */
enum class AssetSlot(
    val resName: String,
    val widthPx: Int,
    val heightPx: Int,
    val transparent: Boolean,
    val usage: String,
) {
    BG_LISTEN("slot_bg_listen", 720, 1600, false, "Full-screen background of the Listen screen"),
    BG_RESULT("slot_bg_result", 720, 1600, false, "Full-screen background of the result/discovery screen"),
    BG_LIBRARY("slot_bg_library", 720, 1600, false, "Background of History and Favorites"),
    BG_SETTINGS("slot_bg_settings", 720, 1600, false, "Background of Settings and Help"),
    LOGO("slot_logo", 640, 200, true, "Wordmark/logo at the top of the Listen screen"),
    LISTEN_IDLE("slot_listen_idle", 512, 512, true, "Round Listen control, resting state (shown at 168dp)"),
    LISTEN_ACTIVE("slot_listen_active", 512, 512, true, "Round Listen control while listening/processing"),
    WAVEFORM("slot_waveform", 720, 200, true, "Static waveform overlay; replaces the animated default if present"),
    RESULTS_FRAME("slot_results_frame", 720, 720, true, "Decorative frame drawn over the album-art container"),
    ALBUM_PLACEHOLDER("slot_album_placeholder", 512, 512, false, "Album-art placeholder (no remote artwork is bundled)"),
    HISTORY_TILE("slot_history_tile", 720, 176, true, "Background of one History/Favorites row (stretched horizontally)"),
    NAV_LISTEN("slot_nav_listen", 96, 96, true, "Bottom-nav icon: Listen (unselected)"),
    NAV_LISTEN_ON("slot_nav_listen_on", 96, 96, true, "Bottom-nav icon: Listen (selected)"),
    NAV_HISTORY("slot_nav_history", 96, 96, true, "Bottom-nav icon: History (unselected)"),
    NAV_HISTORY_ON("slot_nav_history_on", 96, 96, true, "Bottom-nav icon: History (selected)"),
    NAV_FAVORITES("slot_nav_favorites", 96, 96, true, "Bottom-nav icon: Favorites (unselected)"),
    NAV_FAVORITES_ON("slot_nav_favorites_on", 96, 96, true, "Bottom-nav icon: Favorites (selected)"),
    NAV_SETTINGS("slot_nav_settings", 96, 96, true, "Bottom-nav icon: Settings (unselected)"),
    NAV_SETTINGS_ON("slot_nav_settings_on", 96, 96, true, "Bottom-nav icon: Settings (selected)"),
    EMPTY_HISTORY("slot_empty_history", 560, 560, true, "Empty-state illustration for History"),
    EMPTY_FAVORITES("slot_empty_favorites", 560, 560, true, "Empty-state illustration for Favorites"),
    EMPTY_NO_ARTICLE("slot_empty_no_article", 560, 560, true, "Illustration shown when no verified Wikipedia article exists"),
}

/** Resolves a slot to a drawable resource id, or 0 if no replacement artwork has been supplied. */
@Composable
fun rememberSlotResId(slot: AssetSlot): Int {
    val ctx = LocalContext.current
    return remember(slot) { ctx.resources.getIdentifier(slot.resName, "drawable", ctx.packageName) }
}

/** Draws the slot's artwork if supplied, otherwise runs [fallback] (the built-in default drawn in code). */
@Composable
fun SlotImage(
    slot: AssetSlot,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val id = rememberSlotResId(slot)
    if (id != 0) {
        // Decorative: meaning always comes from code-supplied text, never from the picture.
        Image(painter = painterResource(id), contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier, content = fallback)
    }
}

/** Full-screen background slot plus a code-owned legibility scrim. */
@Composable
fun SlotBackground(slot: AssetSlot, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier) {
        SlotImage(slot, Modifier.fillMaxSize(), ContentScale.Crop) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(TmiColors.BackgroundDeep, TmiColors.Background, androidx.compose.ui.graphics.Color(0xFF111B33))),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(TmiColors.Accent.copy(alpha = 0.22f), androidx.compose.ui.graphics.Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(200f, 300f), radius = 900f,
                    ),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(TmiColors.Primary.copy(alpha = 0.14f), androidx.compose.ui.graphics.Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(900f, 1500f), radius = 1000f,
                    ),
                ),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(TmiColors.ScrimTop, TmiColors.ScrimBottom))))
    }
}
