package com.vasmarfas.UniversalAmbientLight.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlowRegionsTest {

    // Экран 0.3..0.7 по обеим осям в сетке 50 × 50: ячейка - 0.02 кадра
    private val regions = GlowRegions(COLS, ROWS, floatArrayOf(0.3f, 0.3f, 0.7f, 0.3f, 0.7f, 0.7f, 0.3f, 0.7f))

    @Test
    fun `the middle of the screen belongs to the screen`() {
        assertTrue(cell(0.5f, 0.5f) in regions.screenCells)
    }

    @Test
    fun `the edge of the panel is left out of the screen`() {
        assertFalse(cell(0.69f, 0.5f) in regions.screenCells)
    }

    @Test
    fun `the bezel gap belongs to neither region`() {
        assertFalse(cell(0.71f, 0.5f) in regions.glowCells)
    }

    @Test
    fun `the wall beyond the gap belongs to the glow`() {
        assertTrue(cell(0.79f, 0.5f) in regions.glowCells)
    }

    @Test
    fun `a screen filling the whole frame leaves no room for the glow`() {
        val full = GlowRegions(COLS, ROWS, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertFalse(full.usable)
    }

    @Test
    fun `a tilted quad still contains its centre`() {
        val quad = floatArrayOf(0.2f, 0.25f, 0.75f, 0.3f, 0.7f, 0.8f, 0.25f, 0.7f)
        assertTrue(GlowRegions.inside(quad, 0.47f, 0.51f))
    }

    private fun cell(x: Float, y: Float): Int = (y * ROWS).toInt() * COLS + (x * COLS).toInt()

    companion object {
        private const val COLS = 50
        private const val ROWS = 50
    }
}
