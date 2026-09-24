package com.vasmarfas.UniversalAmbientLight.common.remote

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование канала после рукопожатия: AES-256-GCM, ключи сеанса выводятся из секрета
 * сопряжения и двух случайных nonce — ТВ и телефона. У направлений разные ключи, а IV —
 * это номер кадра: повтор, перестановка или подмена кадра не проходят проверку тега, и
 * соединение рвётся. Nonce обеих сторон делают ключи уникальными для каждого соединения,
 * поэтому записанный трафик нельзя проиграть заново.
 *
 * Счётчики не потокобезопасны сами по себе: запись кадра обязана идти под тем же замком,
 * что и [seal], иначе номер на проводе разойдётся с номером в IV.
 */
class SessionCipher private constructor(
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
) {
    private var mSendCounter = 0L
    private var mReceiveCounter = 0L

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, iv(mSendCounter++)))
        return cipher.doFinal(plain)
    }

    /** Бросает [javax.crypto.AEADBadTagException], если кадр чужой, подменён или не по порядку. */
    fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(TAG_BITS, iv(mReceiveCounter)))
        val plain = cipher.doFinal(sealed)
        mReceiveCounter++
        return plain
    }

    companion object {
        const val NONCE_BYTES = 16

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val LABEL = "uamblight-remote-v1".toByteArray(Charsets.US_ASCII)
        private val CLIENT_TO_SERVER = "c2s".toByteArray(Charsets.US_ASCII)
        private val SERVER_TO_CLIENT = "s2c".toByteArray(Charsets.US_ASCII)

        fun forServer(secret: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): SessionCipher {
            val master = master(secret, serverNonce, clientNonce)
            return SessionCipher(key(master, SERVER_TO_CLIENT), key(master, CLIENT_TO_SERVER))
        }

        fun forClient(secret: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): SessionCipher {
            val master = master(secret, serverNonce, clientNonce)
            return SessionCipher(key(master, CLIENT_TO_SERVER), key(master, SERVER_TO_CLIENT))
        }

        private fun master(secret: ByteArray, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray {
            require(serverNonce.size == NONCE_BYTES && clientNonce.size == NONCE_BYTES)
            return hmac(secret, LABEL + serverNonce + clientNonce)
        }

        private fun key(master: ByteArray, direction: ByteArray) =
            SecretKeySpec(hmac(master, direction), "AES")

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun iv(counter: Long): ByteArray =
            ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
    }
}
