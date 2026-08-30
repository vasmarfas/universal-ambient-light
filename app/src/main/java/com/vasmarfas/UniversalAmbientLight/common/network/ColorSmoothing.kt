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
        private const val DEFAULT_UPDATE_FREQUENCY_HZ = 60
        private const val DEFAULT_SETTLING_TIME_MS = 50
        private const val DEFAULT_OUTPUT_DELAY_MS = 0L
        private const val MIN_UPDATE_INTERVAL_MS = 1L

        // Пауза между повторами сошедшегося кадра. Совсем замолчать нельзя: часть прошивок
        // по таймауту тишины уходит в собственный эффект, у WLED это staytime ~10 секунд.
        private const val IDLE_RESEND_MS = 1000L
    }

    // Конфигурация
    private var mUpdateFrequencyHz = DEFAULT_UPDATE_FREQUENCY_HZ
    private var mSettlingTimeMs = DEFAULT_SETTLING_TIME_MS

    // Меняется на ходу из потока настроек, читается циклом отправки
    @Volatile
    private var mOutputDelayMs: Long = DEFAULT_OUTPUT_DELAY_MS
    private var mEnabled = true

    // Состояние
    private var mPreviousValues: Array<ColorRgb>? = null
    private var mTargetValues: Array<ColorRgb>? = null
    private var mTargetTime: Long = 0

    // Кадр после схождения интерполяции уже отправлен: дальше цикл шлёт только редкие
    // повторы. Гнать одинаковые кадры на полной частоте нельзя — сплошной поток без пауз
    // переполняет приёмник Adalight, протокол рассинхронизируется, и лента мигает даже
    // на статичной картинке (issue #42).
    private var mIdleFrameSent = false

    @Volatile
    private var mLastSendMs = 0L

    // Очередь вывода (Output Delay) - хранит пары (время добавления, кадр)
    private data class TimedFrame(val timestamp: Long, val colors: Array<ColorRgb>)

    private val mOutputQueue = ArrayDeque<TimedFrame>()

    // Таймер
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: Handler? = null

    @Volatile
    private var mRunning = false

    // Поколение цикла: доигрывающий тик остановленного цикла иначе перепощивал бы себя в
    // очередь нового HandlerThread'а параллельно с постом из start() — две цепочки тиков
    // дают двойную частоту отправки навсегда
    @Volatile
    private var mGeneration = 0

    // Отслеживание времени последнего обновления (дебаунсинг)
    private var mLastUpdateTime: Long = 0

    // Интерфейс для отправки данных
    fun interface LedDataSender {
        fun sendLedData(colors: Array<ColorRgb>)
    }

    private val mUpdateRunnable = object : Runnable {
        override fun run() {
            val generation = mGeneration
            if (!mRunning || !needsTimer()) return

            updateLeds()

            if (mRunning && generation == mGeneration && mHandler != null) {
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
            mTargetTime = now + if (mEnabled) mSettlingTimeMs else 0
            mIdleFrameSent = false

            // Инициализация при первом вызове или изменении размера
            val current = mTargetValues
            if (current == null || current.size != targetColors.size) {
                val targets = Array(targetColors.size) { ColorRgb(0, 0, 0) }
                val previous = Array(targetColors.size) { ColorRgb(0, 0, 0) }
                mTargetValues = targets
                mPreviousValues = previous

                // Копируем начальное состояние
                for (i in targetColors.indices) {
                    targets[i].set(targetColors[i])
                    previous[i].set(targetColors[i])
                }

                if (needsTimer()) {
                    start()
                }
            } else {
                // Обновление без мусора: копируем значения на месте
                for (i in targetColors.indices) {
                    current[i].set(targetColors[i])
                }
            }
        }

        // Ни сглаживания, ни задержки - отправляем данные напрямую (клонируем для безопасности)
        if (!needsTimer()) {
            val colorsCopy = Array(targetColors.size) { i -> targetColors[i].clone() }
            sendToDevice(colorsCopy)
        }
    }

    private fun updateLeds() {
        val colorsToSend: Array<ColorRgb>?

        synchronized(this) {
            colorsToSend = interpolateFrameLinear()
        }

        if (colorsToSend != null) {
            queueColors(colorsToSend)
        } else if (mOutputDelayMs != 0L) {
            // Новый кадр в паузе между повторами не добавился, но хвост очереди задержки
            // обязан дойти до ленты и без него
            drainOutputQueue(System.currentTimeMillis())
        }
    }

    private fun interpolateFrameLinear(): Array<ColorRgb>? {
        val targets = mTargetValues ?: return null
        val previous = mPreviousValues ?: return null
        val now = System.currentTimeMillis()
        val deltaTime = mTargetTime - now

        if (deltaTime <= 0) {
            // Время истекло, использовать целевые значения
            // Обновляем mPreviousValues на месте
            for (i in targets.indices) previous[i].set(targets[i])

            // Цель достигнута — дальше только редкие повторы (см. mIdleFrameSent)
            if (mIdleFrameSent && now - mLastSendMs < IDLE_RESEND_MS) return null
            mIdleFrameSent = true

            if (mOutputDelayMs == 0L) return previous

            // Клонируем только если кадр уходит в очередь
            return Array(previous.size) { i -> previous[i].clone() }
        }

        // Линейная интерполяция
        var k = 1.0f - deltaTime.toFloat() / mSettlingTimeMs
        k = max(0.0f, min(1.0f, k))

        val length = min(previous.size, targets.size)
        for (i in 0 until length) {
            val rDiff = targets[i].red - previous[i].red
            val gDiff = targets[i].green - previous[i].green
            val bDiff = targets[i].blue - previous[i].blue

            val r = max(0, min(255, previous[i].red + (k * rDiff).roundToInt()))
            val g = max(0, min(255, previous[i].green + (k * gDiff).roundToInt()))
            val b = max(0, min(255, previous[i].blue + (k * bDiff).roundToInt()))

            previous[i].set(r, g, b)
        }

        if (mOutputDelayMs == 0L) return previous

        return Array(previous.size) { i -> previous[i].clone() }
    }

    private fun queueColors(ledColors: Array<ColorRgb>) {
        if (mOutputDelayMs == 0L) {
            sendToDevice(ledColors)
        } else {
            val now = System.currentTimeMillis()
            synchronized(mOutputQueue) {
                mOutputQueue.addLast(TimedFrame(now, ledColors))
            }
            drainOutputQueue(now)
        }
    }

    /**
     * Отдаёт все кадры, отлежавшие свою задержку. Под блокировкой только работа с очередью.
     * Отправка — снаружи: внутри неё блокирующая запись в порт или сокет, а этот же монитор
     * берёт stop() с главного потока при засыпании ТВ — держать его на время I/O нельзя.
     */
    private fun drainOutputQueue(now: Long) {
        val ready = ArrayList<TimedFrame>(2)
        synchronized(mOutputQueue) {
            while (mOutputQueue.isNotEmpty()) {
                val oldestFrame = mOutputQueue.first
                if (now - oldestFrame.timestamp >= mOutputDelayMs) {
                    ready.add(mOutputQueue.removeFirst())
                } else {
                    break
                }
            }
        }
        for (frame in ready) {
            sendToDevice(frame.colors)
        }
    }

    private fun sendToDevice(colors: Array<ColorRgb>) {
        mLastSendMs = System.currentTimeMillis()
        mDataSender?.sendLedData(colors)
    }

    // start/stop синхронизированы: start() зовут и поток отправки кадров, и поток
    // переподключения WLED — без блокировки два одновременных вызова создали бы по
    // HandlerThread, и первый из них остался бы жить навсегда (stop() видит только
    // последний mHandler).
    @Synchronized
    fun start() {
        if (mRunning) return

        mGeneration++
        val thread = HandlerThread("ColorSmoothing", Process.THREAD_PRIORITY_BACKGROUND)
        mHandlerThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        mHandler = handler

        mRunning = true
        val intervalMs = 1000L / mUpdateFrequencyHz
        handler.postDelayed(mUpdateRunnable, intervalMs)
    }

    @Synchronized
    fun stop() {
        mRunning = false
        mGeneration++
        mHandler?.removeCallbacksAndMessages(null)
        mHandler = null
        mHandlerThread?.quitSafely()
        mHandlerThread = null
        synchronized(mOutputQueue) {
            mOutputQueue.clear()
        }
        mPreviousValues = null
        mTargetValues = null
        mIdleFrameSent = false
    }

    fun setSettlingTime(ms: Int) {
        mSettlingTimeMs = max(0, min(1000, ms))
    }

    fun setOutputDelay(ms: Long) {
        val neededBefore = needsTimer()
        mOutputDelayMs = max(0L, min(1000L, ms))
        // Без задержки очередь больше не разбирается, и застрявшие в ней кадры вспыхнули бы
        // разом при следующем включении задержки
        if (mOutputDelayMs == 0L) synchronized(mOutputQueue) { mOutputQueue.clear() }
        onTimerNeedChanged(neededBefore)
    }

    fun setUpdateFrequency(hz: Int) {
        // Перепощивать runnable отсюда нельзя: auto-throttle Adalight зовёт этот метод из
        // самого цикла отправки, removeCallbacksAndMessages выполняющийся runnable не
        // снимает, и в очереди оказались бы две цепочки тиков — двойная частота навсегда.
        // Цикл сам читает mUpdateFrequencyHz на каждом тике и подхватит новое значение.
        mUpdateFrequencyHz = max(1, min(60, hz))
    }

    fun setEnabled(enabled: Boolean) {
        val neededBefore = needsTimer()
        mEnabled = enabled
        onTimerNeedChanged(neededBefore)
    }

    /**
     * Цикл с таймером нужен не только сглаживанию: задержка вывода держит кадры в очереди,
     * и без цикла выключенное сглаживание отправляло бы их сразу, молча игнорируя задержку.
     * Без интерполяции цикл просто отдаёт цель на первом же тике.
     */
    private fun needsTimer(): Boolean = mEnabled || mOutputDelayMs > 0

    private fun onTimerNeedChanged(neededBefore: Boolean) {
        val needed = needsTimer()
        if (neededBefore && !needed && mRunning) {
            stop()
        } else if (needed && !neededBefore && mTargetValues != null && !mRunning) {
            start()
        }
    }

    fun isEnabled(): Boolean {
        return mEnabled
    }

    /**
     * Применяет пресет сглаживания
     * @param preset «off», «responsive», «balanced» или «smooth»
     */
    fun applyPreset(preset: String) {
        when (preset.lowercase()) {
            "off" -> {
                mEnabled = false
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "responsive" -> {
                mEnabled = true
                mSettlingTimeMs = 50
                mOutputDelayMs = 0L
                mUpdateFrequencyHz = 60
            }

            "balanced" -> {
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }

            "smooth" -> {
                mEnabled = true
                mSettlingTimeMs = 500
                mOutputDelayMs = 200L // ~5 кадров при 25 FPS
                mUpdateFrequencyHz = 20
            }

            else -> {
                // По умолчанию "balanced"
                mEnabled = true
                mSettlingTimeMs = 200
                mOutputDelayMs = 80L // ~2 кадра при 25 FPS
                mUpdateFrequencyHz = 25
            }
        }
        // Выключенный пресет должен погасить и цикл: иначе mRunning остаётся true, и
        // последующий setEnabled(true) не смог бы его перезапустить.
        if (!needsTimer() && mRunning) stop()
        // Новый интервал цикл подхватит сам на следующем тике (см. setUpdateFrequency).
    }
}
