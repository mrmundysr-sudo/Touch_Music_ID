package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.LrcDoc
import com.mundymorning.touchmusicid.lyricsproto.LrcLine
import com.mundymorning.touchmusicid.lyricsproto.LrcParser
import com.mundymorning.touchmusicid.lyricsproto.LyricsSanity
import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun parsesEveryCommonTimestampFormatAndSorts() {
        val d = LrcParser.parse("[00:12.34]Hello\n[01:02]World\n[00:05.5]Half\n[00:07:25]Colon\n[00:01.123]Milli\n[00:03.05]Tens")
        assertEquals(listOf(1123L, 3050L, 5500L, 7250L, 12340L, 62000L), d.lines.map { it.timeMs })
        assertEquals(listOf("Milli", "Tens", "Half", "Colon", "Hello", "World"), d.lines.map { it.text })
    }

    @Test fun oneLineWithSeveralTimestampsBecomesSeveralLinesInOrder() {
        val d = LrcParser.parse("[00:10.00][00:50.00]Chorus line\n[00:30.00]Verse")
        assertEquals(listOf(10_000L, 30_000L, 50_000L), d.lines.map { it.timeMs })
        assertEquals(listOf("Chorus line", "Verse", "Chorus line"), d.lines.map { it.text })
    }

    @Test fun offsetTagShiftsAndNeverGoesNegative() {
        assertEquals(9_500L, LrcParser.parse("[offset:500]\n[00:10.00]A").lines.single().timeMs)
        assertEquals(10_500L, LrcParser.parse("[offset:-500]\n[00:10.00]A").lines.single().timeMs)
        assertEquals(0L, LrcParser.parse("[offset:5000]\n[00:01.00]A").lines.single().timeMs)
        assertEquals(500L, LrcParser.parse("[offset:500]\n[00:10.00]A").offsetMs)
    }

    @Test fun metadataTagsAreKeptSeparately() {
        val d = LrcParser.parse("[ti:Song]\n[ar:Artist]\n[00:01.00]x")
        assertEquals("Song", d.meta["ti"]); assertEquals("Artist", d.meta["ar"]); assertEquals(1, d.lines.size)
    }

    @Test fun enhancedLrcWordTagsAreStripped() {
        assertEquals("Hello", LrcParser.parse("[00:12.00]<00:12.00>Hel<00:12.50>lo").lines.single().text)
    }

    @Test fun emptyLinesAreKeptAsInstrumentalBreaks() {
        assertEquals("", LrcParser.parse("[00:30.00]").lines.single().text)
    }

    @Test fun malformedInputNeverThrowsAndYieldsNothing() {
        for (bad in listOf(null, "", "   ", "garbage text", "[xx:yy]nope", "[99:99.99", "[00:61.00]late")) {
            val d = LrcParser.parse(bad)
            assertTrue("input=$bad", d.lines.isEmpty() || bad == "[00:61.00]late")
        }
    }

    @Test fun activeIndexIsTheLastLineAtOrBeforeThePosition() {
        val lines = listOf(LrcLine(1000, "a"), LrcLine(5000, "b"), LrcLine(9000, "c"))
        assertEquals(-1, LrcParser.findActiveIndex(lines, 0))
        assertEquals(-1, LrcParser.findActiveIndex(lines, 999))
        assertEquals(0, LrcParser.findActiveIndex(lines, 1000))
        assertEquals(0, LrcParser.findActiveIndex(lines, 4999))
        assertEquals(1, LrcParser.findActiveIndex(lines, 5000))
        assertEquals(2, LrcParser.findActiveIndex(lines, 9000))
        assertEquals(2, LrcParser.findActiveIndex(lines, 10_000_000))
        assertEquals(-1, LrcParser.findActiveIndex(emptyList(), 5000))
    }

    @Test fun repeatedChorusTimestampsStillGiveTheRightLineForAMidSongPosition() {
        val d = LrcParser.parse("[00:20.00]Chorus\n[00:40.00]Verse 2\n[01:00.00]Chorus")
        assertEquals(0, LrcParser.findActiveIndex(d.lines, 25_000))
        assertEquals(2, LrcParser.findActiveIndex(d.lines, 65_000))
    }

    @Test fun syncedLyricsThatRunPastTheTrackEndAreNotTrusted() {
        val doc = LrcDoc(listOf(LrcLine(1000, "a"), LrcLine(200_000, "b")))
        assertTrue(LyricsSanity.syncedLooksPlausible(doc, 205_000))
        assertTrue("small overrun tolerated", LyricsSanity.syncedLooksPlausible(doc, 196_000))
        assertFalse("clearly a different (shorter) recording", LyricsSanity.syncedLooksPlausible(doc, 120_000))
        assertTrue("unknown duration gets the benefit of the doubt", LyricsSanity.syncedLooksPlausible(doc, null))
        assertFalse(LyricsSanity.syncedLooksPlausible(LrcDoc(emptyList()), 100_000))
    }
}
