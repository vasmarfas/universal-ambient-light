package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LedDataExtractorTest {

    @Test
    fun `a uniform frame lights every led with its color`() {
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE,
            LedLayout(topLed = 4, rightLed = 4, bottomLed = 4, leftLed = 4),
            null
        )
        assertArrayEquals(Array(16) { ColorRgb(10, 20, 30) }, leds)
    }

    @Test
    fun `each led averages only its own segment of the edge`() {
        val leds = topLeds(halves(), LedLayout(topLed = 4, startCorner = "top_left"))
        assertArrayEquals(arrayOf(RED, RED, GREEN, GREEN), leds)
    }

    @Test
    fun `counterclockwise numbering from the top right mirrors the edge`() {
        val leds = topLeds(
            halves(),
            LedLayout(topLed = 4, startCorner = "top_right", direction = "counterclockwise")
        )
        assertArrayEquals(arrayOf(GREEN, GREEN, RED, RED), leds)
    }

    @Test
    fun `an unknown start corner falls back to the default order`() {
        val leds = topLeds(halves(), LedLayout(topLed = 4, startCorner = "somewhere"))
        assertArrayEquals(arrayOf(RED, RED, GREEN, GREEN), leds)
    }

    @Test
    fun `a positive offset shifts the strip forward`() {
        val leds = topLeds(halves(), LedLayout(topLed = 4, startCorner = "top_left", ledOffset = 1))
        assertArrayEquals(arrayOf(GREEN, RED, RED, GREEN), leds)
    }

    @Test
    fun `a negative offset shifts the strip backward`() {
        val leds = topLeds(halves(), LedLayout(topLed = 4, startCorner = "top_left", ledOffset = -1))
        assertArrayEquals(arrayOf(RED, GREEN, GREEN, RED), leds)
    }

    @Test
    fun `a side that is not installed is left out of the strip`() {
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE,
            LedLayout(topLed = 4, bottomLed = 4, sideBottom = "not_installed"),
            null
        )
        assertEquals(4, leds.size)
    }

    @Test
    fun `a disabled side keeps its place in the strip and stays black`() {
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE,
            LedLayout(topLed = 4, bottomLed = 4, startCorner = "top_left", sideBottom = "disabled"),
            null
        )
        assertArrayEquals(Array(4) { ColorRgb(0, 0, 0) }, leds.copyOfRange(4, 8))
    }

    @Test
    fun `the bottom gap blanks the leds behind the tv stand`() {
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE,
            LedLayout(
                bottomLed = 4,
                bottomGap = 2,
                startCorner = "bottom_left",
                direction = "counterclockwise"
            ),
            null
        )
        assertArrayEquals(arrayOf(GREY, BLACK, BLACK, GREY), leds)
    }

    @Test
    fun `without a margin the scan line sits on the very edge of the frame`() {
        val leds = topLeds(bandedTop(), LedLayout(topLed = 1, startCorner = "top_left"), BAND)
        assertArrayEquals(arrayOf(BLACK), leds)
    }

    @Test
    fun `a capture margin moves the scan line off the black band`() {
        val leds = topLeds(
            bandedTop(),
            LedLayout(topLed = 1, startCorner = "top_left", captureMarginTop = 15),
            BAND
        )
        assertArrayEquals(arrayOf(RED), leds)
    }

    @Test
    fun `a deeper scan averages a thicker strip of the frame`() {
        // Полоса 20% от 100 пикселей: десять чёрных строк и десять красных дают половину яркости
        val leds = topLeds(
            bandedTop(),
            LedLayout(topLed = 1, startCorner = "top_left", scanDepth = 20),
            BAND
        )
        assertArrayEquals(arrayOf(ColorRgb(127, 0, 0)), leds)
    }

    @Test
    fun `a single pixel frame does not break the extraction`() {
        val pixel = ColorRgb(7, 8, 9)
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(pixel, 1), 1, 1,
            LedLayout(topLed = 1, rightLed = 1, bottomLed = 1, leftLed = 1),
            null
        )
        assertArrayEquals(Array(4) { ColorRgb(7, 8, 9) }, leds)
    }

    @Test
    fun `a layout without leds returns an empty strip`() {
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE, LedLayout(), null
        )
        assertEquals(0, leds.size)
    }

    @Test
    fun `a buffer of the right size is filled in place`() {
        val buffer = Array(4) { ColorRgb(0, 0, 0) }
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE, LedLayout(topLed = 4), buffer
        )
        assertSame(buffer, leds)
    }

    @Test
    fun `a buffer of the wrong size is replaced`() {
        val buffer = Array(2) { ColorRgb(0, 0, 0) }
        val leds = LedDataExtractor.extractPerimeterPixels(
            uniform(GREY), SIZE, SIZE, LedLayout(topLed = 4), buffer
        )
        assertNotSame(buffer, leds)
    }

    @Test
    fun `capture margins narrow a strip down to a part of the edge`() {
        val leds = topLeds(
            frame(SIZE) { x, _ -> if (x < SIZE / 4) RED else GREEN },
            LedLayout(topLed = 2, startCorner = "top_left", captureMarginRight = 75)
        )
        assertArrayEquals(arrayOf(RED, RED), leds)
    }

    @Test
    fun `a bar on one side takes its colors from that side only`() {
        val leds = topLeds(halves(), LedLayout(leftLed = 3))
        assertArrayEquals(arrayOf(RED, RED, RED), leds)
    }

    private fun topLeds(
        screenData: ByteArray,
        layout: LedLayout,
        size: Int = SIZE,
    ): Array<ColorRgb> = LedDataExtractor.extractPerimeterPixels(screenData, size, size, layout, null)

    /** Одноцветный кадр. */
    private fun uniform(color: ColorRgb, size: Int = SIZE) = frame(size) { _, _ -> color }

    /** Левая половина красная, правая зелёная — по ней видно порядок обхода верхней стороны. */
    private fun halves() = frame(SIZE) { x, _ -> if (x < SIZE / 2) RED else GREEN }

    /** Кадр 100×100 с чёрной полосой в десять строк сверху — как рамка вокруг картинки. */
    private fun bandedTop() = frame(BAND) { _, y -> if (y < 10) BLACK else RED }

    private fun frame(size: Int, color: (x: Int, y: Int) -> ColorRgb): ByteArray {
        val data = ByteArray(size * size * 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val pixel = color(x, y)
                val offset = (y * size + x) * 3
                data[offset] = pixel.red.toByte()
                data[offset + 1] = pixel.green.toByte()
                data[offset + 2] = pixel.blue.toByte()
            }
        }
        return data
    }

    private companion object {
        const val SIZE = 64
        const val BAND = 100

        val RED = ColorRgb(255, 0, 0)
        val GREEN = ColorRgb(0, 255, 0)
        val GREY = ColorRgb(10, 20, 30)
        val BLACK = ColorRgb(0, 0, 0)
    }
}
