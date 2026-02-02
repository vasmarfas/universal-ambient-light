package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Определяет тип устройства (WLED или Hyperion) по IP адресу
 */
class DeviceDetector {
    
    data class DeviceInfo(
        val host: String,
        val port: Int,
        val type: DeviceType,
        val protocol: String? = null,
        val name: String? = null,
        val hostname: String? = null
    )
    
    enum class DeviceType {
        WLED,
        HYPERION,
        UNKNOWN
    }
    
    companion object {
        private const val TAG = "DeviceDetector"
        private const val CONNECTION_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 1500
        
        private val WLED_PORTS = listOf(80, 4048, 19446)
        private val HYPERION_PORTS = listOf(19400)
        
        /**
         * Detects device type by IP address
         * Tries all possible port and protocol combinations
         */
        @JvmStatic
        fun detectDevice(host: String): DeviceInfo? {
            val wledHttpInfo = detectWLED(host, 80)
            if (wledHttpInfo != null) {
                return wledHttpInfo
            }
            
            for (port in HYPERION_PORTS) {
                val info = detectHyperion(host, port)
                if (info != null) {
                    return info
                }
            }
            
            return null
        }
        
        /**
         * Checks if device is WLED
         */
        private fun detectWLED(host: String, port: Int): DeviceInfo? {
            return when (port) {
                80 -> {
                    try {
                        val url = URL("http://$host/json/info")
                        val connection = url.openConnection() as HttpURLConnection
                        connection.connectTimeout = CONNECTION_TIMEOUT_MS
                        connection.readTimeout = READ_TIMEOUT_MS
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "HyperionAndroid/1.0")
                        
                        val responseCode = connection.responseCode
                        Log.d(TAG, "WLED HTTP check at $host:80 - response code: $responseCode")
                        if (responseCode == HttpURLConnection.HTTP_OK) {
                            val reader = BufferedReader(InputStreamReader(connection.inputStream))
                            val response = reader.readText()
                            reader.close()
                            connection.disconnect()
                            
                            Log.d(TAG, "WLED HTTP response: ${response.take(200)}")
                            val json = JSONObject(response)
                            val name = json.optString("name", null)
                            
                            if (json.has("ver") || json.has("leds") || json.has("info")) {
                                Log.d(TAG, "WLED detected at $host via HTTP API")
                                return DeviceInfo(
                                    host = host,
                                    port = 4048,
                                    type = DeviceType.WLED,
                                    protocol = "ddp",
                                    name = name ?: "WLED"
                                )
                            } else {
                                Log.d(TAG, "HTTP response at $host doesn't look like WLED")
                            }
                        } else {
                            Log.d(TAG, "HTTP response code $responseCode at $host:80")
                            try {
                                val altUrl = URL("http://$host/json")
                                val altConnection = altUrl.openConnection() as HttpURLConnection
                                altConnection.connectTimeout = CONNECTION_TIMEOUT_MS
                                altConnection.readTimeout = READ_TIMEOUT_MS
                                altConnection.requestMethod = "GET"
                                altConnection.setRequestProperty("User-Agent", "HyperionAndroid/1.0")
                                
                                if (altConnection.responseCode == HttpURLConnection.HTTP_OK) {
                                    val reader = BufferedReader(InputStreamReader(altConnection.inputStream))
                                    val response = reader.readText()
                                    reader.close()
                                    altConnection.disconnect()
                                    
                                    val json = JSONObject(response)
                                    val name = json.optString("name", null)
                                    
                                    if (json.has("info") || json.has("state")) {
                                        return DeviceInfo(
                                            host = host,
                                            port = 4048,
                                            type = DeviceType.WLED,
                                            protocol = "ddp",
                                            name = name ?: "WLED"
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "Unknown error"
                        when {
                            errorMsg.contains("Cleartext HTTP traffic") -> {
                                Log.w(TAG, "Cleartext HTTP not permitted for $host:80 - check AndroidManifest.xml")
                            }
                            errorMsg.contains("unexpected end of stream") -> {
                                Log.d(TAG, "HTTP connection closed unexpectedly at $host:80 - might not be WLED")
                            }
                            errorMsg.contains("Failed to connect") -> {
                                Log.d(TAG, "Failed to connect to $host:80 - port might be closed")
                            }
                            else -> {
                                Log.d(TAG, "Error checking WLED HTTP API at $host:80 - $errorMsg")
                            }
                        }
                    }
                    null
                }
                4048, 19446 -> {
                    null
                }
                else -> null
            }
        }
        
        /**
         * Checks if device is Hyperion
         */
        private fun detectHyperion(host: String, port: Int): DeviceInfo? {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS)
                
                if (socket.isConnected) {
                    socket.close()
                    Log.d(TAG, "Hyperion detected at $host:$port")
                    return DeviceInfo(
                        host = host,
                        port = port,
                        type = DeviceType.HYPERION,
                        protocol = "flatbuffers",
                        name = "Hyperion"
                    )
                }
            } catch (e: Exception) {
            }
            return null
        }
        
        /**
         * Быстрая проверка доступности порта
         */
        @JvmStatic
        fun isPortOpen(host: String, port: Int, timeoutMs: Int = CONNECTION_TIMEOUT_MS): Boolean {
            return try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val isConnected = socket.isConnected
                socket.close()
                isConnected
            } catch (e: Exception) {
                false
            }
        }
    }
}
