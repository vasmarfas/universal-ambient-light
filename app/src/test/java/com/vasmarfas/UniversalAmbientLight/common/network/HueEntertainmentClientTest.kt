package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.CertificateFactory

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
    fun `a channel message has protocol version 2`() {
        assertArrayEquals(byteArrayOf(2, 0), HueEntertainmentClient.message(1, AREA).copyOfRange(9, 11))
    }

    @Test
    fun `the area id follows the header`() {
        assertEquals(AREA, String(HueEntertainmentClient.message(1, AREA), 16, 36, Charsets.US_ASCII))
    }

    @Test
    fun `every channel takes seven bytes after the area id`() {
        assertEquals(16 + 36 + 3 * 7, HueEntertainmentClient.message(3, AREA).size)
    }

    @Test
    fun `the channel id takes one byte`() {
        val message = HueEntertainmentClient.message(2, AREA)
        HueEntertainmentClient.writeLight(message, 1, 5, 0)
        assertEquals(5, message[16 + 36 + 7].toInt())
    }

    @Test
    fun `full blue of a channel reaches the top of the 16 bit range`() {
        val message = HueEntertainmentClient.message(1, AREA)
        HueEntertainmentClient.writeLight(message, 0, 0, 0x0000FF)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, -1, -1), message.copyOfRange(16 + 36 + 1, 16 + 36 + 7))
    }

    @Test
    fun `both hue root certificates parse`() {
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(HueEntertainmentClient.HUE_ROOT_CA.byteInputStream())
        assertEquals(2, certificates.size)
    }

    @Test
    fun `the client key turns from hex into bytes`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0xFF.toByte(), 0x10),
            HueEntertainmentClient.hexToBytes("0aFF10")
        )
    }

    private companion object {
        const val AREA = "1a8d99cc-967b-44f2-9202-43f976c0fa6b"
    }
}
