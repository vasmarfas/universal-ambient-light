package com.vasmarfas.UniversalAmbientLight.common

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.effect.AudioSpectrum

/**
 * Микрофон для цветомузыки: читает PCM в своём потоке и раскладывает его на полосы. Звук
 * не сохраняется: от блока после разбора остаются только уровни полос.
 */
class MicrophoneSpectrum(sensitivity: Int) : SoundSpectrum {

    private val mSpectrum = AudioSpectrum(SAMPLE_RATE, BLOCK_SIZE).also {
        it.sensitivity = sensitivity
    }

    private var mRecord: AudioRecord? = null
    private var mThread: Thread? = null

    @Volatile
    private var mRunning = false

    override var sensitivity: Int
        get() = mSpectrum.sensitivity
        set(value) {
            mSpectrum.sensitivity = value
        }

    @SuppressLint("MissingPermission") // разрешение сервис проверяет до запуска
    override fun start(): Boolean {
        if (mRunning) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "No microphone input: getMinBufferSize=$minBuffer")
            return false
        }
        val record = try {
            // VOICE_RECOGNITION по CDD идёт без шумоподавления и автоусиления, которые
            // на источнике MIC у части прошивок давят музыку
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, BLOCK_SIZE * 2 * 4)
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "AudioRecord rejected the format", e)
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "RECORD_AUDIO is not granted", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord is not initialized")
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            record.release()
            return false
        }
        // Занятый микрофон часть прошивок отдаёт без исключения, просто не начиная запись
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Log.w(TAG, "Microphone is busy")
            record.release()
            return false
        }
        mRecord = record
        mRunning = true
        mThread = Thread({ read(record) }, "music-capture").apply { start() }
        return true
    }

    override fun levels(out: FloatArray) {
        synchronized(mSpectrum) { mSpectrum.levels.copyInto(out) }
    }

    override fun stop() {
        mRunning = false
        val record = mRecord ?: return
        mRecord = null
        try {
            // Заодно отпускает блокирующий read в потоке чтения
            record.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        mThread?.join(STOP_TIMEOUT_MS)
        mThread = null
        record.release()
    }

    private fun read(record: AudioRecord) {
        val block = ShortArray(BLOCK_SIZE)
        while (mRunning) {
            var filled = 0
            while (filled < BLOCK_SIZE) {
                val count = record.read(block, filled, BLOCK_SIZE - filled)
                if (count <= 0) {
                    if (mRunning) Log.w(TAG, "AudioRecord.read returned $count")
                    // Без этого лента застыла бы на последних уровнях
                    synchronized(mSpectrum) { mSpectrum.levels.fill(0f) }
                    return
                }
                filled += count
            }
            synchronized(mSpectrum) { mSpectrum.process(block) }
        }
    }

    companion object {
        private const val TAG = "MicrophoneSpectrum"

        // 44,1 кГц - единственная частота, которую обязан поддерживать любой микрофон
        private const val SAMPLE_RATE = 44100

        // 23 мс звука на блок, бин FFT 43 Гц: уровни обновляются чаще, чем уходят кадры ленты
        private const val BLOCK_SIZE = 1024
        private const val STOP_TIMEOUT_MS = 500L
    }
}
