package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LifxClientTest {

    @Test
    fun `the header starts with the whole message size`() {
        val header = LifxClient.header(102, 13, tagged = true, sequence = 1).array()
        assertArrayEquals(byteArrayOf(49, 0), header.copyOfRange(0, 2))
    }

    @Test
    fun `a tagged message sets the tagged and addressable bits next to protocol 1024`() {
        val header = LifxClient.header(102, 13, tagged = true, sequence = 1).array()
        assertArrayEquals(byteArrayOf(0x00, 0x34), header.copyOfRange(2, 4))
    }

    @Test
    fun `the message type sits at offset 32`() {
        val header = LifxClient.header(102, 13, tagged = true, sequence = 1).array()
        assertArrayEquals(byteArrayOf(102, 0), header.copyOfRange(32, 34))
    }

    @Test
    fun `pure red has zero hue and full saturation and brightness`() {
        assertArrayEquals(intArrayOf(0, 65535, 65535, 6500), LifxClient.hsbk(255, 0, 0, null))
    }

    @Test
    fun `brightness from the picture replaces the color value`() {
        assertArrayEquals(intArrayOf(0, 65535, 32896, 6500), LifxClient.hsbk(255, 0, 0, 128))
    }
}
