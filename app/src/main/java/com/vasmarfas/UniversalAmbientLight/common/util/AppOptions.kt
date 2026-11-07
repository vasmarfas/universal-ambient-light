package com.vasmarfas.UniversalAmbientLight.common.util

import android.util.Log

class AppOptions(
    horizontalLED: Int,
    verticalLED: Int,
    frameRate: Int,
    val useAverageColor: Boolean,
    val captureQuality: Int,
    // Настройки цветокоррекции изменяемые: сервис подставляет сюда правки пользователя
    // прямо в идущую сессию захвата, не перезапуская её.
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
    @Volatile var borderIgnoreSubtitles: Boolean = false,
    // Автосон камеры. Изменяемый по той же причине, что и цвет: пороги подбираются только
    // вживую под работающей камерой.
    @Volatile var cameraIdleEnabled: Boolean = false,
    @Volatile var cameraIdleTimeoutSec: Int = 120,
    @Volatile var cameraIdleDarkLevel: Int = 12,
    @Volatile var cameraIdleMotionLevel: Int = 4,
    @Volatile var cameraIdleStaticSleep: Boolean = false,
) {

    // Все бэкенды захвата считают интервал кадра как 1000/frameRate, поэтому ноль здесь
    // означает падение сервиса на старте, а не медленный захват.
    val frameRate: Int = frameRate.coerceAtLeast(1)

    /** Перечитывает поля автосна камеры из настроек. */
    fun refreshCameraIdleSettings(prefs: Preferences) {
        cameraIdleEnabled = prefs.getBoolean(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_camera_idle_enabled,
            false
        )
        cameraIdleTimeoutSec = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_camera_idle_timeout,
            120
        ).coerceIn(5, 3600)
        cameraIdleDarkLevel = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_camera_idle_dark_level,
            12
        ).coerceIn(0, 96)
        cameraIdleMotionLevel = prefs.getInt(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_camera_idle_motion_level,
            4
        ).coerceIn(1, 64)
        cameraIdleStaticSleep = prefs.getBoolean(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_camera_idle_static,
            false
        )
    }

    /** Перечитывает поля определения чёрных полос из настроек. */
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
        borderIgnoreSubtitles = prefs.getBoolean(
            com.vasmarfas.UniversalAmbientLight.R.string.pref_key_border_ignore_subtitles,
            false
        )
    }

    /** Перечитывает все поля цветокоррекции из настроек. Дёшево, вызывать можно из любого потока. */
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

    init {
        /*
        * Минимально допустимый размер пакета с картинкой считается так: берём количество
        * светодиодов по ширине и высоте (столько их выставлено на сервере Hyperion),
        * перемножаем и умножаем на 3 — по байту на каждый канал RGB. Получается число
        * байт, до которого пакет должен дотягивать.
        **/
        minimumImagePacketSize = horizontalLED * verticalLED * 3

        if (DEBUG) {
            Log.d(TAG, "Horizontal LED Count: $horizontalLED")
            Log.d(TAG, "Vertical LED Count: $verticalLED")
            Log.d(TAG, "Minimum Image Packet: $minimumImagePacketSize")
        }
    }

    /**
     * Подбирает делитель, при котором пакет дотягивает до минимального размера.
     * Масштабировать хочется только целыми числами, поэтому ищем общие делители ширины и
     * высоты и среди них берём наименьший, при котором размер пакета всё ещё не меньше
     * требуемого числом светодиодов на сервере Hyperion.
     *
     * @param width исходная ширина экрана устройства
     * @param height исходная высота экрана устройства
     * @return делитель, на который стоит уменьшить размеры экрана
     */
    fun findDivisor(width: Int, height: Int): Int {
        val divisors = getCommonDivisors(width, height)
        if (DEBUG) Log.d(TAG, "Available Divisors: $divisors")
        val it = divisors.listIterator(divisors.size)

        // Идём с конца: делители перечислены от большего к меньшему
        while (it.hasPrevious()) {
            val i = it.previous()

            // Проверяем, что при этом делителе размер пакета не меньше минимального:
            // как и выше, перемножаем размеры и умножаем на 3 байта RGB
            if ((width / i) * (height / i) * 3 >= minimumImagePacketSize)
                return i
        }
        return 1
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "AppOptions"

        /**
         * Возвращает все общие делители двух чисел, от большего к меньшему.
         * @param num1 первое число
         * @param num2 второе число
         * @return список общих делителей от большего к меньшему
         */
        private fun getCommonDivisors(num1: Int, num2: Int): List<Int> {
            // Нулевые метрики экрана (часть прошивок на старте) дали бы деление на ноль
            val min = Math.min(num1, num2)
            if (min <= 0) return listOf(1)
            val list = ArrayList<Int>()
            for (i in 1..min / 2)
                if (num1 % i == 0 && num2 % i == 0)
                    list.add(i)
            if (num1 % min == 0 && num2 % min == 0) list.add(min)
            return list
        }
    }
}
