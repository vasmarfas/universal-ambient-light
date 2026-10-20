package com.vasmarfas.UniversalAmbientLight.ui.led

import org.junit.Assert.assertEquals
import org.junit.Test

class LedLayoutGeometryTest {
    // Только верхняя сторона из 4 светодиодов на кадре 400x200 без отступа:
    // захват делит её на отрезки по 100 px.
    private fun topSide() = calculateLedPositions(
        topLed = 4, rightLed = 0, bottomLed = 0, leftLed = 0,
        startCorner = "top_left", direction = "clockwise",
        sideTop = "enabled", sideRight = "enabled", sideBottom = "enabled", sideLeft = "enabled",
        bottomGap = 0, width = 400f, height = 200f, padding = 0f, scanDepth = 10,
    )

    @Test
    fun `LED centers match the capture segments`() {
        assertEquals(listOf(50f, 150f, 250f, 350f), topSide().map { it.position.x })
    }

    @Test
    fun `LED rectangles do not overlap the neighbours`() {
        assertEquals(listOf(100f, 100f, 100f, 100f), topSide().map { it.rectSize.width })
    }
}
