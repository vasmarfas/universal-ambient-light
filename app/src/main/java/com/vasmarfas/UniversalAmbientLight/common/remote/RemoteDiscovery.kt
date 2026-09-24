package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Поиск телевизоров с включённым управлением в локальной сети (mDNS). Нужен, когда ТВ
 * получил от роутера новый адрес: QR-код с прежним адресом тогда уже не поможет.
 *
 * Блокирующий вызов, из главного потока не запускать.
 */
object RemoteDiscovery {
    private const val TAG = "RemoteDiscovery"

    data class Found(val id: String, val name: String, val host: String, val port: Int)

    /**
     * Собирает ответы за [timeoutMs]. С [wantedId] возвращается сразу, как только нашёлся
     * этот телевизор.
     */
    fun discover(context: Context, timeoutMs: Long, wantedId: String? = null): List<Found> =
        discover(context.getSystemService(Context.NSD_SERVICE) as? NsdManager, timeoutMs, wantedId)

    fun discover(nsd: NsdManager?, timeoutMs: Long, wantedId: String? = null): List<Found> {
        nsd ?: return emptyList()
        val found = ConcurrentHashMap<String, Found>()
        val done = CountDownLatch(1)
        // NsdManager до Android 14 резолвит сервисы строго по одному: параллельный запрос
        // падает с FAILURE_ALREADY_ACTIVE, поэтому очередь
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            val next = synchronized(queue) {
                if (resolving) return
                val item = queue.removeFirstOrNull() ?: return
                resolving = true
                item
            }
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve ${serviceInfo.serviceName} failed: $errorCode")
                        synchronized(queue) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val id = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8)
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host?.hostAddress
                        if (id != null && host != null && serviceInfo.port > 0) {
                            found[id] = Found(id, serviceInfo.serviceName, host, serviceInfo.port)
                            if (id == wantedId) done.countDown()
                        }
                        synchronized(queue) { resolving = false }
                        resolveNext()
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "resolveService threw: ${e.message}")
                synchronized(queue) { resolving = false }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed to start: $errorCode")
                done.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(serviceInfo) }
                resolveNext()
            }
        }

        return try {
            nsd.discoverServices(RemoteProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.values.toList()
        } catch (e: Exception) {
            Log.w(TAG, "Discovery error: ${e.message}")
            found.values.toList()
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // Поиск мог не стартовать вовсе — останавливать нечего.
            }
        }
    }
}
