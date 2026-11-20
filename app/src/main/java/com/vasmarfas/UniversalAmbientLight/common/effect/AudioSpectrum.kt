package com.vasmarfas.UniversalAmbientLight.common.effect

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Раскладывает звук на полосы для цветомузыки: блок PCM, окно Ханна, FFT, энергия в
 * [BANDS] полосах с логарифмическим шагом по частоте и уровень полосы 0..1.
 *
 * Уровень полосы считается от её собственного среднего за последние полторы секунды:
 * лента зажигается, когда звук в полосе поднимается над средним, а ровный звук - шум
 * комнаты, вентилятор, тянущаяся нота - за пару секунд гаснет. Абсолютные пороги здесь
 * не работают: фон обычной комнаты у микрофона телефона - от -80 до -50 dBFS на полосу,
 * а у другого микрофона и в другой комнате он свой. Чувствительность задаёт, на сколько
 * децибел звук должен подняться над средним. Полоса тише самой громкой больше чем на
 * [SPREAD_DB] не зажигается вовсе: это утечка соседней частоты или гул, а не музыка.
 *
 * Зависимостей от Android нет, проверяется юнит-тестами.
 */
class AudioSpectrum(sampleRate: Int, private val mBlockSize: Int) {

    /** Уровни полос 0..1 после спада; индекс 0 - нижние частоты. */
    val levels = FloatArray(BANDS)

    /** 0..100: чем выше, тем меньший подъём звука над средним зажигает ленту. */
    @Volatile
    var sensitivity = 50

    private val mWindow = FloatArray(mBlockSize) {
        (0.5 - 0.5 * cos(2 * PI * it / mBlockSize)).toFloat()
    }
    private val mCos = FloatArray(mBlockSize / 2) { cos(-2 * PI * it / mBlockSize).toFloat() }
    private val mSin = FloatArray(mBlockSize / 2) { sin(-2 * PI * it / mBlockSize).toFloat() }
    private val mRe = FloatArray(mBlockSize)
    private val mIm = FloatArray(mBlockSize)
    private val mDb = FloatArray(BANDS)

    /** Границы полос в бинах FFT: полоса b - от mBandStart[b] до mBandStart[b + 1]. */
    private val mBandStart = IntArray(BANDS + 1)
    private val mAverage = FloatArray(BANDS) { Float.NaN }
    private val mAverageRate: Float
    private val mFall: Float

    init {
        require(mBlockSize >= 4 && mBlockSize and (mBlockSize - 1) == 0) { "FFT needs a power of two" }
        val binHz = sampleRate.toFloat() / mBlockSize
        var previous = 0
        for (b in 0..BANDS) {
            val hz = MIN_HZ * (MAX_HZ / MIN_HZ).pow(b.toFloat() / BANDS)
            // Внизу несколько полос попадают в один бин, а каждой нужен хотя бы свой
            val bin = max((hz / binHz).roundToInt(), previous + 1).coerceAtMost(mBlockSize / 2)
            mBandStart[b] = bin
            previous = bin
        }
        val blockSeconds = mBlockSize.toFloat() / sampleRate
        mAverageRate = 1 - exp(-blockSeconds / AVERAGE_S)
        mFall = exp(-blockSeconds / RELEASE_S)
    }

    /** Принимает блок 16-битного моно ровно той длины, что задана в конструкторе. */
    fun process(samples: ShortArray) {
        for (i in 0 until mBlockSize) {
            mRe[i] = samples[i] / 32768f * mWindow[i]
            mIm[i] = 0f
        }
        fft()
        // Синус на полной шкале после окна Ханна даёт в своём бине N/4: нормируем к 0 dB
        val norm = (4f / mBlockSize).let { it * it }
        var loudest = Float.NEGATIVE_INFINITY
        for (b in 0 until BANDS) {
            var energy = 0f
            for (k in mBandStart[b] until mBandStart[b + 1]) {
                energy += mRe[k] * mRe[k] + mIm[k] * mIm[k]
            }
            mDb[b] = 10 * log10(energy * norm + 1e-12f)
            loudest = max(loudest, mDb[b])
        }
        val gate = max(SILENCE_DB, loudest - SPREAD_DB)
        val margin = MARGIN_DB - (sensitivity - 50) * MARGIN_DB_PER_STEP
        for (b in 0 until BANDS) {
            val db = mDb[b]
            // Первый блок сразу становится средним: звук, шедший до запуска, не вспыхивает
            val average = mAverage[b].takeUnless { it.isNaN() } ?: db
            val floor = max(average + margin, gate)
            val level = ((db - floor) / RANGE_DB).coerceIn(0f, 1f)
            mAverage[b] = average + (db - average) * mAverageRate
            levels[b] = max(level, levels[b] * mFall)
        }
    }

    private fun fft() {
        val re = mRe
        val im = mIm
        val n = mBlockSize
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until half) {
                    val a = start + k
                    val b = a + half
                    val wRe = mCos[k * step]
                    val wIm = mSin[k * step]
                    val tRe = re[b] * wRe - im[b] * wIm
                    val tIm = re[b] * wIm + im[b] * wRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                }
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 16

        private const val MIN_HZ = 40f
        private const val MAX_HZ = 12000f

        // Цифровая тишина: шум квантования 16 бит в узкой полосе около -125 dBFS
        private const val SILENCE_DB = -100f

        // Подъём над средним при чувствительности 50: шум в узких нижних полосах (один-два
        // бина FFT) скачет от блока к блоку на 5-6 dB и не должен мигать лентой
        private const val MARGIN_DB = 6f
        private const val MARGIN_DB_PER_STEP = 0.12f
        private const val AVERAGE_S = 1.5f

        // В полосах по октавам верха у музыки обычно тише баса на 20-30 dB, а утечка окна
        // Ханна через пару октав от тона - на 60 и больше
        private const val SPREAD_DB = 45f

        /** Сколько децибел над порогом помещается в яркость от нуля до полной. */
        private const val RANGE_DB = 12f
        private const val RELEASE_S = 0.15f
    }
}
