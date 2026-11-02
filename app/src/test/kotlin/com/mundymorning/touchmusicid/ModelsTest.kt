package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.domain.*
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun normalizationIgnoresCaseAccentsPunctuationAndAmpersands() {
        assertEquals(normalizeText("Beyoncé"), normalizeText("BEYONCE"))
        assertEquals("simon and garfunkel", normalizeText("Simon & Garfunkel"))
        assertEquals("hey jude", normalizeText("  Hey,   Jude! "))
    }

    @Test fun songKeyIsStableAcrossFormatting() {
        assertEquals(Song("Hey Jude", "The Beatles").key, Song("hey jude!", "the  beatles").key)
        assertNotEquals(Song("Hey Jude", "The Beatles").key, Song("Hey Jude", "Wilson Pickett").key)
    }
}
