package com.vasmarfas.UniversalAmbientLight.common.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioSpectrumTest {

    @Test
    fun `silence keeps every band dark`() {
        val spectrum = AudioSpectrum(SAMPLE_RATE, BLOCK)
        repeat(BLOCKS) { spectrum.process(ShortArray(BLOCK)) }
        assertEquals(0f, spectrum.levels.max(), 0f)
    }

    @Test
    fun `a low tone lights a lower band than a high tone`() {
        assertTrue(loudestBand(100.0) < loudestBand(5000.0))
    }

    @Test
    fun `steady noise fades to dark`() {
        val spectrum = AudioSpectrum(SAMPLE_RATE, BLOCK)
        val random = Random(7)
        var sum = 0f
        repeat(SETTLE_BLOCKS) { i ->
            spectrum.process(ShortArray(BLOCK) { (random.nextGaussian() * NOISE_RMS).toInt().toShort() })
            // Последние сто блоков, когда среднее уже догнало шум
            if (i >= SETTLE_BLOCKS - 100) sum += spectrum.levels.average().toFloat()
        }
        assertTrue(sum / 100 < 0.05f)
    }

    @Test
    fun `normal sensitivity shows a rise of 8 dB`() {
        assertTrue(levelsAfterRise(sensitivity = 50).max() > 0.1f)
    }

    @Test
    fun `low sensitivity ignores the same rise`() {
        assertEquals(0f, levelsAfterRise(sensitivity = 0).max(), 0f)
    }

    @Test
    fun `the strip fades after the sound stops`() {
        val spectrum = AudioSpectrum(SAMPLE_RATE, BLOCK)
        repeat(BLOCKS) { spectrum.process(ShortArray(BLOCK)) }
        repeat(BLOCKS) { spectrum.process(tone(1000.0, LOUD)) }
        repeat(BLOCKS) { spectrum.process(ShortArray(BLOCK)) }
        // Двадцать блоков - около 0,46 с, спад за это время почти до нуля
        assertTrue(spectrum.levels.max() < 0.1f)
    }

    private fun loudestBand(hz: Double): Int {
        val spectrum = AudioSpectrum(SAMPLE_RATE, BLOCK)
        repeat(BLOCKS) { spectrum.process(ShortArray(BLOCK)) }
        repeat(BLOCKS) { spectrum.process(tone(hz, LOUD)) }
        val levels = spectrum.levels
        return levels.indices.maxBy { levels[it] }
    }

    /** Ровный тон, пока среднее его не догонит, затем тот же тон на 8 dB громче. */
    private fun levelsAfterRise(sensitivity: Int): FloatArray {
        val spectrum = AudioSpectrum(SAMPLE_RATE, BLOCK)
        spectrum.sensitivity = sensitivity
        repeat(SETTLE_BLOCKS) { spectrum.process(tone(1000.0, QUIET)) }
        spectrum.process(tone(1000.0, QUIET * 2.5))
        return spectrum.levels
    }

    private fun tone(hz: Double, amplitude: Double): ShortArray = ShortArray(BLOCK) {
        (sin(2 * PI * hz * it / SAMPLE_RATE) * amplitude * Short.MAX_VALUE).roundToInt().toShort()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BLOCK = 1024
        private const val BLOCKS = 20

        // Около девяти секунд: среднее за полторы секунды успевает догнать ровный звук
        private const val SETTLE_BLOCKS = 400
        private const val LOUD = 0.5
        private const val QUIET = 0.01
        private const val NOISE_RMS = 300.0
    }
}
