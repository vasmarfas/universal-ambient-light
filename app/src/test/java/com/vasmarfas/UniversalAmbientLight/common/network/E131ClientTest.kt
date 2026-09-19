package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class E131ClientTest {

    private val cid = ByteArray(16) { it.toByte() }
    private val leds = arrayOf(ColorRgb(1, 2, 3), ColorRgb(4, 5, 6))

    @Test
    fun `the root layer length covers everything after the preamble`() {
        val packet = E131Client.packet(cid, 1, 0, leds, 0, 2, "rgb")
        // 126 байт заголовка и 6 каналов, корневой уровень начинается с 16-го байта
        assertArrayEquals(byteArrayOf(0x70, 0x74), packet.copyOfRange(16, 18))
    }

    @Test
    fun `the universe is written big-endian`() {
        val packet = E131Client.packet(cid, 300, 0, leds, 0, 2, "rgb")
        assertArrayEquals(byteArrayOf(0x01, 0x2C), packet.copyOfRange(113, 115))
    }

    @Test
    fun `the property count includes the start code`() {
        val packet = E131Client.packet(cid, 1, 0, leds, 0, 2, "rgb")
        assertArrayEquals(byteArrayOf(0x00, 0x07), packet.copyOfRange(123, 125))
    }

    @Test
    fun `pixels follow the start code`() {
        val packet = E131Client.packet(cid, 1, 0, leds, 0, 2, "rgb")
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), packet.copyOfRange(125, 129))
    }

    @Test
    fun `a packet carries only its slice of the strip`() {
        val packet = E131Client.packet(cid, 2, 0, leds, 1, 1, "rgb")
        assertEquals(126 + 3, packet.size)
    }

    @Test
    fun `the sender id is copied into the root layer`() {
        val packet = E131Client.packet(cid, 1, 0, leds, 0, 2, "rgb")
        assertArrayEquals(cid, packet.copyOfRange(22, 38))
    }
}
