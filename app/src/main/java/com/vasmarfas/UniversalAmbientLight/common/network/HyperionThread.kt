package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HyperionThread(
    private val mCallback: ScreenGrabberService.HyperionThreadBroadcaster,
    private val mHost: String,
    private val mPort: Int,
    private val mPriority: Int,
    reconnect: Boolean,
    delaySeconds: Int,
    connectionType: String?,
    private val mContext: Context,
    private val mBaudRate: Int,
    wledColorOrder: String?,
    private val mWledProtocol: String = "ddp",
    private val mWledRgbw: Boolean = false,
    private val mWledBrightness: Int = 255,
    private val mAdalightProtocol: String = "ada",
    private val mSmoothingEnabled: Boolean = true,
    private val mSmoothingPreset: String = "balanced",
    private val mSettlingTime: Int = 200,
    private val mOutputDelayMs: Long = 80L,
    private val mUpdateFrequency: Int = 25
) : Thread(TAG) {

    private val mReconnectDelayMs: Long = (delaySeconds * 1000).toLong()
    private val mConnectionType: String = connectionType ?: "hyperion"
    private val mWledColorOrder: String = wledColorOrder ?: "rgb"
    private val mReconnectEnabled = AtomicBoolean(reconnect)
    private val mConnected = AtomicBoolean(false)
    private val mClient = AtomicReference<HyperionClient?>()
    private val mExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var mPendingTask: Future<*>? = null
    @Volatile
    private var mPendingFrame: FrameData? = null

    // ColorSmoothing
    private val mSmoothing: ColorSmoothing? = null // Smoothing теперь внутри клиентов

    private class FrameData(val data: ByteArray, val width: Int, val height: Int)

    private val mListener = object : HyperionThreadListener {
        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            val client = mClient.get()
            if (client == null || !client.isConnected()) return

            // Если используется WLED или Adalight, сглаживание уже встроено внутри клиентов
            if (client is WLEDClient || client is AdalightClient) {
                mPendingFrame = FrameData(data, width, height)
                val pending = mPendingTask
                if (pending != null && !pending.isDone) {
                    pending.cancel(false)
                }
                mPendingTask = mExecutor.submit { sendPendingFrame() }
                return
            }

            // Для обычного Hyperion можно использовать локальное сглаживание, если нужно
            // Но пока оставим прямую отправку для Hyperion протокола, так как сглаживание
            // обычно делается на стороне сервера Hyperion.

            mPendingFrame = FrameData(data, width, height)
            val pending = mPendingTask
            if (pending != null && !pending.isDone) {
                pending.cancel(false)
            }
            mPendingTask = mExecutor.submit { sendPendingFrame() }
        }

        private fun sendPendingFrame() {
            val frame = mPendingFrame
            val client = mClient.get()

            if (frame == null || client == null || !client.isConnected()) return

            try {
                client.setImage(frame.data, frame.width, frame.height, mPriority, FRAME_DURATION)

                if (client is HyperionFlatBuffers) {
                    client.cleanReplies()
                }
            } catch (e: IOException) {
                handleError(e)
            }
        }

        override fun clear() {
            val client = mClient.get()
            if (client != null && client.isConnected()) {
                try {
                    client.clear(mPriority)
                } catch (e: IOException) {
                    mCallback.onConnectionError(e.hashCode(), e.message)
                }
            }
        }

        override fun disconnect() {
            val pending = mPendingTask
            if (pending != null) {
                pending.cancel(true)
                mPendingTask = null
            }
            mPendingFrame = null

            if (!mExecutor.isShutdown) {
                mExecutor.shutdownNow()
                try {
                    mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                }
            }

            val client = mClient.getAndSet(null)
            if (client != null) {
                try {
                    client.disconnect()
                } catch (ignored: IOException) {
                }
            }

            mConnected.set(false)
        }

        override fun sendStatus(isGrabbing: Boolean) {
            mCallback.onReceiveStatus(isGrabbing)
        }
    }

    val receiver: HyperionThreadListener
        get() = mListener

    /**
     * Сбрасывает блокировку отправки данных для WLED клиента.
     * Вызывается при включении экрана, чтобы возобновить отправку после ошибки EPERM.
     */
    fun resetBlockedIfWLED() {
        val client = mClient.get()
        if (client is WLEDClient) {
            client.resetBlocked()
        }
    }

    override fun run() {
        connect()
    }

    private fun connect() {
        do {
            try {
                val client = createClient()

                if (client != null && client.isConnected()) {
                    mClient.set(client)
                    mConnected.set(true)
                    // Логируем запуск конкретного протокола
                    AnalyticsHelper.logProtocolStarted(mContext, mConnectionType)
                    mCallback.onConnected()
                    Log.i(TAG, "Connected to $mConnectionType at $mHost:$mPort")
                    return
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: " + e.message)
                mCallback.onConnectionError(e.hashCode(), e.message)
                if (mReconnectEnabled.get() && mConnected.get()) {
                    sleepSafe(mReconnectDelayMs)
                }
            }
        } while (mReconnectEnabled.get() && mConnected.get())
    }

    @Throws(IOException::class)
    private fun createClient(): HyperionClient? {
        val host = mHost ?: "localhost"
        return if ("wled".equals(mConnectionType, ignoreCase = true)) {
            // WLEDClient (context, host, port, priority, colorOrder, smoothingEnabled, smoothingPreset, settlingTime, outputDelayMs, updateFrequency)
            WLEDClient(mContext, host, mPort, mPriority, mWledColorOrder,
                mSmoothingEnabled, mSmoothingPreset, mSettlingTime, mOutputDelayMs, mUpdateFrequency)
        } else if ("adalight".equals(mConnectionType, ignoreCase = true)) {
            // AdalightClient (context, priority, baudrate, protocol, smoothingEnabled, smoothingPreset, settlingTime, outputDelayMs, updateFrequency)
            AdalightClient(mContext, mPriority, mBaudRate, mAdalightProtocol,
                mSmoothingEnabled, mSmoothingPreset, mSettlingTime, mOutputDelayMs, mUpdateFrequency)
        } else {
            // Default to Hyperion
            HyperionFlatBuffers(host, mPort, mPriority)
        }
    }

    private fun handleError(e: IOException) {
        mCallback.onConnectionError(e.hashCode(), e.message)

        if (mReconnectEnabled.get() && mConnected.get()) {
            sleepSafe(mReconnectDelayMs)
            try {
                val newClient = createClient()

                if (newClient != null && newClient.isConnected()) {
                    mClient.set(newClient)
                }
            } catch (ignored: IOException) {
            }
        }
    }

    private fun sleepSafe(ms: Long) {
        try {
            sleep(ms)
        } catch (e: InterruptedException) {
            mReconnectEnabled.set(false)
            mConnected.set(false)
            currentThread().interrupt()
        }
    }

    interface HyperionThreadListener {
        fun sendFrame(data: ByteArray, width: Int, height: Int)
        fun clear()
        fun disconnect()
        fun sendStatus(isGrabbing: Boolean)
    }

    companion object {
        private const val TAG = "HyperionThread"
        private const val FRAME_DURATION = -1
        private const val SHUTDOWN_TIMEOUT_MS = 100

        @JvmStatic
        fun fromPreferences(
            callback: ScreenGrabberService.HyperionThreadBroadcaster,
            context: Context
        ): HyperionThread {
            val prefs = Preferences(context)

            val host = prefs.getString(R.string.pref_key_host, "") ?: ""
            val port = prefs.getInt(R.string.pref_key_port, 19400)
            val priority = prefs.getInt(R.string.pref_key_priority, 100)
            val reconnect = prefs.getBoolean(R.string.pref_key_reconnect, true)
            val reconnectDelay = prefs.getInt(R.string.pref_key_reconnect_delay, 5)
            val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion")
            val baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate, 115200)
            val wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb")

            val wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
            val wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false)
            val wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255)

            val adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada") ?: "ada"

            val smoothingEnabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, true)
            val smoothingPreset = prefs.getString(R.string.pref_key_smoothing_preset, "balanced") ?: "balanced"
            val settlingTime = prefs.getInt(R.string.pref_key_settling_time, 200)
            val outputDelayMs = prefs.getInt(R.string.pref_key_output_delay, 80).toLong() // Теперь в миллисекундах
            val updateFrequency = prefs.getInt(R.string.pref_key_update_frequency, 25)

            return HyperionThread(
                callback, host, port, priority, reconnect, reconnectDelay,
                connectionType, context, baudRate, wledColorOrder,
                wledProtocol, wledRgbw, wledBrightness, adalightProtocol,
                smoothingEnabled, smoothingPreset, settlingTime, outputDelayMs, updateFrequency
            )
        }
    }
}
