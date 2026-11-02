package com.mundymorning.touchmusicid

import com.mundymorning.touchmusicid.lyricsproto.CsvLog
import com.mundymorning.touchmusicid.lyricsproto.LabAudioCapture
import com.mundymorning.touchmusicid.lyricsproto.LabFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class LabUtilitiesTest {
    @Test fun csvRowsAreQuotedAndKeepValuesOnOneLine() {
        val line = CsvLog.format("FIX", 1234.5678, 1_700_000_000_000L, linkedMapOf("title" to "A, \"B\"; C", "n" to 3, "x" to null))
        assertEquals("FIX,1234.6,1700000000000,\"title=A, \"\"B\"\", C;n=3;x=null\"", line)
        assertFalse(CsvLog.format("E", 0.0, 0, mapOf("m" to "a\nb")).contains('\n'))
    }

    @Test fun csvWriterGetsAHeaderAndRowsAndANullWriterIsHarmless() {
        val sw = StringWriter()
        val log = CsvLog(sw)
        log.row("TICK", 10.0, 5L, mapOf("pos" to 1))
        log.flush()
        assertEquals(listOf(CsvLog.HEADER, "TICK,10.0,5,\"pos=1\""), sw.toString().trim().lines())
        CsvLog(null).row("X", 0.0, 0, emptyMap())
    }

    @Test fun positionIsFormattedWithMilliseconds() {
        assertEquals("1:25.301", LabFormat.position(85_301))
        assertEquals("0:00.000", LabFormat.position(0))
        assertEquals("12:03.050", LabFormat.position(723_050))
        assertEquals("--:--.---", LabFormat.position(-1))
    }

    @Test fun audioLevelsOfSilenceAndOfAFullScaleSquareWave() {
        val silence = ByteArray(960)
        assertEquals(0.0 to 0, LabAudioCapture.levels(silence, silence.size))
        val loud = ByteArray(960)
        for (i in loud.indices step 2) { loud[i] = 0x00; loud[i + 1] = 0x40 } // +16384
        val (rms, peak) = LabAudioCapture.levels(loud, loud.size)
        assertEquals(0.5, rms, 1e-6); assertEquals(16384, peak)
        assertEquals(0.0 to 0, LabAudioCapture.levels(ByteArray(0), 0))
    }

    @Test fun chunkSizeIsTimeBased() {
        assertEquals(9_600, LabAudioCapture(48_000, 100).chunkBytes)
        assertEquals(4_800, LabAudioCapture(24_000, 100).chunkBytes)
    }
}
