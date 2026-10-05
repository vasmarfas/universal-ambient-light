package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.util.BorderProcessor.BorderRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

class BorderProcessorTest {

    @Test
    fun `letterbox bars above and below are detected`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = letterbox()
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `pillarbox bars on the sides are detected`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = pillarbox()
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(0, 16, 0, 16), processor.currentBorder)
    }

    @Test
    fun `a frame filling the screen is left uncropped`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = frame { _, _ -> LIT }
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a fully black frame is not treated as a crop`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = frame { _, _ -> 0 }
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertNull(processor.currentBorder)
    }

    @Test
    fun `bars brighter than the threshold are not cropped`() {
        val processor = BorderProcessor(initialBlackThreshold = 18, initialStabilityDetections = 1)
        val frame = letterbox(barLevel = 30)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a raised threshold makes the same dim bars count as black`() {
        val processor = BorderProcessor(initialBlackThreshold = 40, initialStabilityDetections = 1)
        val frame = letterbox(barLevel = 30)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `a border is kept until the required number of equal detections`() {
        val processor = BorderProcessor(initialStabilityDetections = 3)
        val frame = letterbox()
        repeat(2) { processor.parseBorderRgb(frame, SIZE, SIZE) }
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a jittering detection never switches the border`() {
        val processor = BorderProcessor(initialStabilityDetections = 3)
        val bars = letterbox()
        val full = frame { _, _ -> LIT }
        repeat(3) {
            processor.parseBorderRgb(bars, SIZE, SIZE)
            processor.parseBorderRgb(full, SIZE, SIZE)
        }
        assertNull(processor.currentBorder)
    }

    @Test
    fun `letterbox bars are detected in a strided rgba buffer`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val buffer = rgbaLetterbox()
        processor.parseBorder(buffer, SIZE, SIZE, ROW_STRIDE, PIXEL_STRIDE)
        processor.parseBorder(buffer, SIZE, SIZE, ROW_STRIDE, PIXEL_STRIDE)
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `a black rgba buffer is not treated as a crop`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val buffer = rgbaLetterbox(barLevel = 0, litLevel = 0)
        processor.parseBorder(buffer, SIZE, SIZE, ROW_STRIDE, PIXEL_STRIDE)
        processor.parseBorder(buffer, SIZE, SIZE, ROW_STRIDE, PIXEL_STRIDE)
        assertNull(processor.currentBorder)
    }

    @Test
    fun `the probed rgba buffer is rewound for the caller`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val buffer = rgbaLetterbox()
        processor.parseBorder(buffer, SIZE, SIZE, ROW_STRIDE, PIXEL_STRIDE)
        assertEquals(0, buffer.position())
    }

    @Test
    fun `cropping cuts the detected bars off the frame`() {
        val processor = detectedLetterbox()
        val result = processor.applyKnownBorderCrop(letterbox(), SIZE, SIZE)
        assertEquals(SIZE - 32, result.height)
    }

    @Test
    fun `the cropped frame starts at the first lit row`() {
        val processor = detectedLetterbox()
        val result = processor.applyKnownBorderCrop(letterbox(), SIZE, SIZE)
        assertEquals(LIT, result.rgb[0].toInt() and 0xff)
    }

    @Test
    fun `consecutive cropped frames reuse one buffer`() {
        val processor = detectedLetterbox()
        val first = processor.applyKnownBorderCrop(letterbox(), SIZE, SIZE)
        val second = processor.applyKnownBorderCrop(letterbox(), SIZE, SIZE)
        assertSame(first.rgb, second.rgb)
    }

    @Test
    fun `an unknown border leaves the frame buffer as it is`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = letterbox()
        assertSame(frame, processor.applyKnownBorderCrop(frame, SIZE, SIZE).rgb)
    }

    @Test
    fun `turning border detection off forgets the stored border`() {
        val processor = detectedLetterbox()
        processor.applyForEncoder(letterbox(), SIZE, SIZE, options(enabled = false))
        assertNull(processor.currentBorder)
    }

    @Test
    fun `the border is not measured before the check interval elapses`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val options = options(intervalFrames = 3)
        repeat(5) { processor.applyForEncoder(letterbox(), SIZE, SIZE, options) }
        assertNull(processor.currentBorder)
    }

    @Test
    fun `the border is measured once the check interval elapses`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val options = options(intervalFrames = 3)
        repeat(6) { processor.applyForEncoder(letterbox(), SIZE, SIZE, options) }
        assertNotNull(processor.currentBorder)
    }

    private fun detectedLetterbox(): BorderProcessor {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = letterbox()
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        return processor
    }

    private fun options(enabled: Boolean = true, intervalFrames: Int = 1) = AppOptions(
        horizontalLED = 20,
        verticalLED = 10,
        frameRate = 30,
        useAverageColor = false,
        captureQuality = 100,
        borderDetectionEnabled = enabled,
        borderThreshold = 18,
        borderCheckIntervalFrames = intervalFrames,
    )

    private fun letterbox(barLevel: Int = 0) =
        frame { _, y -> if (y < BAR || y >= SIZE - BAR) barLevel else LIT }

    private fun pillarbox(barLevel: Int = 0) =
        frame { x, _ -> if (x < BAR || x >= SIZE - BAR) barLevel else LIT }

    /** Кадр SIZE×SIZE в RGBA со stride — так его отдаёт ImageReader из MediaProjection. */
    private fun rgbaLetterbox(barLevel: Int = 0, litLevel: Int = LIT): ByteBuffer {
        val buffer = ByteBuffer.allocate(ROW_STRIDE * SIZE)
        for (y in 0 until SIZE) {
            val value = (if (y < BAR || y >= SIZE - BAR) barLevel else litLevel).toByte()
            for (x in 0 until SIZE) {
                val offset = y * ROW_STRIDE + x * PIXEL_STRIDE
                buffer.put(offset, value)
                buffer.put(offset + 1, value)
                buffer.put(offset + 2, value)
                buffer.put(offset + 3, -1)
            }
        }
        return buffer
    }

    /** Кадр SIZE×SIZE в упакованном RGB; [level] задаёт одинаковое значение всех трёх каналов. */
    private fun frame(level: (x: Int, y: Int) -> Int): ByteArray {
        val data = ByteArray(SIZE * SIZE * 3)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val value = level(x, y).toByte()
                val offset = (y * SIZE + x) * 3
                data[offset] = value
                data[offset + 1] = value
                data[offset + 2] = value
            }
        }
        return data
    }

    private companion object {
        const val SIZE = 64
        const val BAR = 16
        const val LIT = 200
        const val PIXEL_STRIDE = 4

        /** Строка кадра длиннее ширины: ImageReader выравнивает её собственным отступом. */
        const val ROW_STRIDE = SIZE * PIXEL_STRIDE + 16
    }
}
