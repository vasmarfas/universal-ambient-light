package com.vasmarfas.UniversalAmbientLight.common.network

import android.util.Log
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Philips Hue через Entertainment API: лампы зоны развлечений из приложения Hue получают цвет
 * потоком по DTLS на порт 2100 моста, до 25 раз в секунду, а не парой REST-команд в секунду.
 *
 * Каждая лампа берёт цвет того места кадра, где стоит в зоне: x из настроек зоны идёт слева
 * направо, z снизу вверх (пол, уровень ТВ, потолок). Глубина y на картинку не влияет.
 *
 * Поток версии 2 адресует не лампы, а каналы зоны: у градиентной ленты их несколько, у каждого
 * своё место, и лента светит разными цветами. В версии 1 она одна лампа с одним цветом; эта
 * версия остаётся для мостов без CLIP API v2 и сторонних вроде diyHue.
 */
class HueEntertainmentClient(
    host: String,
    private val mUsername: String,
    private val mClientKey: String,
    private val mAreaId: String,
    private val mTurnOffOnStop: Boolean,
    smoothing: LedStreamClient.SmoothingSettings,
) : HyperionClient, StreamingClient {

    /** Лампа в потоке версии 1 или канал в версии 2. */
    private class Light(val id: Int, val u: Float, val v: Float)

    private class Configuration(val id: String, val applicationId: String, val channels: List<Light>)

    private val mHost = host.trim()
    private val mBase = "http://$mHost/api/${mUsername.trim()}"
    private var mLightIds: List<Int> = emptyList()
    private var mLights: List<Light> = emptyList()
    private var mConfigurationId: String? = null
    private var mBridgeId = ""
    private var mSocket: DatagramSocket? = null
    private var mTransport: DTLSTransport? = null
    private var mMessage = ByteArray(0)
    private var mSequence = 0
    private var mDirty = false
    private val mTicker = Executors.newSingleThreadScheduledExecutor { Thread(it, "hue-stream-ticker") }
    private val mSmoothing = ColorSmoothing { colors -> deliver(colors) }

    @Volatile
    private var mLastSendMs = 0L

    @Volatile
    private var mConnected = false

    init {
        mSmoothing.applyPreset(smoothing.preset)
        mSmoothing.setSettlingTime(smoothing.settlingTime)
        mSmoothing.setOutputDelay(smoothing.outputDelayMs)
        mSmoothing.setUpdateFrequency(smoothing.updateFrequency)
        mSmoothing.setEnabled(smoothing.enabled)
        connect()
    }

    @Throws(IOException::class)
    private fun connect() {
        if (mClientKey.isBlank()) throw IOException("The Hue bridge was paired without a streaming key")
        val lights = readArea()
        if (lights.isEmpty()) throw IOException("The entertainment area has no lights")
        mLightIds = lights.map { it.id }
        val configuration = try {
            readConfiguration()
        } catch (e: IOException) {
            Log.w(TAG, "Hue API v2 is unavailable, streaming whole lights: ${e.message}")
            null
        }
        mConfigurationId = configuration?.id
        mLights = configuration?.channels ?: lights
        // Выключенную лампу поток не зажигает
        for (id in mLightIds) HueClient.request("PUT", "$mBase/lights/$id/state", """{"on":true}""")
        setStreamActive(true)
        try {
            val socket = DatagramSocket()
            socket.connect(InetAddress.getByName(mHost), STREAM_PORT)
            mSocket = socket
            val identity = configuration?.applicationId ?: mUsername.trim()
            mTransport = DTLSClientProtocol().connect(PskClient(identity, hexToBytes(mClientKey)), UDPTransport(socket, MTU))
        } catch (e: IOException) {
            closeTransport()
            setStreamActiveQuietly(false)
            throw IOException("DTLS handshake with the Hue bridge failed: ${e.message}", e)
        }
        mMessage = message(mLights.size, mConfigurationId)
        mConnected = true
        // Кадр, пришедший раньше срока, уходит здесь: иначе последний цвет сцены терялся бы
        // до следующей смены картинки. Без сообщений десять секунд мост закрывает поток и
        // возвращает лампам прежний свет, поэтому и на статичной картинке раз в секунду повтор
        mTicker.scheduleWithFixedDelay({
            val idle = System.currentTimeMillis() - mLastSendMs
            if (idle >= KEEPALIVE_MS || idle >= FRAME_INTERVAL_MS && isDirty()) {
                try {
                    send()
                } catch (e: IOException) {
                    Log.w(TAG, "Hue stream send failed: ${e.message}")
                }
            }
        }, FRAME_INTERVAL_MS, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS)
        Log.i(TAG, "Hue entertainment stream v${if (configuration != null) 2 else 1} to ${mLights.size} channels")
    }

    @Throws(IOException::class)
    private fun readArea(): List<Light> {
        val reply = HueClient.request("GET", "$mBase/groups/$mAreaId", null)
        val group = try {
            JSONObject(reply)
        } catch (e: JSONException) {
            throw IOException("Unexpected answer from the Hue bridge", e)
        }
        if (group.optString("type") != "Entertainment") throw IOException("Hue group $mAreaId is not an entertainment area")
        val locations = group.optJSONObject("locations") ?: throw IOException("The entertainment area has no light positions")
        return locations.keys().asSequence().mapNotNull { id ->
            val position = locations.optJSONArray(id) ?: return@mapNotNull null
            place(id.toIntOrNull() ?: return@mapNotNull null, position.optDouble(0, 0.0), position.optDouble(2, 0.0))
        }.toList()
    }

    /** Та же зона в CLIP API v2: UUID для сообщений, каналы и hue-application-id, имя PSK в v2. */
    @Throws(IOException::class)
    private fun readConfiguration(): Configuration = try {
        mBridgeId = JSONObject(HueClient.request("GET", "$mBase/config", null)).getString("bridgeid")
        val applicationId = clip("GET", "/auth/v1", null) { it.getHeaderField("hue-application-id") }
            ?: throw IOException("The Hue bridge gave no application id")
        val data = JSONObject(clip("GET", CONFIGURATIONS, null) { connection ->
            connection.inputStream.bufferedReader().use { it.readText() }
        }).getJSONArray("data")
        val area = (0 until data.length()).map { data.getJSONObject(it) }
            .find { it.optString("id_v1") == "/groups/$mAreaId" }
            ?: throw IOException("Hue API v2 has no area $mAreaId")
        val id = area.getString("id")
        if (id.length != CONFIGURATION_ID_SIZE) throw IOException("Unexpected Hue area id $id")
        val channels = area.getJSONArray("channels")
        if (channels.length() == 0) throw IOException("The entertainment area has no channels")
        Configuration(id, applicationId, (0 until channels.length()).map {
            val channel = channels.getJSONObject(it)
            val position = channel.getJSONObject("position")
            place(channel.getInt("channel_id"), position.getDouble("x"), position.getDouble("z"))
        })
    } catch (e: JSONException) {
        throw IOException("Unexpected answer from the Hue bridge", e)
    }

    private fun place(id: Int, x: Double, z: Double) =
        Light(id, ((x.toFloat() + 1f) / 2f).coerceIn(0f, 1f), ((1f - z.toFloat()) / 2f).coerceIn(0f, 1f))

    /** CLIP API v2 есть только по HTTPS; сертификат моста подписан корневым CA Hue и выдан на id моста. */
    @Throws(IOException::class)
    private fun <T> clip(method: String, path: String, body: String?, read: (HttpURLConnection) -> T): T {
        val connection = URL("https://$mHost$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = HUE_TLS
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            certificate?.subjectX500Principal?.name.orEmpty().split(',')
                .any { it.trim().equals("CN=$mBridgeId", ignoreCase = true) }
        }
        connection.setRequestProperty("hue-application-key", mUsername.trim())
        return HueClient.exchange(connection, method, body, read)
    }

    override fun isConnected(): Boolean = mConnected

    // Во сне ТВ кадры не приходят, поток к мосту держит тикер; гасить нужно только сглаживание
    override fun pauseSending() {
        mSmoothing.stop()
    }

    override fun resumeSending() {}

    override val delaysOutput = true

    override fun setOutputDelay(ms: Long) {
        mSmoothing.setOutputDelay(ms)
    }

    override fun setSmoothingEnabled(enabled: Boolean) {
        mSmoothing.setEnabled(enabled)
    }

    @Throws(IOException::class)
    override fun disconnect() {
        mSmoothing.stop()
        mTicker.shutdownNow()
        if (mConnected) {
            fill { 0 }
            try {
                send()
            } catch (e: IOException) {
                Log.w(TAG, "Final black message failed: ${e.message}")
            }
        }
        mConnected = false
        closeTransport()
        setStreamActiveQuietly(false)
        if (mTurnOffOnStop) {
            for (id in mLightIds) {
                try {
                    HueClient.request("PUT", "$mBase/lights/$id/state", """{"on":false}""")
                } catch (e: IOException) {
                    Log.w(TAG, "Could not switch off Hue light $id: ${e.message}")
                }
            }
        }
    }

    @Throws(IOException::class)
    override fun clear(priority: Int) {
        if (!mConnected) return
        mSmoothing.setTargetColors(Array(mLights.size) { ColorRgb(0, 0, 0) })
    }

    @Throws(IOException::class)
    override fun clearAll() {
        clear(0)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int) {
        setColor(color, priority, -1)
    }

    @Throws(IOException::class)
    override fun setColor(color: Int, priority: Int, duration_ms: Int) {
        if (!mConnected) return
        mSmoothing.setTargetColors(Array(mLights.size) { ColorRgb(color shr 16, color shr 8, color) })
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int) {
        setImage(data, width, height, priority, -1)
    }

    @Throws(IOException::class)
    override fun setImage(data: ByteArray, width: Int, height: Int, priority: Int, duration_ms: Int) {
        if (!mConnected || width <= 0 || height <= 0) return
        mSmoothing.setTargetColors(Array(mLights.size) { sample(data, width, height, mLights[it]) })
    }

    private fun deliver(colors: Array<ColorRgb>) {
        if (!mConnected) return
        fill { index -> colors.getOrNull(index)?.let { (it.red shl 16) or (it.green shl 8) or it.blue } ?: 0 }
        if (System.currentTimeMillis() - mLastSendMs < FRAME_INTERVAL_MS) return
        try {
            send()
        } catch (e: IOException) {
            Log.w(TAG, "Hue stream send failed: ${e.message}")
        }
    }

    @Synchronized
    private fun fill(colorOf: (Int) -> Int) {
        for ((index, light) in mLights.withIndex()) {
            writeLight(mMessage, index, light.id, colorOf(index))
        }
        mDirty = true
    }

    @Synchronized
    private fun isDirty(): Boolean = mDirty

    @Synchronized
    @Throws(IOException::class)
    private fun send() {
        val transport = mTransport ?: return
        mMessage[SEQUENCE_OFFSET] = mSequence.toByte()
        mSequence = (mSequence + 1) and 0xFF
        transport.send(mMessage, 0, mMessage.size)
        mDirty = false
        mLastSendMs = System.currentTimeMillis()
    }

    private fun closeTransport() {
        try {
            mTransport?.close()
        } catch (e: IOException) {
            Log.w(TAG, "DTLS close failed: ${e.message}")
        }
        mTransport = null
        mSocket?.close()
        mSocket = null
    }

    @Throws(IOException::class)
    private fun setStreamActive(active: Boolean) {
        val configurationId = mConfigurationId
        if (configurationId != null) {
            clip("PUT", "$CONFIGURATIONS/$configurationId", """{"action":"${if (active) "start" else "stop"}"}""") {}
        } else {
            HueClient.request("PUT", "$mBase/groups/$mAreaId", """{"stream":{"active":$active}}""")
        }
    }

    private fun setStreamActiveQuietly(active: Boolean) {
        try {
            setStreamActive(active)
        } catch (e: IOException) {
            Log.w(TAG, "Could not switch the Hue stream to $active: ${e.message}")
        }
    }

    /** Средний цвет квадрата кадра вокруг места лампы. */
    private fun sample(data: ByteArray, width: Int, height: Int, light: Light): ColorRgb {
        val half = maxOf(width, height) / 8
        val cx = (light.u * (width - 1)).toInt()
        val cy = (light.v * (height - 1)).toInt()
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        for (y in (cy - half).coerceAtLeast(0)..(cy + half).coerceAtMost(height - 1)) {
            var i = (y * width + (cx - half).coerceAtLeast(0)) * 3
            for (x in (cx - half).coerceAtLeast(0)..(cx + half).coerceAtMost(width - 1)) {
                if (i + 2 >= data.size) break
                r += data[i].toInt() and 0xFF
                g += data[i + 1].toInt() and 0xFF
                b += data[i + 2].toInt() and 0xFF
                count++
                i += 3
            }
        }
        if (count == 0) return ColorRgb(0, 0, 0)
        return ColorRgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * Мосту нужны ровно DTLS 1.2 и PSK с AES-128-GCM; сертификатов у него нет. Шифры даёт
     * платформа (Conscrypt): BcTlsCrypto тянет за собой все движки Bouncy Castle, это +420 КБ dex.
     */
    private class PskClient(identity: String, psk: ByteArray) :
        PSKTlsClient(JcaTlsCryptoProvider().create(SecureRandom()), BasicTlsPSKIdentity(identity, psk)) {

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.DTLSv12.only()

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

        override fun getHandshakeTimeoutMillis(): Int = HANDSHAKE_TIMEOUT_MS
    }

    companion object {
        private const val TAG = "HueEntertainment"
        private const val STREAM_PORT = 2100
        private const val MTU = 1400
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
        private const val FRAME_INTERVAL_MS = 40L
        private const val KEEPALIVE_MS = 1000L
        private const val HEADER_SIZE = 16
        private const val LIGHT_SIZE = 9
        private const val CHANNEL_SIZE = 7
        private const val CONFIGURATION_ID_SIZE = 36
        private const val VERSION_OFFSET = 9
        private const val SEQUENCE_OFFSET = 11
        private const val CONFIGURATIONS = "/clip/v2/resource/entertainment_configuration"

        /** Корневые сертификаты мостов: root-bridge от Philips Hue и Hue Root CA 01 от Signify. */
        internal val HUE_ROOT_CA = """
            -----BEGIN CERTIFICATE-----
            MIICMjCCAdigAwIBAgIUO7FSLbaxikuXAljzVaurLXWmFw4wCgYIKoZIzj0EAwIw
            OTELMAkGA1UEBhMCTkwxFDASBgNVBAoMC1BoaWxpcHMgSHVlMRQwEgYDVQQDDAty
            b290LWJyaWRnZTAiGA8yMDE3MDEwMTAwMDAwMFoYDzIwMzgwMTE5MDMxNDA3WjA5
            MQswCQYDVQQGEwJOTDEUMBIGA1UECgwLUGhpbGlwcyBIdWUxFDASBgNVBAMMC3Jv
            b3QtYnJpZGdlMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEjNw2tx2AplOf9x86
            aTdvEcL1FU65QDxziKvBpW9XXSIcibAeQiKxegpq8Exbr9v6LBnYbna2VcaK0G22
            jOKkTqOBuTCBtjAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNV
            HQ4EFgQUZ2ONTFrDT6o8ItRnKfqWKnHFGmQwdAYDVR0jBG0wa4AUZ2ONTFrDT6o8
            ItRnKfqWKnHFGmShPaQ7MDkxCzAJBgNVBAYTAk5MMRQwEgYDVQQKDAtQaGlsaXBz
            IEh1ZTEUMBIGA1UEAwwLcm9vdC1icmlkZ2WCFDuxUi22sYpLlwJY81Wrqy11phcO
            MAoGCCqGSM49BAMCA0gAMEUCIEBYYEOsa07TH7E5MJnGw557lVkORgit2Rm1h3B2
            sFgDAiEA1Fj/C3AN5psFMjo0//mrQebo0eKd3aWRx+pQY08mk48=
            -----END CERTIFICATE-----
            -----BEGIN CERTIFICATE-----
            MIIBzDCCAXOgAwIBAgICEAAwCgYIKoZIzj0EAwIwPDELMAkGA1UEBhMCTkwxFDAS
            BgNVBAoMC1NpZ25pZnkgSHVlMRcwFQYDVQQDDA5IdWUgUm9vdCBDQSAwMTAgFw0y
            NTAyMjUwMDAwMDBaGA8yMDUwMTIzMTIzNTk1OVowPDELMAkGA1UEBhMCTkwxFDAS
            BgNVBAoMC1NpZ25pZnkgSHVlMRcwFQYDVQQDDA5IdWUgUm9vdCBDQSAwMTBZMBMG
            ByqGSM49AgEGCCqGSM49AwEHA0IABFfOO0jfSAUXGQ9kjEDzyBrcMQ3ItyA5krE+
            cyvb1Y3xFti7KlAad8UOnAx0FBLn7HZrlmIwm1QnX0fK3LPM13mjYzBhMB0GA1Ud
            DgQWBBTF1pSpsCASX/z0VHLigxU2CAaqoTAfBgNVHSMEGDAWgBTF1pSpsCASX/z0
            VHLigxU2CAaqoTAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggq
            hkjOPQQDAgNHADBEAiAk7duT+IHbOGO4UUuGLAEpyYejGZK9Z7V9oSfnvuQ5BQIg
            IYSgwwxHXm73/JgcU9lAM6c8Bmu3UE3kBIUwBs1qXFw=
            -----END CERTIFICATE-----
        """.trimIndent()

        private val HUE_TLS: SSLSocketFactory by lazy {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            CertificateFactory.getInstance("X.509").generateCertificates(HUE_ROOT_CA.byteInputStream())
                .forEachIndexed { index, certificate -> store.setCertificateEntry("hue$index", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
        }

        /** Заголовок HueStream и место под [lights] ламп, цвета в RGB; с [configurationId] версия 2 по каналам. */
        internal fun message(lights: Int, configurationId: String? = null): ByteArray {
            val id = configurationId?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
            val message = ByteArray(HEADER_SIZE + id.size + lights * if (configurationId == null) LIGHT_SIZE else CHANNEL_SIZE)
            "HueStream".toByteArray(Charsets.US_ASCII).copyInto(message)
            message[VERSION_OFFSET] = if (configurationId == null) 1 else 2
            id.copyInto(message, HEADER_SIZE)
            return message
        }

        /**
         * Лампа [index] сообщения и RGB по 16 бит на цвет. В версии 1 номер лампы - три байта
         * с типом, в версии 2 номер канала - один байт, и все они идут после UUID зоны.
         */
        internal fun writeLight(message: ByteArray, index: Int, id: Int, rgb: Int) {
            val color = if (message[VERSION_OFFSET].toInt() == 2) {
                val offset = HEADER_SIZE + CONFIGURATION_ID_SIZE + index * CHANNEL_SIZE
                message[offset] = id.toByte()
                offset + 1
            } else {
                val offset = HEADER_SIZE + index * LIGHT_SIZE
                message[offset] = 0
                message[offset + 1] = (id shr 8).toByte()
                message[offset + 2] = id.toByte()
                offset + 3
            }
            for (component in 0 until 3) {
                // 0..255 в 0..65535 без провала у верхней границы: 255 * 257 = 65535
                val value = ((rgb shr (16 - component * 8)) and 0xFF) * 257
                message[color + component * 2] = (value shr 8).toByte()
                message[color + 1 + component * 2] = value.toByte()
            }
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
