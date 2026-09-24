package com.vasmarfas.UniversalAmbientLight.common.remote

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/** По адресу ответил другой телевизор, а не тот, с которым сопряжён телефон. */
class WrongTvException(val actualId: UUID) : IOException("Another TV answered: $actualId")

/**
 * ТВ ответил на приветствие и молча закрыл соединение: он не смог расшифровать первое
 * сообщение, то есть код у телефона не тот — на ТВ его сбросили или ввели с ошибкой.
 */
class PairingRejectedException : IOException("Pairing code rejected")

/** ТВ и телефон говорят на разных версиях протокола — одно из приложений устарело. */
class ProtocolMismatchException(val remoteVersion: Int) :
    IOException("Protocol version $remoteVersion, expected ${RemoteProtocol.VERSION}")

/**
 * Зашифрованный канал поверх сокета: JSON-сообщения в кадрах [FrameIO], шифрование
 * [SessionCipher]. Читает один поток, писать можно из любого.
 */
class RemoteChannel private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val cipher: SessionCipher,
) : Closeable {

    private val mWriteLock = Any()

    fun send(message: JSONObject) {
        val plain = message.toString().toByteArray(Charsets.UTF_8)
        // Номер кадра в IV и порядок кадров на проводе обязаны совпадать — шифрование и
        // запись идут под одним замком
        synchronized(mWriteLock) {
            FrameIO.write(output, cipher.seal(plain))
        }
    }

    fun receive(): JSONObject =
        JSONObject(String(cipher.open(FrameIO.read(input)), Charsets.UTF_8))

    fun setReadTimeout(timeoutMs: Int) {
        socket.soTimeout = timeoutMs
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // Сокет уже закрыт другой стороной — закрывать нечего.
        }
    }

    companion object {
        private val sRandom = SecureRandom()

        /**
         * Сторона ТВ. Возвращает канал и первое сообщение телефона; бросает исключение,
         * если телефон не знает секрета (сообщение не расшифровывается).
         */
        fun accept(socket: Socket, secret: ByteArray, tvId: UUID): Pair<RemoteChannel, JSONObject> {
            socket.soTimeout = RemoteProtocol.HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val serverNonce = ByteArray(SessionCipher.NONCE_BYTES).also { sRandom.nextBytes(it) }
            FrameIO.write(output, Handshake.serverHello(serverNonce, tvId))

            val (clientNonce, sealed) = Handshake.splitClientHello(FrameIO.read(input))
            val cipher = SessionCipher.forServer(secret, serverNonce, clientNonce)
            val first = JSONObject(String(cipher.open(sealed), Charsets.UTF_8))
            return RemoteChannel(socket, input, output, cipher) to first
        }

        /** Сторона телефона: рукопожатие и первое сообщение [hello] уже внутри шифра. */
        fun connect(
            socket: Socket,
            secret: ByteArray,
            expectedTvId: UUID?,
            hello: JSONObject,
        ): RemoteChannel {
            socket.soTimeout = RemoteProtocol.HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            val serverHello = Handshake.parseServerHello(FrameIO.read(input))
            if (serverHello.version != RemoteProtocol.VERSION) {
                throw ProtocolMismatchException(serverHello.version)
            }
            if (expectedTvId != null && serverHello.tvId != expectedTvId) {
                throw WrongTvException(serverHello.tvId)
            }

            val clientNonce = ByteArray(SessionCipher.NONCE_BYTES).also { sRandom.nextBytes(it) }
            val cipher = SessionCipher.forClient(secret, serverHello.nonce, clientNonce)
            val sealed = cipher.seal(hello.toString().toByteArray(Charsets.UTF_8))
            FrameIO.write(output, Handshake.clientHello(clientNonce, sealed))
            return RemoteChannel(socket, input, output, cipher)
        }
    }
}
