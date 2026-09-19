package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Поиск контроллеров в локальной сети. Кто объявляет себя по mDNS (WLED, Hyperion,
 * HyperHDR, Home Assistant), находится через NsdManager. Узлы Art-Net mDNS не умеют,
 * их ищем широковещательным запросом.
 *
 * Находки приходят в onFound на главном потоке по мере ответов, каждая пара «тип и адрес»
 * один раз. mDNS слушает до [stop], широковещательные запросы отрабатывают за пару секунд.
 */
class LedDiscovery(context: Context, private val mOnFound: (Found) -> Unit) {

    /** [port] 0 - порт по умолчанию для этого типа. */
    data class Found(val type: OutputType, val host: String, val port: Int, val name: String)

    /** [port]: -1 - порт из объявления, 0 - порт типа по умолчанию, иначе фиксированный. */
    private class Service(val type: String, val output: OutputType, val port: Int = -1)

    private val mContext = context.applicationContext
    private val mNsd = mContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mMain = Handler(Looper.getMainLooper())
    private val mSeen = ConcurrentHashMap.newKeySet<String>()
    private val mListeners = ArrayList<NsdManager.DiscoveryListener>()
    private val mMulticastLock = (mContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock(TAG)?.apply { setReferenceCounted(false) }

    // NsdManager до Android 14 резолвит сервисы строго по одному: параллельный запрос
    // падает с FAILURE_ALREADY_ACTIVE, поэтому очередь
    private val mQueue = ArrayDeque<Pair<NsdServiceInfo, Service>>()
    private var mResolving = false

    @Volatile
    private var mStopped = false

    fun start() {
        try {
            mMulticastLock?.acquire()
        } catch (e: RuntimeException) {
            // Без блокировки часть прошивок режет ответы mDNS, но искать всё равно можно
            Log.w(TAG, "Multicast lock failed: ${e.message}")
        }
        mNsd?.let { nsd -> for (service in SERVICES) browse(nsd, service) }
        probe("artnet") { pollArtNet() }
    }

    /**
     * Перебор всех адресов подсети для устройств без mDNS: WLED со старой прошивкой и
     * Hyperion до 2.0.13. Идёт минуту и больше, прогресс приходит на главный поток.
     */
    fun sweep(onProgress: (Float) -> Unit) {
        Thread({
            val scanner = DeviceScanner()
            while (!mStopped && scanner.hasNextAttempt()) {
                val device = scanner.tryNext()
                when (device?.type) {
                    DeviceDetector.DeviceType.WLED -> report(OutputType.WLED, device.host, 0, device.name ?: device.host)
                    DeviceDetector.DeviceType.HYPERION ->
                        report(OutputType.HYPERION, device.host, device.port, device.hostname ?: device.host)

                    else -> Unit
                }
                val progress = scanner.progress
                mMain.post { if (!mStopped) onProgress(progress) }
            }
            mMain.post { if (!mStopped) onProgress(1f) }
        }, "LedDiscoverySweep").start()
    }

    fun stop() {
        mStopped = true
        val nsd = mNsd
        if (nsd != null) {
            for (listener in mListeners) {
                try {
                    nsd.stopServiceDiscovery(listener)
                } catch (_: IllegalArgumentException) {
                    // Поиск этого типа не стартовал - останавливать нечего.
                }
            }
        }
        mListeners.clear()
        synchronized(mQueue) { mQueue.clear() }
        try {
            mMulticastLock?.release()
        } catch (_: RuntimeException) {
            // Блокировку так и не взяли.
        }
    }

    private fun report(type: OutputType, host: String, port: Int, name: String) {
        if (mStopped || host.isBlank() || !mSeen.add("${type.id}|$host")) return
        val found = Found(type, host, port, name.ifBlank { host })
        mMain.post { if (!mStopped) mOnFound(found) }
    }

    private fun probe(name: String, block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (e: IOException) {
                // Нет Wi-Fi или сеть режет широковещание: этот тип просто не найдётся
                Log.w(TAG, "Probe $name failed: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Probe $name not allowed: ${e.message}")
            }
        }, "LedDiscovery-$name").start()
    }

    private fun browse(nsd: NsdManager, service: Service) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS $serviceType failed to start: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(mQueue) { mQueue.addLast(serviceInfo to service) }
                resolveNext(nsd)
            }
        }
        try {
            nsd.discoverServices(service.type, NsdManager.PROTOCOL_DNS_SD, listener)
            mListeners.add(listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "mDNS ${service.type} rejected: ${e.message}")
        }
    }

    private fun resolveNext(nsd: NsdManager) {
        val (info, service) = synchronized(mQueue) {
            if (mResolving || mStopped) return
            val next = mQueue.removeFirstOrNull() ?: return
            mResolving = true
            next
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve ${serviceInfo.serviceName} failed: $errorCode")
                done()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = addressOf(serviceInfo)
                if (host != null) {
                    val port = when {
                        service.port >= 0 -> service.port
                        serviceInfo.port > 0 -> serviceInfo.port
                        else -> 0
                    }
                    report(service.output, host, port, serviceInfo.serviceName)
                }
                done()
            }

            private fun done() {
                synchronized(mQueue) { mResolving = false }
                resolveNext(nsd)
            }
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, listener)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "resolveService rejected ${info.serviceName}: ${e.message}")
            synchronized(mQueue) { mResolving = false }
            resolveNext(nsd)
        }
    }

    private fun addressOf(info: NsdServiceInfo): String? {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.host
        }
        return address?.hostAddress
    }

    /**
     * ArtPoll на широковещательный адрес. Узлы отвечают ArtPollReply на порт 6454 отправителя,
     * а не на порт, с которого ушёл запрос, поэтому сокет занимает именно его.
     */
    private fun pollArtNet() {
        val socket = DatagramSocket(null)
        socket.use {
            it.reuseAddress = true
            it.broadcast = true
            it.soTimeout = 300
            it.bind(InetSocketAddress(ArtNetClient.DEFAULT_PORT))
            it.send(
                DatagramPacket(ART_POLL, ART_POLL.size, InetAddress.getByName("255.255.255.255"), ArtNetClient.DEFAULT_PORT)
            )
            val buffer = ByteArray(1024)
            val deadline = SystemClock.elapsedRealtime() + PROBE_MS
            while (!mStopped && SystemClock.elapsedRealtime() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    it.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val name = artPollReplyName(packet.data, packet.length) ?: continue
                report(OutputType.ARTNET, packet.address.hostAddress.orEmpty(), 0, name)
            }
        }
    }

    companion object {
        private const val TAG = "LedDiscovery"
        private const val PROBE_MS = 2500

        private val SERVICES = listOf(
            // WLED объявляет веб-интерфейс, а поток принимает на порту своего протокола
            Service("_wled._tcp", OutputType.WLED, 0),
            Service("_hyperiond-flatbuf._tcp", OutputType.HYPERION),
            // HyperHDR объявляет только веб-интерфейс, FlatBuffers у него на стандартном 19400
            Service("_hyperhdr-http._tcp", OutputType.HYPERION, OutputType.HYPERION.defaultPort),
            Service("_home-assistant._tcp", OutputType.HOME_ASSISTANT),
        )

        private val ART_NET_ID = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)
        private const val OP_POLL_REPLY = 0x2100

        /** ArtPoll версии 14 без флагов: ответить один раз. */
        private val ART_POLL = ART_NET_ID + byteArrayOf(0x00, 0x20, 0x00, 14, 0x00, 0x00)

        /** Имя узла из ArtPollReply: короткое, а если пусто - длинное; null - это не ответ узла. */
        internal fun artPollReplyName(data: ByteArray, length: Int): String? {
            if (length < 108) return null
            for (i in ART_NET_ID.indices) if (data[i] != ART_NET_ID[i]) return null
            val opCode = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
            if (opCode != OP_POLL_REPLY) return null
            val short = cString(data, 26, 18)
            return short.ifBlank { cString(data, 44, 64) }.ifBlank { "Art-Net" }
        }

        private fun cString(data: ByteArray, offset: Int, max: Int): String {
            var end = offset
            while (end < offset + max && data[end] != 0.toByte()) end++
            return String(data, offset, end - offset, Charsets.US_ASCII).trim()
        }
    }
}
