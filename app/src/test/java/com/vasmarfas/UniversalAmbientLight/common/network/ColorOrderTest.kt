package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorOrderTest {

    @Test
    fun `bgr swaps red and blue`() {
        val packet = ByteArray(3)
        ColorOrder.write(packet, 0, ColorRgb(1, 2, 3), "bgr")
        assertArrayEquals(byteArrayOf(3, 2, 1), packet)
    }

    @Test
    fun `an unknown order falls back to rgb`() {
        val packet = ByteArray(3)
        ColorOrder.write(packet, 0, ColorRgb(1, 2, 3), "xyz")
        assertArrayEquals(byteArrayOf(1, 2, 3), packet)
    }

    @Test
    fun `write returns the position after the LED`() {
        assertEquals(7, ColorOrder.write(ByteArray(10), 4, ColorRgb(1, 2, 3), "rgb"))
    }
}
