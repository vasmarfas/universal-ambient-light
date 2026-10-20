package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.R
import kotlin.math.max

/**
 * Раскладка ленты по периметру: сколько светодиодов на каждой стороне, откуда и в какую
 * сторону они пронумерованы, какие стороны физически наклеены и какую часть кадра снимать.
 *
 * Отделена от [LedDataExtractor] по той же причине, что и [com.vasmarfas.UniversalAmbientLight.common.network.ConnectionConfig]
 * от HyperionThread: разбор кадра получал девятнадцать позиционных аргументов подряд.
 * Заодно вся работа с пикселями перестала зависеть от Context и настроек.
 *
 * Значения по умолчанию повторяют прежние дефолты чтения настроек.
 */
data class LedLayout(
    val topLed: Int = 0,
    val rightLed: Int = 0,
    val bottomLed: Int = 0,
    val leftLed: Int = 0,
    val startCorner: String = "bottom_left",
    val direction: String = "clockwise",
    val sideTop: String = "enabled",
    val sideRight: String = "enabled",
    val sideBottom: String = "enabled",
    val sideLeft: String = "enabled",
    /**
     * Сколько светодиодов в середине нижней стороны закрыто саундбаром или подставкой.
     * Они остаются на ленте и в нумерации, им уходит чёрный цвет.
     */
    val bottomGap: Int = 0,
    val captureMarginTop: Int = 0,
    val captureMarginRight: Int = 0,
    val captureMarginBottom: Int = 0,
    val captureMarginLeft: Int = 0,
    /** Сдвиг нумерации по периметру; может быть отрицательным. */
    val ledOffset: Int = 0,
    /** Толщина снимаемой полосы в процентах от области захвата. */
    val scanDepth: Int = 1,
    /** Общее число светодиодов по горизонтали и вертикали — запасной вариант для [ledCount]. */
    val xLed: Int = 0,
    val yLed: Int = 0,
) {

    /**
     * Сколько светодиодов ждёт контроллер. Стороны, снятые с ленты (`not_installed`),
     * не считаются — их светодиодов физически нет, и разбор кадра их тоже выбрасывает;
     * выключенные (`disabled`) стороны остаются в счёте и приходят чёрными. Расхождение
     * с разбором кадра здесь ломало бы сглаживание: clear() и живые кадры имели бы
     * разную длину.
     */
    fun ledCount(): Int = max(configuredLedCount(), 1)

    /**
     * То же число, но без страховочного минимума: 0 и меньше означает, что раскладка
     * не задана. По этому значению validateSettings отличает пустые настройки от
     * настроенных, поэтому проверка и отправка не могут разойтись.
     */
    fun configuredLedCount(): Int {
        var total = 0
        if (sideTop != "not_installed") total += topLed
        if (sideRight != "not_installed") total += rightLed
        if (sideBottom != "not_installed") total += bottomLed
        if (sideLeft != "not_installed") total += leftLed
        if (total <= 0) total = 2 * (xLed + yLed)
        return total
    }

    companion object {

        fun from(prefs: Preferences): LedLayout {
            val xLed = prefs.getInt(R.string.pref_key_x_led)
            val yLed = prefs.getInt(R.string.pref_key_y_led)
            val margins = readCaptureMargins(prefs)
            return LedLayout(
                topLed = prefs.getInt(R.string.pref_key_led_count_top, xLed),
                rightLed = prefs.getInt(R.string.pref_key_led_count_right, yLed),
                bottomLed = prefs.getInt(R.string.pref_key_led_count_bottom, xLed),
                leftLed = prefs.getInt(R.string.pref_key_led_count_left, yLed),
                startCorner = prefs.getString(R.string.pref_key_led_start_corner, "bottom_left")
                    ?: "bottom_left",
                direction = prefs.getString(R.string.pref_key_led_direction, "clockwise")
                    ?: "clockwise",
                sideTop = prefs.getString(R.string.pref_key_led_side_top, "enabled") ?: "enabled",
                sideRight = prefs.getString(R.string.pref_key_led_side_right, "enabled")
                    ?: "enabled",
                sideBottom = prefs.getString(R.string.pref_key_led_side_bottom, "enabled")
                    ?: "enabled",
                sideLeft = prefs.getString(R.string.pref_key_led_side_left, "enabled") ?: "enabled",
                bottomGap = prefs.getInt(R.string.pref_key_bottom_gap, 0),
                captureMarginTop = margins[0],
                captureMarginRight = margins[1],
                captureMarginBottom = margins[2],
                captureMarginLeft = margins[3],
                ledOffset = prefs.getInt(R.string.pref_key_led_offset, 0),
                scanDepth = prefs.getInt(R.string.pref_key_scan_depth, 1).coerceIn(1, 50),
                xLed = xLed,
                yLed = yLed,
            )
        }

        /**
         * Отступы области захвата в порядке top, right, bottom, left. Ключей три поколения:
         * один общий, пара горизонталь/вертикаль и четыре стороны; `-1` означает «не задано».
         * Старый ключ проверяется первым и перекрывает новые, поэтому настройка, сделанная
         * до появления сторон, продолжает работать как раньше.
         */
        private fun readCaptureMargins(prefs: Preferences): IntArray {
            val legacy = prefs.getInt(R.string.pref_key_capture_margin, -1)
            if (legacy >= 0) return intArrayOf(legacy, legacy, legacy, legacy)

            val horizontal = prefs.getInt(R.string.pref_key_capture_margin_horizontal, -1)
            val vertical = prefs.getInt(R.string.pref_key_capture_margin_vertical, -1)
            if (horizontal >= 0 || vertical >= 0) {
                val h = max(horizontal, 0)
                val v = max(vertical, 0)
                return intArrayOf(v, h, v, h)
            }

            return intArrayOf(
                prefs.getInt(R.string.pref_key_capture_margin_top, 0),
                prefs.getInt(R.string.pref_key_capture_margin_right, 0),
                prefs.getInt(R.string.pref_key_capture_margin_bottom, 0),
                prefs.getInt(R.string.pref_key_capture_margin_left, 0),
            )
        }
    }
}
