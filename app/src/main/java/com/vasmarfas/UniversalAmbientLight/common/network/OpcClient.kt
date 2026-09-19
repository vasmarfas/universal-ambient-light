package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Open Pixel Control по TCP: FadeCandy, сервер fcserver и совместимые прошивки. Каждый кадр -
 * заголовок из четырёх байт (канал, команда 0, длина) и RGB подряд.
 */
class OpcClient(
    context: Context,
    private val mHost: String,
    port: Int,
    private val mChannel: Int,
    private val mColorOrder: String,
    smoothing: SmoothingSettings,
) : LedStreamClient(context, smoothing) {

    private val mPort = if (port > 0) port else DEFAULT_PORT
    private var mSocket: Socket? = null
    private var mOutput: OutputStream? = null
    private var mBuffer = ByteArray(0)

    override val reconnectOnError = true

    init {
        start()
    }

    @Throws(IOException::class)
    override fun open() {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(mHost, mPort), CONNECT_TIMEOUT_MS)
        mSocket = socket
        mOutput = socket.getOutputStream()
    }

    @Throws(IOException::class)
    override fun write(leds: Array<ColorRgb>) {
        val output = mOutput ?: throw IOException("Not connected")
        if (mBuffer.size != HEADER_SIZE + leds.size * 3) mBuffer = ByteArray(HEADER_SIZE + leds.size * 3)
        fill(mBuffer, mChannel, leds, mColorOrder)
        output.write(mBuffer)
        output.flush()
    }

    override fun close() {
        try {
            mSocket?.close()
        } catch (_: IOException) {
            // Сокет уже закрыла другая сторона.
        }
        mSocket = null
        mOutput = null
    }

    companion object {
        const val DEFAULT_PORT = 7890
        private const val HEADER_SIZE = 4
        private const val CONNECT_TIMEOUT_MS = 3000

        /** Команда «задать пиксели» в [packet] длиной ровно 4 + 3 * leds.size. */
        internal fun fill(packet: ByteArray, channel: Int, leds: Array<ColorRgb>, colorOrder: String) {
            val length = leds.size * 3
            packet[0] = channel.toByte()
            packet[1] = 0
            packet[2] = (length shr 8).toByte()
            packet[3] = length.toByte()
            var offset = HEADER_SIZE
            for (led in leds) offset = ColorOrder.write(packet, offset, led, colorOrder)
        }
    }
}
