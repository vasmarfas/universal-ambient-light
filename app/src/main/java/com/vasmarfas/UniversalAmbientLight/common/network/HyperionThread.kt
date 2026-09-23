package com.vasmarfas.UniversalAmbientLight.common.network

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HyperionThread(
    private val mCallback: ScreenGrabberService.HyperionThreadBroadcaster,
    private val mContext: Context,
    config: ConnectionConfig,
) : Thread(TAG) {

    // Home Assistant настроек много — клиент собирается прямо из конфига
    private val mConfig: ConnectionConfig = config
    private val mHost: String = config.host
    private val mPort: Int = config.port
    private val mPriority: Int = config.priority
    private val mBaudRate: Int = config.baudRate
    private val mWledProtocol: String = config.wledProtocol
    private val mWledRgbw: Boolean = config.wledRgbw
    private val mWledBrightness: Int = config.wledBrightness
    private val mAdalightProtocol: String = config.adalightProtocol
    private val mSmoothingEnabled: Boolean = config.smoothingEnabled
    private val mSmoothingPreset: String = config.smoothingPreset
    private val mSettlingTime: Int = config.settlingTime
    private val mOutputDelayMs: Long = config.outputDelayMs
    private val mUpdateFrequency: Int = config.updateFrequency

    private val mReconnectDelayMs: Long = (config.reconnectDelaySeconds * 1000).toLong()
    private val mConnectionType: String = config.connectionType
    private val mWledColorOrder: String = config.wledColorOrder
    private val mReconnectEnabled = AtomicBoolean(config.reconnect)
    private val mConnected = AtomicBoolean(false)
    private val mStandbyPaused = AtomicBoolean(false)
    private val mClient = AtomicReference<HyperionClient?>()
    private val mExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var mPendingTask: Future<*>? = null

    @Volatile
    private var mPendingFrame: FrameData? = null

    @Volatile
    private var mLastSentFrame: FrameData? = null
    private val mSendLock = Any()
    private val mKeepAliveExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private val mRecovering = AtomicBoolean(false)

    @Volatile
    private var mLastRecoveryAttemptMs = 0L

    private class FrameData(val data: ByteArray, val width: Int, val height: Int)

    // Кольцо из трёх буферов под входящие кадры: энкодеры переиспользуют свой массив и
    // перезапишут его следующим кадром раньше, чем executor дослал текущий, — без копии
    // клиенту достаётся рваный кадр. Три слота, потому что в полёте может быть кадр в
    // отправке плюс отложенный, и писать надо в третий.
    private val mFrameRing = arrayOfNulls<ByteArray>(3)
    private var mFrameRingIndex = 0

    // Слот кольца переживает и три кадра, если отправка застряла на mSendLock (keepalive
    // висит в записи в сокет) — перед отправкой кадр перекладывается в собственный буфер,
    // который поток захвата не трогает
    private var mSendBuffer: ByteArray? = null

    // Кадр под повторы keepalive. mSendBuffer для этого не годится: его перезаписывают до
    // входа в замок. Буфер переиспользуется — свежая копия на каждом кадре при 1080p это
    // 6 МБ мусора за кадр, и ТВ-боксы со 128 МБ кучи не переживали такой темп (OOM 1.4.3).
    private var mKeepAliveBuffer: ByteArray? = null

    private val mListener = object : HyperionThreadListener {
        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            if (mStandbyPaused.get()) return
            val client = mClient.get()
            if (client == null || !client.isConnected()) {
                // Клиент умирает молча: у Adalight после standby ТВ хост переоткрывает USB,
                // ошибка записи гасит isConnected без исключения наружу, и без этой ветки
                // лента не оживала бы до ручного перезапуска (issue #41). Приходящие кадры
                // и есть сигнал, что захват жив и соединение пора поднимать заново.
                scheduleClientRecovery()
                return
            }
            if (mExecutor.isShutdown) return

            // sendFrame зовёт единственный поток захвата активного энкодера, поэтому
            // кольцо не нуждается в блокировке
            mFrameRingIndex = (mFrameRingIndex + 1) % mFrameRing.size
            var slot = mFrameRing[mFrameRingIndex]
            if (slot == null || slot.size != data.size) {
                slot = ByteArray(data.size)
                mFrameRing[mFrameRingIndex] = slot
            }
            System.arraycopy(data, 0, slot, 0, data.size)

            mPendingFrame = FrameData(slot, width, height)
            val pending = mPendingTask
            if (pending != null && !pending.isDone) {
                pending.cancel(false)
            }
            try {
                mPendingTask = mExecutor.submit { sendPendingFrame() }
            } catch (_: RejectedExecutionException) {
                // Executor успели остановить между проверкой isShutdown и submit (гонка при отключении).
            }
        }

        private fun sendPendingFrame() {
            if (mStandbyPaused.get()) return
            val frame = mPendingFrame
            val client = mClient.get()

            if (frame == null || client == null || !client.isConnected()) return

            // Снимок до входа в замок: пока эта задача ждёт mSendLock, поток захвата может
            // обернуть кольцо и переписать слот кадра
            var buffer = mSendBuffer
            if (buffer == null || buffer.size != frame.data.size) {
                buffer = ByteArray(frame.data.size)
                mSendBuffer = buffer
            }
            System.arraycopy(frame.data, 0, buffer, 0, frame.data.size)

            try {
                synchronized(mSendLock) {
                    client.setImage(
                        buffer,
                        frame.width,
                        frame.height,
                        mPriority,
                        FRAME_DURATION
                    )

                    if (client is HyperionFlatBuffers) {
                        // Стабильная копия для повторов keepalive. Нужна только Hyperion:
                        // у WLED, Adalight и Home Assistant свой keepalive, кадр им не нужен
                        var keepAlive = mKeepAliveBuffer
                        if (keepAlive == null || keepAlive.size != buffer.size) {
                            keepAlive = ByteArray(buffer.size)
                            mKeepAliveBuffer = keepAlive
                        }
                        System.arraycopy(buffer, 0, keepAlive, 0, buffer.size)
                        mLastSentFrame = FrameData(keepAlive, frame.width, frame.height)

                        // Под тем же замком, что и keepalive: два читателя одного сокета
                        // поделили бы заголовок ответа и рассинхронизировали поток
                        client.cleanReplies()
                    }
                }
            } catch (e: IOException) {
                handleError(e)
            }
        }

        override fun clear() {
            val client = mClient.get()
            if (client != null && client.isConnected()) {
                try {
                    // Под тем же замком, что и setImage: у Hyperion это один TCP-поток, и
                    // clear() из потока гашения вперемешку с кадром из executor'а дал бы
                    // битый length-prefixed поток и разрыв соединения
                    synchronized(mSendLock) {
                        client.clear(mPriority)
                        // Иначе keepalive через секунду заново зажёг бы ленту последним
                        // кадром — и она горела бы стоп-кадром весь простой
                        mLastSentFrame = null
                    }
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
            mLastSentFrame = null

            if (!mKeepAliveExecutor.isShutdown) {
                mKeepAliveExecutor.shutdownNow()
            }

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

    /**
     * Полностью останавливает отправку данных на время сна ТВ (экран выключен).
     * Соединение с устройством сохраняется, чтобы возобновление было мгновенным.
     */
    fun pauseSending() {
        mStandbyPaused.set(true)
        when (val client = mClient.get()) {
            is WLEDClient -> client.pauseSending()
            is AdalightClient -> client.pauseSending()
            is HomeAssistantClient -> client.pauseSending()
            else -> {}
        }
    }

    /**
     * Возобновляет отправку данных после включения экрана.
     */
    fun resumeSending() {
        mStandbyPaused.set(false)
        when (val client = mClient.get()) {
            is WLEDClient -> client.resumeSending()
            is AdalightClient -> client.resumeSending()
            is HomeAssistantClient -> client.resumeSending()
            else -> {}
        }
    }

    override fun run() {
        startKeepAlive()
        connect()
    }

    private fun startKeepAlive() {
        mKeepAliveExecutor.scheduleWithFixedDelay({
            if (mStandbyPaused.get()) return@scheduleWithFixedDelay
            val client = mClient.get() ?: return@scheduleWithFixedDelay
            if (!client.isConnected()) return@scheduleWithFixedDelay
            // У WLED и Adalight своя логика keepalive.
            if (client !is HyperionFlatBuffers) return@scheduleWithFixedDelay

            val last = mLastSentFrame ?: return@scheduleWithFixedDelay
            try {
                synchronized(mSendLock) {
                    client.setImage(last.data, last.width, last.height, mPriority, FRAME_DURATION)
                }
                client.cleanReplies()
            } catch (e: IOException) {
                handleError(e)
            }
        }, KEEPALIVE_PERIOD_MS, KEEPALIVE_PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    private fun connect() {
        while (!isInterrupted) {
            try {
                val client = createClient()
                if (client != null && client.isConnected()) {
                    mClient.set(client)
                    mConnected.set(true)
                    AnalyticsHelper.logProtocolStarted(mContext, mConnectionType)
                    mCallback.onConnected()
                    Log.i(TAG, "Connected to $mConnectionType at $mHost:$mPort")
                    return
                }
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed: " + e.message)
                mCallback.onConnectionError(e.hashCode(), e.message)
            }
            if (!mReconnectEnabled.get()) return
            sleepSafe(mReconnectDelayMs)
        }
    }

    @Throws(IOException::class)
    private fun createClient(): HyperionClient? {
        // Порт должен попадать в диапазон 1-65535
        if (mPort < 1 || mPort > 65535) {
            throw IOException("Port out of range: $mPort (must be between 1 and 65535)")
        }

        val host = mHost
        return if ("wled".equals(mConnectionType, ignoreCase = true)) {
            WLEDClient(
                mContext,
                host,
                mPort,
                mPriority,
                mWledColorOrder,
                mWledProtocol,
                mSmoothingEnabled,
                mSmoothingPreset,
                mSettlingTime,
                mOutputDelayMs,
                mUpdateFrequency,
                mWledRgbw,
                mWledBrightness
            )
        } else if ("adalight".equals(mConnectionType, ignoreCase = true)) {
            AdalightClient(
                mContext, mPriority, mBaudRate, mAdalightProtocol,
                mSmoothingEnabled, mSmoothingPreset, mSettlingTime, mOutputDelayMs, mUpdateFrequency
            )
        } else if ("homeassistant".equals(mConnectionType, ignoreCase = true)) {
            HomeAssistantClient(
                host,
                mPort,
                mConfig.haToken,
                mConfig.haLamps,
                mConfig.haUpdateIntervalMs,
                mConfig.haChangeThreshold,
                mConfig.haTransitionMs,
                mConfig.haBrightnessMode,
                mConfig.haBrightnessMax,
                mConfig.haDarkOffEnabled,
                mConfig.haDarkThreshold,
                mConfig.haTurnOffLights
            )
        } else {
            // По умолчанию — Hyperion
            HyperionFlatBuffers(host, mPort, mPriority)
        }
    }

    private fun handleError(e: IOException) {
        mCallback.onConnectionError(e.hashCode(), e.message)

        // Не пересоздавать клиент во сне: для Adalight новое открытие порта сбрасывает Arduino.
        if (mReconnectEnabled.get() && mConnected.get() && !mStandbyPaused.get()) {
            sleepSafe(mReconnectDelayMs)
            // Сон прерван — это shutdownNow при остановке, пересоздавать клиент уже нельзя
            if (!mReconnectEnabled.get()) return
            try {
                val newClient = createClient()

                if (newClient != null && newClient.isConnected()) {
                    installRecoveredClient(newClient)
                }
            } catch (ignored: IOException) {
            }
        }
    }

    /**
     * Ставит пересозданный клиент на место умершего. Если за время подключения сервис
     * успели остановить (disconnect() гасит executor), свежий клиент не подключается,
     * а закрывается — иначе он пережил бы остановку и держал порт или сокет до конца
     * процесса.
     */
    private fun installRecoveredClient(newClient: HyperionClient) {
        if (mExecutor.isShutdown) {
            try {
                newClient.disconnect()
            } catch (ignored: IOException) {
            }
            return
        }
        val old = mClient.getAndSet(newClient)
        if (old != null && old !== newClient) {
            // Сокет прежнего клиента иначе остаётся открытым до конца процесса
            try {
                old.disconnect()
            } catch (ignored: IOException) {
            }
        }
        if (mExecutor.isShutdown) {
            // Остановка пришла между проверкой и установкой — забираем клиент обратно
            val stale = mClient.getAndSet(null)
            if (stale != null) {
                try {
                    stale.disconnect()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    /**
     * Пересоздаёт клиент после молчаливой смерти соединения. Попытки идут не чаще
     * mReconnectDelayMs и только после первого успешного подключения — гонку с начальным
     * циклом connect() отсекает mConnected. Отдельный поток, потому что открытие порта
     * или сокета блокирует, а зовут нас из потока захвата.
     */
    private fun scheduleClientRecovery() {
        if (!mReconnectEnabled.get() || !mConnected.get() || mStandbyPaused.get()) return
        if (mExecutor.isShutdown) return
        if (System.currentTimeMillis() - mLastRecoveryAttemptMs < mReconnectDelayMs) return
        if (!mRecovering.compareAndSet(false, true)) return
        Thread({
            try {
                mLastRecoveryAttemptMs = System.currentTimeMillis()
                val stale = mClient.get()
                if (stale != null && stale.isConnected()) return@Thread
                if (stale != null) {
                    // Мёртвый Adalight-клиент держит USB-устройство занятым — освобождаем
                    // до открытия нового
                    try {
                        stale.disconnect()
                    } catch (ignored: IOException) {
                    }
                }
                try {
                    val newClient = createClient()
                    if (newClient != null && newClient.isConnected()) {
                        installRecoveredClient(newClient)
                        if (mClient.get() === newClient) {
                            Log.i(TAG, "Recovered $mConnectionType connection")
                            mCallback.onConnected()
                        }
                    }
                } catch (e: IOException) {
                    mCallback.onConnectionError(e.hashCode(), e.message)
                }
            } finally {
                mRecovering.set(false)
            }
        }, "$TAG-recovery").apply { isDaemon = true }.start()
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
        private const val KEEPALIVE_PERIOD_MS = 1000L
    }
}

