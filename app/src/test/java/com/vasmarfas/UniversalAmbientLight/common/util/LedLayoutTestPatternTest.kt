package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb
import org.junit.Assert.assertEquals
import org.junit.Test

class LedLayoutTestPatternTest {
    private fun rgb(led: ColorRgb) = (led.red shl 16) or (led.green shl 8) or led.blue

    @Test
    fun `side colors follow actual configured LED counts`() {
        val leds = LedLayoutTestPattern.build(LedLayout(
            topLed = 72, rightLed = 40, bottomLed = 74, leftLed = 40,
            startCorner = "bottom_left", direction = "clockwise"
        ))
        assertEquals(226, leds.size)
        // От нижнего левого угла по часовой: левая, верхняя, правая, нижняя.
        assertEquals(0xFFFF00, rgb(leds[0]))
        assertEquals(0xFFFF00, rgb(leds[39]))
        assertEquals(0xFF0000, rgb(leds[40]))
        assertEquals(0xFF0000, rgb(leds[111]))
        assertEquals(0x00FF00, rgb(leds[112]))
        assertEquals(0x00FF00, rgb(leds[151]))
        assertEquals(0x0000FF, rgb(leds[152]))
        assertEquals(0x0000FF, rgb(leds[225]))
    }

    @Test
    fun `changing a side count changes the color boundary`() {
        val layout = LedLayout(topLed = 72, rightLed = 40, bottomLed = 74, leftLed = 40)
        val revised = layout.copy(leftLed = 41)
        assertEquals(0xFF0000, rgb(LedLayoutTestPattern.build(layout)[40]))
        assertEquals(0xFFFF00, rgb(LedLayoutTestPattern.build(revised)[40]))
        assertEquals(227, LedLayoutTestPattern.build(revised).size)
    }

    @Test
    fun `LED offset rotates physical color sequence`() {
        val original = LedLayoutTestPattern.build(LedLayout(
            topLed = 2, rightLed = 1, bottomLed = 2, leftLed = 1,
            startCorner = "top_left"
        ))
        val shifted = LedLayoutTestPattern.build(LedLayout(
            topLed = 2, rightLed = 1, bottomLed = 2, leftLed = 1,
            startCorner = "top_left", ledOffset = -1
        ))
        assertEquals(original.size, shifted.size)
        for (i in original.indices) {
            assertEquals(rgb(original[i]), rgb(shifted[(i + original.size - 1) % original.size]))
        }
    }

    @Test
    fun `disabled side remains black and missing side is omitted`() {
        val layout = LedLayout(
            topLed = 2, rightLed = 1, bottomLed = 2, leftLed = 1,
            startCorner = "top_left", sideRight = "disabled"
        )
        val disabled = LedLayoutTestPattern.build(layout)
        assertEquals(6, disabled.size)
        assertEquals(0, rgb(disabled[2]))
        val missing = LedLayoutTestPattern.build(layout.copy(sideRight = "not_installed"))
        assertEquals(5, missing.size)
        assertEquals(0x0000FF, rgb(missing[2]))
    }

    @Test
    fun `bottom gap keeps physical LED slots black`() {
        val leds = LedLayoutTestPattern.build(LedLayout(
            topLed = 1, rightLed = 1, bottomLed = 6, leftLed = 1,
            startCorner = "top_left", bottomGap = 2,
        ))
        // Низ идёт справа налево; середина из двух LED остаётся тёмной.
        assertEquals(0, rgb(leds[1 + 1 + 2]))
        assertEquals(0, rgb(leds[1 + 1 + 3]))
        assertEquals(0x0000FF, rgb(leds[1 + 1 + 1]))
    }
}
