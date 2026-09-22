package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.LedDataExtractor
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class WLEDClient(
    private val mContext: Context, // Needed for LedDataExtractor
    private val mHost: String,
    port: Int,
    private val mPriority: Int,
    colorOrder: String?,
    wledProtocol: String = "ddp",
    smoothingEnabled: Boolean = true,
    smoothingPreset: String = "balanced",
    settlingTime: Int = 200,
    outputDelayMs: Long = 80L,
    updateFrequency: Int = 25,
    rgbw: Boolean = false,
    brightness: Int = 255,
) : HyperionClient, StreamingClient {

    enum class Protocol {
        DDP,
        UDP_RAW // DRGB/DNRGB
    }

    private val mPort: Int
    private val mColorOrder: String = colorOrder?.lowercase() ?: "rgb"
    private val mProtocol: Protocol = when (wledProtocol.lowercase()) {
        "udp_raw" -> Protocol.UDP_RAW
        else -> Protocol.DDP
    }
    private val mRgbw: Boolean = rgbw
    private val mBrightness: Int = brightness.coerceIn(0, 255)

    @Volatile
    private var mRgbwFallbackLogged = false

    @Volatile
    private var mRawTruncationLogged = false

    @Volatile
    private var mConnected = false

    @Volatile
    private var mClosed = false
    private var mSocket: DatagramSocket? = null
    private var mAddress: InetAddress? = null

    private val mSmoothing: ColorSmoothing
    private var mLedDataBuffer: Array<ColorRgb>? = null

    // Поддержание соединения
    private val mKeepAliveExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mResumeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WLEDClient-resume").apply { isDaemon = true }
    }

    @Volatile
    private var mLastLeds: Array<ColorRgb>? = null

    @Volatile
    private var mPaused = false
    private val mLastReconnectAttemptMs = AtomicLong(0L)
    private val mBlockedUntilMs = AtomicLong(0L)
    private val mLastErrorLogMs = AtomicLong(0L)

    init {
        // Порт должен попадать в диапазон 1-65535
        if (port > 65535) {
            throw IllegalArgumentException("Port out of range: $port (must be between 1 and 65535)")
        }

        // Если порт не задан, берём значение по умолчанию для выбранного протокола
        if (port <= 0 || port == 80) {
            mPort = if (mProtocol == Protocol.DDP) DEFAULT_PORT_DDP else HYPERION_RAW_PORT
        } else {
            mPort = port
        }

        mSmoothing = ColorSmoothing { leds -> sendLedData(leds) }
        mSmoothing.applyPreset(smoothingPreset)
        val presetValues = getPresetValues(smoothingPreset)
        if (settlingTime != presetValues.settlingTime) {
            mSmoothing.setSettlingTime(settlingTime)
        }
        if (outputDelayMs != presetValues.outputDelayMs) {
            mSmoothing.setOutputDelay(outputDelayMs)
        }
        if (updateFrequency != presetValues.updateFrequency) {
            mSmoothing.setUpdateFrequency(updateFrequency)
        }
        mSmoothing.setEnabled(smoothingEnabled)

        connect()
        startKeepAlive()
    }

    private fun startKeepAlive() {
        mKeepAliveExecutor.scheduleWithFixedDelay({
            val lastLeds = mLastLeds
            if (mPaused || !mConnected || lastLeds == null) return@scheduleWithFixedDelay
            // Повторяем последний кадр, чтобы соединение не считалось потерянным.
            // Копия обязательна: при outputDelay=0 сглаживание отдаёт свой рабочий массив
            // и продолжает менять его в такт интерполяции — сериализация живого массива
            // дала бы кадр из смеси старых и новых цветов. Раз в секунду копия дешёвая.
            sendLedData(Array(lastLeds.size) { lastLeds[it].clone() })
        }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    /**
     * Останавливает любую исходящую отправку на время сна ТВ (экран выключен).
     * Сокет остаётся открытым, чтобы возобновление было мгновенным.
     */
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
    private fun connect() {
        try {
            mAddress = InetAddress.getByName(mHost)
            val socket = DatagramSocket()
            mSocket = socket
            socket.soTimeout = 1000
            mConnected = true
            mSmoothing.start()
            if (logsEnabled) Log.d(TAG, "Connected to WLED at $mHost:$mPort")
        } catch (e: Exception) {
            mConnected = false
            throw IOException("Failed to connect to WLED: " + e.message, e)
        }
    }

    @Synchronized
    private fun reconnectIfNeeded() {
        // После disconnect() воскрешать сокет нельзя: реконнект, начатый из sendPacket в
        // момент остановки, заново открыл бы сокет и HandlerThread сглаживания навсегда
        if (mClosed) return
        val now = System.currentTimeMillis()
        val last = mLastReconnectAttemptMs.get()
        if (now - last < 2000) return
        mLastReconnectAttemptMs.set(now)

        try {
            try {
                mSocket?.close()
            } catch (ignored: Exception) {
                // Переподключение: старый сокет мог быть уже закрыт при обрыве сети.
            }
            mSocket = null
            mConnected = false

            connect()
            mBlockedUntilMs.set(0L)
        } catch (e: Exception) {
            if (logsEnabled) Log.w(TAG, "Reconnect failed", e)
        }
    }

    override fun isConnected(): Boolean {
        return mConnected && mSocket?.isClosed == false
    }

    /**
     * Снимает блокировку отправки, поставленную после ошибки EPERM.
     * Вызывается при пробуждении экрана, чтобы возобновить передачу; заодно
     * переподключается, если соединение было потеряно, и отправляет последний кадр.
     * Саму блокировку снимает синхронно, чтобы возобновление было мгновенным, а сетевые
     * операции уводит в фоновый поток — иначе получим NetworkOnMainThreadException.
     */
    fun resetBlocked() {
        val wasBlocked = mBlockedUntilMs.get() > System.currentTimeMillis()
        mBlockedUntilMs.set(0L)

        if (logsEnabled) {
            Log.d(
                TAG,
                "resetBlocked: wasBlocked=$wasBlocked, connection=${isConnected()}, mLastLeds=${mLastLeds != null}"
            )
        }

        val hadConnection = isConnected()
        // Снимок делаем один раз: mLastLeds помечено @Volatile, так что чтение безопасно, а
        // копия защищает от параллельной правки из sendLedData в другом потоке.
        val snapshot = mLastLeds?.let { Array(it.size) { i -> it[i].clone() } }

        if (mResumeExecutor.isShutdown) return
        try {
            mResumeExecutor.submit {
                try {
                    if (hadConnection && wasBlocked && snapshot != null) {
                        sendLedData(snapshot)
                    }
                    if (!hadConnection) {
                        reconnectIfNeeded()
                        if (isConnected() && snapshot != null) {
                            mSmoothing.setTargetColors(snapshot)
                            sendLedData(snapshot)
                        }
                    } else if (wasBlocked && snapshot != null) {
                        mSmoothing.setTargetColors(snapshot)
                    }
                } catch (e: Exception) {
                    if (logsEnabled) Log.w(TAG, "resetBlocked task failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Параллельно случился disconnect — возобновлять нечего.
        }
    }

    @Throws(IOException::class)
    override fun disconnect() {
        synchronized(this) {
            mClosed = true
            mConnected = false
        }
        mSmoothing.stop()
        mKeepAliveExecutor.shutdownNow()
        mResumeExecutor.shutdownNow()
        val socket = mSocket
        if (socket != null && !socket.isClosed) {
            socket.close()
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
    override fun setImage(
        data: ByteArray,
        width: Int,
        height: Int,
        priority: Int,
        duration_ms: Int,
    ) {
        if (!isConnected()) {
            throw IOException("Not connected to WLED")
        }

        val leds = LedDataExtractor.extractLEDData(mContext, data, width, height, mLedDataBuffer)
        mLedDataBuffer = leds
        if (leds.isEmpty()) return

        mSmoothing.setTargetColors(leds)
    }

    private fun sendLedData(leds: Array<ColorRgb>) {
        if (!isConnected() || mPaused) return
        // Ссылка volatile — keepalive увидит либо прежний массив, либо новый.
        mLastLeds = leds

        // Часть прошивок Android TV режет отправку UDP (sendto даёт EPERM) при SCREEN_OFF.
        // Не пытаемся слать на каждой итерации, чтобы не засорять лог и не жечь процессор.
        val now = System.currentTimeMillis()
        val blockedUntil = mBlockedUntilMs.get()
        if (now < blockedUntil) return

        try {
            // Изредка пишем в лог для диагностики
            if (System.currentTimeMillis() % 2000 < 100) {
                if (logsEnabled) Log.d(
                    TAG,
                    "sendLedData: sending ${leds.size} LEDs via ${if (mProtocol == Protocol.DDP) "DDP" else "UDP Raw"} to $mAddress:$mPort"
                )
                // Показываем первые несколько светодиодов
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
                // Запасной путь — UDP raw
                sendUdpRaw(leds)
            }

            // Отправка прошла — считаем устройство проснувшимся и снимаем блокировку.
            // Это спасает в редких случаях, когда ACTION_SCREEN_ON не пришёл, а сеть уже доступна.
            mBlockedUntilMs.set(0L)
        } catch (e: IOException) {
            val msg = e.message ?: ""
            if (msg.contains("EPERM", ignoreCase = true) || msg.contains(
                    "Operation not permitted",
                    ignoreCase = true
                )
            ) {
                mBlockedUntilMs.set(System.currentTimeMillis() + 3_000L)
            }

            val lastLog = mLastErrorLogMs.get()
            if (System.currentTimeMillis() - lastLog > 5_000L) {
                mLastErrorLogMs.set(System.currentTimeMillis())
                Log.e(TAG, "Failed to send data to WLED", e)
            }
        }
    }

    @Throws(IOException::class)
    private fun sendPacket(packet: ByteArray) {
        val socket = mSocket
        val address = mAddress
        if (socket == null || address == null) {
            // Сокет или адрес обнулились — помечаем соединение потерянным
            mConnected = false
            return
        }
        val datagramPacket = DatagramPacket(packet, packet.size, address, mPort)
        try {
            socket.send(datagramPacket)
        } catch (e: IOException) {
            // На Android TV во сне sendto может вернуть EPERM. Пересоздаём сокет —
            // это позволяет восстановиться самостоятельно после пробуждения.
            reconnectIfNeeded()
            throw e
        } catch (e: NullPointerException) {
            // Гонка: сокет обнулился прямо во время отправки
            mConnected = false
        }
    }

    // Реализация протокола DDP
    private fun createDdpPackets(leds: Array<ColorRgb>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val bytesPerPixel = bytesPerPixel()
        // Предел пакета в байтах данных, а не в светодиодах: RGBW-лента на пределе в 480
        // светодиодов дала бы 1930 байт — больше MTU, и часть прошивок теряет фрагменты.
        val channelsPerPacket = (DDP_MAX_DATA_BYTES / bytesPerPixel) * bytesPerPixel
        val channelCount = leds.size * bytesPerPixel
        val packetCount = (channelCount + channelsPerPacket - 1) / channelsPerPacket

        var channelOffset = 0

        for (packetIndex in 0 until packetCount) {
            val isLastPacket = packetIndex == packetCount - 1
            val packetDataSize = if (isLastPacket)
                channelCount - channelOffset
            else
                channelsPerPacket

            val packet = ByteArray(DDP_HEADER_SIZE + packetDataSize)

            // Заголовок
            packet[0] = (0x40 or (if (isLastPacket) 0x01 else 0x00)).toByte() // VER1 | PUSH
            packet[1] = 0 // Sequence number 0 (ignored by receiver)
            // Байт типа данных DDP: биты 5-3 — формат (001 = RGB, 011 = RGBW), биты 2-0 —
            // разрядность канала (011 = 8 бит). WLED различает RGB/RGBW именно по битам
            // формата; customer-бит и коды разрядности «на пиксель» из старой версии были
            // отсебятиной вне спецификации.
            packet[2] = if (mRgbw) 0x1B.toByte() else 0x0B.toByte()

            packet[3] = 0x01 // ID: DISPLAY

            // Смещение (big endian)
            val offset = channelOffset // Offset in BYTES (channels)
            packet[4] = ((offset shr 24) and 0xFF).toByte()
            packet[5] = ((offset shr 16) and 0xFF).toByte()
            packet[6] = ((offset shr 8) and 0xFF).toByte()
            packet[7] = (offset and 0xFF).toByte()

            // Длина (big endian)
            packet[8] = ((packetDataSize shr 8) and 0xFF).toByte()
            packet[9] = (packetDataSize and 0xFF).toByte()

            if (logsEnabled && System.currentTimeMillis() % 1000 < 50) {
                val hexHeader = packet.take(10).joinToString(" ") { String.format("%02X", it) }
                Log.v(
                    TAG,
                    "DDP Header [$packetIndex/$packetCount]: $hexHeader (Seq=${packet[1]}, Push=${packet[0].toInt() and 1}, Len=$packetDataSize)"
                )
            }

            // Данные
            var dataIdx = DDP_HEADER_SIZE
            val ledsProcessed = channelOffset / bytesPerPixel
            val ledsInThisPacket = packetDataSize / bytesPerPixel

            for (i in 0 until ledsInThisPacket) {
                dataIdx = writePixel(packet, dataIdx, leds[ledsProcessed + i], mColorOrder)
            }

            packets.add(packet)
            channelOffset += packetDataSize
        }

        return packets
    }

    // Устаревший UDP raw. На порту 19446 WLED слушает приёмник Hyperion (сырой RGB),
    // на остальных портах — свои realtime-протоколы DRGB/DRGBW/DNRGB с заголовком.
    @Throws(IOException::class)
    private fun sendUdpRaw(leds: Array<ColorRgb>) {
        if (mPort == HYPERION_RAW_PORT) {
            sendHyperionRaw(leds)
            return
        }

        val ledCount = leds.size
        var packet: ByteArray

        if (mRgbw) {
            if (ledCount <= MAX_LEDS_DRGBW) {
                sendPacket(createDrgbwPacket(leds))
                return
            }
            // Протокола RGBW со стартовым индексом (аналога DNRGB) в WLED не описано,
            // поэтому длинную ленту отправляем как RGB — белый канал посчитает сама
            // прошивка. На DDP этого ограничения нет, и там RGBW работает при любой длине.
            if (!mRgbwFallbackLogged) {
                mRgbwFallbackLogged = true
                Log.w(
                    TAG,
                    "RGBW over UDP raw supports up to $MAX_LEDS_DRGBW LEDs, got $ledCount; " +
                            "falling back to RGB. Use DDP for RGBW on long strips."
                )
            }
        }

        if (ledCount <= MAX_LEDS_DRGB) {
            packet = createDRGBPacket(leds)
            sendPacket(packet)
        } else {
            // Разбиваем на несколько пакетов
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

    /**
     * Приёмник Hyperion в WLED (udpRgbPort, по умолчанию 19446): поток RGB без заголовка,
     * ровно три байта на светодиод, белого канала в нём нет. Пакет длиннее 1472 байт
     * прошивка отбрасывает целиком.
     */
    @Throws(IOException::class)
    private fun sendHyperionRaw(leds: Array<ColorRgb>) {
        if (mRgbw && !mRgbwFallbackLogged) {
            mRgbwFallbackLogged = true
            Log.w(
                TAG,
                "Hyperion raw port $HYPERION_RAW_PORT carries no white channel; sending RGB. " +
                        "Use DDP for RGBW."
            )
        }
        if (leds.size > MAX_LEDS_HYPERION_RAW && !mRawTruncationLogged) {
            mRawTruncationLogged = true
            Log.w(
                TAG,
                "Hyperion raw port accepts up to $MAX_LEDS_HYPERION_RAW LEDs, got ${leds.size}; " +
                        "the rest is dropped. Use DDP for longer strips."
            )
        }

        val ledCount = min(leds.size, MAX_LEDS_HYPERION_RAW)
        val packet = ByteArray(ledCount * 3)
        var idx = 0
        for (i in 0 until ledCount) {
            idx = writeRgb(packet, idx, leds[i], mColorOrder)
        }
        sendPacket(packet)
    }

    private fun createDRGBPacket(leds: Array<ColorRgb>): ByteArray {
        val packet = ByteArray(2 + leds.size * 3)
        packet[0] = PROTOCOL_DRGB
        packet[1] = WLED_TIMEOUT_SECONDS

        var idx = 2
        for (led in leds) {
            idx = writeRgb(packet, idx, led, mColorOrder)
        }
        return packet
    }

    /** DRGBW: как DRGB, но четыре байта на светодиод (см. UDP realtime в документации WLED). */
    private fun createDrgbwPacket(leds: Array<ColorRgb>): ByteArray {
        val packet = ByteArray(2 + leds.size * 4)
        packet[0] = PROTOCOL_DRGBW
        packet[1] = WLED_TIMEOUT_SECONDS

        var idx = 2
        for (led in leds) {
            idx = writePixel(packet, idx, led, mColorOrder)
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
            idx = writeRgb(packet, idx, leds[startIndex + i], mColorOrder)
        }
        return packet
    }

    private fun bytesPerPixel(): Int = if (mRgbw) 4 else 3

    /** Яркость применяется к каждому каналу; 255 означает «не трогать». */
    private fun scaled(value: Int): Int =
        if (mBrightness >= 255) value else (value * mBrightness) / 255

    /**
     * Пишет один светодиод и возвращает новую позицию записи. Для RGBW-лент общая для
     * всех трёх каналов часть уходит на белый светодиод и вычитается из цветных: белый
     * ярче и чище смешанного из RGB, а суммарная яркость точки не меняется.
     */
    private fun writePixel(packet: ByteArray, offset: Int, led: ColorRgb, order: String): Int {
        if (!mRgbw) return writeRgb(packet, offset, led, order)

        val r = scaled(led.red)
        val g = scaled(led.green)
        val b = scaled(led.blue)
        val w = min(r, min(g, b))
        val idx = writeChannels(packet, offset, r - w, g - w, b - w, order)
        packet[idx] = w.toByte()
        return idx + 1
    }

    private fun writeRgb(packet: ByteArray, offset: Int, led: ColorRgb, order: String): Int =
        writeChannels(packet, offset, scaled(led.red), scaled(led.green), scaled(led.blue), order)

    private fun writeChannels(
        packet: ByteArray,
        offset: Int,
        r: Int,
        g: Int,
        b: Int,
        order: String,
    ): Int {
        val rb = r.toByte()
        val gb = g.toByte()
        val bb = b.toByte()
        when (order) {
            "grb" -> {
                packet[offset] = gb; packet[offset + 1] = rb; packet[offset + 2] = bb
            }

            "brg" -> {
                packet[offset] = bb; packet[offset + 1] = rb; packet[offset + 2] = gb
            }

            "rbg" -> {
                packet[offset] = rb; packet[offset + 1] = bb; packet[offset + 2] = gb
            }

            "gbr" -> {
                packet[offset] = gb; packet[offset + 1] = bb; packet[offset + 2] = rb
            }

            "bgr" -> {
                packet[offset] = bb; packet[offset + 1] = gb; packet[offset + 2] = rb
            }

            else -> {
                packet[offset] = rb; packet[offset + 1] = gb; packet[offset + 2] = bb
            }
        }
        return offset + 3
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
            else -> PresetValues(200, 80L, 25)
        }
    }

    companion object {
        private const val TAG = "WLEDClient"
        private const val logsEnabled = false
        private const val DEFAULT_PORT_DDP = 4048

        /** udpRgbPort в WLED — приёмник Hyperion, сырой RGB без заголовка. */
        private const val HYPERION_RAW_PORT = 19446

        // Константы DDP
        private const val DDP_HEADER_SIZE = 10

        /** 480 RGB-светодиодов — максимум данных на пакет по спецификации DDP. */
        private const val DDP_MAX_DATA_BYTES = 1440

        // Константы UDP raw
        private const val PROTOCOL_DRGB: Byte = 2
        private const val PROTOCOL_DRGBW: Byte = 3
        private const val PROTOCOL_DNRGB: Byte = 4
        private const val MAX_LEDS_DRGB = 490
        private const val MAX_LEDS_DRGBW = 367

        /** 1470 байт данных — предел приёмника Hyperion (буфер прошивки 1472 байта). */
        private const val MAX_LEDS_HYPERION_RAW = 490
        private const val MAX_LEDS_PER_PACKET_DNRGB = 489
        private const val WLED_TIMEOUT_SECONDS: Byte = 5
        private const val INFO_TIMEOUT_MS = 1500

        /** Сколько светодиодов настроено в самом WLED; null - не ответил или это не WLED. */
        fun ledCount(host: String): Int? {
            val connection = try {
                URL("http://${host.trim()}/json/info").openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return null
            }
            return try {
                connection.connectTimeout = INFO_TIMEOUT_MS
                connection.readTimeout = INFO_TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                JSONObject(text).optJSONObject("leds")?.optInt("count")?.takeIf { it > 0 }
            } catch (e: IOException) {
                Log.w(TAG, "WLED info from $host failed: ${e.message}")
                null
            } catch (e: JSONException) {
                Log.w(TAG, "WLED info from $host is not JSON: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }
    }
}
