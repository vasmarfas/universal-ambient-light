package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WLEDClient(
    private val mContext: Context, // Needed for LedDataExtractor
    private val mHost: String,
    port: Int,
    private val mPriority: Int,
    colorOrder: String?
) : HyperionClient {

    enum class Protocol {
        DDP,
        UDP_RAW // DRGB/DNRGB
    }

    private val mPort: Int
    private val mColorOrder: String = colorOrder?.lowercase() ?: "rgb"
    private val mProtocol = Protocol.DDP // Default to DDP

    @Volatile
    private var mConnected = false
    private var mSocket: DatagramSocket? = null
    private var mAddress: InetAddress? = null

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    // KeepAlive
    private val mKeepAliveExecutor = Executors.newSingleThreadScheduledExecutor()
    private var mLastLeds: Array<ColorRgb>? = null

    init {
        // Use default port based on protocol if not specified
        if (port <= 0 || port == 80) {
            mPort = if (mProtocol == Protocol.DDP) DEFAULT_PORT_DDP else DEFAULT_PORT_DRGB
        } else {
            mPort = port
        }

        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        // Configure smoothing for Ambilight (Low Latency)
        mSmoothing.setSettlingTime(100) // Fast transition (100ms)
        mSmoothing.setOutputDelay(0)    // No buffering delay
        mSmoothing.setUpdateFrequency(40) // 40Hz update rate

        connect()
        startKeepAlive()
    }

    private fun startKeepAlive() {
        mKeepAliveExecutor.scheduleWithFixedDelay({
            if (!mConnected || mLastLeds == null) return@scheduleWithFixedDelay
            // Resend last frame to keep alive
            sendLedData(mLastLeds!!)
        }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    @Throws(IOException::class)
    private fun connect() {
        try {
            mAddress = InetAddress.getByName(mHost)
            mSocket = DatagramSocket()
            mSocket!!.soTimeout = 1000
            mConnected = true
            mSmoothing.start()
            if (logsEnabled) Log.d(TAG, "Connected to WLED at $mHost:$mPort")
        } catch (e: Exception) {
            mConnected = false
            throw IOException("Failed to connect to WLED: " + e.message, e)
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mSocket != null && !mSocket!!.isClosed
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mConnected = false
        mSmoothing.stop()
        mKeepAliveExecutor.shutdownNow()
        if (mSocket != null && !mSocket!!.isClosed) {
            mSocket!!.close()
            mSocket = null
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val blackLeds = Array(ledCount) { ColorRgb(0, 0, 0) }
        mSmoothing.setTargetColors(blackLeds)
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(mPriority)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        val ledCount = LedDataExtractor.getLedCount(mContext)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val leds = Array(ledCount) { ColorRgb(r, g, b) }
        mSmoothing.setTargetColors(leds)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!isConnected()) {
            throw IOException("Not connected to WLED")
        }

        // Extract LED data reusing buffer
        mLedDataBuffer = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        if (mLedDataBuffer!!.isEmpty()) return

        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected()) return
        mLastLeds = leds // Save for keepalive

        try {
            // Log occasionally for debugging
            if (System.currentTimeMillis() % 2000 < 100) {
                if (logsEnabled) Log.d(TAG, "sendLedData: sending ${leds.size} LEDs via ${if (mProtocol == Protocol.DDP) "DDP" else "UDP Raw"} to $mAddress:$mPort")
                // Sample first few LEDs
                if (leds.isNotEmpty()) {
                    val sample = leds.take(5).mapIndexed { idx, led -> 
                        "[$idx: R=${led.red}, G=${led.green}, B=${led.blue}]" 
                    }.joinToString(", ")
                    if (logsEnabled) Log.v(TAG, "Sample LEDs: $sample")
                }
            }
            
            if (mProtocol == Protocol.DDP) {
                val packets = createDdpPackets(leds)
                for (packet in packets) {
                    sendPacket(packet)
                }
            } else {
                // Fallback to UDP Raw
                sendUdpRaw(leds)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data to WLED", e)
        }
    }

    @Throws(IOException::class)
    private fun sendPacket(packet: ByteArray) {
        if (mSocket == null || mAddress == null) return
        val datagramPacket = DatagramPacket(packet, packet.size, mAddress, mPort)
        mSocket!!.send(datagramPacket)
    }

    // DDP Protocol Implementation
    private fun createDdpPackets(leds: Array<ColorRgb>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val channelCount = leds.size * 3
        val packetCount = (channelCount + DDP_CHANNELS_PER_PACKET - 1) / DDP_CHANNELS_PER_PACKET

        var channelOffset = 0

        for (packetIndex in 0 until packetCount) {
            val isLastPacket = packetIndex == packetCount - 1
            val packetDataSize = if (isLastPacket)
                channelCount - channelOffset
            else
                DDP_CHANNELS_PER_PACKET

            val packet = ByteArray(DDP_HEADER_SIZE + packetDataSize)

            // Header
            packet[0] = (0x40 or (if (isLastPacket) 0x01 else 0x00)).toByte() // VER1 | PUSH
            packet[1] = ((packetIndex + 1) and 0x0F).toByte() // sequence number
            packet[2] = (0x80 or (1 shl 3) or 5).toByte() // customerDefined | RGB | Pixel8

            packet[3] = 0x01 // ID: DISPLAY

            // Offset (Big Endian)
            val offset = channelOffset // Offset in BYTES (channels)
            packet[4] = ((offset shr 24) and 0xFF).toByte()
            packet[5] = ((offset shr 16) and 0xFF).toByte()
            packet[6] = ((offset shr 8) and 0xFF).toByte()
            packet[7] = (offset and 0xFF).toByte()

            // Length (Big Endian)
            packet[8] = ((packetDataSize shr 8) and 0xFF).toByte()
            packet[9] = (packetDataSize and 0xFF).toByte()

            // Data
            var dataIdx = DDP_HEADER_SIZE
            val ledsProcessed = channelOffset / 3
            val ledsInThisPacket = packetDataSize / 3

            for (i in 0 until ledsInThisPacket) {
                val led = leds[ledsProcessed + i]
                packet[dataIdx++] = led.red.toByte()
                packet[dataIdx++] = led.green.toByte()
                packet[dataIdx++] = led.blue.toByte()
            }

            packets.add(packet)
            channelOffset += packetDataSize
        }

        return packets
    }

    // Legacy UDP Raw (DRGB/DNRGB)
    @Throws(IOException::class)
    private fun sendUdpRaw(leds: Array<ColorRgb>) {
        val ledCount = leds.size
        var packet: ByteArray

        if (ledCount <= MAX_LEDS_DRGB) {
            packet = createDRGBPacket(leds)
            sendPacket(packet)
        } else {
            // Split
            var startIndex = 0
            var remaining = ledCount
            while (remaining > 0) {
                val ledsInPacket = min(remaining, MAX_LEDS_PER_PACKET_DNRGB)
                packet = createDNRGBPacket(leds, startIndex, ledsInPacket)
                sendPacket(packet)
                startIndex += ledsInPacket
                remaining -= ledsInPacket
            }
        }
    }

    private fun createDRGBPacket(leds: Array<ColorRgb>): ByteArray {
        val packet = ByteArray(2 + leds.size * 3)
        packet[0] = PROTOCOL_DRGB
        packet[1] = WLED_TIMEOUT_SECONDS

        var idx = 2
        for (led in leds) {
            val ordered = convertColorOrder(led, mColorOrder)
            packet[idx++] = ordered[0]
            packet[idx++] = ordered[1]
            packet[idx++] = ordered[2]
        }
        return packet
    }

    private fun createDNRGBPacket(leds: Array<ColorRgb>, startIndex: Int, count: Int): ByteArray {
        val packet = ByteArray(4 + count * 3)
        packet[0] = PROTOCOL_DNRGB
        packet[1] = WLED_TIMEOUT_SECONDS
        packet[2] = ((startIndex shr 8) and 0xFF).toByte()
        packet[3] = (startIndex and 0xFF).toByte()

        var idx = 4
        for (i in 0 until count) {
            val led = leds[startIndex + i]
            val ordered = convertColorOrder(led, mColorOrder)
            packet[idx++] = ordered[0]
            packet[idx++] = ordered[1]
            packet[idx++] = ordered[2]
        }
        return packet
    }

    private fun convertColorOrder(led: ColorRgb, order: String): ByteArray {
        val r = led.red.toByte()
        val g = led.green.toByte()
        val b = led.blue.toByte()

        val result = ByteArray(3)
        when (order) {
            "grb" -> {
                result[0] = g
                result[1] = r
                result[2] = b
            }
            "brg" -> {
                result[0] = b
                result[1] = r
                result[2] = g
            }
            "rbg" -> {
                result[0] = r
                result[1] = b
                result[2] = g
            }
            "gbr" -> {
                result[0] = g
                result[1] = b
                result[2] = r
            }
            "bgr" -> {
                result[0] = b
                result[1] = g
                result[2] = r
            }
            else -> {
                result[0] = r
                result[1] = g
                result[2] = b
            } // rgb
        }
        return result
    }

    companion object {
        private const val TAG = "WLEDClient"
        private val logsEnabled = false
        private const val DEFAULT_PORT_DDP = 4048
        private const val DEFAULT_PORT_DRGB = 19446

        // DDP Constants
        private const val DDP_HEADER_SIZE = 10
        private const val DDP_MAX_LEDS_PER_PACKET = 480
        private const val DDP_CHANNELS_PER_PACKET = DDP_MAX_LEDS_PER_PACKET * 3

        // UDP Raw Constants
        private const val PROTOCOL_DRGB: Byte = 2
        private const val PROTOCOL_DNRGB: Byte = 4
        private const val MAX_LEDS_DRGB = 490
        private const val MAX_LEDS_PER_PACKET_DNRGB = 489
        private const val WLED_TIMEOUT_SECONDS: Byte = 5
    }
}
