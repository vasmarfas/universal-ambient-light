package com.vasmarfas.UniversalAmbientLight.common

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectConfig
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectRenderer
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor

/**
 * Источник кадров без захвата экрана: эффект рисуется здесь же. Кадры идут на ленту тем
 * же путём, что и снимки экрана, поэтому раскладка, цветокоррекция и все протоколы
 * работают как есть, а запуск не требует ни согласия на запись экрана, ни ADB.
 */
class EffectEncoder(
    private val mListener: HyperionThread.HyperionThreadListener,
    private val mOptions: AppOptions,
    config: EffectConfig,
) : CaptureBackend {

    @Volatile
    private var mConfig = config

    @Volatile
    private var mRunning = false

    private val mRenderer = EffectRenderer(WIDTH, HEIGHT)
    private val mFrame = ByteArray(WIDTH * HEIGHT * 3)
    private val mThread = HandlerThread(TAG, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val mHandler = Handler(mThread.looper)
    private val mStartMs = SystemClock.elapsedRealtime()

    private val mTick = object : Runnable {
        override fun run() {
            if (!mRunning) return
            val config = mConfig
            mRenderer.render(config, SystemClock.elapsedRealtime() - mStartMs, mFrame)
            ColorProcessor.processRgbData(mFrame, mOptions)
            mListener.sendFrame(mFrame, WIDTH, HEIGHT)
            // Неподвижный эффект перерисовывать незачем, но и замолкать нельзя: без кадров
            // WLED через несколько секунд возвращается к собственному эффекту
            val interval = if (config.effect.animated) FRAME_INTERVAL_MS else STATIC_INTERVAL_MS
            mHandler.postDelayed(this, interval)
        }
    }

    init {
        mRunning = true
        mHandler.post(mTick)
    }

    fun setConfig(config: EffectConfig) {
        mConfig = config
        // Неподвижный эффект иначе показал бы новый цвет только через секунду
        mHandler.removeCallbacks(mTick)
        if (mRunning) mHandler.post(mTick)
    }

    /** Экран ТВ погас: эффект гаснет вместе с ним и ждёт [resumeRecording]. */
    fun pause() {
        mRunning = false
        mHandler.removeCallbacks(mTick)
        clearLights()
    }

    override fun isCapturing(): Boolean = mRunning

    override fun sendStatus() {
        mListener.sendStatus(mRunning)
    }

    override fun clearLights() {
        sendBlack(disconnect = false)
    }

    override fun stopRecording() {
        mRunning = false
        mHandler.removeCallbacks(mTick)
        mThread.quitSafely()
        sendBlack(disconnect = true)
    }

    override fun resumeRecording() {
        if (mRunning || !mThread.isAlive) return
        mRunning = true
        mHandler.post(mTick)
    }

    override fun setOrientation(orientation: Int) {
    }

    private fun sendBlack(disconnect: Boolean) {
        Thread({
            repeat(CLEAR_FRAMES) {
                SystemClock.sleep(CLEAR_DELAY_MS)
                mListener.clear()
            }
            if (disconnect) mListener.disconnect()
        }, "effect-clear").start()
    }

    companion object {
        private const val TAG = "EffectEncoder"

        // Кадр той же пропорции, что экран ТВ; по ширине с запасом на сотню светодиодов
        // вдоль верхней стороны
        const val WIDTH = 128
        const val HEIGHT = 72

        private const val FRAME_INTERVAL_MS = 33L
        private const val STATIC_INTERVAL_MS = 1000L
        private const val CLEAR_DELAY_MS = 100L
        private const val CLEAR_FRAMES = 5
    }
}
