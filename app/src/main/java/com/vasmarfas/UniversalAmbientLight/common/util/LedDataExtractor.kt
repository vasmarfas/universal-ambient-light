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
        var startCorner = "top_left"
        var direction = "clockwise"
        
        try {
            val prefs = Preferences(context)
            xLed = prefs.getInt(R.string.pref_key_x_led)
            yLed = prefs.getInt(R.string.pref_key_y_led)
            startCorner = prefs.getString(R.string.pref_key_led_start_corner, "top_left") ?: "top_left"
            direction = prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise"
        } catch (e: Exception) {
            if (logsEnabled) Log.w(TAG, "Failed to get LED settings from preferences", e)
        }

        return extractPerimeterPixels(screenData, width, height, xLed, yLed, startCorner, direction, reuseBuffer)
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
            // Calculate total LED count: 2 * (xLed + yLed)
            // Assuming xLed and yLed are the counts of LEDs on the respective sides
            val totalLEDs = 2 * (xLed + yLed)
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
        xLed: Int,
        yLed: Int,
        startCorner: String,
        direction: String,
        reuseBuffer: Array<ColorRgb>?
    ): Array<ColorRgb> {
        // Total LEDs = 2 * (x + y)
        val totalLEDs = 2 * (xLed + yLed)
        if (totalLEDs == 0) return emptyArray()

        // Diagnostic logging
        val expectedSize = width * height * 3
        if (logsEnabled) Log.d(TAG, "extractPerimeterPixels: width=$width, height=$height, xLed=$xLed, yLed=$yLed, totalLEDs=$totalLEDs")
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

        // Calculate step sizes
        val stepX = width.toFloat() / xLed
        val stepY = height.toFloat() / yLed

        // Determine edge order based on start corner and direction
        val edges = getEdgeOrder(startCorner, direction)
        
        // Process each edge in order
        for (edge in edges) {
            when (edge) {
                "top_lr" -> {
                    // Top edge (left to right)
                    for (i in 0 until xLed) {
                        val x = min((i * stepX + stepX / 2).toInt(), width - 1)
                        val y = 0
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "top_rl" -> {
                    // Top edge (right to left)
                    for (i in 0 until xLed) {
                        val x = min(((xLed - 1 - i) * stepX + stepX / 2).toInt(), width - 1)
                        val y = 0
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "right_tb" -> {
                    // Right edge (top to bottom)
                    for (i in 0 until yLed) {
                        val x = width - 1
                        val y = min((i * stepY + stepY / 2).toInt(), height - 1)
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "right_bt" -> {
                    // Right edge (bottom to top)
                    for (i in 0 until yLed) {
                        val x = width - 1
                        val y = min(((yLed - 1 - i) * stepY + stepY / 2).toInt(), height - 1)
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "bottom_rl" -> {
                    // Bottom edge (right to left)
                    for (i in 0 until xLed) {
                        val x = min(((xLed - 1 - i) * stepX + stepX / 2).toInt(), width - 1)
                        val y = height - 1
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "bottom_lr" -> {
                    // Bottom edge (left to right)
                    for (i in 0 until xLed) {
                        val x = min((i * stepX + stepX / 2).toInt(), width - 1)
                        val y = height - 1
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "left_bt" -> {
                    // Left edge (bottom to top)
                    for (i in 0 until yLed) {
                        val x = 0
                        val y = min(((yLed - 1 - i) * stepY + stepY / 2).toInt(), height - 1)
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
                "left_tb" -> {
                    // Left edge (top to bottom)
                    for (i in 0 until yLed) {
                        val x = 0
                        val y = min((i * stepY + stepY / 2).toInt(), height - 1)
                        setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
                    }
                }
            }
        }

        // Fill remaining if any (should not happen)
        while (ledIdx < totalLEDs) {
            ledData[ledIdx++].set(0, 0, 0)
        }

        return ledData
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
