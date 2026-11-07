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

    @Test
    fun `subtitles in the bottom bar count as picture by default`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = subtitled(letterbox())
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(16, 0, 6, 0), processor.currentBorder)
    }

    @Test
    fun `subtitles in the bottom bar do not reduce the crop when ignored`() {
        val processor = subtitleProcessor()
        val frame = subtitled(letterbox())
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `the encoder path passes the subtitle option to the processor`() {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = subtitled(letterbox())
        val options = options(ignoreSubtitles = true)
        repeat(2) { processor.applyForEncoder(frame, SIZE, SIZE, options) }
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `a bottom bar smaller than the top one is kept when subtitles are ignored`() {
        val processor = subtitleProcessor()
        val frame = frame { _, y -> if (y < BAR || y >= SIZE - BAR / 2) 0 else LIT }
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(16, 0, 8, 0), processor.currentBorder)
    }

    @Test
    fun `a bottom bar never exceeds the top one when subtitles are ignored`() {
        val processor = subtitleProcessor()
        val frame = frame { _, y -> if (y < BAR / 2 || y >= SIZE - BAR) 0 else LIT }
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertEquals(BorderRect(8, 0, 8, 0), processor.currentBorder)
    }

    @Test
    fun `dark bottom corners of a full frame are not cropped when subtitles are ignored`() {
        val processor = subtitleProcessor()
        val frame = frame { x, y -> if (y >= SIZE / 2 && (x < 8 || x >= SIZE - 8)) 0 else LIT }
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a dark scene drops the crop by default`() {
        val processor = detectedLetterbox()
        val dark = frame { _, _ -> 0 }
        repeat(2) { processor.parseBorderRgb(dark, SIZE, SIZE) }
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a dark scene with subtitles keeps the crop when they are ignored`() {
        val processor = detectedLetterbox().apply { ignoreSubtitles = true }
        val dark = subtitled(frame { _, _ -> 0 })
        repeat(5) { processor.parseBorderRgb(dark, SIZE, SIZE) }
        assertEquals(BorderRect(16, 0, 16, 0), processor.currentBorder)
    }

    @Test
    fun `a dark frame does not invent a crop when subtitles are ignored`() {
        val processor = subtitleProcessor()
        val dark = subtitled(frame { _, _ -> 0 })
        repeat(2) { processor.parseBorderRgb(dark, SIZE, SIZE) }
        assertNull(processor.currentBorder)
    }

    @Test
    fun `a full frame after a dark scene removes the kept crop`() {
        val processor = detectedLetterbox().apply { ignoreSubtitles = true }
        val dark = frame { _, _ -> 0 }
        val full = frame { _, _ -> LIT }
        repeat(2) { processor.parseBorderRgb(dark, SIZE, SIZE) }
        repeat(2) { processor.parseBorderRgb(full, SIZE, SIZE) }
        assertNull(processor.currentBorder)
    }

    private fun detectedLetterbox(): BorderProcessor {
        val processor = BorderProcessor(initialStabilityDetections = 1)
        val frame = letterbox()
        processor.parseBorderRgb(frame, SIZE, SIZE)
        processor.parseBorderRgb(frame, SIZE, SIZE)
        return processor
    }

    private fun subtitleProcessor() =
        BorderProcessor(initialStabilityDetections = 1).apply { ignoreSubtitles = true }

    private fun options(
        enabled: Boolean = true,
        intervalFrames: Int = 1,
        ignoreSubtitles: Boolean = false,
    ) = AppOptions(
        horizontalLED = 20,
        verticalLED = 10,
        frameRate = 30,
        useAverageColor = false,
        captureQuality = 100,
        borderDetectionEnabled = enabled,
        borderThreshold = 18,
        borderCheckIntervalFrames = intervalFrames,
        borderIgnoreSubtitles = ignoreSubtitles,
    )

    private fun letterbox(barLevel: Int = 0) =
        frame { _, y -> if (y < BAR || y >= SIZE - BAR) barLevel else LIT }

    /** Две строки белого текста в нижней полосе: шире половины кадра, но не доходят до углов. */
    private fun subtitled(base: ByteArray): ByteArray {
        val data = base.copyOf()
        for (y in intArrayOf(SIZE - 12, SIZE - 11, SIZE - 8, SIZE - 7)) {
            for (x in SIZE / 4 until SIZE * 3 / 4) {
                val offset = (y * SIZE + x) * 3
                data.fill(LIT.toByte(), offset, offset + 3)
            }
        }
        return data
    }

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
