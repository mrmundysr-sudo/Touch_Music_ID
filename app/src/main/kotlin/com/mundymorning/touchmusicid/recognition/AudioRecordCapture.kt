package com.mundymorning.touchmusicid.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Captures mono PCM16 from the microphone. Audio lives only in the chunk handed downstream; nothing is written to disk.
 * The AudioRecord is released in `finally`, so cancel / timeout / match / error all free the microphone.
 * Caller must hold RECORD_AUDIO (the controller checks before starting).
 */
class AudioRecordCapture(override val sampleRateHz: Int = 48_000) : AudioCapture {
    private val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    /** ~250 ms of audio per read, never below the platform minimum. */
    override val readBufferSize: Int = maxOf(minBuffer, sampleRateHz / 4 * 2)

    @SuppressLint("MissingPermission")
    override fun chunks(): Flow<AudioChunk> = flow {
        if (minBuffer <= 0) throw AudioUnavailable("This device can't record audio at $sampleRateHz Hz.")
        val record = open() ?: throw AudioUnavailable("The microphone is busy or unavailable. Close other apps using it and try again.")
        try {
            try { record.startRecording() } catch (e: IllegalStateException) { throw AudioUnavailable("Couldn't start the microphone.") }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioUnavailable("The microphone is in use by another app.")
            var samples = 0L
            val bytesPerMs = sampleRateHz * 2 / 1000
            while (true) {
                currentCoroutineContext().ensureActive()
                val buf = ByteArray(readBufferSize)
                val n = record.read(buf, 0, buf.size)
                if (n < 0) throw AudioUnavailable("Microphone read failed (code $n).")
                if (n == 0) continue
                emit(AudioChunk(buf, n, samples * 1000 / sampleRateHz))
                samples += n / 2
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    /** UNPROCESSED gives the cleanest signal for fingerprinting but is not supported everywhere; fall back to MIC. */
    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val format = AudioFormat.Builder().setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        for (source in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord.Builder().setAudioSource(source).setAudioFormat(format).setBufferSizeInBytes(readBufferSize * 2).build()
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }
}

class AudioUnavailable(message: String) : RuntimeException(message)
