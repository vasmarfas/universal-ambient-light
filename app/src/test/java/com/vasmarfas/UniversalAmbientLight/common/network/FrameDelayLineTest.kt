package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameDelayLineTest {

    @Test
    fun `a frame is not due before its time`() {
        val line = FrameDelayLine()
        line.push(frame(2, 2, 10), 2, 2, dueAt = 100)
        assertNull(line.takeDue(99))
    }

    @Test
    fun `the newest due frame wins over older ones`() {
        val line = FrameDelayLine()
        line.push(frame(2, 2, 10), 2, 2, dueAt = 100)
        line.push(frame(2, 2, 20), 2, 2, dueAt = 110)
        assertEquals(20, line.takeDue(120)?.data?.get(0)?.toInt())
    }

    @Test
    fun `a frame that is not due yet stays queued`() {
        val line = FrameDelayLine()
        line.push(frame(2, 2, 10), 2, 2, dueAt = 100)
        line.push(frame(2, 2, 20), 2, 2, dueAt = 200)
        line.takeDue(150)
        assertEquals(1, line.size)
    }

    @Test
    fun `wide frames are shrunk to the limit`() {
        val line = FrameDelayLine()
        line.push(frame(512, 2, 0), 512, 2, dueAt = 0)
        assertEquals(FrameDelayLine.MAX_WIDTH, line.takeDue(0)?.width)
    }

    @Test
    fun `shrinking averages neighbouring pixels`() {
        val line = FrameDelayLine()
        // Столбцы через один 0 и 200: после уменьшения вдвое остаётся среднее
        val source = ByteArray(512 * 2 * 3) { if ((it / 3) % 2 == 0) 0 else 200.toByte() }
        line.push(source, 512, 2, dueAt = 0)
        assertEquals(100, line.takeDue(0)?.data?.get(0)?.toInt()?.and(0xFF))
    }

    @Test
    fun `clearing drops the queued frames`() {
        val line = FrameDelayLine()
        line.push(frame(2, 2, 10), 2, 2, dueAt = 100)
        line.clear()
        assertNull(line.takeDue(Long.MAX_VALUE))
    }

    private fun frame(width: Int, height: Int, value: Int) = ByteArray(width * height * 3) { value.toByte() }
}
