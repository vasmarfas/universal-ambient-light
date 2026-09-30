package com.vasmarfas.UniversalAmbientLight.common.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сглаживание с включённым таймером живёт на HandlerThread, поэтому здесь проверяется
 * только то, что работает без Android-рантайма: пресеты и прямая отдача кадров.
 */
class ColorSmoothingTest {

    @Test
    fun `the off preset disables smoothing`() {
        val smoothing = ColorSmoothing(null)
        smoothing.applyPreset("off")
        assertFalse(smoothing.isEnabled())
    }

    @Test
    fun `a named preset enables smoothing`() {
        val smoothing = ColorSmoothing(null)
        smoothing.applyPreset("off")
        smoothing.applyPreset("smooth")
        assertTrue(smoothing.isEnabled())
    }

    @Test
    fun `an unknown preset falls back to an enabled one`() {
        val smoothing = ColorSmoothing(null)
        smoothing.applyPreset("off")
        smoothing.applyPreset("nonsense")
        assertTrue(smoothing.isEnabled())
    }

    @Test
    fun `preset names are case insensitive`() {
        val smoothing = ColorSmoothing(null)
        smoothing.applyPreset("OFF")
        assertFalse(smoothing.isEnabled())
    }

    @Test
    fun `disabled smoothing passes colors straight through`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        smoothing.setTargetColors(arrayOf(ColorRgb(10, 20, 30), ColorRgb(40, 50, 60)))
        assertArrayEquals(
            arrayOf(ColorRgb(10, 20, 30), ColorRgb(40, 50, 60)),
            sender.lastFrame
        )
    }

    @Test
    fun `the forwarded frame does not alias the source array`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        val source = arrayOf(ColorRgb(10, 20, 30))
        smoothing.setTargetColors(source)
        source[0].set(200, 200, 200)
        assertEquals(ColorRgb(10, 20, 30), sender.lastFrame?.get(0))
    }

    @Test
    fun `an output delay holds frames back even without smoothing`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        smoothing.setOutputDelay(200)
        smoothing.setTargetColors(arrayOf(ColorRgb(10, 20, 30)))
        assertEquals(0, sender.frames)
    }

    @Test
    fun `removing the delay passes frames straight through again`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        smoothing.setOutputDelay(200)
        smoothing.setOutputDelay(0)
        smoothing.setTargetColors(arrayOf(ColorRgb(10, 20, 30)))
        assertEquals(1, sender.frames)
    }

    @Test
    fun `an empty frame is not forwarded`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        smoothing.setTargetColors(emptyArray())
        assertEquals(0, sender.frames)
    }

    @Test
    fun `a null frame is not forwarded`() {
        val sender = RecordingSender()
        val smoothing = ColorSmoothing(sender)
        smoothing.setEnabled(false)
        smoothing.setTargetColors(null)
        assertEquals(0, sender.frames)
    }

    private class RecordingSender : ColorSmoothing.LedDataSender {
        var lastFrame: Array<ColorRgb>? = null
        var frames = 0

        override fun sendLedData(colors: Array<ColorRgb>) {
            lastFrame = colors
            frames++
        }
    }
}
