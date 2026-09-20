package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HueClientTest {

    @Test
    fun `white lands next to the D65 white point`() {
        val xy = HueClient.rgbToXy(255, 255, 255)
        assertEquals(0.3227f, xy[0], 0.001f)
    }

    @Test
    fun `white keeps the y of the white point`() {
        val xy = HueClient.rgbToXy(255, 255, 255)
        assertEquals(0.3290f, xy[1], 0.001f)
    }

    @Test
    fun `black does not divide by zero`() {
        assertArrayEquals(floatArrayOf(0.3127f, 0.329f, 0f), HueClient.rgbToXy(0, 0, 0), 0f)
    }

    @Test
    fun `a point outside the gamut moves to the nearest edge`() {
        // Прямой угол в нуле: ближайшая к (1, 1) точка гипотенузы - её середина
        val triangle = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), HueClient.clampToGamut(1f, 1f, triangle), 0.0001f)
    }

    @Test
    fun `a point inside the gamut stays as it is`() {
        val triangle = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.2f, 0.3f), HueClient.clampToGamut(0.2f, 0.3f, triangle), 0f)
    }
}
