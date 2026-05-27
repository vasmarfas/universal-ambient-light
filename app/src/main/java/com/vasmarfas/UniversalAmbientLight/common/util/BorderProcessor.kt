package com.vasmarfas.UniversalAmbientLight.common.util

import java.nio.ByteBuffer

/**
 * Detects black-bar borders independently for top/right/bottom/left so an
 * asymmetric letterbox (e.g. status bar at the top only) is handled correctly.
 *
 * Each axis-end is scanned by 3 probe lines at 25%/50%/75% of the other axis.
 * The first row/column whose probes include at least one non-black pixel marks
 * where content starts; everything outside that boundary is cropped.
 *
 * Jitter is filtered by requiring [stabilityDetections] consecutive matching
 * detections before switching to a new border. The very first known border is
 * adopted immediately so the user sees an effect on the first detection cycle
 * rather than after many seconds.
 *
 * Thread-safety: a single processor is used from one capture thread.
 * [blackThreshold] and [stabilityDetections] are volatile to allow the
 * preference listener to update them at any time.
 */
class BorderProcessor(
    initialBlackThreshold: Int = 18,
    initialStabilityDetections: Int = 3,
) {
    @Volatile
    var blackThreshold: Int = initialBlackThreshold

    /** Consecutive matching detections required before switching to a new border. */
    @Volatile
    var stabilityDetections: Int = initialStabilityDetections

    private var mPreviousBorder: BorderRect? = null
    var currentBorder: BorderRect? = null
        private set
    private var mConsistentDetections = 0
    private var mDetectCounter = 0

    /** Reusable destination buffer for the crop output. */
    private var mCropBuffer: ByteArray? = null

    private fun checkNewBorder(newBorder: BorderRect) {
        val previous = mPreviousBorder
        if (previous == null) {
            // First detection cycle: record baseline only. Activation requires the
            // same stability check as deactivation — keeps both transitions smooth
            // and avoids an abrupt switch on the very first detection (which can
            // disrupt the downstream pipeline when dimensions suddenly change).
            mPreviousBorder = newBorder
            mConsistentDetections = 1
            return
        }
        if (previous == newBorder) {
            mConsistentDetections++
            val needed = stabilityDetections.coerceAtLeast(1)
            if (mConsistentDetections < needed) return
            // Stable result. "no border" (isKnown=false) is also a valid stable
            // state — it means letterbox disappeared, so we drop the crop.
            val desired: BorderRect? = if (newBorder.isKnown) newBorder else null
            if (currentBorder != desired) currentBorder = desired
        } else {
            mPreviousBorder = newBorder
            mConsistentDetections = 1
        }
    }

    /** Flat-RGB path (3 bytes per pixel, row-major). */
    fun parseBorderRgb(rgb: ByteArray, width: Int, height: Int) {
        checkNewBorder(findBorderRgb(rgb, width, height))
    }

    /** MediaProjection ImageReader path (RGBA buffer with strides). */
    fun parseBorder(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        checkNewBorder(findBorderRgba(buffer, width, height, rowStride, pixelStride))
    }

    fun applyBorderCrop(rgb: ByteArray, width: Int, height: Int): CropResult {
        parseBorderRgb(rgb, width, height)
        return applyKnownBorderCrop(rgb, width, height)
    }

    fun applyKnownBorderCrop(rgb: ByteArray, width: Int, height: Int): CropResult {
        val border = currentBorder ?: return CropResult(rgb, width, height)
        if (!border.isKnown) return CropResult(rgb, width, height)

        val top = border.top.coerceAtLeast(0)
        val bottom = border.bottom.coerceAtLeast(0)
        val left = border.left.coerceAtLeast(0)
        val right = border.right.coerceAtLeast(0)
        // Don't crop more than half on any axis — protects against runaway detections.
        val maxV = height / 2 - 1
        val maxH = width / 2 - 1
        val t = top.coerceAtMost(maxV)
        val b = bottom.coerceAtMost(maxV)
        val l = left.coerceAtMost(maxH)
        val r = right.coerceAtMost(maxH)

        if (t == 0 && b == 0 && l == 0 && r == 0) return CropResult(rgb, width, height)

        val newW = width - l - r
        val newH = height - t - b
        if (newW <= 0 || newH <= 0) return CropResult(rgb, width, height)

        val needed = newW * newH * 3
        var buffer = mCropBuffer
        if (buffer == null || buffer.size != needed) {
            buffer = ByteArray(needed)
            mCropBuffer = buffer
        }
        val rowBytes = newW * 3
        var dst = 0
        for (y in 0 until newH) {
            val srcRow = (t + y) * width * 3 + l * 3
            System.arraycopy(rgb, srcRow, buffer, dst, rowBytes)
            dst += rowBytes
        }
        // Return a fresh copy: the Hyperion executor reads the buffer asynchronously
        // and the encoder thread will write the next frame into mCropBuffer before
        // the executor finishes. Without a copy the executor sees a torn frame and
        // downstream code (WLED packetisation, smoothing) can stall briefly.
        return CropResult(buffer.copyOf(), newW, newH)
    }

    /**
     * One-call encoder helper:
     *  - honors [AppOptions.borderDetectionEnabled];
     *  - re-runs detection every [AppOptions.borderCheckIntervalFrames] frames;
     *  - applies the cached crop on every frame so the output stays cropped
     *    between detections;
     *  - keeps [AppOptions.borderThreshold] in sync with this processor.
     */
    fun applyForEncoder(
        rgb: ByteArray, width: Int, height: Int, options: AppOptions,
    ): CropResult {
        if (!options.borderDetectionEnabled) {
            mDetectCounter = 0
            currentBorder = null
            mPreviousBorder = null
            mConsistentDetections = 0
            return CropResult(rgb, width, height)
        }
        blackThreshold = options.borderThreshold
        val interval = options.borderCheckIntervalFrames.coerceAtLeast(1)
        if (++mDetectCounter >= interval) {
            mDetectCounter = 0
            return applyBorderCrop(rgb, width, height)
        }
        return applyKnownBorderCrop(rgb, width, height)
    }

    private fun findBorderRgb(rgb: ByteArray, width: Int, height: Int): BorderRect {
        if (width <= 0 || height <= 0 || rgb.size < width * height * 3) {
            return BorderRect.UNKNOWN
        }
        val rowBytes = width * 3
        // Allow detection up to half of each axis so we catch wide letterbox
        // (~2.39:1 content on a 16:9 phone leaves ~45% black above and below
        // the picture — wider than the previous 1/3 cap could see).
        val maxV = height / 2
        val maxH = width / 2
        // Sample density. With ~32 probes per row/column the status-bar icons
        // (≈10% of the row width) can't trip a false "non-black" verdict, but
        // we still spend < width/32 * width pixels per axis — negligible.
        val xStep = maxOf(1, width / NUM_SAMPLES)
        val yStep = maxOf(1, height / NUM_SAMPLES)

        var top = -1
        for (y in 0 until maxV) {
            if (!isRowBlack(rgb, rowBytes, y, width, xStep)) {
                top = y; break
            }
        }
        var bottom = -1
        for (y in height - 1 downTo height - maxV) {
            if (!isRowBlack(rgb, rowBytes, y, width, xStep)) {
                bottom = height - 1 - y; break
            }
        }
        var left = -1
        for (x in 0 until maxH) {
            if (!isColBlack(rgb, rowBytes, x, height, yStep)) {
                left = x; break
            }
        }
        var right = -1
        for (x in width - 1 downTo width - maxH) {
            if (!isColBlack(rgb, rowBytes, x, height, yStep)) {
                right = width - 1 - x; break
            }
        }

        // Quantize each edge to ~3% bands so single-pixel jitter at the edges
        // doesn't break BorderRect equality between consecutive detections.
        // Without this, "no border" content with one stray dark pixel produces
        // BorderRect(1,0,0,0) one frame and BorderRect(0,1,0,0) the next →
        // mConsistentDetections never reaches stabilityDetections.
        return BorderRect(
            quantize(top, height),
            quantize(right, width),
            quantize(bottom, height),
            quantize(left, width)
        )
    }

    private fun quantize(value: Int, frameSize: Int): Int {
        if (value <= 0) return value  // preserve -1 (unknown) as-is; 0 stays 0
        val band = maxOf(1, frameSize / 32)
        return (value / band) * band
    }

    /** Row is "black" when at least [BLACK_FRACTION] of sampled pixels test as black. */
    private fun isRowBlack(rgb: ByteArray, rowBytes: Int, y: Int, width: Int, xStep: Int): Boolean {
        val base = y * rowBytes
        var samples = 0
        var blacks = 0
        var x = 0
        while (x < width) {
            samples++
            if (isBlackAt(rgb, base + x * 3)) blacks++
            x += xStep
        }
        return samples > 0 && blacks * 100 >= samples * BLACK_PERCENT
    }

    private fun isColBlack(
        rgb: ByteArray,
        rowBytes: Int,
        x: Int,
        height: Int,
        yStep: Int,
    ): Boolean {
        var samples = 0
        var blacks = 0
        var y = 0
        while (y < height) {
            samples++
            if (isBlackAt(rgb, y * rowBytes + x * 3)) blacks++
            y += yStep
        }
        return samples > 0 && blacks * 100 >= samples * BLACK_PERCENT
    }

    private fun findBorderRgba(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
    ): BorderRect {
        val xa = width / 4
        val xb = width / 2
        val xc = (3 * width) / 4
        val ya = height / 4
        val yb = height / 2
        val yc = (3 * height) / 4
        val maxV = height / 2
        val maxH = width / 2

        fun probeNonBlack(off: Int): Boolean {
            if (off < 0 || off + 2 >= buffer.limit()) return false
            val r = buffer.get(off).toInt() and 0xff
            val g = buffer.get(off + 1).toInt() and 0xff
            val b = buffer.get(off + 2).toInt() and 0xff
            return !isBlack(r, g, b)
        }

        buffer.position(0).mark()

        var top = -1
        for (y in 0 until maxV) {
            val base = y * rowStride
            if (probeNonBlack(base + xa * pixelStride) ||
                probeNonBlack(base + xb * pixelStride) ||
                probeNonBlack(base + xc * pixelStride)
            ) {
                top = y; break
            }
        }
        var bottom = -1
        for (y in height - 1 downTo height - maxV) {
            val base = y * rowStride
            if (probeNonBlack(base + xa * pixelStride) ||
                probeNonBlack(base + xb * pixelStride) ||
                probeNonBlack(base + xc * pixelStride)
            ) {
                bottom = height - 1 - y; break
            }
        }
        var left = -1
        for (x in 0 until maxH) {
            if (probeNonBlack(ya * rowStride + x * pixelStride) ||
                probeNonBlack(yb * rowStride + x * pixelStride) ||
                probeNonBlack(yc * rowStride + x * pixelStride)
            ) {
                left = x; break
            }
        }
        var right = -1
        for (x in width - 1 downTo width - maxH) {
            if (probeNonBlack(ya * rowStride + x * pixelStride) ||
                probeNonBlack(yb * rowStride + x * pixelStride) ||
                probeNonBlack(yc * rowStride + x * pixelStride)
            ) {
                right = width - 1 - x; break
            }
        }

        buffer.reset()
        return BorderRect(top, right, bottom, left)
    }

    private fun isBlackAt(rgb: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 2 >= rgb.size) return true
        val r = rgb[offset].toInt() and 0xff
        val g = rgb[offset + 1].toInt() and 0xff
        val b = rgb[offset + 2].toInt() and 0xff
        return isBlack(r, g, b)
    }

    private fun isBlack(r: Int, g: Int, b: Int): Boolean {
        val t = blackThreshold
        return r < t && g < t && b < t
    }

    companion object {
        /** How many points to sample per row/column. */
        private const val NUM_SAMPLES = 32

        /** Row/column counts as "black" when ≥ this fraction (in percent) of sampled pixels are black. */
        private const val BLACK_PERCENT = 85
    }

    data class CropResult(val rgb: ByteArray, val width: Int, val height: Int)

    /** Crop offsets in pixels from each edge. `-1` means "all probes black on that edge". */
    data class BorderRect(val top: Int, val right: Int, val bottom: Int, val left: Int) {
        /** At least one edge had a non-black region — enough to attempt a crop. */
        val isKnown: Boolean
            get() = top > 0 || right > 0 || bottom > 0 || left > 0

        companion object {
            val UNKNOWN = BorderRect(-1, -1, -1, -1)
        }
    }
}
