package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class MqttLinkTest {

    @Test
    fun `a length under 128 takes one byte`() {
        assertArrayEquals(byteArrayOf(0x7F), MqttLink.encodeRemainingLength(127))
    }

    @Test
    fun `128 spills into a second byte`() {
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), MqttLink.encodeRemainingLength(128))
    }

    @Test
    fun `an encoded length reads back the same`() {
        val encoded = MqttLink.encodeRemainingLength(2_097_152)
        assertEquals(2_097_152, MqttLink.readRemainingLength(ByteArrayInputStream(encoded)))
    }

    @Test(expected = IOException::class)
    fun `a length longer than four bytes is rejected`() {
        val bytes = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01)
        MqttLink.readRemainingLength(ByteArrayInputStream(bytes))
    }

    @Test
    fun `connect without a login asks only for a clean session`() {
        assertEquals(0x02, flagsOf(MqttLink.connectPacket("tv", "", "", 60)))
    }

    @Test
    fun `connect with a login sets the user and password flags`() {
        assertEquals(0xC2, flagsOf(MqttLink.connectPacket("tv", "user", "secret", 60)))
    }

    @Test
    fun `a password without a user is not sent`() {
        assertEquals(0x02, flagsOf(MqttLink.connectPacket("tv", "", "secret", 60)))
    }

    @Test
    fun `publish carries the topic and then the payload`() {
        val expected = byteArrayOf(0x30, 7, 0, 3) + "a/bon".toByteArray()
        assertArrayEquals(expected, MqttLink.publishPacket("a/b", "on".toByteArray()))
    }

    @Test
    fun `subscribe asks for QoS 0`() {
        val packet = MqttLink.subscribePacket(1, "zigbee2mqtt/bridge/devices")
        assertEquals(0, packet.last().toInt())
    }

    @Test
    fun `an incoming publish splits into topic and payload`() {
        val body = byteArrayOf(0, 3) + "a/b[]".toByteArray()
        val parsed = MqttLink.parsePublish(body)?.let { it.first to String(it.second) }
        assertEquals("a/b" to "[]", parsed)
    }

    @Test
    fun `a publish shorter than its topic is ignored`() {
        assertNull(MqttLink.parsePublish(byteArrayOf(0, 10, 'a'.code.toByte())))
    }

    // Фиксированный заголовок (2 байта), имя протокола (6), уровень (1), затем флаги
    private fun flagsOf(packet: ByteArray) = packet[9].toInt() and 0xFF
}
