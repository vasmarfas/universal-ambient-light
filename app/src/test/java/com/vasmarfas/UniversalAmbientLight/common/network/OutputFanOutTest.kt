package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFanOutTest {

    private class Recorder : HyperionThread.HyperionThreadListener {
        val calls = ArrayList<String>()

        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            calls += "frame ${width}x$height"
        }

        override fun clear() {
            calls += "clear"
        }

        override fun disconnect() {
            calls += "disconnect"
        }

        override fun sendStatus(isGrabbing: Boolean) {
            calls += "status"
        }
    }

    private val primary = Recorder()
    private val extra = Recorder()
    private val fanOut = OutputFanOut(primary, listOf(extra))

    @Test
    fun `a frame reaches every controller`() {
        fanOut.sendFrame(ByteArray(12), 2, 2)
        assertEquals(listOf("frame 2x2", "frame 2x2"), primary.calls + extra.calls)
    }

    @Test
    fun `clearing and disconnecting reach every controller`() {
        fanOut.clear()
        fanOut.disconnect()
        assertEquals(primary.calls, extra.calls)
    }

    @Test
    fun `only the main output reports the capture status`() {
        fanOut.sendStatus(true)
        assertEquals(listOf("status"), primary.calls + extra.calls)
    }
}
