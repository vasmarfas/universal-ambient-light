package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb
import kotlin.math.max
import kotlin.math.min

object LedDataExtractor {
    private const val TAG = "LedDataExtractor"

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
        try {
            val prefs = Preferences(context)
            xLed = prefs.getInt(R.string.pref_key_x_led)
            yLed = prefs.getInt(R.string.pref_key_y_led)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get LED counts from preferences", e)
        }

        return extractPerimeterPixels(screenData, width, height, xLed, yLed, reuseBuffer)
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
            Log.w(TAG, "Failed to get LED count, using default", e)
            60
        }
    }

    /**
     * Extract perimeter pixels from 2D screen data
     * Order: top (left->right), right (top->bottom), bottom (right->left), left (bottom->top)
     */
    private fun extractPerimeterPixels(
        screenData: ByteArray,
        width: Int,
        height: Int,
        xLed: Int,
        yLed: Int,
        reuseBuffer: Array<ColorRgb>?
    ): Array<ColorRgb> {
        // Total LEDs = 2 * (x + y)
        val totalLEDs = 2 * (xLed + yLed)
        if (totalLEDs == 0) return emptyArray()

        val ledData: Array<ColorRgb>
        if (reuseBuffer != null && reuseBuffer.size == totalLEDs) {
            ledData = reuseBuffer
        } else {
            ledData = Array(totalLEDs) { ColorRgb(0, 0, 0) }
        }

        var ledIdx = 0

        // Calculate step sizes
        // We use xLed and yLed directly to divide the sides
        val stepX = width.toFloat() / xLed
        val stepY = height.toFloat() / yLed

        // Top edge (left to right)
        for (i in 0 until xLed) {
            val x = min((i * stepX + stepX / 2).toInt(), width - 1)
            val y = 0
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
        }

        // Right edge (top to bottom)
        for (i in 0 until yLed) {
            val x = width - 1
            val y = min((i * stepY + stepY / 2).toInt(), height - 1)
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
        }

        // Bottom edge (right to left)
        for (i in 0 until xLed) {
            val x = min(((xLed - 1 - i) * stepX + stepX / 2).toInt(), width - 1)
            val y = height - 1
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
        }

        // Left edge (bottom to top)
        for (i in 0 until yLed) {
            val x = 0
            val y = min(((yLed - 1 - i) * stepY + stepY / 2).toInt(), height - 1)
            setPixelFromData(ledData[ledIdx++], screenData, width, x, y)
        }

        // Fill remaining if any (should not happen)
        while (ledIdx < totalLEDs) {
            ledData[ledIdx++].set(0, 0, 0)
        }

        return ledData
    }

    private fun setPixelFromData(
        dest: ColorRgb,
        screenData: ByteArray,
        width: Int,
        x: Int,
        y: Int
    ) {
        val srcIdx = (y * width + x) * 3
        if (srcIdx + 2 < screenData.size) {
            val r = screenData[srcIdx].toInt() and 0xFF
            val g = screenData[srcIdx + 1].toInt() and 0xFF
            val b = screenData[srcIdx + 2].toInt() and 0xFF
            dest.set(r, g, b)
        } else {
            dest.set(0, 0, 0)
        }
    }
}
