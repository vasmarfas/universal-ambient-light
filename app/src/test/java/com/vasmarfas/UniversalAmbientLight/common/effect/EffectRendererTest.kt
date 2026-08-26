package com.vasmarfas.UniversalAmbientLight.common.effect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectRendererTest {

    private val renderer = EffectRenderer(WIDTH, HEIGHT)
    private val frame = ByteArray(WIDTH * HEIGHT * 3)

    @Test
    fun `a solid color fills the whole frame`() {
        renderer.render(EffectConfig(Effect.SOLID, color = 0x102030), 0, frame)
        val expected = ByteArray(frame.size) { byteArrayOf(0x10, 0x20, 0x30)[it % 3] }
        assertArrayEquals(expected, frame)
    }

    @Test
    fun `brightness scales the color`() {
        renderer.render(EffectConfig(Effect.SOLID, color = 0xC86432, brightness = 50), 0, frame)
        assertEquals(listOf(100, 50, 25), rgb(0, 0))
    }

    @Test
    fun `movie white at 6500 K is nearly neutral`() {
        val white = EffectRenderer.kelvinToRgb(6500)
        val channels = listOf(white shr 16 and 0xFF, white shr 8 and 0xFF, white and 0xFF)
        assertTrue(channels.min() >= 235)
    }

    @Test
    fun `warm white has less blue than red`() {
        val warm = EffectRenderer.kelvinToRgb(2700)
        assertTrue((warm and 0xFF) < (warm shr 16 and 0xFF))
    }

    @Test
    fun `the rainbow differs on opposite sides of the screen`() {
        renderer.render(EffectConfig(Effect.RAINBOW), 0, frame)
        assertNotEquals(rgb(0, HEIGHT / 2), rgb(WIDTH - 1, HEIGHT / 2))
    }

    @Test
    fun `the layout test dot leaves the first led along the numbering`() {
        // Лента из левого нижнего угла по часовой: точка сначала поднимается по левой стороне
        renderer.render(EffectConfig(Effect.LAYOUT_TEST, startCorner = "bottom_left"), DOT_TIME_MS, frame)
        assertTrue(rgb(0, HEIGHT * 8 / 10)[2] > 150)
    }

    @Test
    fun `the layout test dot follows the direction of the strip`() {
        // Против часовой из того же угла точка уходит по нижней стороне, левая остаётся жёлтой
        renderer.render(
            EffectConfig(Effect.LAYOUT_TEST, startCorner = "bottom_left", direction = "counterclockwise"),
            DOT_TIME_MS,
            frame
        )
        assertEquals(0, rgb(0, HEIGHT * 8 / 10)[2])
    }

    @Test
    fun `a color string is read as rgb`() {
        assertEquals(0xFF8C3C, EffectConfig.parseColor("#FF8C3C", 0))
    }

    @Test
    fun `a broken color string falls back`() {
        assertEquals(7, EffectConfig.parseColor("orange", 7))
    }

    @Test
    fun `a color is written with six hex digits`() {
        assertEquals("#00A0FF", EffectConfig.formatColor(0x00A0FF))
    }

    private fun rgb(x: Int, y: Int): List<Int> {
        val i = (y * WIDTH + x) * 3
        return listOf(frame[i].toInt() and 0xFF, frame[i + 1].toInt() and 0xFF, frame[i + 2].toInt() and 0xFF)
    }

    companion object {
        private const val WIDTH = 128
        private const val HEIGHT = 72

        /** На обычной скорости точка проходит 0.15 периметра в секунду: за треть секунды - 0.05. */
        private const val DOT_TIME_MS = 333L
    }
}
