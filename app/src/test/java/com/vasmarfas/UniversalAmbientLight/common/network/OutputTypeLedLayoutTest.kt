package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputTypeLedLayoutTest {
    @Test
    fun `all addressable strip protocols support LED layout tests`() {
        val expected = setOf(
            OutputType.WLED, OutputType.DDP, OutputType.E131,
            OutputType.ARTNET, OutputType.TPM2NET, OutputType.UDP_RAW,
            OutputType.OPC, OutputType.ADALIGHT,
        )
        assertEquals(expected, OutputType.entries.filter { it.supportsLedLayoutTest }.toSet())
    }

    @Test
    fun `server and lamp protocols are excluded from addressable tests`() {
        assertFalse(OutputType.entries.filter {
            it.group == OutputType.Group.LAMPS || it.group == OutputType.Group.SERVER
        }.any { it.supportsLedLayoutTest })
    }
}
