package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import androidx.annotation.WorkerThread
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.*
import kotlin.math.abs

/**
 * Расширенный сканер сети для поиска WLED и Hyperion устройств
 * Оптимизирован: сначала быстрый пинг, потом детальное определение только для доступных хостов
 */
class DeviceScanner(
    private val onDeviceFound: ((DeviceDetector.DeviceInfo) -> Unit)? = null
) {
    private val ipsToTry: Array<String>
    private var lastTriedIndex = -1
    private val foundDevices = mutableListOf<DeviceDetector.DeviceInfo>()
    private val responsiveHosts = mutableSetOf<String>()

    init {
        ipsToTry = getIPsToTry()
        Log.d(TAG, "Initialized scanner with ${ipsToTry.size} IPs to scan")
        if (ipsToTry.isNotEmpty()) {
            Log.d(TAG, "Scanning range: ${ipsToTry.first()} to ${ipsToTry.last()}")
        }
    }

    /**
     * Быстрая проверка доступности хоста
     * Пробует ICMP ping, если не работает - проверяет порты
     */
    /**
     * Быстрая проверка доступности хоста через ICMP ping
     */
    @WorkerThread
    private fun isHostResponsive(host: String): Boolean {
        try {
            val address = InetAddress.getByName(host)
            return address.isReachable(PING_TIMEOUT_MS)
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Быстрая проверка открытых портов для определения типа устройства
     * Проверяет TCP 19400 (Hyperion) и UDP 4048, 19446 (WLED)
     * @return Pair<hasHyperionPort, hasWledPort>
     */
    @WorkerThread
    private fun checkDevicePorts(host: String): Pair<Boolean, Boolean> {
        var hasHyperionPort = false
        var hasWledPort = false
        
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, 19400), PORT_CHECK_TIMEOUT_MS)
            hasHyperionPort = socket.isConnected
            socket.close()
        } catch (e: Exception) {
        }
        
        try {
            val datagramSocket = DatagramSocket()
            datagramSocket.connect(InetAddress.getByName(host), 4048)
            datagramSocket.close()
            hasWledPort = true
        } catch (e: Exception) {
            try {
                val datagramSocket = DatagramSocket()
                datagramSocket.connect(InetAddress.getByName(host), 19446)
                datagramSocket.close()
                hasWledPort = true
            } catch (e: Exception) {
            }
        }
        
        return Pair(hasHyperionPort, hasWledPort)
    }
    
    /**
     * Определяет hostname для IP адреса через reverse DNS lookup
     */
    @WorkerThread
    private fun getHostname(ip: String): String? {
        return try {
            val address = InetAddress.getByName(ip)
            val hostname = address.hostName
            if (hostname == ip || hostname.isEmpty()) {
                null
            } else {
                hostname
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Сканирует следующий IP адрес: сначала пинг, потом детальное определение
     * @return DeviceInfo если устройство найдено, null в противном случае
     */
    @WorkerThread
    fun tryNext(): DeviceDetector.DeviceInfo? {
        if (!hasNextAttempt()) {
            return null
        }

        val ip = ipsToTry[++lastTriedIndex]
        
        if (lastTriedIndex % 50 == 0) {
            Log.d(TAG, "Scanning IP $ip (${lastTriedIndex + 1}/${ipsToTry.size}, ${(progress * 100).toInt()}%)")
        }
        
        if (!isHostResponsive(ip)) {
            return null
        }
        
        Log.d(TAG, "Host $ip is responsive, checking ports...")
        responsiveHosts.add(ip)
        
        val (hasHyperionPort, hasWledPort) = checkDevicePorts(ip)
        
        if (!hasHyperionPort && !hasWledPort) {
            Log.d(TAG, "Host $ip is responsive but no device ports found")
            return null
        }
        
        val hostname = getHostname(ip)
        
        val deviceInfo = DeviceDetector.detectDevice(ip)
        
        if (deviceInfo != null) {
            val alreadyFound = foundDevices.any { 
                it.host == deviceInfo.host
            }
            
            if (!alreadyFound) {
                val deviceInfoWithHostname = deviceInfo.copy(hostname = hostname)
                foundDevices.add(deviceInfoWithHostname)
                Log.d(TAG, "Found device: ${deviceInfoWithHostname.type} at ${deviceInfoWithHostname.host}:${deviceInfoWithHostname.port} (hostname: ${hostname ?: "N/A"})")
                onDeviceFound?.invoke(deviceInfoWithHostname)
                return deviceInfoWithHostname
            } else {
                Log.d(TAG, "Device at $ip already found, skipping")
            }
        } else {
            Log.d(TAG, "Host $ip is responsive but device type not detected")
        }

        return null
    }

    /**
     * Get all found devices
     */
    fun getFoundDevices(): List<DeviceDetector.DeviceInfo> {
        return foundDevices.toList()
    }

    /**
     * Прогресс сканирования в диапазоне [0.0 .. 1.0]
     */
    val progress: Float
        get() {
            if (ipsToTry.isEmpty()) {
                return 1f
            }
            return (lastTriedIndex + 1) / ipsToTry.size.toFloat()
        }

    /**
     * Есть ли еще IP адреса для проверки
     */
    fun hasNextAttempt(): Boolean {
        return ipsToTry.isNotEmpty() && lastTriedIndex + 1 < ipsToTry.size
    }

    /**
     * Сброс сканера для нового сканирования
     */
    fun reset() {
        lastTriedIndex = -1
        foundDevices.clear()
        responsiveHosts.clear()
    }
    
    /**
     * Получить текущий индекс сканирования
     */
    fun getCurrentIndex(): Int {
        return lastTriedIndex
    }
    
    /**
     * Получить текущий IP адрес
     */
    fun getCurrentIp(): String? {
        return if (lastTriedIndex >= 0 && lastTriedIndex < ipsToTry.size) {
            ipsToTry[lastTriedIndex]
        } else {
            null
        }
    }
    
    /**
     * Информация о сканировании
     */
    data class ScanInfo(
        val totalIps: Int,
        val firstIp: String?,
        val lastIp: String?
    )
    
    /**
     * Получить информацию о сканировании
     */
    fun getScanInfo(): ScanInfo {
        return ScanInfo(
            totalIps = ipsToTry.size,
            firstIp = ipsToTry.firstOrNull(),
            lastIp = ipsToTry.lastOrNull()
        )
    }

    companion object {
        private const val TAG = "DeviceScanner"
        private const val PING_TIMEOUT_MS = 300
        private const val PORT_CHECK_TIMEOUT_MS = 500

        /**
         * Получить IP адреса для не-localhost интерфейсов
         */
        private fun getIPAddresses(useIPv4: Boolean): List<String> {
            val foundAddresses = mutableListOf<String>()
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress
                            val isIPv4 = sAddr.indexOf(':') < 0

                            if (useIPv4) {
                                if (isIPv4)
                                    foundAddresses.add(sAddr)
                            } else {
                                if (!isIPv4 && sAddr != null) {
                                    val delim = sAddr.indexOf('%') // drop ip6 zone suffix
                                    val v6Addr = if (delim < 0) sAddr.uppercase() else sAddr.substring(0, delim).uppercase()
                                    foundAddresses.add(v6Addr)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not get ip address", e)
            }
            return foundAddresses
        }

        private fun getIPsToTry(): Array<String> {
            try {
                val localIpV4Addresses = getIPAddresses(true)
                Log.d(TAG, "Found ${localIpV4Addresses.size} local IP addresses: $localIpV4Addresses")
                
                if (localIpV4Addresses.isEmpty()) {
                    Log.w(TAG, "No local IP addresses found! Cannot scan network.")
                    return emptyArray()
                }
                
                val allIpsToTry = arrayOfNulls<String>(localIpV4Addresses.size * 254)

                for (localIpIdx in localIpV4Addresses.indices) {
                    val localIpV4Address = localIpV4Addresses[localIpIdx]
                    val ipsToTry = arrayOfNulls<String>(254)

                    val ipParts = localIpV4Address.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    if (ipParts.size != 4) continue

                    val ipPrefix = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}."
                    for (i in 1..254) {
                        ipsToTry[i - 1] = "$ipPrefix$i"
                    }

                    for (i in ipsToTry.indices) {
                        val baseIndex = localIpIdx * 254
                        if (baseIndex + i < allIpsToTry.size) {
                            allIpsToTry[baseIndex + i] = ipsToTry[i]
                        }
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val result = allIpsToTry.filterNotNull().toTypedArray()
                Log.d(TAG, "Generated ${result.size} IPs to scan")
                if (result.isNotEmpty()) {
                    val first10 = result.take(10).joinToString(", ")
                    val last10 = result.takeLast(10).joinToString(", ")
                    Log.d(TAG, "First 10 IPs: $first10")
                    Log.d(TAG, "Last 10 IPs: $last10")
                }
                return result
            } catch (e: Exception) {
                Log.e(TAG, "Error while building list of subnet ip's", e)
                return emptyArray()
            }
        }
    }
}
