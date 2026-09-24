package com.vasmarfas.UniversalAmbientLight.common.remote

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Кадры с длиной впереди: 4 байта big-endian и тело. */
object FrameIO {
    /** С запасом под отладочную информацию ТВ; всё, что больше, — мусор или атака. */
    const val MAX_FRAME_BYTES = 1024 * 1024

    fun write(output: DataOutputStream, payload: ByteArray) {
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("Bad frame length $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return payload
    }
}

/**
 * Первые два кадра соединения.
 *
 * ТВ отправляет открытым текстом MAGIC, версию протокола, свой nonce и id — по id телефон
 * сразу видит, что по старому адресу теперь другой телевизор. Телефон отвечает своим
 * nonce и первым уже зашифрованным сообщением: если ТВ сумел его расшифровать, у телефона
 * правильный код, и отдельного шага авторизации не требуется.
 */
object Handshake {
    private val MAGIC = byteArrayOf('U'.code.toByte(), 'A'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte())

    class ServerHello(val version: Int, val nonce: ByteArray, val tvId: UUID)

    fun serverHello(nonce: ByteArray, tvId: UUID): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 1 + SessionCipher.NONCE_BYTES + 16)
            .put(MAGIC)
            .put(RemoteProtocol.VERSION.toByte())
            .put(nonce)
            .putLong(tvId.mostSignificantBits)
            .putLong(tvId.leastSignificantBits)
            .array()

    fun parseServerHello(frame: ByteArray): ServerHello {
        if (frame.size != MAGIC.size + 1 + SessionCipher.NONCE_BYTES + 16) {
            throw IOException("Not a remote control server")
        }
        val buffer = ByteBuffer.wrap(frame)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a remote control server")
        val version = buffer.get().toInt() and 0xFF
        val nonce = ByteArray(SessionCipher.NONCE_BYTES)
        buffer.get(nonce)
        return ServerHello(version, nonce, UUID(buffer.long, buffer.long))
    }

    fun clientHello(nonce: ByteArray, sealedFirstMessage: ByteArray): ByteArray = nonce + sealedFirstMessage

    /** Возвращает nonce телефона и зашифрованное первое сообщение. */
    fun splitClientHello(frame: ByteArray): Pair<ByteArray, ByteArray> {
        if (frame.size <= SessionCipher.NONCE_BYTES) throw IOException("Short client hello")
        return frame.copyOfRange(0, SessionCipher.NONCE_BYTES) to
                frame.copyOfRange(SessionCipher.NONCE_BYTES, frame.size)
    }
}
