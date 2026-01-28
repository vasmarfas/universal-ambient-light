package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb
import kotlin.math.max
import kotlin.math.min

object LedDataExtractor {
    private const val TAG = "LedDataExtractor"
    private val logsEnabled = false
    /**
     * Extract LED data reusing an existing buffer to avoid GC overhead.
     * @param reuseBuffer Optional buffer to reuse. If null or wrong size, a new one is allocated.
     * @return The array containing extracted data (either reused or new).
     */
    fun extractLEDData(
        context: Context,
        screenData: ByteArray,
        width: Int,
        height: Int,
        reuseBuffer: Array<ColorRgb>?
    ): Array<ColorRgb> {
        var xLed = 0
        var yLed = 0
        var topLed = 0
        var rightLed = 0
        var bottomLed = 0
        var leftLed = 0
        var startCorner = "top_left"
        var direction = "clockwise"
        var sideTop = "enabled"
        var sideRight = "enabled"
        var sideBottom = "enabled"
        var sideLeft = "enabled"
        var bottomGap = 0
        var captureMarginPercent = 0
        var ledOffset = 0
        
        try {
            val prefs = Preferences(context)
            xLed = prefs.getInt(R.string.pref_key_x_led)
            yLed = prefs.getInt(R.string.pref_key_y_led)
            // Новые отдельные значения для каждой стороны. Если их ещё нет – используем старые x/y.
            topLed = prefs.getInt(R.string.pref_key_led_count_top, xLed)
            rightLed = prefs.getInt(R.string.pref_key_led_count_right, yLed)
            bottomLed = prefs.getInt(R.string.pref_key_led_count_bottom, xLed)
            leftLed = prefs.getInt(R.string.pref_key_led_count_left, yLed)
            startCorner = prefs.getString(R.string.pref_key_led_start_corner, "top_left") ?: "top_left"
            direction = prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise"
            sideTop = prefs.getString(R.string.pref_key_led_side_top, "enabled") ?: "enabled"
            sideRight = prefs.getString(R.string.pref_key_led_side_right, "enabled") ?: "enabled"
            sideBottom = prefs.getString(R.string.pref_key_led_side_bottom, "enabled") ?: "enabled"
            sideLeft = prefs.getString(R.string.pref_key_led_side_left, "enabled") ?: "enabled"
            bottomGap = prefs.getInt(R.string.pref_key_bottom_gap, 0)
            captureMarginPercent = prefs.getInt(R.string.pref_key_capture_margin, 0)
            ledOffset = prefs.getInt(R.string.pref_key_led_offset, 0)
        } catch (e: Exception) {
            if (logsEnabled) Log.w(TAG, "Failed to get LED settings from preferences", e)
        }

        return extractPerimeterPixels(
            screenData, width, height,
            topLed, rightLed, bottomLed, leftLed,
            startCorner, direction,
            sideTop, sideRight, sideBottom, sideLeft, bottomGap,
            captureMarginPercent, ledOffset, reuseBuffer
        )
    }

    fun extractLEDData(
        context: Context,
        screenData: ByteArray,
        width: Int,
        height: Int
    ): Array<ColorRgb> {
        return extractLEDData(context, screenData, width, height, null)
    }

    fun getLedCount(context: Context): Int {
        return try {
            val prefs = Preferences(context)
            val xLed = prefs.getInt(R.string.pref_key_x_led)
            val yLed = prefs.getInt(R.string.pref_key_y_led)

            val topLed = prefs.getInt(R.string.pref_key_led_count_top, xLed)
            val rightLed = prefs.getInt(R.string.pref_key_led_count_right, yLed)
            val bottomLed = prefs.getInt(R.string.pref_key_led_count_bottom, xLed)
            val leftLed = prefs.getInt(R.string.pref_key_led_count_left, yLed)

            // Если заданы явные значения для сторон – используем их.
            var totalLEDs = topLed + rightLed + bottomLed + leftLed
            if (totalLEDs <= 0) {
                // Фолбэк на старую схему 2 * (x + y)
                totalLEDs = 2 * (xLed + yLed)
            }
            max(totalLEDs, 1)
        } catch (e: Exception) {
            if (logsEnabled) Log.w(TAG, "Failed to get LED count, using default", e)
            60
        }
    }

    /**
     * Extract perimeter pixels from 2D screen data
     * Order depends on start corner and direction
     */
    private fun extractPerimeterPixels(
        screenData: ByteArray,
        width: Int,
        height: Int,
        topLed: Int,
        rightLed: Int,
        bottomLed: Int,
        leftLed: Int,
        startCorner: String,
        direction: String,
        sideTop: String,
        sideRight: String,
        sideBottom: String,
        sideLeft: String,
        bottomGap: Int,
        captureMarginPercent: Int,
        ledOffset: Int,
        reuseBuffer: Array<ColorRgb>?
    ): Array<ColorRgb> {
        val topCount = topLed.coerceAtLeast(0)
        val rightCount = rightLed.coerceAtLeast(0)
        val bottomCount = bottomLed.coerceAtLeast(0)
        val leftCount = leftLed.coerceAtLeast(0)

        // Calculate total LEDs considering which sides are installed
        var totalLEDs = 0
        if (sideTop != "not_installed") totalLEDs += topCount
        if (sideRight != "not_installed") totalLEDs += rightCount
        if (sideBottom != "not_installed") totalLEDs += bottomCount
        if (sideLeft != "not_installed") totalLEDs += leftCount
        if (totalLEDs == 0) return emptyArray()

        // Diagnostic logging
        val expectedSize = width * height * 3
        if (logsEnabled) Log.d(TAG, "extractPerimeterPixels: width=$width, height=$height, top=$topCount, right=$rightCount, bottom=$bottomCount, left=$leftCount, totalLEDs=$totalLEDs")
        if (logsEnabled) Log.d(TAG, "startCorner=$startCorner, direction=$direction")
        if (logsEnabled) Log.d(TAG, "screenData.size=${screenData.size}, expected=$expectedSize")
        
        if (screenData.size < expectedSize) {
            if (logsEnabled) Log.e(TAG, "screenData is too small! Expected at least $expectedSize bytes, got ${screenData.size}")
        }

        val ledData: Array<ColorRgb>
        if (reuseBuffer != null && reuseBuffer.size == totalLEDs) {
            ledData = reuseBuffer
        } else {
            ledData = Array(totalLEDs) { ColorRgb(0, 0, 0) }
        }

        var ledIdx = 0

        // Calculate capture area with margin
        val margin = captureMarginPercent.coerceIn(0, 40) // не даём вырезать слишком много
        val marginX = width * margin / 100f
        val marginY = height * margin / 100f

        val captureLeft = marginX
        val captureRight = width - marginX
        val captureTop = marginY
        val captureBottom = height - marginY

        val captureWidth = (captureRight - captureLeft).coerceAtLeast(1f)
        val captureHeight = (captureBottom - captureTop).coerceAtLeast(1f)

        // Calculate step sizes по области захвата для каждой стороны отдельно
        fun sideStep(length: Float, count: Int): Float {
            return if (count <= 0) 0f else length / count
        }

        val stepTop = sideStep(captureWidth, topCount)
        val stepRight = sideStep(captureHeight, rightCount)
        val stepBottom = sideStep(captureWidth, bottomCount)
        val stepLeft = sideStep(captureHeight, leftCount)

        // Calculate gap range for bottom edge
        val gapStart = if (bottomGap > 0 && bottomCount > 0) (bottomCount - bottomGap) / 2 else -1
        val gapEnd = if (bottomGap > 0 && bottomCount > 0) gapStart + bottomGap else -1

        // Determine edge order based on start corner and direction
        val edges = getEdgeOrder(startCorner, direction)
        
        // Process each edge in order
        for (edge in edges) {
            val sideMode = when {
                edge.startsWith("top_") -> sideTop
                edge.startsWith("right_") -> sideRight
                edge.startsWith("bottom_") -> sideBottom
                edge.startsWith("left_") -> sideLeft
                else -> "enabled"
            }
            
            // Skip if not installed
            if (sideMode == "not_installed") continue
            
            when (edge) {
                "top_lr" -> {
                    // Top edge (left to right)
                    for (i in 0 until topCount) {
                        if (sideMode == "enabled") {
                            val x = min((captureLeft + i * stepTop + stepTop / 2).toInt(), width - 1)
                            val y = captureTop.toInt().coerceAtLeast(0)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "top_rl" -> {
                    // Top edge (right to left)
                    for (i in 0 until topCount) {
                        if (sideMode == "enabled") {
                            val x = min((captureLeft + (topCount - 1 - i) * stepTop + stepTop / 2).toInt(), width - 1)
                            val y = captureTop.toInt().coerceAtLeast(0)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "right_tb" -> {
                    // Right edge (top to bottom)
                    for (i in 0 until rightCount) {
                        if (sideMode == "enabled") {
                            val x = captureRight.toInt().coerceAtMost(width - 1)
                            val y = min((captureTop + i * stepRight + stepRight / 2).toInt(), height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "right_bt" -> {
                    // Right edge (bottom to top)
                    for (i in 0 until rightCount) {
                        if (sideMode == "enabled") {
                            val x = captureRight.toInt().coerceAtMost(width - 1)
                            val y = min((captureTop + (rightCount - 1 - i) * stepRight + stepRight / 2).toInt(), height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "bottom_rl" -> {
                    // Bottom edge (right to left)
                    for (i in 0 until bottomCount) {
                        val ledIndex = bottomCount - 1 - i
                        val isInGap = bottomGap > 0 && ledIndex >= gapStart && ledIndex < gapEnd
                        if (sideMode == "enabled" && !isInGap) {
                            val x = min((captureLeft + (bottomCount - 1 - i) * stepBottom + stepBottom / 2).toInt(), width - 1)
                            val y = captureBottom.toInt().coerceAtMost(height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "bottom_lr" -> {
                    // Bottom edge (left to right)
                    for (i in 0 until bottomCount) {
                        val isInGap = bottomGap > 0 && i >= gapStart && i < gapEnd
                        if (sideMode == "enabled" && !isInGap) {
                            val x = min((captureLeft + i * stepBottom + stepBottom / 2).toInt(), width - 1)
                            val y = captureBottom.toInt().coerceAtMost(height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "left_bt" -> {
                    // Left edge (bottom to top)
                    for (i in 0 until leftCount) {
                        if (sideMode == "enabled") {
                            val x = captureLeft.toInt().coerceAtLeast(0)
                            val y = min((captureTop + (leftCount - 1 - i) * stepLeft + stepLeft / 2).toInt(), height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
                "left_tb" -> {
                    // Left edge (top to bottom)
                    for (i in 0 until leftCount) {
                        if (sideMode == "enabled") {
                            val x = captureLeft.toInt().coerceAtLeast(0)
                            val y = min((captureTop + i * stepLeft + stepLeft / 2).toInt(), height - 1)
                            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
            }
        }

        // Fill remaining if any (should not happen)
        while (ledIdx < totalLEDs) {
            ledData[ledIdx++].set(0, 0, 0)
        }

        // Apply LED offset (rotation along perimeter)
        val offset = (ledOffset % totalLEDs + totalLEDs) % totalLEDs
        if (offset == 0) return ledData

        val rotated = Array(totalLEDs) { ColorRgb(0, 0, 0) }
        for (i in 0 until totalLEDs) {
            val targetIndex = (i + offset) % totalLEDs
            rotated[targetIndex].set(ledData[i])
        }

        return rotated
    }

    /**
     * Determine edge processing order based on start corner and direction
     * Clockwise: top→right→bottom→left
     * Counterclockwise: opposite direction
     */
    private fun getEdgeOrder(startCorner: String, direction: String): List<String> {
        return when (startCorner) {
            "top_left" -> {
                if (direction == "clockwise") {
                    // Start top-left, go clockwise: right along top, down right side, left along bottom, up left side
                    listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
                } else {
                    // Start top-left, go counterclockwise: down left side, right along bottom, up right side, left along top
                    listOf("left_tb", "bottom_lr", "right_bt", "top_rl")
                }
            }
            "top_right" -> {
                if (direction == "clockwise") {
                    // Start top-right, go clockwise: down right side, left along bottom, up left side, right along top
                    listOf("right_tb", "bottom_rl", "left_bt", "top_lr")
                } else {
                    // Start top-right, go counterclockwise: left along top, down left side, right along bottom, up right side
                    listOf("top_rl", "left_tb", "bottom_lr", "right_bt")
                }
            }
            "bottom_right" -> {
                if (direction == "clockwise") {
                    // Start bottom-right, go clockwise: left along bottom, up left side, right along top, down right side
                    listOf("bottom_rl", "left_bt", "top_lr", "right_tb")
                } else {
                    // Start bottom-right, go counterclockwise: up right side, left along top, down left side, right along bottom
                    listOf("right_bt", "top_rl", "left_tb", "bottom_lr")
                }
            }
            "bottom_left" -> {
                if (direction == "clockwise") {
                    // Start bottom-left, go clockwise: up left side, right along top, down right side, left along bottom
                    listOf("left_bt", "top_lr", "right_tb", "bottom_rl")
                } else {
                    // Start bottom-left, go counterclockwise: right along bottom, up right side, left along top, down left side
                    listOf("bottom_lr", "right_bt", "top_rl", "left_tb")
                }
            }
            else -> {
                // Default to top_left clockwise
                if (logsEnabled) Log.w(TAG, "Unknown start corner: $startCorner, using default")
                listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
            }
        }
    }

    private fun setPixelFromData(
        dest: ColorRgb,
        screenData: ByteArray,
        width: Int,
        x: Int,
        y: Int
    ) {
        val srcIdx = (y * width + x) * 3
        if (srcIdx >= 0 && srcIdx + 2 < screenData.size) {
            val r = screenData[srcIdx].toInt() and 0xFF
            val g = screenData[srcIdx + 1].toInt() and 0xFF
            val b = screenData[srcIdx + 2].toInt() and 0xFF
            dest.set(r, g, b)
        } else {
            if (srcIdx < 0 || srcIdx + 2 >= screenData.size) {
                if (logsEnabled) Log.w(TAG, "Pixel out of bounds: x=$x, y=$y, srcIdx=$srcIdx, size=${screenData.size}")
            }
            dest.set(0, 0, 0)
        }
    }
}
