package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedDiscoveryTest {

    @Test
    fun `the short name of a node is read from its poll reply`() {
        val reply = pollReply(shortName = "Stage left", longName = "")
        assertEquals("Stage left", LedDiscovery.artPollReplyName(reply, reply.size))
    }

    @Test
    fun `an empty short name falls back to the long one`() {
        val reply = pollReply(shortName = "", longName = "Pixel controller 2")
        assertEquals("Pixel controller 2", LedDiscovery.artPollReplyName(reply, reply.size))
    }

    @Test
    fun `a poll request is not taken for a reply`() {
        val poll = pollReply(shortName = "x", longName = "").also { it[9] = 0x20 }
        assertNull(LedDiscovery.artPollReplyName(poll, poll.size))
    }

    @Test
    fun `a truncated packet is ignored`() {
        val reply = pollReply(shortName = "x", longName = "")
        assertNull(LedDiscovery.artPollReplyName(reply, 60))
    }

    private fun pollReply(shortName: String, longName: String): ByteArray {
        val packet = ByteArray(239)
        "Art-Net\u0000".toByteArray(Charsets.US_ASCII).copyInto(packet)
        packet[9] = 0x21
        shortName.toByteArray(Charsets.US_ASCII).copyInto(packet, 26)
        longName.toByteArray(Charsets.US_ASCII).copyInto(packet, 44)
        return packet
    }
}
