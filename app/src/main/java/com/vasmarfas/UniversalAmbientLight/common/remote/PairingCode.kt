package com.vasmarfas.UniversalAmbientLight.common.remote

import java.security.SecureRandom

/**
 * Секрет сопряжения в виде, который можно переписать руками с экрана ТВ: 16 символов
 * Crockford Base32, 80 бит. В алфавите нет I, L, O и U — при вводе их путают с 1, 0 и V,
 * поэтому при разборе O читается как 0, а I и L как 1.
 */
object PairingCode {
    const val SECRET_BYTES = 10
    const val LENGTH = SECRET_BYTES * 8 / 5

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return encode(bytes)
    }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    /** Байты секрета из введённого кода; регистр, пробелы и дефисы не важны. */
    fun decode(text: String): ByteArray? {
        val clean = normalize(text) ?: return null
        val out = ByteArray(SECRET_BYTES)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out[index++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
            }
        }
        return out
    }

    /** Код в каноническом виде (16 символов алфавита) или null, если это не код. */
    fun normalize(text: String): String? {
        val clean = StringBuilder()
        for (raw in text.uppercase()) {
            if (raw == '-' || raw.isWhitespace()) continue
            val c = when (raw) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> raw
            }
            if (ALPHABET.indexOf(c) < 0) return null
            clean.append(c)
        }
        return if (clean.length == LENGTH) clean.toString() else null
    }

    /** Группы по четыре символа: так код проще сверить глазами с экраном ТВ. */
    fun format(code: String): String = code.chunked(4).joinToString("-")
}
