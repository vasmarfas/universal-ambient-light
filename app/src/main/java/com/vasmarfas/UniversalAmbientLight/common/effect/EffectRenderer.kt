package com.vasmarfas.UniversalAmbientLight.common.effect

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Рисует кадр эффекта в RGB-буфер того же вида, что отдают энкодеры захвата. Дальше кадр
 * идёт по обычному пути: разбор по раскладке ленты, цветокоррекция, любой протокол.
 *
 * Эффекты, бегущие вокруг экрана, считаются по координате вдоль периметра: каждый пиксель
 * проецируется из центра на край кадра, и длина пути по краю от левого верхнего угла по
 * часовой стрелке делится на весь периметр. Так «радуга по кругу» выглядит одинаково при
 * любых отступах захвата и глубине сканирования - светодиоды берут цвет с одного луча.
 *
 * Зависимостей от Android нет, проверяется юнит-тестами.
 */
class EffectRenderer(val width: Int, val height: Int) {

    private val mAspect = width.toFloat() / height

    /** Координата вдоль периметра, 0..1 по часовой стрелке от левого верхнего угла. */
    private val mAround = FloatArray(width * height)

    /** Положение по вертикали: 0 - верх, 1 - низ. */
    private val mDown = FloatArray(width * height)

    /** Сторона, на которую проецируется пиксель: 0 верх, 1 право, 2 низ, 3 лево. */
    private val mSide = ByteArray(width * height)

    private val mNx = FloatArray(width * height)
    private val mNy = FloatArray(width * height)

    private val mRgb = FloatArray(3)

    init {
        val perimeter = 4 * mAspect + 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val nx = (x + 0.5f) / width * 2 - 1
                val ny = (y + 0.5f) / height * 2 - 1
                mNx[i] = nx
                mNy[i] = ny
                mDown[i] = (ny + 1) / 2
                // Прямоугольник кадра - [-a, a] × [-1, 1]; сторона, в которую упирается луч
                // из центра, решается сравнением нормированных координат
                val s: Float
                if (abs(nx) >= abs(ny)) {
                    val edgeY = if (nx == 0f) 0f else ny / abs(nx)
                    if (nx > 0) {
                        s = 2 * mAspect + (edgeY + 1)
                        mSide[i] = 1
                    } else {
                        s = 4 * mAspect + 2 + (1 - edgeY)
                        mSide[i] = 3
                    }
                } else {
                    val edgeX = mAspect * nx / abs(ny)
                    if (ny < 0) {
                        s = edgeX + mAspect
                        mSide[i] = 0
                    } else {
                        s = 2 * mAspect + 2 + (mAspect - edgeX)
                        mSide[i] = 2
                    }
                }
                mAround[i] = (s / perimeter).let { it - floor(it) }
            }
        }
    }

    /** Рисует кадр в момент [timeMs] от начала эффекта; [out] длиной не меньше width*height*3. */
    fun render(config: EffectConfig, timeMs: Long, out: ByteArray) {
        val t = timeMs / 1000.0
        // Скорость по экспоненте: 50 - обычная, каждые 25 делений вдвое быстрее или медленнее
        val speed = 2.0.pow((config.speed - 50) / 25.0)
        val level = config.brightness / 100f
        val start = stripStart(config.startCorner)
        val clockwise = config.direction != "counterclockwise"
        val white = kelvinToRgb(config.temperature)
        val rgb = mRgb
        var o = 0
        for (i in 0 until width * height) {
            shade(config, i, t, speed, start, clockwise, white, rgb)
            out[o++] = toByte(rgb[0] * level)
            out[o++] = toByte(rgb[1] * level)
            out[o++] = toByte(rgb[2] * level)
        }
    }

    private fun shade(
        config: EffectConfig,
        i: Int,
        t: Double,
        speed: Double,
        start: Float,
        clockwise: Boolean,
        white: Int,
        rgb: FloatArray,
    ) {
        val u = mAround[i]
        when (config.effect) {
            Effect.SOLID -> setRgb(rgb, config.color)

            Effect.GRADIENT -> {
                val k = smoothstep(0f, 1f, mDown[i])
                mix(rgb, config.color, config.color2, k)
            }

            Effect.WHITE -> setRgb(rgb, white)

            Effect.RAINBOW -> hsv(rgb, frac(u + t * speed * 0.08), 1f, 1f)

            Effect.COLOR_CYCLE -> hsv(rgb, frac(t * speed * 0.03), 1f, 1f)

            Effect.BREATHING -> {
                // Кривая «дыхания»: медленный вдох и выдох с паузой внизу, у синусоиды её нет
                val phase = t * speed * 2 * PI / 5
                val breath = ((exp(sin(phase)) - 1 / E) / (E - 1 / E)).toFloat()
                setRgb(rgb, config.color)
                scale(rgb, 0.04f + 0.96f * breath)
            }

            Effect.CANDLE -> {
                val slow = noise(u * 7f, (t * speed * 2.2).toFloat(), 7)
                val fast = noise(u * 13f + 50f, (t * speed * 7.0).toFloat(), 13)
                val n = 0.65f * slow + 0.35f * fast
                rgb[0] = 1f
                rgb[1] = 0.42f + 0.22f * n
                rgb[2] = 0.08f + 0.1f * n
                scale(rgb, 0.45f + 0.55f * n)
            }

            Effect.FIRE -> {
                val down = mDown[i]
                val flame = noise(u * 11f, (t * speed * 2.6).toFloat() - down * 3f, 11)
                val heat = (0.08f + down * 0.85f + (flame - 0.5f) * 0.6f).coerceIn(0f, 1f)
                fire(rgb, heat)
            }

            Effect.AURORA -> {
                val tt = (t * speed).toFloat()
                val band = noise(u * 4f + tt * 0.25f, tt * 0.12f, 4)
                val glow = noise(u * 6f - tt * 0.18f + 20f, tt * 0.09f + 7f, 6)
                val hue = 0.33f + 0.17f * band + if (glow > 0.72f) (glow - 0.72f) * 1.6f else 0f
                val value = smoothstep(0.25f, 0.95f, band * 0.6f + glow * 0.4f)
                hsv(rgb, frac(hue.toDouble()), 0.85f, 0.15f + 0.85f * value)
                // Сияние - явление неба: сверху ярче, снизу едва теплится
                scale(rgb, 0.45f + 0.55f * (1f - mDown[i]))
            }

            Effect.OCEAN -> {
                val tt = (t * speed).toFloat()
                val swell = 0.5f + 0.5f * sin(2 * PI * (u * 3 - tt * 0.07)).toFloat()
                val ripple = noise(u * 9f + tt * 0.3f, tt * 0.4f, 9)
                val w = (0.65f * swell + 0.35f * ripple).coerceIn(0f, 1f)
                rgb[0] = 0.02f + 0.1f * w * w
                rgb[1] = 0.12f + 0.55f * w
                rgb[2] = 0.35f + 0.6f * w
                // Пена на гребнях
                val foam = smoothstep(0.86f, 1f, w)
                rgb[0] += (1f - rgb[0]) * foam * 0.7f
                rgb[1] += (1f - rgb[1]) * foam * 0.7f
                rgb[2] += (1f - rgb[2]) * foam * 0.7f
            }

            Effect.PLASMA -> {
                val tt = t * speed
                val x = mNx[i] * mAspect
                val y = mNy[i]
                val p = sin(x * 2.2 + tt) + sin(y * 3.1 + tt * 1.3) +
                        sin((x + y) * 1.7 + tt * 0.7) + sin(sqrt((x * x + y * y).toDouble()) * 3.5 - tt * 1.1)
                hsv(rgb, frac(p / 8 + tt * 0.02), 0.85f, 1f)
            }

            Effect.COMET -> {
                val head = frac(t * speed * 0.12)
                val behind = frac((head - u).toDouble())
                val tail = 0.28f
                val k = if (behind < tail) (1f - behind / tail).let { it * it } else 0f
                setRgb(rgb, config.color)
                val intensity = 0.03f + 0.97f * k
                // У головы кометы цвет выгорает к белому
                val burn = k * k * k * k * 0.6f
                rgb[0] = (rgb[0] + (1f - rgb[0]) * burn) * intensity
                rgb[1] = (rgb[1] + (1f - rgb[1]) * burn) * intensity
                rgb[2] = (rgb[2] + (1f - rgb[2]) * burn) * intensity
            }

            Effect.LAYOUT_TEST -> {
                setRgb(rgb, SIDE_COLORS[mSide[i].toInt()])
                scale(rgb, 0.35f)
                // Бегущая точка идёт по ленте от первого светодиода в сторону нумерации:
                // где она появляется - там начало, куда бежит - туда направление
                val along = if (clockwise) frac((u - start).toDouble()) else frac((start - u).toDouble())
                val dot = frac(t * speed * 0.15)
                val behind = dot - along
                if (behind in 0f..0.06f) {
                    val k = 1f - behind / 0.06f
                    rgb[0] += (1f - rgb[0]) * k
                    rgb[1] += (1f - rgb[1]) * k
                    rgb[2] += (1f - rgb[2]) * k
                }
            }
        }
    }

    /** Положение угла начала ленты на периметре в тех же долях, что и [mAround]. */
    private fun stripStart(corner: String): Float {
        val perimeter = 4 * mAspect + 4
        return when (corner) {
            "top_right" -> 2 * mAspect / perimeter
            "bottom_right" -> (2 * mAspect + 2) / perimeter
            "bottom_left" -> (4 * mAspect + 2) / perimeter
            else -> 0f
        }
    }

    /**
     * Сглаженный шум по сетке: по первой координате он замкнут с периодом [period], иначе
     * в углу, где периметр начинается заново, на ленте был бы заметный шов.
     */
    private fun noise(x: Float, y: Float, period: Int): Float {
        val xf = floor(x)
        val yf = floor(y)
        val x0 = Math.floorMod(xf.toInt(), period)
        val x1 = (x0 + 1) % period
        val y0 = yf.toInt()
        val sx = smooth(x - xf)
        val sy = smooth(y - yf)
        val top = lerp(hash(x0, y0), hash(x1, y0), sx)
        val bottom = lerp(hash(x0, y0 + 1), hash(x1, y0 + 1), sx)
        return lerp(top, bottom, sy)
    }

    private fun fire(rgb: FloatArray, heat: Float) {
        // Чёрный -> тёмно-красный -> оранжевый -> жёлтый
        when {
            heat < 0.35f -> {
                val k = heat / 0.35f
                rgb[0] = 0.55f * k; rgb[1] = 0f; rgb[2] = 0f
            }

            heat < 0.7f -> {
                val k = (heat - 0.35f) / 0.35f
                rgb[0] = 0.55f + 0.45f * k; rgb[1] = 0.38f * k; rgb[2] = 0f
            }

            else -> {
                val k = (heat - 0.7f) / 0.3f
                rgb[0] = 1f; rgb[1] = 0.38f + 0.5f * k; rgb[2] = 0.25f * k
            }
        }
    }

    companion object {
        private val SIDE_COLORS = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00)

        /**
         * Цвет раскалённого тела по температуре - приближение Таннера Хелланда по данным
         * Митчелла Чарити. Точности в пару процентов для подсветки хватает с запасом.
         */
        fun kelvinToRgb(kelvin: Int): Int {
            val temp = kelvin.coerceIn(1000, 40000) / 100.0
            val r = if (temp <= 66) 255.0 else 329.698727446 * (temp - 60).pow(-0.1332047592)
            val g = if (temp <= 66) {
                99.4708025861 * ln(temp) - 161.1195681661
            } else {
                288.1221695283 * (temp - 60).pow(-0.0755148492)
            }
            val b = when {
                temp >= 66 -> 255.0
                temp <= 19 -> 0.0
                else -> 138.5177312231 * ln(temp - 10) - 305.0447927307
            }
            return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }

        private fun channel(value: Double): Int = value.toInt().coerceIn(0, 255)

        private fun hash(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0x7FFFFFFF) / 2147483647f
        }

        private fun smooth(t: Float) = t * t * (3 - 2 * t)

        private fun smoothstep(from: Float, to: Float, x: Float): Float =
            smooth(((x - from) / (to - from)).coerceIn(0f, 1f))

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun frac(x: Double): Float = (x - floor(x)).toFloat()

        private fun toByte(value: Float): Byte = (value * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

        private fun setRgb(rgb: FloatArray, color: Int) {
            rgb[0] = ((color shr 16) and 0xFF) / 255f
            rgb[1] = ((color shr 8) and 0xFF) / 255f
            rgb[2] = (color and 0xFF) / 255f
        }

        private fun mix(rgb: FloatArray, from: Int, to: Int, k: Float) {
            rgb[0] = lerp(((from shr 16) and 0xFF) / 255f, ((to shr 16) and 0xFF) / 255f, k)
            rgb[1] = lerp(((from shr 8) and 0xFF) / 255f, ((to shr 8) and 0xFF) / 255f, k)
            rgb[2] = lerp((from and 0xFF) / 255f, (to and 0xFF) / 255f, k)
        }

        private fun scale(rgb: FloatArray, k: Float) {
            rgb[0] *= k
            rgb[1] *= k
            rgb[2] *= k
        }

        private fun hsv(rgb: FloatArray, hue: Float, saturation: Float, value: Float) {
            val h = hue * 6f
            val sector = floor(h).toInt() % 6
            val f = h - floor(h)
            val p = value * (1 - saturation)
            val q = value * (1 - saturation * f)
            val r = value * (1 - saturation * (1 - f))
            when (sector) {
                0 -> { rgb[0] = value; rgb[1] = r; rgb[2] = p }
                1 -> { rgb[0] = q; rgb[1] = value; rgb[2] = p }
                2 -> { rgb[0] = p; rgb[1] = value; rgb[2] = r }
                3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = value }
                4 -> { rgb[0] = r; rgb[1] = p; rgb[2] = value }
                else -> { rgb[0] = value; rgb[1] = p; rgb[2] = q }
            }
        }
    }
}
