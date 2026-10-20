package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset

/**
 * Геометрия раскладки: расчёт координат светодиодов и порядок обхода сторон.
 */
data class LedData(
    val position: Offset,
    val enabled: Boolean,
    val rectSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size(0f, 0f),
)

fun calculateLedPositions(
    topLed: Int,
    rightLed: Int,
    bottomLed: Int,
    leftLed: Int,
    startCorner: String,
    direction: String,
    sideTop: String,
    sideRight: String,
    sideBottom: String,
    sideLeft: String,
    bottomGap: Int,
    width: Float,
    height: Float,
    padding: Float,
    scanDepth: Int, // Percent 1-50
): List<LedData> {
    val positions = mutableListOf<LedData>()

    val screenWidth = width - padding * 2
    val screenHeight = height - padding * 2
    val topCount = topLed.coerceIn(0, MAX_LEDS_PER_SIDE)
    val rightCount = rightLed.coerceIn(0, MAX_LEDS_PER_SIDE)
    val bottomCount = bottomLed.coerceIn(0, MAX_LEDS_PER_SIDE)
    val leftCount = leftLed.coerceIn(0, MAX_LEDS_PER_SIDE)

    // Глубина сканирования в пикселях — визуальное приближение. Здесь она показывается
    // относительно серого прямоугольника «экрана», который считаем полным снятым кадром;
    // в самом захвате глубина отсчитывается от области захвата.
    val scanDepthV = (screenHeight * scanDepth / 100f).coerceAtLeast(2f)
    val scanDepthH = (screenWidth * scanDepth / 100f).coerceAtLeast(2f)

    // Тот же шаг, что у LedDataExtractor: сторона делится на count отрезков, светодиод
    // рисуется в центре своего, иначе картинка расходилась бы с тем, что реально снимается
    fun step(length: Float, count: Int): Float {
        return if (count <= 0) 0f else length / count
    }

    val stepTop = step(screenWidth, topCount)
    val stepRight = step(screenHeight, rightCount)
    val stepBottom = step(screenWidth, bottomCount)
    val stepLeft = step(screenHeight, leftCount)

    // Порядок обхода сторон
    val edges = getEdgeOrder(startCorner, direction)

    // Диапазон разрыва для нижней стороны
    val gapStart = if (bottomGap > 0 && bottomCount > 0) (bottomCount - bottomGap) / 2 else -1
    val gapEnd = if (bottomGap > 0 && bottomCount > 0) gapStart + bottomGap else -1

    for (edge in edges) {
        val sideMode = when {
            edge.startsWith("top_") -> sideTop
            edge.startsWith("right_") -> sideRight
            edge.startsWith("bottom_") -> sideBottom
            edge.startsWith("left_") -> sideLeft
            else -> "enabled"
        }

        // Пропускаем выключенные стороны
        if (sideMode == "not_installed") continue

        when (edge) {
            "top_lr" -> {
                // Верх, слева направо
                for (i in 0 until topCount) {
                    val x = if (topCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + i * stepTop + stepTop / 2f
                    }
                    // Для верхней стороны прямоугольник центрируется по x, начинается от отступа сверху, высота scanDepthV
                    positions.add(
                        LedData(
                            position = Offset(x, padding + scanDepthV / 2f), // Center of rect
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(stepTop, scanDepthV)
                        )
                    )
                }
            }

            "top_rl" -> {
                // Верх, справа налево
                for (i in 0 until topCount) {
                    val ledIndex = topCount - 1 - i
                    val x = if (topCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + ledIndex * stepTop + stepTop / 2f
                    }
                    positions.add(
                        LedData(
                            position = Offset(x, padding + scanDepthV / 2f),
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(stepTop, scanDepthV)
                        )
                    )
                }
            }

            "right_tb" -> {
                // Правый край, сверху вниз
                for (i in 0 until rightCount) {
                    val y = if (rightCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + i * stepRight + stepRight / 2f
                    }
                    // Правый край: прямоугольник начинается от width-padding-scanDepthH
                    positions.add(
                        LedData(
                            position = Offset(padding + screenWidth - scanDepthH / 2f, y),
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepRight)
                        )
                    )
                }
            }

            "right_bt" -> {
                // Правый край, снизу вверх
                for (i in 0 until rightCount) {
                    val ledIndex = rightCount - 1 - i
                    val y = if (rightCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + ledIndex * stepRight + stepRight / 2f
                    }
                    positions.add(
                        LedData(
                            position = Offset(padding + screenWidth - scanDepthH / 2f, y),
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepRight)
                        )
                    )
                }
            }

            "bottom_rl" -> {
                // Низ, справа налево
                for (i in 0 until bottomCount) {
                    val ledIndex = bottomCount - 1 - i
                    val isInGap =
                        bottomGap > 0 && bottomCount > 0 && ledIndex >= gapStart && ledIndex < gapEnd
                    val x = if (bottomCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + ledIndex * stepBottom + stepBottom / 2f
                    }
                    // Низ: прямоугольник начинается от height-padding-scanDepthV
                    positions.add(
                        LedData(
                            position = Offset(x, padding + screenHeight - scanDepthV / 2f),
                            enabled = sideMode == "enabled" && !isInGap,
                            rectSize = androidx.compose.ui.geometry.Size(stepBottom, scanDepthV)
                        )
                    )
                }
            }

            "bottom_lr" -> {
                // Низ, слева направо
                for (i in 0 until bottomCount) {
                    val isInGap = bottomGap > 0 && bottomCount > 0 && i >= gapStart && i < gapEnd
                    val x = if (bottomCount <= 1) {
                        padding + screenWidth / 2f
                    } else {
                        padding + i * stepBottom + stepBottom / 2f
                    }
                    positions.add(
                        LedData(
                            position = Offset(x, padding + screenHeight - scanDepthV / 2f),
                            enabled = sideMode == "enabled" && !isInGap,
                            rectSize = androidx.compose.ui.geometry.Size(stepBottom, scanDepthV)
                        )
                    )
                }
            }

            "left_bt" -> {
                // Левый край, снизу вверх
                for (i in 0 until leftCount) {
                    val ledIndex = leftCount - 1 - i
                    val y = if (leftCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + ledIndex * stepLeft + stepLeft / 2f
                    }
                    // Левый край: прямоугольник начинается от отступа
                    positions.add(
                        LedData(
                            position = Offset(padding + scanDepthH / 2f, y),
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepLeft)
                        )
                    )
                }
            }

            "left_tb" -> {
                // Левый край, сверху вниз
                for (i in 0 until leftCount) {
                    val y = if (leftCount <= 1) {
                        padding + screenHeight / 2f
                    } else {
                        padding + i * stepLeft + stepLeft / 2f
                    }
                    positions.add(
                        LedData(
                            position = Offset(padding + scanDepthH / 2f, y),
                            enabled = sideMode == "enabled",
                            rectSize = androidx.compose.ui.geometry.Size(scanDepthH, stepLeft)
                        )
                    )
                }
            }
        }
    }

    return positions
}

fun getEdgeOrder(startCorner: String, direction: String): List<String> {
    return when (startCorner) {
        "top_left" -> {
            if (direction == "clockwise") {
                listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
            } else {
                listOf("left_tb", "bottom_lr", "right_bt", "top_rl")
            }
        }

        "top_right" -> {
            if (direction == "clockwise") {
                listOf("right_tb", "bottom_rl", "left_bt", "top_lr")
            } else {
                listOf("top_rl", "left_tb", "bottom_lr", "right_bt")
            }
        }

        "bottom_right" -> {
            if (direction == "clockwise") {
                listOf("bottom_rl", "left_bt", "top_lr", "right_tb")
            } else {
                listOf("right_bt", "top_rl", "left_tb", "bottom_lr")
            }
        }

        "bottom_left" -> {
            if (direction == "clockwise") {
                listOf("left_bt", "top_lr", "right_tb", "bottom_rl")
            } else {
                listOf("bottom_lr", "right_bt", "top_rl", "left_tb")
            }
        }

        else -> listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
    }
}
