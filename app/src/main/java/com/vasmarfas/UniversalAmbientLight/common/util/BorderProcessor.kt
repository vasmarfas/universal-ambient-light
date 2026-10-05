package com.vasmarfas.UniversalAmbientLight.common.util

import java.nio.ByteBuffer

/**
 * Определяет чёрные полосы отдельно сверху, справа, снизу и слева, поэтому
 * несимметричный letterbox (например, строка состояния только сверху) обрабатывается верно.
 *
 * Каждый край проверяется тремя пробными линиями на 25%/50%/75% другой оси.
 * Первая строка или столбец, где среди проб нашёлся нечёрный пиксель, считается началом
 * картинки; всё за этой границей отрезается.
 *
 * Дребезг гасится требованием [stabilityDetections] одинаковых замеров подряд, прежде чем
 * переключиться на новую границу. Самая первая найденная граница применяется сразу, чтобы
 * пользователь увидел эффект на первом же цикле определения, а не через несколько секунд.
 *
 *
 * Потокобезопасность: один экземпляр используется из одного потока захвата.
 * Поля [blackThreshold] и [stabilityDetections] помечены volatile, потому что слушатель
 * настроек может поменять их в любой момент.
 */
class BorderProcessor(
    initialBlackThreshold: Int = 18,
    initialStabilityDetections: Int = 3,
) {
    @Volatile
    var blackThreshold: Int = initialBlackThreshold

    /** Сколько одинаковых замеров подряд нужно, чтобы принять новую границу. */
    @Volatile
    var stabilityDetections: Int = initialStabilityDetections

    private var mPreviousBorder: BorderRect? = null
    var currentBorder: BorderRect? = null
        private set
    private var mConsistentDetections = 0
    private var mDetectCounter = 0

    /** Переиспользуемый буфер под результат обрезки. */
    private var mCropBuffer: ByteArray? = null

    private fun checkNewBorder(newBorder: BorderRect) {
        val previous = mPreviousBorder
        if (previous == null) {
            // Первый цикл: только запоминаем базовое значение. Включение проходит ту же
            // проверку на стабильность, что и выключение, — оба перехода получаются плавными,
            // без рывка на самом первом замере (резкая смена размеров ломает дальнейший
            // конвейер обработки).
            mPreviousBorder = newBorder
            mConsistentDetections = 1
            return
        }
        if (previous == newBorder) {
            mConsistentDetections++
            val needed = stabilityDetections.coerceAtLeast(1)
            if (mConsistentDetections < needed) return
            // Устойчивый результат. «Полос нет» (isKnown=false) — тоже нормальное устойчивое
            // состояние: letterbox пропал, значит снимаем обрезку.
            val desired: BorderRect? = if (newBorder.isKnown) newBorder else null
            if (currentBorder != desired) currentBorder = desired
        } else {
            mPreviousBorder = newBorder
            mConsistentDetections = 1
        }
    }

    /** Путь для плоского RGB (3 байта на пиксель, построчно). */
    fun parseBorderRgb(rgb: ByteArray, width: Int, height: Int) {
        checkNewBorder(findBorderRgb(rgb, width, height))
    }

    /** Путь для ImageReader из MediaProjection (буфер RGBA со stride). */
    fun parseBorder(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        checkNewBorder(findBorderRgba(buffer, width, height, rowStride, pixelStride))
    }

    fun applyBorderCrop(rgb: ByteArray, width: Int, height: Int): CropResult {
        parseBorderRgb(rgb, width, height)
        return applyKnownBorderCrop(rgb, width, height)
    }

    fun applyKnownBorderCrop(rgb: ByteArray, width: Int, height: Int): CropResult {
        val border = currentBorder ?: return CropResult(rgb, width, height)
        if (!border.isKnown) return CropResult(rgb, width, height)

        val top = border.top.coerceAtLeast(0)
        val bottom = border.bottom.coerceAtLeast(0)
        val left = border.left.coerceAtLeast(0)
        val right = border.right.coerceAtLeast(0)
        // Не срезаем больше половины ни по одной оси — страховка от разошедшихся замеров.
        val maxV = height / 2 - 1
        val maxH = width / 2 - 1
        val t = top.coerceAtMost(maxV)
        val b = bottom.coerceAtMost(maxV)
        val l = left.coerceAtMost(maxH)
        val r = right.coerceAtMost(maxH)

        if (t == 0 && b == 0 && l == 0 && r == 0) return CropResult(rgb, width, height)

        val newW = width - l - r
        val newH = height - t - b
        if (newW <= 0 || newH <= 0) return CropResult(rgb, width, height)

        val needed = newW * newH * 3
        var buffer = mCropBuffer
        if (buffer == null || buffer.size != needed) {
            buffer = ByteArray(needed)
            mCropBuffer = buffer
        }
        val rowBytes = newW * 3
        var dst = 0
        for (y in 0 until newH) {
            val srcRow = (t + y) * width * 3 + l * 3
            System.arraycopy(rgb, srcRow, buffer, dst, rowBytes)
            dst += rowBytes
        }
        // Возвращаем свежую копию: исполнитель Hyperion читает буфер асинхронно, а поток
        // энкодера успеет записать в mCropBuffer следующий кадр раньше, чем тот дочитает.
        // Без копии исполнитель увидит разорванный кадр, и дальше по цепочке (нарезка
        // пакетов WLED, сглаживание) возможны короткие подвисания.
        return CropResult(buffer, newW, newH)
    }

    /**
     * Всё, что нужно энкодеру, одним вызовом:
     *  - учитывает [AppOptions.borderDetectionEnabled];
     *  - перезапускает определение каждые [AppOptions.borderCheckIntervalFrames] кадров;
     *  - применяет запомненную обрезку к каждому кадру, чтобы между замерами картинка
     *    оставалась обрезанной;
     *  - поддерживает [AppOptions.borderThreshold] в актуальном состоянии.
     */
    fun applyForEncoder(
        rgb: ByteArray, width: Int, height: Int, options: AppOptions,
    ): CropResult {
        if (!options.borderDetectionEnabled) {
            mDetectCounter = 0
            currentBorder = null
            mPreviousBorder = null
            mConsistentDetections = 0
            return CropResult(rgb, width, height)
        }
        blackThreshold = options.borderThreshold
        val interval = options.borderCheckIntervalFrames.coerceAtLeast(1)
        if (++mDetectCounter >= interval) {
            mDetectCounter = 0
            return applyBorderCrop(rgb, width, height)
        }
        return applyKnownBorderCrop(rgb, width, height)
    }

    private fun findBorderRgb(rgb: ByteArray, width: Int, height: Int): BorderRect {
        if (width <= 0 || height <= 0 || rgb.size < width * height * 3) {
            return BorderRect.UNKNOWN
        }
        val rowBytes = width * 3
        // Разрешаем срезать до половины каждой оси, чтобы ловить широкий letterbox:
        // контент 2.39:1 на экране 16:9 оставляет примерно по 45% черноты сверху и снизу —
        // прежнего предела в треть на это не хватало.
        val maxV = height / 2
        val maxH = width / 2
        // Плотность выборки. При ~32 пробах на строку или столбец значки строки состояния
        // (примерно 10% ширины строки) уже не дают ложного вердикта «не чёрное», а тратим
        // мы меньше width/32 * width пикселей на ось — пренебрежимо мало.
        val xStep = maxOf(1, width / NUM_SAMPLES)
        val yStep = maxOf(1, height / NUM_SAMPLES)

        var top = -1
        for (y in 0 until maxV) {
            if (!isRowBlack(rgb, rowBytes, y, width, xStep)) {
                top = y; break
            }
        }
        var bottom = -1
        for (y in height - 1 downTo height - maxV) {
            if (!isRowBlack(rgb, rowBytes, y, width, xStep)) {
                bottom = height - 1 - y; break
            }
        }
        var left = -1
        for (x in 0 until maxH) {
            if (!isColBlack(rgb, rowBytes, x, height, yStep)) {
                left = x; break
            }
        }
        var right = -1
        for (x in width - 1 downTo width - maxH) {
            if (!isColBlack(rgb, rowBytes, x, height, yStep)) {
                right = width - 1 - x; break
            }
        }

        // Округляем каждую границу до полос примерно по 3%, чтобы дрожание в один пиксель
        // не ломало равенство BorderRect между соседними замерами. Без этого на контенте
        // без полос один случайный тёмный пиксель давал BorderRect(1,0,0,0) в одном кадре
        // и BorderRect(0,1,0,0) в следующем → mConsistentDetections никогда не доходил
        // до stabilityDetections.
        return BorderRect(
            quantize(top, height),
            quantize(right, width),
            quantize(bottom, height),
            quantize(left, width)
        )
    }

    private fun quantize(value: Int, frameSize: Int): Int {
        if (value <= 0) return value  // preserve -1 (unknown) as-is; 0 stays 0
        val band = maxOf(1, frameSize / 32)
        return (value / band) * band
    }

    /** Строка считается «чёрной», если чёрными оказались не менее [BLACK_FRACTION] проб. */
    private fun isRowBlack(rgb: ByteArray, rowBytes: Int, y: Int, width: Int, xStep: Int): Boolean {
        val base = y * rowBytes
        var samples = 0
        var blacks = 0
        var x = 0
        while (x < width) {
            samples++
            if (isBlackAt(rgb, base + x * 3)) blacks++
            x += xStep
        }
        return samples > 0 && blacks * 100 >= samples * BLACK_PERCENT
    }

    private fun isColBlack(
        rgb: ByteArray,
        rowBytes: Int,
        x: Int,
        height: Int,
        yStep: Int,
    ): Boolean {
        var samples = 0
        var blacks = 0
        var y = 0
        while (y < height) {
            samples++
            if (isBlackAt(rgb, y * rowBytes + x * 3)) blacks++
            y += yStep
        }
        return samples > 0 && blacks * 100 >= samples * BLACK_PERCENT
    }

    private fun findBorderRgba(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
    ): BorderRect {
        val xa = width / 4
        val xb = width / 2
        val xc = (3 * width) / 4
        val ya = height / 4
        val yb = height / 2
        val yc = (3 * height) / 4
        val maxV = height / 2
        val maxH = width / 2

        fun probeNonBlack(off: Int): Boolean {
            if (off < 0 || off + 2 >= buffer.limit()) return false
            val r = buffer.get(off).toInt() and 0xff
            val g = buffer.get(off + 1).toInt() and 0xff
            val b = buffer.get(off + 2).toInt() and 0xff
            return !isBlack(r, g, b)
        }

        buffer.position(0).mark()

        var top = -1
        for (y in 0 until maxV) {
            val base = y * rowStride
            if (probeNonBlack(base + xa * pixelStride) ||
                probeNonBlack(base + xb * pixelStride) ||
                probeNonBlack(base + xc * pixelStride)
            ) {
                top = y; break
            }
        }
        var bottom = -1
        for (y in height - 1 downTo height - maxV) {
            val base = y * rowStride
            if (probeNonBlack(base + xa * pixelStride) ||
                probeNonBlack(base + xb * pixelStride) ||
                probeNonBlack(base + xc * pixelStride)
            ) {
                bottom = height - 1 - y; break
            }
        }
        var left = -1
        for (x in 0 until maxH) {
            if (probeNonBlack(ya * rowStride + x * pixelStride) ||
                probeNonBlack(yb * rowStride + x * pixelStride) ||
                probeNonBlack(yc * rowStride + x * pixelStride)
            ) {
                left = x; break
            }
        }
        var right = -1
        for (x in width - 1 downTo width - maxH) {
            if (probeNonBlack(ya * rowStride + x * pixelStride) ||
                probeNonBlack(yb * rowStride + x * pixelStride) ||
                probeNonBlack(yc * rowStride + x * pixelStride)
            ) {
                right = width - 1 - x; break
            }
        }

        buffer.reset()
        return BorderRect(top, right, bottom, left)
    }

    private fun isBlackAt(rgb: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + 2 >= rgb.size) return true
        val r = rgb[offset].toInt() and 0xff
        val g = rgb[offset + 1].toInt() and 0xff
        val b = rgb[offset + 2].toInt() and 0xff
        return isBlack(r, g, b)
    }

    private fun isBlack(r: Int, g: Int, b: Int): Boolean {
        val t = blackThreshold
        return r < t && g < t && b < t
    }

    companion object {
        /** Сколько точек берём на строку или столбец. */
        private const val NUM_SAMPLES = 32

        /** Строка или столбец считается чёрным, если чёрных проб не меньше этой доли (в процентах). */
        private const val BLACK_PERCENT = 85
    }

    data class CropResult(val rgb: ByteArray, val width: Int, val height: Int)

    /** Отступы обрезки в пикселях от каждого края. `-1` означает «все пробы на этом крае чёрные». */
    data class BorderRect(val top: Int, val right: Int, val bottom: Int, val left: Int) {
        /** Хотя бы у одного края нашлась нечёрная область — есть что обрезать. */
        val isKnown: Boolean
            get() = top > 0 || right > 0 || bottom > 0 || left > 0

        companion object {
            val UNKNOWN = BorderRect(-1, -1, -1, -1)
        }
    }
}
