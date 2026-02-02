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
    private val mPriority: Int
) : HyperionClient {
    private val TIMEOUT = 1000
    private var mSocket: Socket? = null

    init {
        mSocket = Socket()
        mSocket!!.tcpNoDelay = true // Disable Nagle's algorithm for low latency
        mSocket!!.sendBufferSize = 8192 // Smaller buffer for faster sends
        mSocket!!.receiveBufferSize = 4096
        mSocket!!.connect(InetSocketAddress(address, port), TIMEOUT)
        mSocket!!.soTimeout = 10 // Very short timeout for non-blocking behavior
        register()
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
        return mSocket != null && mSocket!!.isConnected
    }

    @Throws(IOException::class)
    override fun disconnect() {
        if (isConnected()) {
            mSocket!!.close()
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Use separate FlatBufferBuilder for each message to avoid
        // FlatBuffers "object serialization must not be nested" errors on concurrent calls
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
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        val builder = newBuilder()
        val dataOffset = RawImage.createDataVector(builder, data)
        val rawImageOffset = RawImage.createRawImage(builder, dataOffset, width, height)
        val imageOffset = Image.createImage(builder, ImageType.RawImage, rawImageOffset, duration_ms)
        val requestOffset = Request.createRequest(builder, Command.Image, imageOffset)
        Request.finishRequestBuffer(builder, requestOffset)
        sendRequest(builder.dataBuffer())
    }

    @Throws(IOException::class)
    private fun sendRequest(bb: ByteBuffer) {
        if (isConnected()) {
            val size = bb.remaining()
            val header = ByteArray(4)
            header[0] = ((size shr 24) and 0xFF).toByte()
            header[1] = ((size shr 16) and 0xFF).toByte()
            header[2] = ((size shr 8) and 0xFF).toByte()
            header[3] = (size and 0xFF).toByte()

            val output = mSocket!!.getOutputStream()
            output.write(header)

            val data = ByteArray(bb.remaining())
            bb.get(data)
            output.write(data)
            output.flush()

            // Don't wait for reply - fire and forget for minimal latency
            // Replies will be handled asynchronously if needed
        }
    }

    fun cleanReplies() {
        receiveReply()
    }

    private fun receiveReply() {
        // Non-blocking reply consumption to keep socket clean
        // This is called separately and doesn't block frame sending
        try {
            while (mSocket!!.getInputStream().available() >= 4) {
                val header = ByteArray(4)
                val read = mSocket!!.getInputStream().read(header, 0, 4)
                if (read == 4) {
                    val size = ((header[0].toInt() and 0xFF) shl 24) or
                            ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)
                    if (size > 0 && mSocket!!.getInputStream().available() >= size) {
                        val data = ByteArray(size)
                        mSocket!!.getInputStream().read(data, 0, size)
                    } else {
                        break // Not enough data yet, will consume later
                    }
                } else {
                    break
                }
            }
        } catch (e: IOException) {
            // Ignore - non-blocking read
        }
    }
}
