package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialProberFactory
import java.io.IOException

import kotlin.math.max

class AdalightClient(
    private val mContext: Context,
    private val mPriority: Int,
    baudRate: Int,
    protocol: String = "ada",
    smoothingEnabled: Boolean = true,
    smoothingPreset: String = "balanced",
    settlingTime: Int = 200,
    outputDelayMs: Long = 80L,
    updateFrequency: Int = 25,
) : HyperionClient, StreamingClient {

    enum class ProtocolType {
        ADA,    // Standard Adalight
        LBAPA,  // LightBerry APA102
        AWA     // Hyperserial
    }

    private val mBaudRate: Int = if (baudRate > 0) baudRate else 115200
    private val mProtocol = when (protocol.lowercase()) {
        "lbapa", "1" -> ProtocolType.LBAPA
        "awa", "2" -> ProtocolType.AWA
        else -> ProtocolType.ADA
    }

    private var mPort: UsbSerialPort? = null

    @Volatile
    private var mConnected = false

    @Volatile
    private var mPaused = false

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    // Сохраняем исходно запрошенную частоту, чтобы auto-throttle никогда не повышал её выше пользовательской
    private val mRequestedUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mEffectiveUpdateFrequency: Int = updateFrequency

    @Volatile
    private var mLastAutoThrottlePacketSize: Int = -1

    init {
        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        // Применить настройки сглаживания из preferences
        // Сначала применяем пресет как базовые значения
        mSmoothing.applyPreset(smoothingPreset)
        // Затем переопределяем настройками из preferences только если они отличаются от значений пресета
        // Это позволяет пресету работать, но пользователь может переопределить настройки вручную
        val presetValues = getPresetValues(smoothingPreset)
        if (settlingTime != presetValues.settlingTime) {
            mSmoothing.setSettlingTime(settlingTime)
        }
        if (outputDelayMs != presetValues.outputDelayMs) {
            mSmoothing.setOutputDelay(outputDelayMs)
        }
        if (updateFrequency != presetValues.updateFrequency) {
            mSmoothing.setUpdateFrequency(updateFrequency)
            mEffectiveUpdateFrequency = updateFrequency
        } else {
            // Частота из пресета остаётся в силе
            mEffectiveUpdateFrequency = presetValues.updateFrequency
        }
        // enabled всегда переопределяем, так как это отдельная настройка
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        val usbManager = mContext.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB service not available on this device")

        // Ищем все доступные USB-устройства с последовательным портом
        val availableDrivers = UsbSerialProberFactory.getProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IOException("No USB serial devices found. Please connect your Adalight device via USB OTG cable")
        }

        // Пишем найденные устройства в лог для диагностики
        Log.d(TAG, "Found " + availableDrivers.size + " USB serial device(s)")
        for (i in availableDrivers.indices) {
            val dev = availableDrivers[i].device
            Log.d(
                TAG, "Device " + i + ": VID=" + dev.vendorId + " PID=" + dev.productId +
                        " Name=" + dev.deviceName
            )
        }

        // Берём первое доступное устройство
        val driver = availableDrivers[0]
        val device = driver.device

        // Проверяем разрешение.
        // На этом этапе диалог уже должен быть показан активити,
        // поэтому из сервиса мы только проверяем флаг.
        if (!usbManager.hasPermission(device)) {
            throw IOException("USB device permission denied. Please allow USB access when prompted, or grant permission manually in Android Settings > Apps > Hyperion Grabber > Permissions")
        }

        // Открываем порт
        val ports = driver.ports
        if (ports.isEmpty()) {
            throw IOException("No serial ports available on USB device")
        }

        val port = ports[0]
        mPort = port

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device. Please check USB connection and try again")

        try {
            port.open(connection)
            port.setParameters(mBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Поднимаем DTR/RTS. Часть мостов (CP210x, ряд клонов CH340) молчит, пока линии не
            // подняты; на платах с авто-reset это ещё и перезагружает MCU, чтобы прошивка заново
            // отправила свой стартовый маркер "Ada" для хендшейка ниже. Best-effort: драйверы,
            // которые не поддерживают линию, кидают исключение — его игнорируем.
            try {
                port.setDTR(true)
            } catch (_: Exception) {
            }
            try {
                port.setRTS(true)
            } catch (_: Exception) {
            }

            // Хендшейк по «магическому слову» Adalight: стандартная прошивка при старте печатает
            // "Ada\n" (см. adalight-sketch.ino и рекомендованный сторонним приложением скетч).
            // Ждём его, чтобы не начать слать кадры в устройство, которое ещё перезагружается —
            // именно из-за этого у нас были рассинхрон и мерцание на старте в отличие от аналога.
            // Если устройство не прислало "Ada" за таймаут — всё равно продолжаем (некоторые
            // прошивки маркер не шлют), поэтому совместимость не ухудшается.
            waitForAdaHandshake()

            mConnected = true
            mSmoothing.start()
            Log.i(
                TAG, "Successfully connected to Adalight device at " + mBaudRate + " baud (VID=" +
                        device.vendorId + " PID=" + device.productId + ")"
            )
        } catch (e: Exception) {
            mConnected = false
            // Освобождаем USB-соединение до выхода с ошибкой: иначе устройство остаётся
            // «занятым», и повторные попытки подключения не пройдут до перезапуска приложения.
            try {
                mPort?.close()
            } catch (_: Exception) {
            }
            try {
                connection.close()
            } catch (_: Exception) {
                // Освобождаем порт после неудачного открытия: часть ресурсов могла и не
                // создаться, но оставить занятым нельзя (см. комментарий выше).
            }
            mPort = null
            throw IOException(
                "Failed to configure USB serial port: " + e.message +
                        ". Try different baud rate or check device compatibility", e
            )
        }
    }

    /**
     * Ждём стартовый маркер "Ada", который стандартная прошивка Adalight печатает при загрузке.
     * Возвращаемся сразу, как только маркер получен, либо по истечении таймаута (best-effort —
     * не блокируем устройства, которые маркер не шлют).
     */
    private fun waitForAdaHandshake() {
        val port = mPort ?: return
        val buf = ByteArray(64)
        val received = StringBuilder()
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = port.read(buf, HANDSHAKE_READ_TIMEOUT_MS)
                if (n > 0) {
                    received.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (received.contains(ADA_MAGIC_WORD)) {
                        Log.i(TAG, "Adalight handshake OK: device announced 'Ada'")
                        return
                    }
                    // Ограничиваем буфер: маркер короткий, хвоста достаточно для склейки на границе.
                    if (received.length > 128) received.delete(0, received.length - 8)
                }
            }
            Log.i(TAG, "No 'Ada' handshake within ${HANDSHAKE_TIMEOUT_MS}ms — proceeding anyway")
        } catch (e: Exception) {
            Log.w(TAG, "Handshake read failed (${e.message}) — proceeding anyway")
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mPort != null
    }

    override fun pauseSending() {
        mPaused = true
        mSmoothing.stop()
    }

    override fun resumeSending() {
        mPaused = false
    }

    override val delaysOutput = true

    override fun setOutputDelay(ms: Long) {
        mSmoothing.setOutputDelay(ms)
    }

    override fun setSmoothingEnabled(enabled: Boolean) {
        mSmoothing.setEnabled(enabled)
    }

    @Throws(IOException::class)
    override fun disconnect() {
        // stop() обрывает недоигранный фейд в чёрный (переход плюс задержка вывода могут
        // не уложиться в паузу перед disconnect), и лента осталась бы полугорящей —
        // финальный чёрный кадр уходит в порт напрямую, мимо сглаживания. Мы на фоновом
        // потоке гашения, блокирующая запись здесь допустима.
        mSmoothing.stop()
        if (mConnected) {
            val ledCount = LedDataExtractor.getLedCount(mContext)
            sendLedData(Array(ledCount) { ColorRgb(0, 0, 0) })
        }
        mConnected = false
        try {
            mPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing port", e)
        }
        mPort = null
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        // Отправляем все светодиоды чёрными
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
        // Число светодиодов берём из настроек
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
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!isConnected()) {
            throw IOException("Not connected to Adalight device")
        }

        val leds = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        mLedDataBuffer = leds
        if (leds.isEmpty()) return

        // Отдаём в сглаживание
        mSmoothing.setTargetColors(mLedDataBuffer)
    }

    // Обратный вызов от ColorSmoothing
    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected() || mPaused) return

        val port = mPort
        if (port == null) {
            Log.w(TAG, "Port is null, connection lost")
            mConnected = false
            return
        }

        try {
            val packet = createPacket(mProtocol, leds)
            maybeAutoThrottle(packet.size, leds.size)
            port.write(packet, 1000)

            // Изредка пишем в лог для диагностики
            if (System.currentTimeMillis() % 2000 < 50) {
                Log.v(TAG, "Sent packet: " + leds.size + " LEDs")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data", e)
            mConnected = false
        } catch (e: NullPointerException) {
            Log.e(TAG, "USB connection lost (NullPointerException)", e)
            mConnected = false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending data", e)
            mConnected = false
        }
    }

    /**
     * Auto-throttle частоту сглаживания под выбранный baudrate и фактический размер пакета.
     * Это резко повышает стабильность при большом количестве LED (иначе данные начинают идти "без пауз",
     * Arduino теряет байты, и протокол рассинхронизируется).
     */
    private fun maybeAutoThrottle(packetSizeBytes: Int, ledCount: Int) {
        if (packetSizeBytes <= 0) return
        // Пересчитываем только если меняется размер (например, при смене протокола или количества LED)
        if (packetSizeBytes == mLastAutoThrottlePacketSize) return
        mLastAutoThrottlePacketSize = packetSizeBytes

        // Приблизительно: 1 байт = 10 бит (8N1)
        val wireMs = packetSizeBytes * 10_000.0 / mBaudRate
        // Пока прошивка переливает кадр в ленту, приём стоит: WS2812 требует держать
        // прерывания выключенными ~30 мкс на светодиод, и байты следующего пакета,
        // пришедшие в это окно, теряются. Резервируем окно явно, с запасом на цикл прошивки.
        val showMs = ledCount * 0.03 + 2.0
        val safeHz = (1000.0 / (wireMs + showMs)).toInt().coerceIn(1, 60)

        val desiredHz = minOf(mRequestedUpdateFrequency, safeHz)
        if (desiredHz != mEffectiveUpdateFrequency) {
            mEffectiveUpdateFrequency = desiredHz
            mSmoothing.setUpdateFrequency(desiredHz)
            Log.i(
                TAG,
                "Auto-throttle smoothing: ${desiredHz}Hz (baud=$mBaudRate, packet=$packetSizeBytes bytes)"
            )
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

        // Заголовок
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        val ledCountMinusOne = ledCount - 1
        packet[3] = ((ledCountMinusOne shr 8) and 0xFF).toByte()
        packet[4] = (ledCountMinusOne and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Данные RGB
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

        // Заголовок как у ADA, но с ledCount, а не ledCount-1
        packet[0] = 'A'.code.toByte()
        packet[1] = 'd'.code.toByte()
        packet[2] = 'a'.code.toByte()

        // LBAPA использует ledCount напрямую, а НЕ ledCount-1, как обычный Adalight
        packet[3] = ((ledCount shr 8) and 0xFF).toByte()
        packet[4] = (ledCount and 0xFF).toByte()
        packet[5] = (packet[3].toInt() xor packet[4].toInt() xor 0x55).toByte()

        // Стартовый кадр (4 байта 0x00)
        var offset = 6
        for (i in 0 until startFrameSize) {
            packet[offset++] = 0x00
        }

        // Данные светодиодов: [0xFF, R, G, B] на каждый
        for (led in leds) {
            packet[offset++] = 0xFF.toByte()
            packet[offset++] = led.red.toByte()
            packet[offset++] = led.green.toByte()
            packet[offset++] = led.blue.toByte()
        }

        // Завершающий кадр
        for (i in 0 until endFrameSize) {
            packet[offset++] = 0x00
        }

        return packet
    }

    private fun createAwaPacket(leds: Array<ColorRgb>): ByteArray {
        val ledCount = leds.size
        val dataSize = ledCount * 3
        // Размер контрольной суммы — 3 байта (Флетчер)
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

        // Контрольная сумма Флетчера
        var fletcher1 = 0
        var fletcher2 = 0
        var fletcherExt = 0
        // В реализации Hyperion позиция считается с нуля
        var position = 0

        for (i in 0 until dataSize) {
            val `val` = packet[6 + i].toInt() and 0xFF

            fletcherExt = (fletcherExt + (`val` xor position)) % 255
            fletcher1 = (fletcher1 + `val`) % 255
            fletcher2 = (fletcher2 + fletcher1) % 255

            position = (position + 1) % 256
        }

        packet[offset++] = fletcher1.toByte()
        packet[offset++] = fletcher2.toByte()

        // Особый случай 0x41 ('A') обрабатываем отдельно, чтобы не спутать с заголовком
        packet[offset] = if (fletcherExt == 0x41) 0xaa.toByte() else fletcherExt.toByte()

        return packet
    }

    private data class PresetValues(
        val settlingTime: Int,
        val outputDelayMs: Long,
        val updateFrequency: Int,
    )

    private fun getPresetValues(preset: String): PresetValues {
        return when (preset.lowercase()) {
            "off" -> PresetValues(50, 0L, 60)
            "responsive" -> PresetValues(50, 0L, 60)
            "balanced" -> PresetValues(200, 80L, 25)
            "smooth" -> PresetValues(500, 200L, 20)
            else -> PresetValues(200, 80L, 25) // balanced по умолчанию
        }
    }

    companion object {
        private const val TAG = "AdalightClient"

        // Стартовый маркер Adalight, который прошивка печатает при загрузке ("Ada\n").
        private const val ADA_MAGIC_WORD = "Ada"

        // Бюджет ожидания хендшейка. Покрывает окно перезагрузки MCU (~1.5–2 c) после подъёма DTR.
        private const val HANDSHAKE_TIMEOUT_MS = 2500L
        private const val HANDSHAKE_READ_TIMEOUT_MS = 250
    }
}
