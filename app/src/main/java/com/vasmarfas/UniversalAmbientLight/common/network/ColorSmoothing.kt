package com.vasmarfas.UniversalAmbientLight.common.network

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ColorSmoothing - класс для устранения стробоскопического эффекта при обновлении LED.
 */
class ColorSmoothing(private val mDataSender: LedDataSender?) {

    // Значения по умолчанию
    companion object {
        private const val TAG = "ColorSmoothing"
        private const val DEBUG = false
        private const val DEFAULT_UPDATE_FREQUENCY_HZ = 25
        private const val DEFAULT_SETTLING_TIME_MS = 200
        private const val DEFAULT_OUTPUT_DELAY = 2
        private const val MIN_UPDATE_INTERVAL_MS = 1L
    }

    // Конфигурация
    private var mUpdateFrequencyHz = DEFAULT_UPDATE_FREQUENCY_HZ
    private var mSettlingTimeMs = DEFAULT_SETTLING_TIME_MS
    private var mOutputDelay = DEFAULT_OUTPUT_DELAY
    private var mEnabled = true

    // Состояние
    private var mPreviousValues: Array<ColorRgb>? = null
    private var mTargetValues: Array<ColorRgb>? = null
    private var mTargetTime: Long = 0

    // Очередь вывода (Output Delay)
    private val mOutputQueue = ArrayDeque<Array<ColorRgb>>()

    // Таймер
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: Handler? = null
    @Volatile
    private var mRunning = false

    // Отслеживание времени последнего обновления (дебаунсинг)
    private var mLastUpdateTime: Long = 0

    // Интерфейс для отправки данных
    fun interface LedDataSender {
        fun sendLedData(colors: Array<ColorRgb>)
    }

    private val mUpdateRunnable = object : Runnable {
        override fun run() {
            if (!mRunning || !mEnabled) return

            updateLeds()

            if (mRunning && mHandler != null) {
                val intervalMs = 1000L / mUpdateFrequencyHz
                mHandler?.postDelayed(this, intervalMs)
            }
        }
    }

    fun setTargetColors(targetColors: Array<ColorRgb>?) {
        if (targetColors == null || targetColors.isEmpty()) {
            return
        }

        // Дебаунсинг
        val now = System.currentTimeMillis()
        if (now - mLastUpdateTime < MIN_UPDATE_INTERVAL_MS) {
            return
        }
        mLastUpdateTime = now

        synchronized(this) {
            mTargetTime = now + mSettlingTimeMs

            // Инициализация при первом вызове или изменении размера
            if (mTargetValues == null || mTargetValues!!.size != targetColors.size) {
                mTargetValues = Array(targetColors.size) { ColorRgb(0, 0, 0) }
                mPreviousValues = Array(targetColors.size) { ColorRgb(0, 0, 0) }

                // Copy initial state
                for (i in targetColors.indices) {
                    mTargetValues!![i].set(targetColors[i])
                    mPreviousValues!![i].set(targetColors[i])
                }

                start()
            } else {
                // GC-free update: copy values
                for (i in targetColors.indices) {
                    mTargetValues!![i].set(targetColors[i])
                }
            }
        }
    }

    private fun updateLeds() {
        var colorsToSend: Array<ColorRgb>?

        synchronized(this) {
            if (mTargetValues == null || mPreviousValues == null) {
                return
            }

            colorsToSend = interpolateFrameLinear()
            if (colorsToSend == null) return
        }

        queueColors(colorsToSend!!)
    }

    private fun interpolateFrameLinear(): Array<ColorRgb>? {
        val now = System.currentTimeMillis()
        val deltaTime = mTargetTime - now

        if (deltaTime <= 0) {
            // Время истекло, использовать целевые значения
            // Update mPreviousValues in-place
            for (i in mTargetValues!!.indices) mPreviousValues!![i].set(mTargetValues!![i])

            if (mOutputDelay == 0) return mPreviousValues

            // Clone only if queueing
            return Array(mPreviousValues!!.size) { i -> mPreviousValues!![i].clone() }
        }

        // Линейная интерполяция
        var k = 1.0f - deltaTime.toFloat() / mSettlingTimeMs
        k = max(0.0f, min(1.0f, k))

        val length = min(mPreviousValues!!.size, mTargetValues!!.size)
        for (i in 0 until length) {
            val rDiff = mTargetValues!![i].red - mPreviousValues!![i].red
            val gDiff = mTargetValues!![i].green - mPreviousValues!![i].green
            val bDiff = mTargetValues!![i].blue - mPreviousValues!![i].blue

            val r = max(0, min(255, mPreviousValues!![i].red + (k * rDiff).roundToInt()))
            val g = max(0, min(255, mPreviousValues!![i].green + (k * gDiff).roundToInt()))
            val b = max(0, min(255, mPreviousValues!![i].blue + (k * bDiff).roundToInt()))

            mPreviousValues!![i].set(r, g, b)
        }

        if (mOutputDelay == 0) return mPreviousValues

        return Array(mPreviousValues!!.size) { i -> mPreviousValues!![i].clone() }
    }

    private fun queueColors(ledColors: Array<ColorRgb>) {
        if (mOutputDelay == 0) {
            sendToDevice(ledColors)
        } else {
            synchronized(mOutputQueue) {
                mOutputQueue.addLast(ledColors)
                if (mOutputQueue.size > mOutputDelay) {
                    val frameToSend = mOutputQueue.removeFirst()
                    sendToDevice(frameToSend)
                }
            }
        }
    }

    private fun sendToDevice(colors: Array<ColorRgb>) {
        mDataSender?.sendLedData(colors)
    }

    fun start() {
        if (mRunning) return

        mHandlerThread = HandlerThread("ColorSmoothing", Process.THREAD_PRIORITY_BACKGROUND)
        mHandlerThread!!.start()
        mHandler = Handler(mHandlerThread!!.looper)

        mRunning = true
        val intervalMs = 1000L / mUpdateFrequencyHz
        mHandler!!.postDelayed(mUpdateRunnable, intervalMs)
    }

    fun stop() {
        mRunning = false
        if (mHandler != null) {
            mHandler!!.removeCallbacksAndMessages(null)
            mHandler = null
        }
        if (mHandlerThread != null) {
            mHandlerThread!!.quitSafely()
            mHandlerThread = null
        }
        synchronized(mOutputQueue) {
            mOutputQueue.clear()
        }
        synchronized(this) {
            mPreviousValues = null
            mTargetValues = null
        }
    }

    fun setSettlingTime(ms: Int) {
        mSettlingTimeMs = max(0, min(1000, ms))
    }

    fun setOutputDelay(frames: Int) {
        mOutputDelay = max(0, min(10, frames))
    }

    fun setUpdateFrequency(hz: Int) {
        mUpdateFrequencyHz = max(1, min(60, hz))
    }
}
