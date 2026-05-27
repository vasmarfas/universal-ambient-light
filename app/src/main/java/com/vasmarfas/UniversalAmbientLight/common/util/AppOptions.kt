package com.vasmarfas.UniversalAmbientLight.common.util

import android.util.Log

class AppOptions(
    horizontalLED: Int,
    verticalLED: Int,
    val frameRate: Int,
    val useAverageColor: Boolean,
    val captureQuality: Int,
    // Color-correction state is mutable so the service can push live preference
    // changes into an already-running capture session without restarting it.
    @Volatile var brightness: Int = 100,
    @Volatile var contrast: Int = 100,
    @Volatile var blackLevel: Int = 0,
    @Volatile var whiteLevel: Int = 100,
    @Volatile var saturation: Int = 100,
    @Volatile var colorProcessingEnabled: Boolean = true,
    @Volatile var brightnessR: Int = 100,
    @Volatile var brightnessG: Int = 100,
    @Volatile var brightnessB: Int = 100,
    @Volatile var gammaR: Int = 100,
    @Volatile var gammaG: Int = 100,
    @Volatile var gammaB: Int = 100,
    @Volatile var borderDetectionEnabled: Boolean = false,
    @Volatile var borderThreshold: Int = 18,
    @Volatile var borderCheckIntervalFrames: Int = 60,
) {

    /** Reload border-detection fields from preferences. */
    fun refreshBorderSettings(prefs: Preferences) {
        borderDetectionEnabled = prefs.getBoolean(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_border_detection_enabled,
            false
        )
        borderThreshold =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_border_threshold, 18)
                .coerceIn(0, 64)
        borderCheckIntervalFrames = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_border_check_interval,
            60
        )
            .coerceIn(1, 300)
    }

    /** Reload all color-correction fields from preferences. Cheap; safe to call from any thread. */
    fun refreshColorSettings(prefs: Preferences) {
        brightness = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_brightness,
            100
        )
        contrast =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_contrast, 100)
        blackLevel =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_black_level, 0)
        whiteLevel = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_white_level,
            100
        )
        saturation = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_saturation,
            100
        )
        colorProcessingEnabled = prefs.getBoolean(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_processing_enabled,
            true
        )
        brightnessR = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_brightness_r,
            100
        )
        brightnessG = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_brightness_g,
            100
        )
        brightnessB = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_brightness_b,
            100
        )
        gammaR =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_gamma_r, 100)
        gammaG =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_gamma_g, 100)
        gammaB =
            prefs.getInt(com.vasmarfas.UniversalAmbientLight.R.string.pref_key_color_gamma_b, 100)
    }

    private val minimumImagePacketSize: Int
    val blackThreshold: Int =
        5 // The limit each RGB value must be under to be considered a black pixel [0-255]

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
