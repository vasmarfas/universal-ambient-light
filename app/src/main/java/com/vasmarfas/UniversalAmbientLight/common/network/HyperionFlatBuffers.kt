package com.vasmarfas.UniversalAmbientLight.common.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.Clear
import hyperionnet.Color
import hyperionnet.Command
import hyperionnet.Image
import hyperionnet.ImageType
import hyperionnet.RawImage
import hyperionnet.Register
import hyperionnet.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class HyperionFlatBuffers(
    address: String,
    port: Int,
    private val mPriority: Int,
) : HyperionClient {
    private val TIMEOUT = 1000
    private var mSocket: Socket? = null

    private val mImageBuilder = FlatBufferBuilder(1024)

    init {
        // Порт должен попадать в диапазон 1-65535
        if (port < 1 || port > 65535) {
            throw IllegalArgumentException("Port out of range: $port (must be between 1 and 65535)")
        }

        val socket = Socket()
        mSocket = socket
        try {
            socket.tcpNoDelay = true // Disable Nagle's algorithm for low latency
            socket.sendBufferSize = 8192 // Smaller buffer for faster sends
            socket.receiveBufferSize = 4096
            socket.connect(InetSocketAddress(address, port), TIMEOUT)
            socket.soTimeout = 10 // Very short timeout for non-blocking behavior
            register()
        } catch (e: Exception) {
            // Исключение из конструктора оставляет объект недостижимым — сокет, уже
            // успевший подключиться, без close() тёк бы на каждой попытке реконнекта
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    private fun newBuilder(): FlatBufferBuilder = FlatBufferBuilder(1024)

    @Throws(IOException::class)
    private fun register() {
        val builder = newBuilder()
        val originOffset = builder.createString("HyperionAndroidGrabber")
        val registerOffset = Register.createRegister(builder, originOffset, mPriority)
        val requestOffset = Request.createRequest(builder, Command.Register, registerOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        sendRequest(builder.dataBuffer())
    }

    override fun isConnected(): Boolean {
        // Socket.isConnected после close() остаётся true — без проверки isClosed клиент
        // выглядел бы подключённым и после disconnect()
        val socket = mSocket ?: return false
        return socket.isConnected && !socket.isClosed
    }

    @Throws(IOException::class)
    override fun disconnect() {
        // Закрываем безусловно: сокет мог наполовину умереть, а недозакрытый течёт
        val socket = mSocket ?: return
        if (!socket.isClosed) {
            socket.close()
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Для каждого сообщения свой FlatBufferBuilder: иначе при параллельных вызовах
        // FlatBuffers ругается «object serialization must not be nested»
        val builder = newBuilder()
        val clearOffset = Clear.createClear(builder, priority)
        val requestOffset = Request.createRequest(builder, Command.Clear, clearOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        sendRequest(builder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(-1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        val builder = newBuilder()
        val colorOffset = Color.createColor(builder, color, duration_ms)
        val requestOffset = Request.createRequest(builder, Command.Color, colorOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        sendRequest(builder.dataBuffer())
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        synchronized(mImageBuilder) {
            val builder = mImageBuilder
            builder.clear()
            val dataOffset = RawImage.createDataVector(builder, data)
            val rawImageOffset = RawImage.createRawImage(builder, dataOffset, width, height)
            val imageOffset =
                Image.createImage(builder, ImageType.RawImage, rawImageOffset, duration_ms)
            val requestOffset = Request.createRequest(builder, Command.Image, imageOffset)
            Request.finishRequestBuffer(builder, requestOffset)
            sendRequest(builder.dataBuffer())
        }
    }

    @Throws(IOException::class)
    private fun sendRequest(bb: ByteBuffer) {
        val socket = mSocket
        if (socket != null && socket.isConnected) {
            val size = bb.remaining()
            val header = ByteArray(4)
            header[0] = ((size shr 24) and 0xFF).toByte()
            header[1] = ((size shr 16) and 0xFF).toByte()
            header[2] = ((size shr 8) and 0xFF).toByte()
            header[3] = (size and 0xFF).toByte()

            val output = socket.getOutputStream()
            output.write(header)
            output.write(bb.array(), bb.arrayOffset() + bb.position(), size)
            output.flush()

            // Ответа не ждём — так задержка минимальна; при необходимости ответы
            // разбираются асинхронно.
        }
    }

    fun cleanReplies() {
        receiveReply()
    }

    private fun receiveReply() {
        // Неблокирующее вычитывание ответов, чтобы сокет не забивался. Вызывается отдельно
        // и отправку кадров не задерживает.
        try {
            // getInputStream на закрытом сокете бросает — это такой же штатный конец, как
            // и ошибка чтения ниже
            val input = mSocket?.getInputStream() ?: return
            while (input.available() >= 4) {
                val header = ByteArray(4)
                val read = input.read(header, 0, 4)
                if (read == 4) {
                    val size = ((header[0].toInt() and 0xFF) shl 24) or
                            ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)
                    if (size > 0 && input.available() >= size) {
                        val data = ByteArray(size)
                        input.read(data, 0, size)
                    } else {
                        break // Not enough data yet, will consume later
                    }
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            // Чтение неблокирующее, ошибку игнорируем
        }
    }
}
