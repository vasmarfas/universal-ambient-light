package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * Замер моделируется честно: фильм со сменами сцен, камера на 30 кадрах с неровным шагом и
 * выдержкой, которая размазывает скачок яркости между соседними кадрами, лента со своей
 * задержкой и засветка стены самим экраном.
 */
class DelayEstimatorTest {

    @Test
    fun `finds the strip running ahead of the picture`() {
        val result = record(lagMs = -120.0).estimate()
        assertEquals(-120f, result?.lagMs?.toFloat() ?: Float.NaN, 10f)
    }

    @Test
    fun `finds the strip lagging behind the picture`() {
        val result = record(lagMs = 60.0).estimate()
        assertEquals(60f, result?.lagMs?.toFloat() ?: Float.NaN, 10f)
    }

    @Test
    fun `spill correction keeps a small lead from sliding to zero`() {
        val spill = 0.2f
        val result = record(lagMs = -35.0, spill = spill).estimate(spill)
        assertEquals(-35f, result?.lagMs?.toFloat() ?: Float.NaN, 10f)
    }

    @Test
    fun `counts scene changes`() {
        val estimator = record(lagMs = -80.0, durationMs = 20_000.0)
        val cuts = scenes(SEED, 22_000.0).count { it.first in 1100.0..19_900.0 }
        assertEquals(cuts.toFloat(), estimator.events().toFloat(), 2f)
    }

    @Test
    fun `a still picture has no scene changes`() {
        val estimator = DelayEstimator()
        for (i in 0 until 300) estimator.add(i * 33_333_333L, 120f, 30f)
        assertEquals(0, estimator.events())
    }

    @Test
    fun `a short recording gives no estimate`() {
        assertNull(record(lagMs = -100.0, durationMs = 1500.0).estimate())
    }

    @Test
    fun `spill is taken from changes and ignores constant room light`() {
        val random = Random(SEED)
        val screen = FloatArray(120) { 40f + random.nextFloat() * 150f }
        val glow = FloatArray(120) { 55f + 0.1f * screen[it] }
        assertEquals(0.1f, DelayEstimator.spill(screen, glow), 0.01f)
    }

    private fun record(
        lagMs: Double,
        spill: Float = 0f,
        durationMs: Double = 40_000.0,
    ): DelayEstimator {
        val cuts = scenes(SEED, durationMs + 2000)
        val random = Random(SEED + 1)
        val estimator = DelayEstimator()
        var time = 1000.0
        // Отсчёт от загрузки телефона - наносекунды порядка суток, как у настоящей камеры
        val bootOffsetNanos = 86_400_000_000_000L
        while (time < durationMs) {
            val screen = exposed(cuts, time)
            val led = exposed(cuts, time - lagMs)
            val glow = 6f + 0.3f * led + spill * screen + (random.nextFloat() - 0.5f) * 0.6f
            estimator.add(bootOffsetNanos + (time * 1_000_000).toLong(), screen, glow)
            time += 33.333 + (random.nextDouble() - 0.5) * 6
        }
        return estimator
    }

    /** Кадр камеры копит свет за выдержку, поэтому скачок посреди неё даёт промежуточную яркость. */
    private fun exposed(cuts: List<Pair<Double, Float>>, time: Double): Float {
        val from = time - EXPOSURE_MS
        var sum = 0.0
        for (i in cuts.indices) {
            val start = maxOf(cuts[i].first, from)
            val end = minOf(cuts.getOrNull(i + 1)?.first ?: Double.MAX_VALUE, time)
            if (end > start) sum += (end - start) * cuts[i].second
        }
        return (sum / EXPOSURE_MS).toFloat()
    }

    /** Сцены фильма: момент начала и яркость, смены через 0.4–2.5 с. */
    private fun scenes(seed: Long, durationMs: Double): List<Pair<Double, Float>> {
        val random = Random(seed)
        val result = ArrayList<Pair<Double, Float>>()
        var time = -5000.0
        while (time < durationMs) {
            result += time to (10f + random.nextFloat() * 210f)
            time += 400 + random.nextDouble() * 2100
        }
        return result
    }

    companion object {
        private const val SEED = 42L
        private const val EXPOSURE_MS = 25.0
    }
}
