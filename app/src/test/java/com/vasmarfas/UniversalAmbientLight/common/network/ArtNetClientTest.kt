package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtNetClientTest {

    private val leds = arrayOf(ColorRgb(10, 20, 30))

    @Test
    fun `the opcode is ArtDmx in little-endian`() {
        val packet = ArtNetClient.packet(1, 1, leds, 0, 1, "rgb")
        assertArrayEquals(byteArrayOf(0x00, 0x50), packet.copyOfRange(8, 10))
    }

    @Test
    fun `an odd channel count is padded to an even length`() {
        val packet = ArtNetClient.packet(1, 1, leds, 0, 1, "rgb")
        assertArrayEquals(byteArrayOf(0x00, 0x04), packet.copyOfRange(16, 18))
    }

    @Test
    fun `the padding byte is sent too`() {
        val packet = ArtNetClient.packet(1, 1, leds, 0, 1, "rgb")
        assertEquals(18 + 4, packet.size)
    }

    @Test
    fun `the universe is split into sub-universe and net`() {
        val packet = ArtNetClient.packet(0x0123, 1, leds, 0, 1, "rgb")
        assertArrayEquals(byteArrayOf(0x23, 0x01), packet.copyOfRange(14, 16))
    }

    @Test
    fun `the sequence number goes into its own byte`() {
        val packet = ArtNetClient.packet(1, 200, leds, 0, 1, "rgb")
        assertEquals(200, packet[12].toInt() and 0xFF)
    }
}
