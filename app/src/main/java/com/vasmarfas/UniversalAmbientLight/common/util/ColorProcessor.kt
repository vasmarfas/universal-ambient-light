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
     * Обрабатывает RGB байтовый массив на месте
     * @param rgbData Массив RGB данных (r, g, b, r, g, b, ...)
     * @param options Настройки приложения с параметрами обработки
     */
    fun processRgbData(rgbData: ByteArray, options: AppOptions) {
        for (i in rgbData.indices step 3) {
            if (i + 2 < rgbData.size) {
                val r = rgbData[i].toInt() and 0xFF
                val g = rgbData[i + 1].toInt() and 0xFF
                val b = rgbData[i + 2].toInt() and 0xFF
                
                val (rOut, gOut, bOut) = processColor(
                    r, g, b,
                    options.brightness,
                    options.contrast,
                    options.blackLevel,
                    options.whiteLevel,
                    options.saturation
                )
                
                rgbData[i] = rOut.toByte()
                rgbData[i + 1] = gOut.toByte()
                rgbData[i + 2] = bOut.toByte()
            }
        }
    }
}
