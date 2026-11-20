package com.vasmarfas.UniversalAmbientLight.common

import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.effect.AudioSpectrum

/**
 * Звук самого устройства для цветомузыки без микрофона: Visualizer на общем выходе
 * (сессия 0) отдаёт то, что сейчас играет любое приложение. Звук, который идёт мимо
 * микшера Android, - битстрим Dolby/DTS на ресивер, туннельное воспроизведение - сюда не
 * попадает, и лента тогда молчит.
 */
class OutputSpectrum(sensitivity: Int) : SoundSpectrum {

    @Volatile
    private var mSensitivity = sensitivity

    @Volatile
    private var mRunning = false

    @Volatile
    private var mSpectrum: AudioSpectrum? = null

    private var mVisualizer: Visualizer? = null
    private var mThread: Thread? = null

    override var sensitivity: Int
        get() = mSensitivity
        set(value) {
            mSensitivity = value
            mSpectrum?.sensitivity = value
        }

    override fun start(): Boolean {
        if (mRunning) return true
        val visualizer = try {
            Visualizer(0)
        } catch (e: RuntimeException) {
            // Конструктор бросает UnsupportedOperationException или голый RuntimeException
            // с кодом движка, когда прошивка не отдаёт общий выход приложениям
            Log.w(TAG, "Output mix visualizer is unavailable", e)
            return false
        }
        val size = Visualizer.getCaptureSizeRange()[1].coerceAtMost(MAX_CAPTURE_SIZE)
        if (visualizer.setCaptureSize(size) != Visualizer.SUCCESS ||
            visualizer.setEnabled(true) != Visualizer.SUCCESS
        ) {
            Log.w(TAG, "Visualizer refused to start")
            visualizer.release()
            return false
        }
        val sampleRate = visualizer.samplingRate / 1000
        val spectrum = AudioSpectrum(sampleRate, size).also { it.sensitivity = mSensitivity }
        mVisualizer = visualizer
        mSpectrum = spectrum
        mRunning = true
        // Visualizer отдаёт последние отсчёты выхода, а не поток: опрашиваем с длиной блока,
        // чтобы постоянные времени AudioSpectrum совпали с настоящими
        val intervalMs = 1000L * size / sampleRate
        mThread = Thread({ read(visualizer, spectrum, size, intervalMs) }, "music-output")
            .apply { start() }
        return true
    }

    override fun stop() {
        mRunning = false
        val visualizer = mVisualizer ?: return
        mVisualizer = null
        mThread?.join(STOP_TIMEOUT_MS)
        mThread = null
        visualizer.release()
    }

    override fun levels(out: FloatArray) {
        val spectrum = mSpectrum ?: return
        synchronized(spectrum) { spectrum.levels.copyInto(out) }
    }

    private fun read(visualizer: Visualizer, spectrum: AudioSpectrum, size: Int, intervalMs: Long) {
        val wave = ByteArray(size)
        val block = ShortArray(size)
        while (mRunning) {
            val status = try {
                visualizer.getWaveForm(wave)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Visualizer stopped delivering data", e)
                synchronized(spectrum) { spectrum.levels.fill(0f) }
                return
            }
            if (status == Visualizer.SUCCESS) {
                // 8 бит без знака с серединой 128 переводим в 16 бит со знаком, как у AudioRecord
                for (i in 0 until size) {
                    block[i] = (((wave[i].toInt() and 0xFF) - 128) shl 8).toShort()
                }
                synchronized(spectrum) { spectrum.process(block) }
            }
            SystemClock.sleep(intervalMs)
        }
    }

    companion object {
        private const val TAG = "OutputSpectrum"
        private const val MAX_CAPTURE_SIZE = 1024
        private const val STOP_TIMEOUT_MS = 500L
    }
}
