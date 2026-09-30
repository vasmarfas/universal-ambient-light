package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HueEntertainmentClientTest {

    @Test
    fun `the message starts with the protocol name`() {
        val message = HueEntertainmentClient.message(1)
        assertEquals("HueStream", String(message, 0, 9, Charsets.US_ASCII))
    }

    @Test
    fun `the protocol version is major 1 minor 0`() {
        assertArrayEquals(byteArrayOf(1, 0), HueEntertainmentClient.message(1).copyOfRange(9, 11))
    }

    @Test
    fun `every light takes nine bytes after the header`() {
        assertEquals(16 + 3 * 9, HueEntertainmentClient.message(3).size)
    }

    @Test
    fun `the light id is written big endian`() {
        val message = HueEntertainmentClient.message(1)
        HueEntertainmentClient.writeLight(message, 0, 0x0102, 0)
        assertArrayEquals(byteArrayOf(0, 1, 2), message.copyOfRange(16, 19))
    }

    @Test
    fun `full red reaches the top of the 16 bit range`() {
        val message = HueEntertainmentClient.message(1)
        HueEntertainmentClient.writeLight(message, 0, 5, 0xFF0000)
        assertArrayEquals(byteArrayOf(-1, -1, 0, 0, 0, 0), message.copyOfRange(19, 25))
    }

    @Test
    fun `the second light goes right after the first`() {
        val message = HueEntertainmentClient.message(2)
        HueEntertainmentClient.writeLight(message, 1, 7, 0)
        assertEquals(7, message[16 + 9 + 2].toInt())
    }

    @Test
    fun `the client key turns from hex into bytes`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0xFF.toByte(), 0x10),
            HueEntertainmentClient.hexToBytes("0aFF10")
        )
    }
}
