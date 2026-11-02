package com.mundymorning.touchmusicid.recognition

import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/** One contiguous block of mono PCM16 audio. [timestampMs] is where in the stream it starts (sample-count based, so gap-free). */
class AudioChunk(val data: ByteArray, val length: Int, val timestampMs: Long)

/** Microphone capture boundary. Collecting starts the mic; cancelling the collector MUST stop and release it. */
interface AudioCapture {
    val sampleRateHz: Int
    val readBufferSize: Int
    fun chunks(): Flow<AudioChunk>
}

/** Peak absolute sample (0..32768) of little-endian PCM16 in [data] up to [length] bytes. Used to detect silence. */
fun peakAmplitude(data: ByteArray, length: Int): Int {
    var peak = 0
    var i = 0
    while (i + 1 < length) {
        val s = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
        val a = abs(s.toShort().toInt())
        if (a > peak) peak = a
        i += 2
    }
    return peak
}
