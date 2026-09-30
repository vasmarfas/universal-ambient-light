package com.vasmarfas.UniversalAmbientLight.common.network

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * MQTT 3.1.1 ровно в том объёме, что нужен лампам: подключение, публикация и подписка с
 * QoS 0, пинг. Одно соединение пишет только его владелец, поэтому синхронизация лишь на
 * записи: пинг уходит из другого потока.
 */
class MqttLink(
    private val mHost: String,
    port: Int,
    private val mClientId: String,
    private val mUsername: String,
    private val mPassword: String,
) {
    private val mPort = if (port > 0) port else DEFAULT_PORT
    private var mSocket: Socket? = null
    private var mInput: DataInputStream? = null
    private var mOutput: OutputStream? = null
    private var mNextPacketId = 1

    @Throws(IOException::class)
    fun connect() {
        val socket = Socket()
        socket.connect(InetSocketAddress(mHost.trim(), mPort), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = CONNECT_TIMEOUT_MS
        mSocket = socket
        mInput = DataInputStream(socket.getInputStream())
        mOutput = socket.getOutputStream()
        write(connectPacket(mClientId, mUsername, mPassword, KEEP_ALIVE_S))
        val (type, body) = readPacket() ?: throw IOException("MQTT broker closed the connection")
        if (type != TYPE_CONNACK || body.size < 2) throw IOException("Unexpected MQTT answer $type")
        val code = body[1].toInt() and 0xFF
        if (code != 0) throw MqttRefusedException(code)
    }

    @Throws(IOException::class)
    fun publish(topic: String, payload: String) {
        write(publishPacket(topic, payload.toByteArray(Charsets.UTF_8)))
    }

    @Throws(IOException::class)
    fun subscribe(topic: String) {
        val id = mNextPacketId
        mNextPacketId = mNextPacketId % 0xFFFF + 1
        write(subscribePacket(id, topic))
    }

    /** Следующее входящее сообщение подписки или null, если за [timeoutMs] ничего не пришло. */
    @Throws(IOException::class)
    fun receive(timeoutMs: Int): Pair<String, ByteArray>? {
        val socket = mSocket ?: throw IOException("MQTT is not connected")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            socket.soTimeout = left.toInt()
            val (type, body) = try {
                readPacket() ?: throw IOException("MQTT broker closed the connection")
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (type == TYPE_PUBLISH) return parsePublish(body)
        }
    }

    @Throws(IOException::class)
    fun ping() {
        write(byteArrayOf(0xC0.toByte(), 0))
    }

    fun close() {
        try {
            write(byteArrayOf(0xE0.toByte(), 0))
        } catch (_: IOException) {
            // Брокер мог закрыть соединение первым, прощаться уже не с кем.
        }
        try {
            mSocket?.close()
        } catch (_: IOException) {
            // Сокет уже закрыт.
        }
        mSocket = null
        mInput = null
        mOutput = null
    }

    @Synchronized
    @Throws(IOException::class)
    private fun write(packet: ByteArray) {
        val output = mOutput ?: throw IOException("MQTT is not connected")
        output.write(packet)
        output.flush()
    }

    @Throws(IOException::class)
    private fun readPacket(): Pair<Int, ByteArray>? {
        val input = mInput ?: throw IOException("MQTT is not connected")
        val first = input.read()
        if (first < 0) return null
        val length = readRemainingLength(input)
        val body = ByteArray(length)
        input.readFully(body)
        return (first shr 4) to body
    }

    /** Брокер отказал в подключении: неверный логин или пароль, чужой id и так далее. */
    class MqttRefusedException(val code: Int) : IOException("MQTT broker refused the connection, code $code")

    companion object {
        const val DEFAULT_PORT = 1883
        const val KEEP_ALIVE_S = 60
        private const val CONNECT_TIMEOUT_MS = 5000

        private const val TYPE_CONNACK = 2
        private const val TYPE_PUBLISH = 3

        internal fun connectPacket(clientId: String, username: String, password: String, keepAliveS: Int): ByteArray {
            val body = ByteArrayOutputStream()
            writeString(body, "MQTT".toByteArray(Charsets.UTF_8))
            body.write(4)
            var flags = 0x02
            if (username.isNotEmpty()) flags = flags or 0x80
            if (username.isNotEmpty() && password.isNotEmpty()) flags = flags or 0x40
            body.write(flags)
            body.write(keepAliveS shr 8)
            body.write(keepAliveS and 0xFF)
            writeString(body, clientId.toByteArray(Charsets.UTF_8))
            if (username.isNotEmpty()) writeString(body, username.toByteArray(Charsets.UTF_8))
            if (username.isNotEmpty() && password.isNotEmpty()) {
                writeString(body, password.toByteArray(Charsets.UTF_8))
            }
            return packet(0x10, body.toByteArray())
        }

        internal fun publishPacket(topic: String, payload: ByteArray): ByteArray {
            val body = ByteArrayOutputStream()
            writeString(body, topic.toByteArray(Charsets.UTF_8))
            body.write(payload)
            return packet(0x30, body.toByteArray())
        }

        internal fun subscribePacket(packetId: Int, topic: String): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(packetId shr 8)
            body.write(packetId and 0xFF)
            writeString(body, topic.toByteArray(Charsets.UTF_8))
            body.write(0)
            return packet(0x82, body.toByteArray())
        }

        /** Тема и тело входящего PUBLISH. Подписка идёт с QoS 0, номера пакета после темы нет. */
        internal fun parsePublish(body: ByteArray): Pair<String, ByteArray>? {
            if (body.size < 2) return null
            val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            if (body.size < 2 + topicLength) return null
            val topic = String(body, 2, topicLength, Charsets.UTF_8)
            return topic to body.copyOfRange(2 + topicLength, body.size)
        }

        /** Длина остатка пакета: по 7 бит в байте, старший бит - «дальше ещё байт». */
        internal fun encodeRemainingLength(length: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var value = length
            do {
                var digit = value % 128
                value /= 128
                if (value > 0) digit = digit or 0x80
                out.write(digit)
            } while (value > 0)
            return out.toByteArray()
        }

        @Throws(IOException::class)
        internal fun readRemainingLength(input: InputStream): Int {
            var multiplier = 1
            var value = 0
            repeat(4) {
                val digit = input.read()
                if (digit < 0) throw IOException("MQTT stream ended inside a packet header")
                value += (digit and 0x7F) * multiplier
                if (digit and 0x80 == 0) return value
                multiplier *= 128
            }
            throw IOException("Malformed MQTT packet length")
        }

        private fun packet(header: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(header)
            out.write(encodeRemainingLength(body.size))
            out.write(body)
            return out.toByteArray()
        }

        private fun writeString(out: ByteArrayOutputStream, bytes: ByteArray) {
            out.write(bytes.size shr 8)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }
    }
}
