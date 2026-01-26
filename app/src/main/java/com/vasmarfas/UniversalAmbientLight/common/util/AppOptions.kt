package com.vasmarfas.UniversalAmbientLight.common.util

import android.util.Log
import java.util.ArrayList

class AppOptions(
    horizontalLED: Int,
    verticalLED: Int,
    val frameRate: Int,
    val useAverageColor: Boolean,
    val captureQuality: Int
) {
    private val minimumImagePacketSize: Int
    val blackThreshold: Int = 5 // The limit each RGB value must be under to be considered a black pixel [0-255]

    init {
        /*
        * To determine the minimal acceptable image packet size we take the count of the width & height
        * of the LED pixels (that the user is driving via their hyperion server) and multiply them
        * together and then by 3 (1 for each color in RGB). This will give us the count of the bytes
        * that the minimal acceptable quality should be equal to or greater than.
        **/
        minimumImagePacketSize = horizontalLED * verticalLED * 3

        if (DEBUG) {
            Log.d(TAG, "Horizontal LED Count: $horizontalLED")
            Log.d(TAG, "Vertical LED Count: $verticalLED")
            Log.d(TAG, "Minimum Image Packet: $minimumImagePacketSize")
        }
    }

    /**
     * returns the divisor best suited to be used to meet the minimum image packet size
     * Since we only want to scale using whole numbers, we need to find what common divisors
     * are available for the given width & height. We will check those divisors to find the smallest
     * number (that we can divide our screen dimensions by) that would meet the minimum image
     * packet size required to match the count of the LEDs on the destination hyperion server.
     * @param width The original width of the device screen
     * @param height  The original height of the device screen
     * @return int The divisor bes suited to scale the screen dimensions by
     */
    fun findDivisor(width: Int, height: Int): Int {
        val divisors = getCommonDivisors(width, height)
        if (DEBUG) Log.d(TAG, "Available Divisors: $divisors")
        val it = divisors.listIterator(divisors.size)

        // iterate backwards since the divisors are listed largest to smallest
        while (it.hasPrevious()) {
            val i = it.previous()

            // check if the image packet size for this divisor is >= the minimum image packet size
            // like above we multiply the dimensions together and then by 3 for each byte in RGB
            if ((width / i) * (height / i) * 3 >= minimumImagePacketSize)
                return i
        }
        return 1
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "AppOptions"

        /**
         * gets a list of all the common divisors [large to small] for the given integers.
         * @param num1 The first integer to find a whole number divisor for
         * @param num2  The second integer to find a whole number divisor for
         * @return List A list of the common divisors [large to small] that match the provided integers
         */
        private fun getCommonDivisors(num1: Int, num2: Int): List<Int> {
            val list = ArrayList<Int>()
            val min = Math.min(num1, num2)
            for (i in 1..min / 2)
                if (num1 % i == 0 && num2 % i == 0)
                    list.add(i)
            if (num1 % min == 0 && num2 % min == 0) list.add(min)
            return list
        }
    }
}
