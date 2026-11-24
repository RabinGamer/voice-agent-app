package com.example.voiceagent.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock

data class Recording(
    val samples: FloatArray,
    val durationMs: Long,
)

/**
 * Records 16 kHz mono PCM16 while the button is held and converts the result to
 * the float32 samples Whistle expects.
 */
class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    private var startedAt = 0L

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission") // Checked by the caller before start().
    fun start() {
        check(audioRecord == null) { "Already recording" }

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            throw IllegalStateException("The microphone does not support 16 kHz mono audio")
        }

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, SAMPLE_RATE))
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Could not open the microphone")
        }

        synchronized(chunks) { chunks.clear() }
        audioRecord = record
        startedAt = SystemClock.elapsedRealtime()
        running = true

        try {
            record.startRecording()
        } catch (failure: RuntimeException) {
            running = false
            record.release()
            audioRecord = null
            throw failure
        }

        worker = Thread({
            val buffer = ShortArray(SAMPLE_RATE / 10)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    synchronized(chunks) { chunks.add(chunk) }
                } else if (read < 0) {
                    Thread.sleep(20)
                }
                if (SystemClock.elapsedRealtime() - startedAt >= MAX_DURATION_MS) {
                    running = false
                }
            }
        }, "voice-agent-recorder").also { it.start() }
    }

    /** Stops the recorder and returns null when the clip is too short to use. */
    fun stop(): Recording? {
        running = false
        worker?.join(1_500L)
        worker = null

        val record = audioRecord ?: return null
        audioRecord = null
        try {
            record.stop()
        } catch (_: IllegalStateException) {
            // Already stopped because of the duration cap.
        } finally {
            record.release()
        }

        val captured = synchronized(chunks) { chunks.toList() }
        val totalSamples = captured.sumOf { it.size }
        if (totalSamples < MIN_SAMPLES) return null

        val samples = FloatArray(totalSamples)
        var index = 0
        for (chunk in captured) {
            for (sample in chunk) {
                samples[index++] = sample / 32768f
            }
        }
        return Recording(
            samples = samples,
            durationMs = totalSamples * 1000L / SAMPLE_RATE,
        )
    }

    fun cancel() {
        stop()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 30_000L
        const val MIN_SAMPLES = 4_800 // 0.3 s at 16 kHz
    }
}
