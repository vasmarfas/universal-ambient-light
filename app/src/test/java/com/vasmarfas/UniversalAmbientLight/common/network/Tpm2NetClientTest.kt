package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Tpm2NetClientTest {

    private val leds = Array(5) { ColorRgb(it, it, it) }

    @Test
    fun `a long strip is split into packets`() {
        assertEquals(3, Tpm2NetClient.packets(leds, 2, "rgb").size)
    }

    @Test
    fun `packets are numbered from one with the total next to the number`() {
        val last = Tpm2NetClient.packets(leds, 2, "rgb").last()
        assertArrayEquals(byteArrayOf(3, 3), last.copyOfRange(4, 6))
    }

    @Test
    fun `the payload size counts bytes, not LEDs`() {
        val first = Tpm2NetClient.packets(leds, 2, "rgb").first()
        assertArrayEquals(byteArrayOf(0x00, 0x06), first.copyOfRange(2, 4))
    }

    @Test
    fun `every packet ends with the block end byte`() {
        val first = Tpm2NetClient.packets(leds, 2, "rgb").first()
        assertEquals(0x36.toByte(), first.last())
    }

    @Test
    fun `every packet starts with the tpm2 net marker`() {
        val first = Tpm2NetClient.packets(leds, 2, "rgb").first()
        assertArrayEquals(byteArrayOf(0x9C.toByte(), 0xDA.toByte()), first.copyOfRange(0, 2))
    }
}
