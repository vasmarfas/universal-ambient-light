package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Порядок каналов светодиода на проводе. Ленты WS2811 и часть DMX-узлов ждут не RGB, а GRB
 * или BRG; значения те же, что у настройки порядка цветов WLED.
 */
object ColorOrder {

    /** Пишет светодиод в [packet] с [offset] и возвращает позицию после него. */
    fun write(packet: ByteArray, offset: Int, led: ColorRgb, order: String): Int {
        val r = led.red.toByte()
        val g = led.green.toByte()
        val b = led.blue.toByte()
        when (order) {
            "grb" -> {
                packet[offset] = g; packet[offset + 1] = r; packet[offset + 2] = b
            }

            "brg" -> {
                packet[offset] = b; packet[offset + 1] = r; packet[offset + 2] = g
            }

            "rbg" -> {
                packet[offset] = r; packet[offset + 1] = b; packet[offset + 2] = g
            }

            "gbr" -> {
                packet[offset] = g; packet[offset + 1] = b; packet[offset + 2] = r
            }

            "bgr" -> {
                packet[offset] = b; packet[offset + 1] = g; packet[offset + 2] = r
            }

            else -> {
                packet[offset] = r; packet[offset + 1] = g; packet[offset + 2] = b
            }
        }
        return offset + 3
    }
}
