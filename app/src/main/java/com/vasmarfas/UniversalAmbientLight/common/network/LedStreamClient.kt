package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Основа для контроллеров, которым уходят цвета светодиодов по порядку: E1.31, Art-Net,
 * tpm2.net, OPC и прочие. Разбор кадра по раскладке, сглаживание с задержкой, повтор кадра
 * в тишине и пауза на время сна ТВ у всех одинаковые; наследник только открывает своё
 * соединение и упаковывает цвета в пакеты своего протокола.
 *
 * Повтор нужен и неподвижной картинке: приёмники E1.31 и Art-Net через 2,5 секунды
 * тишины считают источник пропавшим и возвращаются к своему эффекту.
 */
abstract class LedStreamClient(
    private val mContext: Context,
    smoothing: SmoothingSettings,
) : HyperionClient, StreamingClient {

    /** Сглаживание из настроек; применяется одинаково для всех протоколов. */
    data class SmoothingSettings(
        val enabled: Boolean,
        val preset: String,
        val settlingTime: Int,
        val outputDelayMs: Long,
        val updateFrequency: Int,
    )

    private val mSmoothing = ColorSmoothing { leds -> deliver(leds) }
    private var mLedBuffer: Array<ColorRgb>? = null
    private val mKeepAlive = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "${javaClass.simpleName}-keepalive").apply { isDaemon = true }
    }

    @Volatile
    private var mLastLeds: Array<ColorRgb>? = null

    @Volatile
    private var mLastSendMs = 0L

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    private var mLastErrorLogMs = 0L

    /**
     * Обрыв соединения делает клиент неподключённым, и поток вывода пересоздаёт его. Для
     * TCP так и надо, а UDP ошибается пачками во сне ТВ и сам оживает после пробуждения.
     */
    protected open val reconnectOnError = false

    init {
        // Пресет задаёт основу, значения из настроек её перекрывают, как у WLED и Adalight
        mSmoothing.applyPreset(smoothing.preset)
        mSmoothing.setSettlingTime(smoothing.settlingTime)
        mSmoothing.setOutputDelay(smoothing.outputDelayMs)
        mSmoothing.setUpdateFrequency(smoothing.updateFrequency)
        mSmoothing.setEnabled(smoothing.enabled)
    }

    /** Открывает соединение; зовёт наследник в конце своего конструктора. */
    @Throws(IOException::class)
    protected fun start() {
        open()
        mConnected = true
        mSmoothing.start()
        mKeepAlive.scheduleWithFixedDelay({
            val last = mLastLeds ?: return@scheduleWithFixedDelay
            if (mPaused || !mConnected) return@scheduleWithFixedDelay
            if (System.currentTimeMillis() - mLastSendMs < KEEPALIVE_MS) return@scheduleWithFixedDelay
            deliver(Array(last.size) { last[it].clone() })
        }, KEEPALIVE_MS, KEEPALIVE_MS / 2, TimeUnit.MILLISECONDS)
    }

    @Throws(IOException::class)
    protected abstract fun open()

    @Throws(IOException::class)
    protected abstract fun write(leds: Array<ColorRgb>)

    protected abstract fun close()

    override fun isConnected(): Boolean = mConnected

    override fun pauseSending() {
        mPaused = true
        mSmoothing.stop()
    }

    override fun resumeSending() {
        mPaused = false
    }

    override val delaysOutput = true

    override fun setOutputDelay(ms: Long) {
        mSmoothing.setOutputDelay(ms)
    }

    override fun setSmoothingEnabled(enabled: Boolean) {
        mSmoothing.setEnabled(enabled)
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()
        mKeepAlive.shutdownNow()
        // Последний кадр чёрный и мимо сглаживания: оно уже остановлено, а лента не должна
        // остаться гореть последним цветом до таймаута приёмника
        if (mConnected) {
            try {
                write(Array(LedDataExtractor.getLedCount(mContext)) { ColorRgb(0, 0, 0) })
            } catch (e: IOException) {
                Log.w(TAG, "Final black frame failed: ${e.message}")
            }
        }
        mConnected = false
        close()
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        mSmoothing.setTargetColors(Array(LedDataExtractor.getLedCount(mContext)) { ColorRgb(0, 0, 0) })
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(0)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        val count = LedDataExtractor.getLedCount(mContext)
        mSmoothing.setTargetColors(Array(count) {
            ColorRgb((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        })
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!mConnected) throw IOException("Not connected")
        val leds = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedBuffer)
        mLedBuffer = leds
        if (leds.isNotEmpty()) mSmoothing.setTargetColors(leds)
    }

    private fun deliver(leds: Array<ColorRgb>) {
        if (!mConnected || mPaused) return
        mLastLeds = leds
        mLastSendMs = System.currentTimeMillis()
        try {
            write(leds)
        } catch (e: IOException) {
            if (reconnectOnError) mConnected = false
            // Сеть ТВ во сне и на пробуждении отваливается пачками ошибок, а кадры идут
            // десятками в секунду: в лог - не чаще раза в пять секунд
            val now = System.currentTimeMillis()
            if (now - mLastErrorLogMs > ERROR_LOG_INTERVAL_MS) {
                mLastErrorLogMs = now
                Log.w(TAG, "${javaClass.simpleName} send failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "LedStreamClient"
        private const val KEEPALIVE_MS = 1000L
        private const val ERROR_LOG_INTERVAL_MS = 5000L
    }
}
