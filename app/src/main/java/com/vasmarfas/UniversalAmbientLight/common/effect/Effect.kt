package com.vasmarfas.UniversalAmbientLight.common.effect

import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Эффекты без захвата экрана. [id] - значение настройки pref_key_effect; флаги говорят
 * экрану эффектов, какие параметры показывать.
 */
enum class Effect(
    val id: String,
    val animated: Boolean = true,
    val usesColor: Boolean = false,
    val usesColor2: Boolean = false,
    val usesTemperature: Boolean = false,
) {
    SOLID("solid", animated = false, usesColor = true),
    GRADIENT("gradient", animated = false, usesColor = true, usesColor2 = true),
    WHITE("white", animated = false, usesTemperature = true),
    RAINBOW("rainbow"),
    COLOR_CYCLE("color_cycle"),
    BREATHING("breathing", usesColor = true),
    CANDLE("candle"),
    FIRE("fire"),
    AURORA("aurora"),
    OCEAN("ocean"),
    PLASMA("plasma"),
    COMET("comet", usesColor = true),
    LAYOUT_TEST("layout_test");

    companion object {
        fun byId(id: String?): Effect = entries.firstOrNull { it.id == id } ?: RAINBOW
    }
}

/** Всё, что нужно для отрисовки кадра эффекта; цвета - 0xRRGGBB. */
data class EffectConfig(
    val effect: Effect,
    val color: Int = DEFAULT_COLOR,
    val color2: Int = DEFAULT_COLOR2,
    /** 0..100, 50 - обычная скорость. */
    val speed: Int = 50,
    /** 1..100 процентов. */
    val brightness: Int = 100,
    /** Цветовая температура белого, кельвины. */
    val temperature: Int = 6500,
    /** Откуда и куда пронумерована лента - нужно только проверке раскладки. */
    val startCorner: String = "bottom_left",
    val direction: String = "clockwise",
) {
    companion object {
        const val DEFAULT_COLOR = 0xFF8C3C
        const val DEFAULT_COLOR2 = 0x3C64FF

        fun from(prefs: Preferences) = EffectConfig(
            effect = Effect.byId(prefs.getString(R.string.pref_key_effect, Effect.RAINBOW.id)),
            color = parseColor(prefs.getString(R.string.pref_key_effect_color), DEFAULT_COLOR),
            color2 = parseColor(prefs.getString(R.string.pref_key_effect_color2), DEFAULT_COLOR2),
            speed = prefs.getInt(R.string.pref_key_effect_speed).coerceIn(0, 100),
            brightness = prefs.getInt(R.string.pref_key_effect_brightness).coerceIn(1, 100),
            temperature = prefs.getInt(R.string.pref_key_effect_temperature).coerceIn(1500, 10000),
            startCorner = prefs.getString(R.string.pref_key_led_start_corner, "bottom_left")
                ?: "bottom_left",
            direction = prefs.getString(R.string.pref_key_led_direction, "clockwise") ?: "clockwise",
        )

        fun parseColor(value: String?, fallback: Int): Int =
            value?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: fallback

        fun formatColor(rgb: Int): String =
            "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
    }
}
