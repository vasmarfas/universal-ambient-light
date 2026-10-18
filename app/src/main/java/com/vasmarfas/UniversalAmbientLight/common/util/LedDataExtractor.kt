package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.ColorRgb

object LedDataExtractor {
    private const val TAG = "LedDataExtractor"
    private val logsEnabled = false

    /** Лента среднего размера: столько светодиодов берём, если настройки прочитать не вышло. */
    private const val DEFAULT_LED_COUNT = 60

    /**
     * Достаёт цвета светодиодов, переиспользуя переданный буфер, чтобы не нагружать сборщик мусора.
     * @param reuseBuffer буфер для повторного использования; при null или неподходящем размере выделяется новый.
     * @return массив с результатом — переиспользованный или новый.
     */
    fun extractLEDData(
        context: Context,
        screenData: ByteArray,
        width: Int,
        height: Int,
        reuseBuffer: Array<ColorRgb>?,
    ): Array<ColorRgb> {
        return extractPerimeterPixels(
            screenData, width, height, readLayout(context), reuseBuffer
        )
    }

    fun extractLEDData(
        context: Context,
        screenData: ByteArray,
        width: Int,
        height: Int,
    ): Array<ColorRgb> {
        return extractLEDData(context, screenData, width, height, null)
    }

    fun getLedCount(context: Context): Int {
        return try {
            LedLayout.from(Preferences(context)).ledCount()
        } catch (e: Exception) {
            // Та же причина, что и в readLayout: без числа светодиодов клиент не соберёт
            // пакет, поэтому берём типовую ленту вместо падения
            if (logsEnabled) Log.w(TAG, "Failed to get LED count, using default", e)
            DEFAULT_LED_COUNT
        }
    }

    /**
     * Настройки читаются на каждом кадре: пользователь правит раскладку прямо во время
     * работы ленты и должен видеть результат сразу.
     */
    private fun readLayout(context: Context): LedLayout {
        return try {
            LedLayout.from(Preferences(context))
        } catch (e: Exception) {
            // Защитный периметр: разбор кадра идёт в потоке отправки, и падение на чтении
            // настроек оборвало бы его целиком — лучше отработать на значениях по умолчанию
            if (logsEnabled) Log.w(TAG, "Failed to get LED settings from preferences", e)
            LedLayout()
        }
    }

    /**
     * Берёт пиксели по периметру двумерной картинки экрана.
     * Порядок обхода зависит от стартового угла и направления.
     */
    internal fun extractPerimeterPixels(
        screenData: ByteArray,
        width: Int,
        height: Int,
        layout: LedLayout,
        reuseBuffer: Array<ColorRgb>?,
    ): Array<ColorRgb> {
        val topCount = layout.topLed.coerceAtLeast(0)
        val rightCount = layout.rightLed.coerceAtLeast(0)
        val bottomCount = layout.bottomLed.coerceAtLeast(0)
        val leftCount = layout.leftLed.coerceAtLeast(0)
        val bottomGap = layout.bottomGap

        // Считаем общее число светодиодов с учётом того, какие стороны включены
        var totalLEDs = 0
        if (layout.sideTop != "not_installed") totalLEDs += topCount
        if (layout.sideRight != "not_installed") totalLEDs += rightCount
        if (layout.sideBottom != "not_installed") totalLEDs += bottomCount
        if (layout.sideLeft != "not_installed") totalLEDs += leftCount
        if (totalLEDs == 0) return emptyArray()

        val expectedSize = width * height * 3
        if (logsEnabled) Log.d(
            TAG,
            "extractPerimeterPixels: width=$width, height=$height, top=$topCount, right=$rightCount, bottom=$bottomCount, left=$leftCount, totalLEDs=$totalLEDs"
        )
        if (logsEnabled) {
            Log.d(TAG, "startCorner=${layout.startCorner}, direction=${layout.direction}")
        }
        if (logsEnabled) Log.d(TAG, "screenData.size=${screenData.size}, expected=$expectedSize")

        if (screenData.size < expectedSize) {
            if (logsEnabled) Log.e(
                TAG,
                "screenData is too small! Expected at least $expectedSize bytes, got ${screenData.size}"
            )
        }

        val ledData: Array<ColorRgb>
        if (reuseBuffer != null && reuseBuffer.size == totalLEDs) {
            ledData = reuseBuffer
        } else {
            ledData = Array(totalLEDs) { ColorRgb(0, 0, 0) }
        }

        var ledIdx = 0

        // Область захвата со своим отступом для каждой стороны
        val marginTop = layout.captureMarginTop.coerceIn(0, 40)
        val marginRight = layout.captureMarginRight.coerceIn(0, 40)
        val marginBottom = layout.captureMarginBottom.coerceIn(0, 40)
        val marginLeft = layout.captureMarginLeft.coerceIn(0, 40)

        val marginTopPx = height * marginTop / 100f
        val marginRightPx = width * marginRight / 100f
        val marginBottomPx = height * marginBottom / 100f
        val marginLeftPx = width * marginLeft / 100f

        val captureLeft = marginLeftPx
        val captureRight = width - marginRightPx
        val captureTop = marginTopPx
        val captureBottom = height - marginBottomPx

        val captureWidth = (captureRight - captureLeft).coerceAtLeast(1f)
        val captureHeight = (captureBottom - captureTop).coerceAtLeast(1f)

        // Глубина сканирования в пикселях
        val scanDepthV = (captureHeight * layout.scanDepth / 100f).toInt().coerceAtLeast(1)
        val scanDepthH = (captureWidth * layout.scanDepth / 100f).toInt().coerceAtLeast(1)

        fun sideStep(length: Float, count: Int): Float {
            return if (count <= 0) 0f else length / count
        }

        val stepTop = sideStep(captureWidth, topCount)
        val stepRight = sideStep(captureHeight, rightCount)
        val stepBottom = sideStep(captureWidth, bottomCount)
        val stepLeft = sideStep(captureHeight, leftCount)

        // Диапазон разрыва для нижней стороны
        val gapStart = if (bottomGap > 0 && bottomCount > 0) (bottomCount - bottomGap) / 2 else -1
        val gapEnd = if (bottomGap > 0 && bottomCount > 0) gapStart + bottomGap else -1

        // Порядок обхода сторон по стартовому углу и направлению
        val edges = getEdgeOrder(layout.startCorner, layout.direction)

        for (edge in edges) {
            val sideMode = when {
                edge.startsWith("top_") -> layout.sideTop
                edge.startsWith("right_") -> layout.sideRight
                edge.startsWith("bottom_") -> layout.sideBottom
                edge.startsWith("left_") -> layout.sideLeft
                else -> "enabled"
            }

            // Пропускаем выключенные стороны
            if (sideMode == "not_installed") continue

            when (edge) {
                "top_lr" -> {
                    // Верх, слева направо
                    for (i in 0 until topCount) {
                        if (sideMode == "enabled") {
                            val x = (captureLeft + i * stepTop).toInt()
                            val w = ((captureLeft + (i + 1) * stepTop).toInt() - x).coerceAtLeast(1)
                            val y = captureTop.toInt()
                            val h = scanDepthV
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "top_rl" -> {
                    // Верх, справа налево
                    for (i in 0 until topCount) {
                        if (sideMode == "enabled") {
                            // Идём по светодиодам 0..N-1, где 0 — самый правый.
                            // Координата x при этом движется справа налево, поэтому
                            // светодиоду i соответствует отрезок (topCount - 1 - i).
                            val segmentIdx = topCount - 1 - i
                            val x = (captureLeft + segmentIdx * stepTop).toInt()
                            val w =
                                ((captureLeft + (segmentIdx + 1) * stepTop).toInt() - x).coerceAtLeast(
                                    1
                                )
                            val y = captureTop.toInt()
                            val h = scanDepthV
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "right_tb" -> {
                    // Правый край, сверху вниз
                    for (i in 0 until rightCount) {
                        if (sideMode == "enabled") {
                            val x = (captureRight - scanDepthH).toInt()
                            val w = scanDepthH
                            val y = (captureTop + i * stepRight).toInt()
                            val h =
                                ((captureTop + (i + 1) * stepRight).toInt() - y).coerceAtLeast(1)
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "right_bt" -> {
                    // Правый край, снизу вверх
                    for (i in 0 until rightCount) {
                        if (sideMode == "enabled") {
                            val segmentIdx = rightCount - 1 - i
                            val x = (captureRight - scanDepthH).toInt()
                            val w = scanDepthH
                            val y = (captureTop + segmentIdx * stepRight).toInt()
                            val h =
                                ((captureTop + (segmentIdx + 1) * stepRight).toInt() - y).coerceAtLeast(
                                    1
                                )
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "bottom_rl" -> {
                    // Низ, справа налево
                    for (i in 0 until bottomCount) {
                        val ledIndex = bottomCount - 1 - i
                        val isInGap = bottomGap > 0 && ledIndex >= gapStart && ledIndex < gapEnd
                        if (sideMode == "enabled" && !isInGap) {
                            val segmentIdx = bottomCount - 1 - i
                            val x = (captureLeft + segmentIdx * stepBottom).toInt()
                            val w =
                                ((captureLeft + (segmentIdx + 1) * stepBottom).toInt() - x).coerceAtLeast(
                                    1
                                )
                            val y = (captureBottom - scanDepthV).toInt()
                            val h = scanDepthV
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "bottom_lr" -> {
                    // Низ, слева направо
                    for (i in 0 until bottomCount) {
                        val isInGap = bottomGap > 0 && i >= gapStart && i < gapEnd
                        if (sideMode == "enabled" && !isInGap) {
                            val x = (captureLeft + i * stepBottom).toInt()
                            val w =
                                ((captureLeft + (i + 1) * stepBottom).toInt() - x).coerceAtLeast(1)
                            val y = (captureBottom - scanDepthV).toInt()
                            val h = scanDepthV
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "left_bt" -> {
                    // Левый край, снизу вверх
                    for (i in 0 until leftCount) {
                        if (sideMode == "enabled") {
                            val segmentIdx = leftCount - 1 - i
                            val x = captureLeft.toInt()
                            val w = scanDepthH
                            val y = (captureTop + segmentIdx * stepLeft).toInt()
                            val h =
                                ((captureTop + (segmentIdx + 1) * stepLeft).toInt() - y).coerceAtLeast(
                                    1
                                )
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }

                "left_tb" -> {
                    // Левый край, сверху вниз
                    for (i in 0 until leftCount) {
                        if (sideMode == "enabled") {
                            val x = captureLeft.toInt()
                            val w = scanDepthH
                            val y = (captureTop + i * stepLeft).toInt()
                            val h = ((captureTop + (i + 1) * stepLeft).toInt() - y).coerceAtLeast(1)
                            setAverageColorFromRect(
                                ledData[ledIdx++],
                                screenData,
                                width,
                                height,
                                x,
                                y,
                                w,
                                h
                            )
                        } else {
                            ledData[ledIdx++].set(0, 0, 0)
                        }
                    }
                }
            }
        }

        // Добиваем остаток, если он вдруг остался
        while (ledIdx < totalLEDs) {
            ledData[ledIdx++].set(0, 0, 0)
        }

        // Сдвиг светодиодов по периметру
        val offset = (layout.ledOffset % totalLEDs + totalLEDs) % totalLEDs
        if (offset == 0) return ledData

        val rotated = Array(totalLEDs) { ColorRgb(0, 0, 0) }
        for (i in 0 until totalLEDs) {
            val targetIndex = (i + offset) % totalLEDs
            rotated[targetIndex].set(ledData[i])
        }

        return rotated
    }

    /**
     * Порядок обхода сторон по стартовому углу и направлению.
     * По часовой: верх → правая → низ → левая.
     * Против часовой: в обратную сторону.
     */
    internal fun getEdgeOrder(startCorner: String, direction: String): List<String> {
        return when (startCorner) {
            "top_left" -> {
                if (direction == "clockwise") {
                    // Из левого верхнего по часовой: вправо по верху, вниз справа, влево по низу, вверх слева
                    listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
                } else {
                    // Из левого верхнего против часовой: вниз слева, вправо по низу, вверх справа, влево по верху
                    listOf("left_tb", "bottom_lr", "right_bt", "top_rl")
                }
            }

            "top_right" -> {
                if (direction == "clockwise") {
                    // Из правого верхнего по часовой: вниз справа, влево по низу, вверх слева, вправо по верху
                    listOf("right_tb", "bottom_rl", "left_bt", "top_lr")
                } else {
                    // Из правого верхнего против часовой: влево по верху, вниз слева, вправо по низу, вверх справа
                    listOf("top_rl", "left_tb", "bottom_lr", "right_bt")
                }
            }

            "bottom_right" -> {
                if (direction == "clockwise") {
                    // Из правого нижнего по часовой: влево по низу, вверх слева, вправо по верху, вниз справа
                    listOf("bottom_rl", "left_bt", "top_lr", "right_tb")
                } else {
                    // Из правого нижнего против часовой: вверх справа, влево по верху, вниз слева, вправо по низу
                    listOf("right_bt", "top_rl", "left_tb", "bottom_lr")
                }
            }

            "bottom_left" -> {
                if (direction == "clockwise") {
                    // Из левого нижнего по часовой: вверх слева, вправо по верху, вниз справа, влево по низу
                    listOf("left_bt", "top_lr", "right_tb", "bottom_rl")
                } else {
                    // Из левого нижнего против часовой: вправо по низу, вверх справа, влево по верху, вниз слева
                    listOf("bottom_lr", "right_bt", "top_rl", "left_tb")
                }
            }

            else -> {
                // По умолчанию — из левого верхнего по часовой
                if (logsEnabled) Log.w(TAG, "Unknown start corner: $startCorner, using default")
                listOf("top_lr", "right_tb", "bottom_rl", "left_bt")
            }
        }
    }

    private fun setAverageColorFromRect(
        dest: ColorRgb,
        screenData: ByteArray,
        width: Int,
        height: Int,
        rectX: Int,
        rectY: Int,
        rectW: Int,
        rectH: Int,
    ) {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0

        val startX = rectX.coerceIn(0, width - 1)
        val startY = rectY.coerceIn(0, height - 1)
        val endX = (rectX + rectW).coerceIn(startX + 1, width)
        val endY = (rectY + rectH).coerceIn(startY + 1, height)

        if (endX <= startX || endY <= startY) {
            dest.set(0, 0, 0)
            return
        }

        for (y in startY until endY) {
            var idx = (y * width + startX) * 3
            // Подстраховка по границам, хотя ограничение выше должно её покрывать
            if (idx + (endX - startX) * 3 > screenData.size) {
                // Не должно случаться, если width/height совпадают со screenData
                continue
            }

            for (x in startX until endX) {
                rSum += screenData[idx].toInt() and 0xFF
                gSum += screenData[idx + 1].toInt() and 0xFF
                bSum += screenData[idx + 2].toInt() and 0xFF
                count++
                idx += 3
            }
        }

        if (count > 0) {
            dest.set(
                (rSum / count).toInt(),
                (gSum / count).toInt(),
                (bSum / count).toInt()
            )
        } else {
            dest.set(0, 0, 0)
        }
    }
}
