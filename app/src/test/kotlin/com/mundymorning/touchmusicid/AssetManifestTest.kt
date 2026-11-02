package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.design.AssetSlot
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Keeps docs/VISUAL_ASSET_MANIFEST.md honest: every slot is documented with its exact file name and size. */
class AssetManifestTest {
    private val manifest = listOf("docs/VISUAL_ASSET_MANIFEST.md", "../docs/VISUAL_ASSET_MANIFEST.md").map(::File).first { it.exists() }.readText()

    @Test fun everySlotIsDocumentedWithFileAndSize() {
        for (s in AssetSlot.entries) {
            assertTrue("${s.name} missing from manifest", manifest.contains("`${s.name}`"))
            assertTrue("${s.resName}.png missing", manifest.contains("`${s.resName}.png`"))
            assertTrue("${s.name} size missing", manifest.contains("${s.widthPx}×${s.heightPx}"))
        }
    }

    @Test fun slotNamesAreValidAndUniqueAndPortraitBackgroundsAre720x1600() {
        assertEquals(AssetSlot.entries.size, AssetSlot.entries.map { it.resName }.toSet().size)
        AssetSlot.entries.forEach { assertTrue(it.resName, it.resName.matches(Regex("slot_[a-z0-9_]+"))) }
        AssetSlot.entries.filter { it.name.startsWith("BG_") }.forEach {
            assertEquals(720, it.widthPx); assertEquals(1600, it.heightPx); assertFalse(it.transparent)
        }
    }

    @Test fun noSlotIsRequiredByCode() {
        // Defaults are drawn in code; the repo ships no slot_* drawables, yet the app builds and the UI tests pass.
        val shipped = File("src/main/res").walkTopDown().filter { it.isFile && it.name.startsWith("slot_") }.toList()
        assertTrue("default build should ship no slot art: $shipped", shipped.isEmpty())
    }
}
