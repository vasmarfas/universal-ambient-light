package com.vasmarfas.UniversalAmbientLight.common.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Обработка цветов с применением яркости, контрастности, уровней черного/белого и насыщенности
 */
object ColorProcessor {
    
    /**
     * Обрабатывает RGB цвет с применением всех настроек
     * @param r Красный канал [0-255]
     * @param g Зеленый канал [0-255]
     * @param b Синий канал [0-255]
     * @param brightness Яркость в процентах [0-200], 100 = без изменений
     * @param contrast Контрастность в процентах [0-200], 100 = без изменений
     * @param blackLevel Уровень черного в процентах [0-100], значения ниже этого обрезаются
     * @param whiteLevel Уровень белого в процентах [0-100], значения выше этого обрезаются
     * @param saturation Насыщенность в процентах [0-200], 100 = без изменений
     * @return Triple(r, g, b) обработанные значения [0-255]
     */
    fun processColor(
        r: Int,
        g: Int,
        b: Int,
        brightness: Int = 100,
        contrast: Int = 100,
        blackLevel: Int = 0,
        whiteLevel: Int = 100,
        saturation: Int = 100
    ): Triple<Int, Int, Int> {
        var rFloat = r.toFloat()
        var gFloat = g.toFloat()
        var bFloat = b.toFloat()
        
        // 1. Применяем яркость (умножаем на коэффициент)
        val brightnessFactor = brightness / 100f
        rFloat *= brightnessFactor
        gFloat *= brightnessFactor
        bFloat *= brightnessFactor
        
        // 2. Применяем контрастность
        // Контрастность работает относительно среднего значения (128)
        val contrastFactor = (contrast - 100) / 100f
        rFloat = 128f + (rFloat - 128f) * (1f + contrastFactor)
        gFloat = 128f + (gFloat - 128f) * (1f + contrastFactor)
        bFloat = 128f + (bFloat - 128f) * (1f + contrastFactor)
        
        // 3. Применяем насыщенность
        if (saturation != 100) {
            // Вычисляем яркость (luminance) для десатурации
            val luminance = 0.299f * rFloat + 0.587f * gFloat + 0.114f * bFloat
            val saturationFactor = saturation / 100f
            
            rFloat = luminance + (rFloat - luminance) * saturationFactor
            gFloat = luminance + (gFloat - luminance) * saturationFactor
            bFloat = luminance + (bFloat - luminance) * saturationFactor
        }
        
        // 4. Применяем уровни черного и белого
        // Преобразуем проценты в значения 0-255
        val blackThreshold = (blackLevel / 100f) * 255f
        val whiteThreshold = (whiteLevel / 100f) * 255f
        
        // Применяем уровни: масштабируем диапазон [blackThreshold, whiteThreshold] в [0, 255]
        val range = whiteThreshold - blackThreshold
        if (range > 0) {
            // Масштабируем каждый канал
            rFloat = ((rFloat - blackThreshold) / range) * 255f
            gFloat = ((gFloat - blackThreshold) / range) * 255f
            bFloat = ((bFloat - blackThreshold) / range) * 255f
            
            // Обрезаем значения ниже 0 и выше 255
            rFloat = max(0f, min(255f, rFloat))
            gFloat = max(0f, min(255f, gFloat))
            bFloat = max(0f, min(255f, bFloat))
        } else {
            // Если диапазон нулевой или отрицательный, все значения становятся 0
            rFloat = 0f
            gFloat = 0f
            bFloat = 0f
        }
        
        // 5. Ограничиваем значения диапазоном [0-255] и округляем
        val rOut = max(0, min(255, round(rFloat).toInt()))
        val gOut = max(0, min(255, round(gFloat).toInt()))
        val bOut = max(0, min(255, round(bFloat).toInt()))
        
        return Triple(rOut, gOut, bOut)
    }
    
    /**
     * Обрабатывает RGB байтовый массив на месте.
     * Оптимизировано для минимизации аллокаций и вычислений.
     */
    fun processRgbData(rgbData: ByteArray, options: AppOptions) {
        // 0. Если обработка отключена, выходим сразу
        if (!options.colorProcessingEnabled) {
            return
        }

        // 1. Быстрая проверка на дефолтные настройки (ничего делать не надо)
        if (options.brightness == 100 && 
            options.contrast == 100 && 
            options.blackLevel == 0 && 
            options.whiteLevel == 100 && 
            options.saturation == 100) {
            return
        }

        // 2. Если насыщенность стандартная, используем быстрый LUT (Look-Up Table)
        // Так как каналы обрабатываются независимо
        if (options.saturation == 100) {
            val lut = ByteArray(256)
            for (i in 0..255) {
                lut[i] = processSingleChannel(
                    i, 
                    options.brightness, 
                    options.contrast, 
                    options.blackLevel, 
                    options.whiteLevel
                ).toByte()
            }

            for (i in rgbData.indices) {
                val index = rgbData[i].toInt() and 0xFF
                rgbData[i] = lut[index]
            }
            return
        }

        // 3. Полная обработка с насыщенностью (без аллокаций объектов)
        val brightnessFactor = options.brightness / 100f
        val contrastFactor = (options.contrast - 100) / 100f + 1f
        val saturationFactor = options.saturation / 100f
        
        // Предвычисляем пороговые значения для уровней
        val blackThreshold = (options.blackLevel / 100f) * 255f
        val whiteThreshold = (options.whiteLevel / 100f) * 255f
        val range = whiteThreshold - blackThreshold
        val rangeFactor = if (range > 0) 255f / range else 0f

        for (i in rgbData.indices step 3) {
            if (i + 2 < rgbData.size) {
                var r = (rgbData[i].toInt() and 0xFF).toFloat()
                var g = (rgbData[i + 1].toInt() and 0xFF).toFloat()
                var b = (rgbData[i + 2].toInt() and 0xFF).toFloat()

                // Яркость
                if (options.brightness != 100) {
                    r *= brightnessFactor
                    g *= brightnessFactor
                    b *= brightnessFactor
                }

                // Контрастность
                if (options.contrast != 100) {
                    r = 128f + (r - 128f) * contrastFactor
                    g = 128f + (g - 128f) * contrastFactor
                    b = 128f + (b - 128f) * contrastFactor
                }

                // Насыщенность (смешивание каналов)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                r = lum + (r - lum) * saturationFactor
                g = lum + (g - lum) * saturationFactor
                b = lum + (b - lum) * saturationFactor

                // Уровни (Black/White)
                if (range > 0 && (options.blackLevel != 0 || options.whiteLevel != 100)) {
                    r = (r - blackThreshold) * rangeFactor
                    g = (g - blackThreshold) * rangeFactor
                    b = (b - blackThreshold) * rangeFactor
                }

                // Clamping и запись
                rgbData[i] = when {
                    r < 0 -> 0
                    r > 255 -> -1 // 255 as signed byte is -1
                    else -> r.toInt().toByte()
                }
                rgbData[i+1] = when {
                    g < 0 -> 0
                    g > 255 -> -1
                    else -> g.toInt().toByte()
                }
                rgbData[i+2] = when {
                    b < 0 -> 0
                    b > 255 -> -1
                    else -> b.toInt().toByte()
                }
            }
        }
    }

    /**
     * Вспомогательная функция для генерации LUT одного канала
     */
    private fun processSingleChannel(
        value: Int,
        brightness: Int,
        contrast: Int,
        blackLevel: Int,
        whiteLevel: Int
    ): Int {
        var v = value.toFloat()

        // Яркость
        v *= (brightness / 100f)

        // Контрастность
        val contrastFactor = (contrast - 100) / 100f
        v = 128f + (v - 128f) * (1f + contrastFactor)

        // Уровни
        val blackThreshold = (blackLevel / 100f) * 255f
        val whiteThreshold = (whiteLevel / 100f) * 255f
        val range = whiteThreshold - blackThreshold
        
        if (range > 0) {
            v = ((v - blackThreshold) / range) * 255f
        } else {
            v = 0f
        }

        return max(0, min(255, round(v).toInt()))
    }
}
