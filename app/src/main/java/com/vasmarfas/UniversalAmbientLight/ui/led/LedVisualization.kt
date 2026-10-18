package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Отрисовка раскладки на Canvas: рамка телевизора и светодиоды по сторонам.
 */
@Composable
fun LedVisualization(
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
    captureMarginTop: Int,
    captureMarginRight: Int,
    captureMarginBottom: Int,
    captureMarginLeft: Int,
    ledOffset: Int,
    scanDepth: Int,
    modifier: Modifier = Modifier,
    layoutTestEnabled: Boolean = false,
) {
    val (safeTop, safeRight, safeBottom, safeLeft) = remember(
        topLed,
        rightLed,
        bottomLed,
        leftLed
    ) {
        val t = topLed.coerceIn(0, MAX_LEDS_PER_SIDE)
        val r = rightLed.coerceIn(0, MAX_LEDS_PER_SIDE)
        val b = bottomLed.coerceIn(0, MAX_LEDS_PER_SIDE)
        val l = leftLed.coerceIn(0, MAX_LEDS_PER_SIDE)
        val total = t + r + b + l

        if (total > MAX_LEDS_VISUALIZATION) {
            val factor = MAX_LEDS_VISUALIZATION.toFloat() / total
            listOf(
                (t * factor).toInt(),
                (r * factor).toInt(),
                (b * factor).toInt(),
                (l * factor).toInt()
            )
        } else {
            listOf(t, r, b, l)
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        // Цвета читаем из темы заранее: в светлой теме белые подписи на surface исчезали
        val labelColor = MaterialTheme.colorScheme.onSurface.toArgb()
        val disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val screenOutline = MaterialTheme.colorScheme.outline
        val captureOutline = MaterialTheme.colorScheme.tertiary

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                // Прямоугольник экрана. Размеры в dp/sp: сырые пиксели на разных
                // плотностях давали рамку и подписи разной физической величины
                val screenPadding = 20.dp.toPx()
                drawRect(
                    color = screenOutline,
                    topLeft = Offset(screenPadding, screenPadding),
                    size = androidx.compose.ui.geometry.Size(
                        width - screenPadding * 2,
                        height - screenPadding * 2
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )

                // Четыре цветовые полоски вокруг схемы показывают адресные цвета
                // проверочного режима. Геометрию и нумерацию LED не изменяем.
                if (layoutTestEnabled) {
                    val thickness = 5.dp.toPx()
                    val gap = 4.dp.toPx()
                    val screenWidth = width - screenPadding * 2f
                    val screenHeight = height - screenPadding * 2f
                    drawRect(
                        color = Color.Red.copy(alpha = if (sideTop == "enabled") 1f else 0.3f),
                        topLeft = Offset(screenPadding, screenPadding - gap - thickness),
                        size = androidx.compose.ui.geometry.Size(screenWidth, thickness),
                    )
                    drawRect(
                        color = Color.Green.copy(alpha = if (sideRight == "enabled") 1f else 0.3f),
                        topLeft = Offset(width - screenPadding + gap, screenPadding),
                        size = androidx.compose.ui.geometry.Size(thickness, screenHeight),
                    )
                    drawRect(
                        color = Color.Blue.copy(alpha = if (sideBottom == "enabled") 1f else 0.3f),
                        topLeft = Offset(screenPadding, height - screenPadding + gap),
                        size = androidx.compose.ui.geometry.Size(screenWidth, thickness),
                    )
                    drawRect(
                        color = Color.Yellow.copy(alpha = if (sideLeft == "enabled") 1f else 0.3f),
                        topLeft = Offset(screenPadding - gap - thickness, screenPadding),
                        size = androidx.compose.ui.geometry.Size(thickness, screenHeight),
                    )
                }

                // Область захвата со своими отступами по каждой стороне — внутренний прямоугольник
                val marginTop = captureMarginTop.coerceIn(0, 40)
                val marginRight = captureMarginRight.coerceIn(0, 40)
                val marginBottom = captureMarginBottom.coerceIn(0, 40)
                val marginLeft = captureMarginLeft.coerceIn(0, 40)
                if (marginTop > 0 || marginRight > 0 || marginBottom > 0 || marginLeft > 0) {
                    val screenWidth = width - screenPadding * 2
                    val screenHeight = height - screenPadding * 2
                    val innerLeft = screenPadding + screenWidth * marginLeft / 100f
                    val innerTop = screenPadding + screenHeight * marginTop / 100f
                    val innerRight = width - screenPadding - screenWidth * marginRight / 100f
                    val innerBottom = height - screenPadding - screenHeight * marginBottom / 100f

                    drawRect(
                        color = captureOutline,
                        topLeft = Offset(innerLeft, innerTop),
                        size = androidx.compose.ui.geometry.Size(
                            innerRight - innerLeft,
                            innerBottom - innerTop
                        ),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Считаем позиции светодиодов
                var ledPositions = calculateLedPositions(
                    safeTop, safeRight, safeBottom, safeLeft,
                    startCorner, direction,
                    sideTop, sideRight, sideBottom, sideLeft, bottomGap,
                    width, height, screenPadding,
                    scanDepth
                )

                // Apply same offset in visualization so порядок номеров совпадает
                if (ledPositions.isNotEmpty()) {
                    val size = ledPositions.size
                    val offset = ((ledOffset % size) + size) % size
                    if (offset != 0) {
                        val rotated = MutableList(size) { ledPositions[0] }
                        for (i in 0 until size) {
                            val targetIndex = (i + offset) % size
                            rotated[targetIndex] = ledPositions[i]
                        }
                        ledPositions = rotated
                    }
                }

                // Рисуем светодиоды
                if (ledPositions.isNotEmpty()) {
                    // Немного ограничим количество подписанных LED, чтобы не грузить слабые устройства
                    val maxLabeled = 200
                    val shouldLabelIndices: (Int) -> Boolean = { index ->
                        if (ledPositions.size > maxLabeled) {
                            // При очень большом количестве только первые/последние пару и каждый десятый
                            index < 3 || index >= ledPositions.size - 3 || index % 10 == 0
                        } else {
                            index < 5 || index >= ledPositions.size - 5 || index % 5 == 0
                        }
                    }

                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val labelSize = 11.sp.toPx()
                    val firstLabelSize = 14.sp.toPx()
                    val enabledPaint = android.graphics.Paint().apply {
                        color = labelColor
                        textSize = labelSize
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val disabledPaint = android.graphics.Paint().apply {
                        color = disabledLabelColor
                        textSize = labelSize
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    val firstLedPaint = android.graphics.Paint().apply {
                        color = labelColor
                        textSize = firstLabelSize
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                        isAntiAlias = true
                    }

                    ledPositions.forEachIndexed { index, ledData ->
                        val color = when {
                            !ledData.enabled -> Color.Gray.copy(alpha = 0.4f) // Disabled LED
                            index == 0 -> Color(0xFF4CAF50) // Green for first LED
                            else -> Color(0xFF2196F3) // Blue for others
                        }

                        if (ledData.rectSize.width > 0 && ledData.rectSize.height > 0) {
                            val topLeft = Offset(
                                ledData.position.x - ledData.rectSize.width / 2f,
                                ledData.position.y - ledData.rectSize.height / 2f
                            )
                            drawRect(
                                color = color.copy(alpha = 0.7f),
                                topLeft = topLeft,
                                size = ledData.rectSize,
                                style = androidx.compose.ui.graphics.drawscope.Fill
                            )
                            // Обводка, чтобы светодиод был виден на светлом фоне
                            drawRect(
                                color = if (index == 0) Color.White else Color.Black.copy(alpha = 0.5f),
                                topLeft = topLeft,
                                size = ledData.rectSize,
                                style = Stroke(width = if (index == 0) 2f else 1f)
                            )
                        } else {
                            // Запасной вариант
                            drawCircle(
                                color = color,
                                radius = if (index == 0) 18f else 8f,
                                center = ledData.position
                            )
                        }

                        if (shouldLabelIndices(index)) {
                            val paint = when {
                                index == 0 -> firstLedPaint
                                ledData.enabled -> enabledPaint
                                else -> disabledPaint
                            }
                            // Для прямоугольника подпись слегка сдвигаем
                            val textY = if (ledData.rectSize.height > 0) {
                                ledData.position.y + labelSize / 3f
                            } else {
                                ledData.position.y +
                                        if (index == 0) firstLabelSize / 3f else labelSize / 3f
                            }

                            nativeCanvas.drawText(
                                "${index + 1}",
                                ledData.position.x,
                                textY,
                                paint
                            )
                        }
                    }
                }
            }
        }
    }
}
