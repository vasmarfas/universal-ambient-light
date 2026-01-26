package com.vasmarfas.UniversalAmbientLight.common.util

import java.nio.ByteBuffer
import java.util.Objects

class BorderProcessor(private val BLACK_THRESHOLD: Int) {

    private val MAX_INCONSISTENT_FRAME_COUNT = 10
    private val MAX_UNKNOWN_FRAME_COUNT = 600
    private val BORDER_CHANGE_FRAME_COUNT = 50

    private var mPreviousBorder: BorderObject? = null
    var currentBorder: BorderObject? = null
        private set
    private var mConsistentFrames = 0
    private var mInconsistentFrames = 0

    private fun checkNewBorder(newBorder: BorderObject) {
        if (mPreviousBorder != null && mPreviousBorder == newBorder) {
            ++mConsistentFrames
            mInconsistentFrames = 0
        } else {
            ++mInconsistentFrames

            if (mInconsistentFrames <= MAX_INCONSISTENT_FRAME_COUNT) {
                return
            }

            mPreviousBorder = newBorder
            mConsistentFrames = 0
        }

        if (currentBorder != null && currentBorder == newBorder) {
            mInconsistentFrames = 0
            return
        }

        if (!newBorder.isKnown) {
            if (mConsistentFrames == MAX_UNKNOWN_FRAME_COUNT) {
                currentBorder = newBorder
            }
        } else {
            if (currentBorder == null || !currentBorder!!.isKnown ||
                mConsistentFrames == BORDER_CHANGE_FRAME_COUNT
            ) {
                currentBorder = newBorder
            }
        }
    }

    fun parseBorder(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int
    ) {
        checkNewBorder(findBorder(buffer, width, height, rowStride, pixelStride))
    }

    private fun findBorder(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int,
        pixelStride: Int
    ): BorderObject {

        val width33percent = width / 3
        val width66percent = width33percent * 2
        val height33percent = height / 3
        val height66percent = height33percent * 2
        val yCenter = height / 2
        val xCenter = width / 2
        var firstNonBlackYPixelIndex = -1
        var firstNonBlackXPixelIndex = -1

        // placeholders for the RGB values of each of the 3 pixel positions we check
        var p1R: Int
        var p1G: Int
        var p1B: Int
        var p2R: Int
        var p2G: Int
        var p2B: Int
        var p3R: Int
        var p3G: Int
        var p3B: Int

        // positions in the byte array that represent 33%, 66%, and center.
        // used when parsing both the X and Y axis of the image
        var pos33percent: Int
        var pos66percent: Int
        var posCentered: Int

        buffer.position(0).mark()

        // iterate through the X axis until we either hit 33% of the image width or a non-black pixel
        for (x in 0 until width33percent) {

            // RGB values at 33% height - to left of image
            pos33percent = height33percent * rowStride + x * pixelStride
            p1R = buffer.get(pos33percent).toInt() and 0xff
            p1G = buffer.get(pos33percent + 1).toInt() and 0xff
            p1B = buffer.get(pos33percent + 2).toInt() and 0xff

            // RGB values at 66% height - to left of image
            pos66percent = height66percent * rowStride + x * pixelStride
            p2R = buffer.get(pos66percent).toInt() and 0xff
            p2G = buffer.get(pos66percent + 1).toInt() and 0xff
            p2B = buffer.get(pos66percent + 2).toInt() and 0xff

            // RGB values at center Y - to right of image
            posCentered = yCenter * rowStride + (width - x - 1) * pixelStride
            p3R = buffer.get(posCentered).toInt() and 0xff
            p3G = buffer.get(posCentered + 1).toInt() and 0xff
            p3B = buffer.get(posCentered + 2).toInt() and 0xff

            // check if any of our RGB values DO NOT evaluate as black
            if (!isBlack(p1R, p1G, p1B) || !isBlack(p2R, p2G, p2B) || !isBlack(
                    p3R,
                    p3G,
                    p3B
                )
            ) {
                firstNonBlackXPixelIndex = x
                break
            }
        }

        buffer.reset()

        // iterate through the Y axis until we either hit 33% of the image height or a non-black pixel
        for (y in 0 until height33percent) {

            // RGB values at 33% width - top of image
            pos33percent = width33percent * pixelStride + y * rowStride
            p1R = buffer.get(pos33percent).toInt() and 0xff
            p1G = buffer.get(pos33percent + 1).toInt() and 0xff
            p1B = buffer.get(pos33percent + 2).toInt() and 0xff

            // RGB values at 66% width - top of image
            pos66percent = width66percent * pixelStride + y * rowStride
            p2R = buffer.get(pos66percent).toInt() and 0xff
            p2G = buffer.get(pos66percent + 1).toInt() and 0xff
            p2B = buffer.get(pos66percent + 2).toInt() and 0xff

            // RGB values at center X - bottom of image
            posCentered = xCenter * pixelStride + (height - y - 1) * rowStride
            p3R = buffer.get(posCentered).toInt() and 0xff
            p3G = buffer.get(posCentered + 1).toInt() and 0xff
            p3B = buffer.get(posCentered + 2).toInt() and 0xff

            // check if any of our RGB values DO NOT evaluate as black
            if (!isBlack(p1R, p1G, p1B) || !isBlack(p2R, p2G, p2B) || !isBlack(
                    p3R,
                    p3G,
                    p3B
                )
            ) {
                firstNonBlackYPixelIndex = y
                break
            }
        }

        return BorderObject(firstNonBlackXPixelIndex, firstNonBlackYPixelIndex)
    }

    private fun isBlack(red: Int, green: Int, blue: Int): Boolean {
        return red < BLACK_THRESHOLD && green < BLACK_THRESHOLD && blue < BLACK_THRESHOLD
    }

    class BorderObject(val horizontalBorderIndex: Int, val verticalBorderIndex: Int) {
        val isKnown: Boolean = this.horizontalBorderIndex != -1 && this.verticalBorderIndex != -1

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as BorderObject
            return isKnown == that.isKnown &&
                    horizontalBorderIndex == that.horizontalBorderIndex &&
                    verticalBorderIndex == that.verticalBorderIndex
        }

        override fun hashCode(): Int {
            return Objects.hash(isKnown, horizontalBorderIndex, verticalBorderIndex)
        }

        override fun toString(): String {
            return "BorderObject{" +
                    "isKnown=" + isKnown +
                    ", horizontalBorderIndex=" + horizontalBorderIndex +
                    ", verticalBorderIndex=" + verticalBorderIndex +
                    '}'
        }
    }
}
