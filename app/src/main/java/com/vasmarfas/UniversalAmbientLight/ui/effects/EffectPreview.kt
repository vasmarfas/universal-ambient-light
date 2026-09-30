package com.vasmarfas.UniversalAmbientLight.ui.effects

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.effect.Effect
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectConfig
import com.vasmarfas.UniversalAmbientLight.common.effect.EffectRenderer

@StringRes
fun Effect.titleRes(): Int = when (this) {
    Effect.SOLID -> R.string.effect_solid
    Effect.GRADIENT -> R.string.effect_gradient
    Effect.WHITE -> R.string.effect_white
    Effect.RAINBOW -> R.string.effect_rainbow
    Effect.COLOR_CYCLE -> R.string.effect_color_cycle
    Effect.BREATHING -> R.string.effect_breathing
    Effect.CANDLE -> R.string.effect_candle
    Effect.FIRE -> R.string.effect_fire
    Effect.AURORA -> R.string.effect_aurora
    Effect.OCEAN -> R.string.effect_ocean
    Effect.PLASMA -> R.string.effect_plasma
    Effect.COMET -> R.string.effect_comet
    Effect.LAYOUT_TEST -> R.string.effect_layout_test
}

/**
 * Эскиз эффекта: тёмный телевизор и светодиоды вокруг него. Цвета считает тот же
 * отрисовщик, что работает в сервисе, поэтому эскиз не расходится с лентой.
 *
 * Анимируется только по [animate]: на слабой ТВ-приставке дюжина одновременно
 * перерисовываемых карточек заметно тормозит прокрутку.
 */
@Composable
fun EffectPreview(config: EffectConfig, animate: Boolean, modifier: Modifier = Modifier) {
    val renderer = remember { EffectRenderer(FRAME_WIDTH, FRAME_HEIGHT) }
    val frame = remember { ByteArray(FRAME_WIDTH * FRAME_HEIGHT * 3) }
    var time by remember { mutableLongStateOf(STILL_TIME_MS) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            while (true) {
                withFrameMillis { time = STILL_TIME_MS + it - start }
            }
        }
    }
    Canvas(modifier = modifier) {
        renderer.render(config, time, frame)
        drawTvWithLeds(frame)
    }
}

private fun DrawScope.drawTvWithLeds(frame: ByteArray) {
    val tvWidth = size.width * 0.7f
    val tvHeight = tvWidth * 9f / 16f
    val left = (size.width - tvWidth) / 2
    val top = (size.height - tvHeight) / 2
    val gap = size.minDimension * 0.05f
    val leds = ArrayList<Pair<Offset, Color>>(2 * (LEDS_H + LEDS_V))

    for (i in 0 until LEDS_H) {
        val k = (i + 0.5f) / LEDS_H
        val x = left + tvWidth * k
        val column = (k * FRAME_WIDTH).toInt().coerceIn(0, FRAME_WIDTH - 1)
        leds += Offset(x, top - gap) to pixel(frame, column, 0)
        leds += Offset(x, top + tvHeight + gap) to pixel(frame, column, FRAME_HEIGHT - 1)
    }
    for (i in 0 until LEDS_V) {
        val k = (i + 0.5f) / LEDS_V
        val y = top + tvHeight * k
        val row = (k * FRAME_HEIGHT).toInt().coerceIn(0, FRAME_HEIGHT - 1)
        leds += Offset(left - gap, y) to pixel(frame, 0, row)
        leds += Offset(left + tvWidth + gap, y) to pixel(frame, FRAME_WIDTH - 1, row)
    }

    // Сначала мягкое свечение на «стене», поверх него сами светодиоды
    val glow = tvWidth / LEDS_H * 2.2f
    for ((center, color) in leds) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.55f), Color.Transparent),
                center = center,
                radius = glow
            ),
            radius = glow,
            center = center
        )
    }
    drawRoundRect(
        color = Color(0xFF15151A),
        topLeft = Offset(left, top),
        size = Size(tvWidth, tvHeight),
        cornerRadius = CornerRadius(gap / 2)
    )
    val dot = gap * 0.45f
    for ((center, color) in leds) drawCircle(color = color, radius = dot, center = center)
}

private fun pixel(frame: ByteArray, x: Int, y: Int): Color {
    val i = (y * FRAME_WIDTH + x) * 3
    return Color(
        frame[i].toInt() and 0xFF,
        frame[i + 1].toInt() and 0xFF,
        frame[i + 2].toInt() and 0xFF
    )
}

private const val FRAME_WIDTH = 48
private const val FRAME_HEIGHT = 27
private const val LEDS_H = 14
private const val LEDS_V = 8

// Неподвижные карточки показывают эффект не с нулевого кадра: у кометы и дыхания он пустой
private const val STILL_TIME_MS = 2600L
