package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import java.io.IOException

import kotlin.math.max

class AdalightClient(
    private val mContext: Context,
    private val mPriority: Int,
    baudRate: Int
) : HyperionClient {

    enum class ProtocolType {
        ADA,    // Standard Adalight
        LBAPA,  // LightBerry APA102
        AWA     // Hyperserial
    }

    private val mBaudRate: Int = if (baudRate > 0) baudRate else 115200
    private val mProtocol = ProtocolType.ADA // Default to ADA

    private var mPort: UsbSerialPort? = null
    @Volatile
    private var mConnected = false

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    init {
        // Initialize smoothing with callback to send data
        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        // Configure smoothing for Ambilight (Low Latency)
        mSmoothing.setSettlingTime(100) // Fast transition (100ms)
        mSmoothing.setOutputDelay(0)    // No buffering delay
        mSmoothing.setUpdateFrequency(40) // 40Hz update rate

        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        val usbManager = mContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB service not available on this device")

        // Find all available USB serial devices
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IOException("No USB serial devices found. Please connect your Adalight device via USB OTG cable")
        }

        // Log all found devices for debugging
        Log.d(TAG, "Found " + availableDrivers.size + " USB serial device(s)")
        for (i in availableDrivers.indices) {
            val dev = availableDrivers[i].device
            Log.d(
                TAG, "Device " + i + ": VID=" + dev.vendorId + " PID=" + dev.productId +
                        " Name=" + dev.deviceName
            )
        }

        // Use the first available device
        val driver = availableDrivers[0]
        val device = driver.device

        // Check if we have permission.
        // На этом этапе диалог уже должен быть показан активити,
        // поэтому из сервиса мы только проверяем флаг.
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB device permission denied. Please allow USB access when prompted, or grant permission manually in Android Settings > Apps > Hyperion Grabber > Permissions")
        }

        // Open the port
        val ports = driver.ports
        if (ports.isEmpty()) {
            throw IOException("No serial ports available on USB device")
        }

        mPort = ports[0]

        // Try to open device connection
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device. Please check USB connection and try again")

        try {
            mPort!!.open(connection)
            mPort!!.setParameters(mBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            mConnected = true
            mSmoothing.start()
            Log.i(
                TAG, "Successfully connected to Adalight device at " + mBaudRate + " baud (VID=" +
                        device.vendorId + " PID=" + device.productId + ")"
            )
        } catch (e: Exception) {
            mConnected = false
            throw IOException(
                "Failed to configure USB serial port: " + e.message +
                        ". Try different baud rate or check device compatibility", e
            )
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mPort != null
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()
        mConnected = false
        if (mPort != null) {
            try {
                mPort!!.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing port", e)
            }
            mPort = null
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Send all black LEDs
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
        // Get LED count from preferences
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
            throw IOException("Not connected to Adalight device")
        }

        // Extract LED data reusing buffer
        mLedDataBuffer = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        if (mLedDataBuffer!!.isEmpty()) return

        // Pass to smoothing
        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    // Callback from ColorSmoothing
    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected()) return

        try {
            val packet = createPacket(mProtocol, leds)
            mPort!!.write(packet, 1000)

            // Log for debugging occasionally
            if (System.currentTimeMillis() % 2000 < 50) {
                Log.v(TAG, "Sent packet: " + leds.size + " LEDs")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data", e)
            mConnected = false
        }
    }

    private fun createPacket(protocol: ProtocolType, leds: Array<ColorRgb>): ByteArray {
        return when (protocol) {
            ProtocolType.ADA -> createAdaPacket(leds)
            ProtocolType.LBAPA -> createLbapaPacket(leds)
            ProtocolType.AWA -> createAwaPacket(leds)
        }
    }

    private fun createAdaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val dataSize = ledCount * 3
        val packet = ByteArray(6 + dataSize)

        // Header
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // RGB data
        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        return packet
    }

    private fun createLbapaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val startFrameSize = 4
        val endFrameSize = max((ledCount + 15) / 16, 4)
        val bytesPerLed = 4
        val dataSize = ledCount * bytesPerLed

        val packet = ByteArray(6 + startFrameSize + dataSize + endFrameSize)

        // Header (same as ADA)
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Start Frame (4 bytes 0x00)
        var offset = 6
        for (i in 0 until startFrameSize) {
            packet[offset++] = 0x00
        }

        // LED data: [0xFF, R, G, B] for each LED
        for (led in leds) {
            packet[offset++] = 0xFF.toByte()
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // End Frame
        for (i in 0 until endFrameSize) {
            packet[offset++] = 0x00
        }

        return packet
    }

    private fun createAwaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val dataSize = ledCount * 3
        // Checksum size = 3 bytes (Fletcher)
        val packet = ByteArray(6 + dataSize + 3)

        packet[0] = 'A'.code.toByte()
        packet[1] = 'w'.code.toByte()
        packet[2] = 'a'.code.toByte() // 'a' = no white calibration

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        var offset = 6
        for (led in leds) {
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // Fletcher Checksum
        var fletcher1 = 0
        var fletcher2 = 0
        var fletcherExt = 0

        for (i in 0 until dataSize) {
            val `val` = packet[6 + i].toInt() and 0xFF
            val position = i + 1 // 1-based index

            fletcherExt = (fletcherExt + (`val` xor position)) % 255
            fletcher1 = (fletcher1 + `val`) % 255
            fletcher2 = (fletcher2 + fletcher1) % 255
        }

        packet[offset++] = fletcher1.toByte()
        packet[offset++] = fletcher2.toByte()
        packet[offset] = fletcherExt.toByte()

        return packet
    }

    companion object {
        private const val TAG = "AdalightClient"
    }
}
