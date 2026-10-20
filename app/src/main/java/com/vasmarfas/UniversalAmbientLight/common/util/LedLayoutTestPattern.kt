package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb

/**
 * Адресная проверка раскладки: каждый реальный LED получает цвет своей стороны.
 * Не используем виртуальный видеокадр, поэтому границы зависят только от настроек
 * количества светодиодов, стартового угла, направления и сдвига индексов.
 */
object LedLayoutTestPattern {
    fun build(layout: LedLayout): Array<ColorRgb> {
        val top = layout.topLed.coerceAtLeast(0)
        val right = layout.rightLed.coerceAtLeast(0)
        val bottom = layout.bottomLed.coerceAtLeast(0)
        val left = layout.leftLed.coerceAtLeast(0)
        val total = listOf(
            top.takeUnless { layout.sideTop == "not_installed" } ?: 0,
            right.takeUnless { layout.sideRight == "not_installed" } ?: 0,
            bottom.takeUnless { layout.sideBottom == "not_installed" } ?: 0,
            left.takeUnless { layout.sideLeft == "not_installed" } ?: 0,
        ).sum()
        if (total == 0) return emptyArray()

        val leds = Array(total) { ColorRgb(0, 0, 0) }
        val gapStart = if (layout.bottomGap > 0) (bottom - layout.bottomGap) / 2 else -1
        val gapEnd = if (layout.bottomGap > 0) gapStart + layout.bottomGap else -1
        var index = 0

        for (edge in LedDataExtractor.getEdgeOrder(layout.startCorner, layout.direction)) {
            val side = edge.substringBefore('_')
            val mode = when (side) {
                "top" -> layout.sideTop
                "right" -> layout.sideRight
                "bottom" -> layout.sideBottom
                else -> layout.sideLeft
            }
            if (mode == "not_installed") continue

            val count = when (side) {
                "top" -> top
                "right" -> right
                "bottom" -> bottom
                else -> left
            }
            val color = when (side) {
                "top" -> 0xFF0000
                "right" -> 0x00FF00
                "bottom" -> 0x0000FF
                else -> 0xFFFF00
            }
            val reverse = edge.endsWith("_rl") || edge.endsWith("_bt")
            for (position in 0 until count) {
                val physicalIndex = if (reverse) count - 1 - position else position
                val gap = side == "bottom" && physicalIndex >= gapStart && physicalIndex < gapEnd
                if (mode == "enabled" && !gap) {
                    leds[index].set(color shr 16, color shr 8, color)
                }
                index++
            }
        }

        val offset = ((layout.ledOffset % total) + total) % total
        if (offset == 0) return leds
        val rotated = Array(total) { ColorRgb(0, 0, 0) }
        for (i in 0 until total) rotated[(i + offset) % total].set(leds[i])
        return rotated
    }
}
