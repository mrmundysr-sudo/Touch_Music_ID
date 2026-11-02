package com.mundymorning.touchmusicid.lyricsproto

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.abs
import kotlin.math.sqrt

class LabAudioException(message: String) : RuntimeException(message)

/**
 * Prototype-only microphone capture (the production AudioRecordCapture is untouched).
 * Differences: 100 ms chunks for finer timing, and the time of the first frame is taken from AudioRecord.getTimestamp()
 * (when the frame was actually captured) rather than from when a read returned, so capture latency is not baked in.
 * Audio is only ever held in the current chunk. Nothing is written to disk.
 */
class LabAudioCapture(val sampleRate: Int = 48_000, val chunkMs: Int = 100) {
    val chunkBytes: Int = sampleRate * chunkMs / 1000 * 2

    class Chunk(
        val data: ByteArray,
        val len: Int,
        val streamStartMs: Long,
        val rms: Double,
        val peak: Int,
        val readMonoMs: Double,
        /** Frame-0 time implied by AudioTimestamp at this chunk, for drift analysis; null if the device gave none. */
        val measuredFrame0MonoMs: Double?,
    )

    @SuppressLint("MissingPermission")
    fun chunks(onStarted: (CaptureTimeline) -> Unit): Flow<Chunk> = flow {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw LabAudioException("This device can't record at $sampleRate Hz")
        val record = open(minBuf) ?: throw LabAudioException("The microphone is busy or unavailable")
        try {
            try { record.startRecording() } catch (e: IllegalStateException) { throw LabAudioException("Couldn't start the microphone") }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw LabAudioException("The microphone is in use by another app")
            val ts = AudioTimestamp()
            var framesRead = 0L
            var timeline: CaptureTimeline? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val data = ByteArray(chunkBytes) // a fresh array per chunk: the SDK may keep a reference
                var n = 0
                while (n < chunkBytes) {
                    val r = record.read(data, n, chunkBytes - n)
                    if (r < 0) throw LabAudioException("Microphone read failed (code $r)")
                    n += r
                }
                val readMono = System.nanoTime() / 1e6
                val frames = n / 2
                val measured: Double? =
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        ts.nanoTime / 1e6 - ts.framePosition * 1000.0 / sampleRate
                    } else null
                if (timeline == null) {
                    val f0 = measured ?: (readMono - frames * 1000.0 / sampleRate)
                    val epochMinusMono = System.currentTimeMillis() - System.nanoTime() / 1e6
                    timeline = CaptureTimeline((f0 + epochMinusMono).toLong(), f0, if (measured != null) "AudioTimestamp" else "estimated-from-read")
                    onStarted(timeline)
                }
                val streamStart = framesRead * 1000 / sampleRate
                framesRead += frames
                val (rms, peak) = levels(data, n)
                emit(Chunk(data, n, streamStart, rms, peak, readMono, measured))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private fun open(minBuf: Int): AudioRecord? {
        val format = AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf, chunkBytes) * 2).build()
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }

    companion object {
        /** RMS (0..1 of full scale) and peak (0..32768) of little-endian PCM16. */
        fun levels(data: ByteArray, len: Int): Pair<Double, Int> {
            var sum = 0.0
            var peak = 0
            var count = 0
            var i = 0
            while (i + 1 < len) {
                val s = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                val a = abs(s)
                if (a > peak) peak = a
                sum += s.toDouble() * s
                count++
                i += 2
            }
            return if (count == 0) 0.0 to 0 else sqrt(sum / count) / 32768.0 to peak
        }
    }
}
