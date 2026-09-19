package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OpcClientTest {

    @Test
    fun `the header carries channel, command and byte length`() {
        val leds = Array(100) { ColorRgb(0, 0, 0) }
        val packet = ByteArray(4 + 300)
        OpcClient.fill(packet, 2, leds, "rgb")
        assertArrayEquals(byteArrayOf(2, 0, 0x01, 0x2C), packet.copyOfRange(0, 4))
    }

    @Test
    fun `pixels follow the header in the chosen order`() {
        val packet = ByteArray(4 + 3)
        OpcClient.fill(packet, 0, arrayOf(ColorRgb(1, 2, 3)), "grb")
        assertArrayEquals(byteArrayOf(2, 1, 3), packet.copyOfRange(4, 7))
    }
}
