package com.vasmarfas.UniversalAmbientLight.common.util

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Считает, насколько подсветка отстаёт от картинки или опережает её, по двум рядам яркости
 * с камеры телефона: экран ТВ и свечение ленты на стене вокруг него. Оба ряда сняты одними
 * и теми же кадрами, поэтому задержка камеры и самого телефона в ответ не попадает.
 *
 * Сравниваются не яркости, а их изменения. Смена сцены в фильме даёт скачок на обоих
 * рядах, и сдвиг, при котором скачки совпадают лучше всего, и есть искомая разница.
 * Свет самого экрана, попавший в зону свечения (блик объектива, отражения комнаты),
 * меняется одновременно с экраном и тянул бы ответ к нулю, поэтому его доля вычитается:
 * её замеряют заранее при погашенной ленте, см. [spill].
 *
 * Зависимостей от Android нет, время приходит снаружи.
 */
class DelayEstimator {

    /** [lagMs] > 0 - лента отстаёт от картинки, < 0 - опережает. */
    class Result(
        val lagMs: Int,
        /** Корреляция изменений при найденном сдвиге, 0..1. */
        val correlation: Float,
        /** Насколько расходятся ответы по трём третям замера - мера доверия. */
        val spreadMs: Int,
        val events: Int,
    )

    private var mTimes = LongArray(INITIAL_CAPACITY)
    private var mScreen = FloatArray(INITIAL_CAPACITY)
    private var mGlow = FloatArray(INITIAL_CAPACITY)

    var size = 0
        private set

    val durationMs: Long
        get() = if (size < 2) 0 else (mTimes[size - 1] - mTimes[0]) / NANOS_PER_MS

    fun add(timeNanos: Long, screen: Float, glow: Float) {
        if (size > 0 && timeNanos <= mTimes[size - 1]) return
        if (size == mTimes.size) {
            mTimes = mTimes.copyOf(size * 2)
            mScreen = mScreen.copyOf(size * 2)
            mGlow = mGlow.copyOf(size * 2)
        }
        mTimes[size] = timeNanos
        mScreen[size] = screen
        mGlow[size] = glow
        size++
    }

    fun clear() {
        size = 0
    }

    /**
     * Сколько заметных смен картинки попало в замер. Порог - от обычного шума кадра к
     * кадру, а соседние скачки ближе [EVENT_GAP_MS] считаются одной сменой сцены.
     */
    fun events(): Int {
        if (size < 3) return 0
        val steps = FloatArray(size - 1) { abs(mScreen[it + 1] - mScreen[it]) }
        val threshold = maxOf(MIN_EVENT_LUMA, median(steps) * EVENT_NOISE_FACTOR)
        var count = 0
        var lastEvent = Long.MIN_VALUE
        for (i in steps.indices) {
            if (steps[i] < threshold) continue
            val time = mTimes[i + 1]
            if (lastEvent == Long.MIN_VALUE || time - lastEvent > EVENT_GAP_MS * NANOS_PER_MS) count++
            lastEvent = time
        }
        return count
    }

    /** null - замер слишком короткий, чтобы что-то утверждать. */
    fun estimate(spill: Float = 0f): Result? {
        if (size < MIN_SAMPLES) return null
        val start = mTimes[0]
        val steps = ((mTimes[size - 1] - start) / STEP_NANOS).toInt()
        if (steps < 2 * MAX_LAG_STEPS) return null
        val screen = resample(mScreen, start, steps)
        val glowRaw = resample(mGlow, start, steps)
        val glow = FloatArray(steps) { glowRaw[it] - spill * screen[it] }
        val ds = derivative(screen)
        val dg = derivative(glow)

        val whole = peak(ds, dg, 0, ds.size) ?: return null
        val third = ds.size / 3
        val parts = (0 until 3).mapNotNull { peak(ds, dg, it * third, (it + 1) * third)?.first }
        val spread = if (parts.size < 2) Int.MAX_VALUE else ((parts.max() - parts.min()) * STEP_MS).roundToInt()
        return Result(
            lagMs = (whole.first * STEP_MS).roundToInt(),
            correlation = whole.second,
            spreadMs = spread,
            events = events()
        )
    }

    /** Ряд на равномерной сетке шагом [STEP_MS]: кадры камеры приходят неровно. */
    private fun resample(values: FloatArray, start: Long, steps: Int): FloatArray {
        val out = FloatArray(steps)
        var j = 0
        for (i in 0 until steps) {
            // Время целиком в Long: наносекунды от загрузки телефона во Float теряли бы
            // миллисекунды точности
            val t = start + i * STEP_NANOS
            while (j < size - 2 && mTimes[j + 1] < t) j++
            val t0 = mTimes[j]
            val t1 = mTimes[j + 1]
            val k = ((t - t0).toFloat() / (t1 - t0)).coerceIn(0f, 1f)
            out[i] = values[j] + (values[j + 1] - values[j]) * k
        }
        return out
    }

    private fun derivative(values: FloatArray) = FloatArray(values.size - 1) { values[it + 1] - values[it] }

    /**
     * Сдвиг ленты относительно экрана в шагах сетки (с дробной частью) и корреляция при нём
     * на отрезке [from, to). Суммы квадратов считаются по перекрытию для каждого сдвига:
     * на краях замера перекрытие короче, и без этого края выглядели бы хуже середины.
     */
    private fun peak(ds: FloatArray, dg: FloatArray, from: Int, to: Int): Pair<Float, Float>? {
        val lags = MIN_LAG_STEPS..MAX_LAG_STEPS
        val scores = FloatArray(lags.count())
        for ((index, lag) in lags.withIndex()) {
            var dot = 0.0
            var ss = 0.0
            var gg = 0.0
            val first = maxOf(from, from - lag)
            val last = minOf(to, to - lag, dg.size - lag)
            for (i in first until last) {
                val s = ds[i]
                val g = dg[i + lag]
                dot += s * g
                ss += s * s
                gg += g * g
            }
            scores[index] = if (ss > 0 && gg > 0) (dot / sqrt(ss * gg)).toFloat() else 0f
        }
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        if (scores[best] <= 0f) return null
        // Парабола через три точки у вершины уточняет сдвиг точнее шага сетки
        var offset = 0f
        if (best > 0 && best < scores.size - 1) {
            val left = scores[best - 1]
            val right = scores[best + 1]
            val curve = left - 2 * scores[best] + right
            if (curve < 0f) offset = (0.5f * (left - right) / curve).coerceIn(-0.5f, 0.5f)
        }
        return (lags.first + best + offset) to scores[best]
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val INITIAL_CAPACITY = 1024

        const val STEP_MS = 4f
        private const val STEP_NANOS = 4 * NANOS_PER_MS

        // Лента может опережать картинку на время обработки кадра телевизором, а отстать
        // на медленном захвате; шире этого окна ответ был бы уже случайным совпадением
        private const val MIN_LAG_STEPS = -175
        private const val MAX_LAG_STEPS = 125

        private const val MIN_SAMPLES = 60
        private const val MIN_EVENT_LUMA = 2.5f
        private const val EVENT_NOISE_FACTOR = 6f
        private const val EVENT_GAP_MS = 300L

        /**
         * Доля света экрана в зоне свечения при погашенной ленте: наклон прямой, по которой
         * изменения свечения следуют за изменениями экрана. Постоянная засветка комнаты на
         * изменения не влияет и в ответ не попадает. Если экран за окно замера почти не
         * менялся, наклон не определить - тогда берётся отношение средних.
         */
        fun spill(screen: FloatArray, glow: FloatArray): Float {
            val n = minOf(screen.size, glow.size)
            if (n < 3) return 0f
            var dot = 0.0
            var ss = 0.0
            for (i in 0 until n - 1) {
                val s = screen[i + 1] - screen[i]
                dot += s * (glow[i + 1] - glow[i])
                ss += s * s
            }
            val ratio = glow.take(n).average() / screen.take(n).average().coerceAtLeast(1.0)
            val slope = if (ss > MIN_SPILL_VARIATION * (n - 1)) dot / ss else ratio
            return slope.coerceIn(0.0, ratio.coerceAtMost(MAX_SPILL)).toFloat()
        }

        private const val MIN_SPILL_VARIATION = 0.5
        private const val MAX_SPILL = 0.5

        private fun median(values: FloatArray): Float {
            val sorted = values.sortedArray()
            return sorted[sorted.size / 2]
        }
    }
}
