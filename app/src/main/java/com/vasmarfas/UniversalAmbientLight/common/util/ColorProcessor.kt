package com.vasmarfas.UniversalAmbientLight.common.util

import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor.processColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Color correction pipeline:
 *  1) per-channel gamma (R/G/B independent curves)
 *  2) global brightness × per-channel brightness
 *  3) contrast (around 128)
 *  4) saturation (mixes channels — disables the fast LUT path)
 *  5) black/white levels (clip & rescale)
 */
object ColorProcessor {

    /** Single-pixel variant; kept for non-hot-path callers (e.g. avg-color mode). */
    fun processColor(
        r: Int,
        g: Int,
        b: Int,
        brightness: Int = 100,
        contrast: Int = 100,
        blackLevel: Int = 0,
        whiteLevel: Int = 100,
        saturation: Int = 100,
        brightnessR: Int = 100,
        brightnessG: Int = 100,
        brightnessB: Int = 100,
        gammaR: Int = 100,
        gammaG: Int = 100,
        gammaB: Int = 100,
    ): Triple<Int, Int, Int> {
        var rf = applyGamma(r.toFloat(), gammaR)
        var gf = applyGamma(g.toFloat(), gammaG)
        var bf = applyGamma(b.toFloat(), gammaB)

        val globalFactor = brightness / 100f
        rf *= globalFactor * (brightnessR / 100f)
        gf *= globalFactor * (brightnessG / 100f)
        bf *= globalFactor * (brightnessB / 100f)

        if (contrast != 100) {
            val cf = 1f + (contrast - 100) / 100f
            rf = 128f + (rf - 128f) * cf
            gf = 128f + (gf - 128f) * cf
            bf = 128f + (bf - 128f) * cf
        }

        if (saturation != 100) {
            val luminance = 0.299f * rf + 0.587f * gf + 0.114f * bf
            val sf = saturation / 100f
            rf = luminance + (rf - luminance) * sf
            gf = luminance + (gf - luminance) * sf
            bf = luminance + (bf - luminance) * sf
        }

        val blackThreshold = (blackLevel / 100f) * 255f
        val whiteThreshold = (whiteLevel / 100f) * 255f
        val range = whiteThreshold - blackThreshold
        if (range > 0) {
            rf = (rf - blackThreshold) / range * 255f
            gf = (gf - blackThreshold) / range * 255f
            bf = (bf - blackThreshold) / range * 255f
        } else {
            rf = 0f; gf = 0f; bf = 0f
        }

        return Triple(
            max(0, min(255, round(rf).toInt())),
            max(0, min(255, round(gf).toInt())),
            max(0, min(255, round(bf).toInt()))
        )
    }

    /** Bulk in-place processing on an RGB-packed byte array. */
    fun processRgbData(rgbData: ByteArray, options: AppOptions) {
        if (!options.colorProcessingEnabled) return

        // Fast bail-out when nothing would change.
        if (options.brightness == 100 &&
            options.contrast == 100 &&
            options.blackLevel == 0 &&
            options.whiteLevel == 100 &&
            options.saturation == 100 &&
            options.brightnessR == 100 && options.brightnessG == 100 && options.brightnessB == 100 &&
            options.gammaR == 100 && options.gammaG == 100 && options.gammaB == 100
        ) return

        // Saturation mixes channels → LUT path is invalid; fall through to per-pixel below.
        if (options.saturation == 100) {
            val rLut = buildChannelLut(options, options.brightnessR, options.gammaR)
            val gLut = buildChannelLut(options, options.brightnessG, options.gammaG)
            val bLut = buildChannelLut(options, options.brightnessB, options.gammaB)

            var i = 0
            val n = rgbData.size - 2
            while (i < n) {
                rgbData[i] = rLut[rgbData[i].toInt() and 0xFF]
                rgbData[i + 1] = gLut[rgbData[i + 1].toInt() and 0xFF]
                rgbData[i + 2] = bLut[rgbData[i + 2].toInt() and 0xFF]
                i += 3
            }
            return
        }

        // Slow per-pixel path (saturation mixes channels, can't use per-channel LUTs).
        val globalFactor = options.brightness / 100f
        val rFactor = globalFactor * (options.brightnessR / 100f)
        val gFactor = globalFactor * (options.brightnessG / 100f)
        val bFactor = globalFactor * (options.brightnessB / 100f)
        val contrastFactor = 1f + (options.contrast - 100) / 100f
        val saturationFactor = options.saturation / 100f
        val blackThreshold = (options.blackLevel / 100f) * 255f
        val whiteThreshold = (options.whiteLevel / 100f) * 255f
        val range = whiteThreshold - blackThreshold
        val rangeFactor = if (range > 0) 255f / range else 0f

        // Gamma LUTs reused per-pixel to avoid Math.pow per channel per pixel.
        val rGammaLut = buildGammaLut(options.gammaR)
        val gGammaLut = buildGammaLut(options.gammaG)
        val bGammaLut = buildGammaLut(options.gammaB)

        var i = 0
        val n = rgbData.size - 2
        while (i < n) {
            var r = rGammaLut[rgbData[i].toInt() and 0xFF]
            var g = gGammaLut[rgbData[i + 1].toInt() and 0xFF]
            var b = bGammaLut[rgbData[i + 2].toInt() and 0xFF]

            if (options.brightness != 100 ||
                options.brightnessR != 100 || options.brightnessG != 100 || options.brightnessB != 100
            ) {
                r *= rFactor; g *= gFactor; b *= bFactor
            }

            if (options.contrast != 100) {
                r = 128f + (r - 128f) * contrastFactor
                g = 128f + (g - 128f) * contrastFactor
                b = 128f + (b - 128f) * contrastFactor
            }

            // Saturation (luminance preserved across all three channels).
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            r = lum + (r - lum) * saturationFactor
            g = lum + (g - lum) * saturationFactor
            b = lum + (b - lum) * saturationFactor

            if (range > 0 && (options.blackLevel != 0 || options.whiteLevel != 100)) {
                r = (r - blackThreshold) * rangeFactor
                g = (g - blackThreshold) * rangeFactor
                b = (b - blackThreshold) * rangeFactor
            }

            rgbData[i] = clampToByte(r)
            rgbData[i + 1] = clampToByte(g)
            rgbData[i + 2] = clampToByte(b)
            i += 3
        }
    }

    private fun clampToByte(v: Float): Byte = when {
        v < 0f -> 0
        v > 255f -> -1
        else -> v.toInt().toByte()
    }

    /**
     * Per-channel LUT used by the fast path. Encodes: gamma → channel × global brightness
     * → contrast → black/white levels. Saturation is NOT included (channel-mixing op).
     */
    private fun buildChannelLut(
        options: AppOptions,
        channelBrightness: Int,
        channelGamma: Int,
    ): ByteArray {
        val lut = ByteArray(256)
        val gammaTable = buildGammaLut(channelGamma)
        val factor = (options.brightness / 100f) * (channelBrightness / 100f)
        val contrastFactor = 1f + (options.contrast - 100) / 100f
        val blackThreshold = (options.blackLevel / 100f) * 255f
        val whiteThreshold = (options.whiteLevel / 100f) * 255f
        val range = whiteThreshold - blackThreshold
        val rangeFactor = if (range > 0) 255f / range else 0f

        for (i in 0..255) {
            var v = gammaTable[i] * factor
            if (options.contrast != 100) v = 128f + (v - 128f) * contrastFactor
            v = if (range > 0) (v - blackThreshold) * rangeFactor else 0f
            lut[i] = clampToByte(v)
        }
        return lut
    }

    /** Single-value gamma; used by [processColor] which is not on the hot path. */
    private fun applyGamma(value: Float, gammaPct: Int): Float {
        if (gammaPct == 100) return value
        val safePct = gammaPct.coerceIn(MIN_GAMMA_PCT, MAX_GAMMA_PCT)
        val invGamma = 100.0 / safePct
        val norm = (value.coerceIn(0f, 255f) / 255.0).pow(invGamma)
        return (norm * 255.0).toFloat()
    }

    /** out = 255 * (in/255)^(100/gamma_pct). gamma_pct = 100 → identity. */
    private fun buildGammaLut(gammaPct: Int): FloatArray {
        val out = FloatArray(256)
        if (gammaPct == 100) {
            for (i in 0..255) out[i] = i.toFloat()
            return out
        }
        val safePct = gammaPct.coerceIn(MIN_GAMMA_PCT, MAX_GAMMA_PCT)
        val invGamma = 100.0 / safePct
        for (i in 0..255) {
            val norm = (i / 255.0).pow(invGamma)
            out[i] = (norm * 255.0).toFloat()
        }
        return out
    }

    private const val MIN_GAMMA_PCT = 10
    private const val MAX_GAMMA_PCT = 500
}
