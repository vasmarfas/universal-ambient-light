package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Поиск контроллеров в локальной сети. Кто объявляет себя по mDNS (WLED, Hyperion,
 * HyperHDR, Home Assistant), находится через NsdManager.
 *
 * Находки приходят в onFound на главном потоке по мере ответов, каждая пара «тип и адрес»
 * один раз. mDNS слушает до [stop].
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

    companion object {
        private const val TAG = "LedDiscovery"

        private val SERVICES = listOf(
            // WLED объявляет веб-интерфейс, а поток принимает на порту своего протокола
            Service("_wled._tcp", OutputType.WLED, 0),
            Service("_hyperiond-flatbuf._tcp", OutputType.HYPERION),
            // HyperHDR объявляет только веб-интерфейс, FlatBuffers у него на стандартном 19400
            Service("_hyperhdr-http._tcp", OutputType.HYPERION, OutputType.HYPERION.defaultPort),
            Service("_home-assistant._tcp", OutputType.HOME_ASSISTANT),
        )
    }
}
