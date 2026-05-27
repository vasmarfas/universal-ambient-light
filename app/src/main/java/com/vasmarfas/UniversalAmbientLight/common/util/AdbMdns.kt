package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.AdbMdns.TLS_CONNECT
import com.vasmarfas.UniversalAmbientLight.common.util.AdbMdns.TLS_PAIRING
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers the device's own wireless-debugging ports over mDNS so the user does not
 * have to read the (random) port from Developer Options by hand.
 *
 *  - [TLS_CONNECT] — the connect port used after pairing (Android 11+)
 *  - [TLS_PAIRING] — the pairing port shown under "Pair device with pairing code"
 *
 * Blocking; call off the main thread.
 */
object AdbMdns {
    private const val TAG = "AdbMdns"

    const val TLS_CONNECT = "_adb-tls-connect._tcp"
    const val TLS_PAIRING = "_adb-tls-pairing._tcp"

    data class Service(val host: InetAddress, val port: Int)

    /**
     * Discovers the first [serviceType] instance advertised on the local network and
     * returns its host/port, or null on timeout.
     */
    fun discover(context: Context, serviceType: String, timeoutMs: Long = 5000): Service? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val result = AtomicReference<Service?>(null)
        val latch = CountDownLatch(1)

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                val port = serviceInfo.port
                if (host != null && port > 0 && result.compareAndSet(null, Service(host, port))) {
                    latch.countDown()
                }
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // resolveService is deprecated on API 34+ but works on API 26+; one resolve
                // at a time is fine here since we only need the first match.
                try {
                    nsd.resolveService(serviceInfo, resolveListener)
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw: ${e.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        return try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            result.get()
        } catch (e: Exception) {
            Log.w(TAG, "discover error: ${e.message}")
            null
        } finally {
            try {
                nsd.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
            }
        }
    }
}
