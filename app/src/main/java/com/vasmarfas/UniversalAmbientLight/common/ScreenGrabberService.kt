package com.vasmarfas.UniversalAmbientLight.common

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.app.ServiceCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.ConnectionConfig
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantClient
import com.vasmarfas.UniversalAmbientLight.common.network.HomeAssistantLamp
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.LedLayout
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import java.util.Objects

/**
 * Отдаёт кадры сразу двум приёмникам — основному подключению и дополнительному выводу на
 * Home Assistant. Энкодер знает только про один listener, поэтому композиция подменяет его.
 */
private class DualHyperionThreadListener(
    private val mPrimary: HyperionThread.HyperionThreadListener,
    private val mSecondary: HyperionThread.HyperionThreadListener?,
) : HyperionThread.HyperionThreadListener {
    override fun sendFrame(data: ByteArray, width: Int, height: Int) {
        mPrimary.sendFrame(data, width, height)
        mSecondary?.sendFrame(data, width, height)
    }

    override fun clear() {
        mPrimary.clear()
        mSecondary?.clear()
    }

    override fun disconnect() {
        mPrimary.disconnect()
        mSecondary?.disconnect()
    }

    override fun sendStatus(isGrabbing: Boolean) {
        mPrimary.sendStatus(isGrabbing)
    }
}

class ScreenGrabberService : Service() {

    private var mForegroundFailed = false
    private var mForegroundStarted = false
    private var mTclBlocked = false
    private var mHandler: Handler? = null
    private var mStandby: StandbyController? = null

    private var mReconnectEnabled = false
    private var mHasConnected = false
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mHyperionThread: HyperionThread? = null

    // Дополнительный вывод на Home Assistant — работает параллельно с основным
    // подключением (например, основной вывод на WLED, а сбоку ещё лампы HA)
    private var mSecondaryHyperionThread: HyperionThread? = null
    private var mFrameRate: Int = 0
    private var mCaptureQuality: Int = 0
    private var mHorizontalLEDCount: Int = 0
    private var mVerticalLEDCount: Int = 0
    private var mSendAverageColor: Boolean = false
    // Одновременно работает ровно один способ захвата: его выбирают startScreenRecord,
    // startAlternativeRecord и startCameraCapture по настройкам и доступности на прошивке.
    private var mActiveBackend: CaptureBackend? = null
    private var mCaptureSource: String = "screen" // "screen" or "camera"
    private var mNotificationManager: NotificationManager? = null
    private var mStartError: String? = null
    private var mConnectionType = "hyperion"
    private var mProjectionResultCode: Int? = null
    private var mProjectionDataExtras: android.os.Bundle? = null

    // Хранит AppOptions, отданные активному энкодеру: правки цветовых настроек по ходу
    // захвата подставляются сюда, не перезапуская сессию.
    @Volatile
    private var mActiveOptions: AppOptions? = null
    private var mPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null

    // Куда энкодер отдаёт кадры. Потоки вывода пересоздаются под тем же энкодером, когда
    // меняют адрес, протокол или сглаживание, — захват, а с ним и согласие MediaProjection,
    // при этом не трогается
    @Volatile
    private var mOutput: HyperionThread.HyperionThreadListener? = null
    private var mGate: OutputGate? = null

    // Правки настроек во время захвата копятся и применяются разом: пресет сглаживания или
    // ползунок пишут несколько ключей подряд
    private var mPendingOutputRestart = false
    private var mPendingCaptureRestart = false
    private var mPendingSessionRestart = false
    private val mApplySettings = Runnable { applyPendingSettings() }

    /**
     * Выход одного энкодера. После отцепления (перезапуск захвата с новыми настройками)
     * старый энкодер больше ничего не шлёт: его прощальные чёрные кадры и disconnect
     * погасили бы уже работающий новый вывод.
     */
    private inner class OutputGate : HyperionThread.HyperionThreadListener {
        @Volatile
        var attached = true

        override fun sendFrame(data: ByteArray, width: Int, height: Int) {
            if (attached) mOutput?.sendFrame(data, width, height)
        }

        override fun clear() {
            if (attached) mOutput?.clear()
        }

        override fun disconnect() {
            if (attached) mOutput?.disconnect()
        }

        override fun sendStatus(isGrabbing: Boolean) {
            if (attached) mOutput?.sendStatus(isGrabbing)
        }
    }

    private val mReceiver = object : HyperionThreadBroadcaster {
        override fun onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to Hyperion server")
            mHasConnected = true
            val prefs = Preferences(baseContext)
            val host = prefs.getString(R.string.pref_key_host, null)
            AnalyticsHelper.logConnectionSuccess(baseContext, mConnectionType, host)
            notifyActivity()
            // Сервис мог быть запущен при уже погашенном экране (например, переподключение USB в простое)
            maybeStandbyPauseOnConnect()
        }

        /** Гасим вывод, только если экран действительно погашен и keepalive выключен. */
        private fun maybeStandbyPauseOnConnect() {
            if (mCaptureSource == "camera") return
            if (Preferences(this@ScreenGrabberService).getBoolean(R.string.pref_key_standby_keepalive)) return
            val standby = mStandby ?: return
            if (!standby.isScreenOff()) return
            standby.schedulePause()
        }

        override fun onConnectionError(errorID: Int, error: String?) {
            Log.e(TAG, "Connection error: " + (error ?: "unknown"))
            AnalyticsHelper.logConnectionError(baseContext, mConnectionType, error)
            if (!mHasConnected) {
                mStartError = connectionErrorText(
                    R.string.error_adalight_unreachable,
                    R.string.error_server_unreachable
                )
                haltStartup()
            } else if (mReconnectEnabled) {
                Log.i(TAG, "Attempting automatic reconnect...")
            } else {
                mStartError = connectionErrorText(
                    R.string.error_adalight_connection_lost,
                    R.string.error_connection_lost
                )
                stopSelf()
            }
        }

        /** У Adalight своя формулировка ошибки: там нет ни адреса, ни сервера. */
        private fun connectionErrorText(
            @StringRes adalight: Int,
            @StringRes network: Int,
        ): String {
            val isAdalight = "adalight".equals(mConnectionType, ignoreCase = true)
            return resources.getString(if (isAdalight) adalight else network)
        }

        override fun onReceiveStatus(isCapturing: Boolean) {
            if (DEBUG) Log.v(TAG, "Received status: capturing=$isCapturing")
            notifyActivity()
        }
    }

    /**
     * Дополнительное подключение — необязательная надстройка над основным выводом, поэтому
     * его сбои не должны валить сервис и основной канал: только лог, без mStartError и
     * haltStartup(). HyperionThread сам продолжит переподключаться на общих основаниях.
     */
    private val mSecondaryReceiver = object : HyperionThreadBroadcaster {
        override fun onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to the additional Home Assistant output")
        }

        override fun onConnectionError(errorID: Int, error: String?) {
            Log.w(TAG, "Additional Home Assistant output error: " + (error ?: "unknown"))
        }

        override fun onReceiveStatus(isCapturing: Boolean) {}
    }

    private val mEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (Objects.requireNonNull(intent.action)) {
                Intent.ACTION_SCREEN_ON -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_ON intent received")
                    mStandby?.releaseWakeLock()
                    mStandby?.releaseWifiLock()

                    // Возобновляем вывод, если он был приглушён на время простоя
                    mStandby?.cancelPause()
                    mHyperionThread?.resumeSending()
                    mSecondaryHyperionThread?.resumeSending()

                    // Снимаем блокировку отправки WLED после ошибки EPERM, чтобы продолжить при пробуждении
                    mHyperionThread?.resetBlockedIfWLED()

                    if (!isCapturing) {
                        val backend = mActiveBackend
                        if (backend != null) {
                            if (DEBUG) Log.v(TAG, "Resuming ${backend.javaClass.simpleName}")
                            backend.resumeRecording()
                            // После onStop от системы (сон ТВ) у ScreenEncoder уже нет ни
                            // ImageReader, ни потока захвата — resumeRecording() no-op, и
                            // единственный путь оживления — пересоздание из сохранённой
                            // проекции.
                            if (!isCapturing && backend is ScreenEncoder) {
                                restartEncoderFromSavedProjection()
                            }
                        } else if (mCaptureSource != "camera") {
                            // Если MediaProjection остановила система (уход в сон), resumeRecording() не спасёт —
                            // пересоздаём энкодер из сохранённых данных проекции.
                            if (DEBUG) Log.v(
                                TAG,
                                "No encoder active, trying restartEncoderFromSavedProjection"
                            )
                            restartEncoderFromSavedProjection()
                        }
                    }
                    notifyActivity()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_OFF intent received")
                    // Камера снимает внешний телевизор, сон экрана устройства ей безразличен — работаем как работали.
                    val isCamera = mCaptureSource == "camera"
                    val standbyKeepalive =
                        Preferences(context).getBoolean(R.string.pref_key_standby_keepalive)
                    if (standbyKeepalive || isCamera) {
                        // На части телевизоров процессор уходит в глубокий сон, keepalive-потоки перестают слать
                        // пакеты, и через ~10 секунд WLED возвращается к своему эффекту. PARTIAL_WAKE_LOCK этому мешает.
                        mStandby?.acquireWakeLock()
                        mStandby?.acquireWifiLock()
                    }
                    // Камера снимает внешний телевизор, её кадры не зависят от экрана
                    // устройства — гасим ленту только для экранных способов захвата.
                    if (mActiveBackend !is CameraEncoder) mActiveBackend?.clearLights()
                    if (!standbyKeepalive && !isCamera) {
                        // Keepalive в простое выключен: даём чёрным кадрам уйти и молчим до SCREEN_ON
                        mStandby?.schedulePause()
                    }
                }

                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received")
                    mActiveBackend?.setOrientation(resources.configuration.orientation)
                }

                Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received")
                    stopAllCapture()
                }
            }
        }
    }

    override fun onCreate() {
        sInstanceRunning = true
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        val handler = Handler(Looper.getMainLooper())
        mHandler = handler
        mStandby = StandbyController(this, handler) {
            if (DEBUG) Log.v(TAG, "Standby: pausing all LED output")
            mHyperionThread?.pauseSending()
            mSecondaryHyperionThread?.pauseSending()
        }

        // На запуске пробуем shell-обход для устройств TCL
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.i(TAG, "Detected restricted manufacturer, attempting shell bypass")
            TclBypass.tryShellBypass(this)
        }

        AutoStart.scheduleWatchdog(this)

        super.onCreate()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun prepared(): Boolean {
        val prefs = Preferences(baseContext)
        mConnectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        validateSettings(baseContext)?.let { error ->
            mStartError = error.message
            AnalyticsHelper.logServiceError(baseContext, error.code, error.details)
            return false
        }

        val host = prefs.getString(R.string.pref_key_host, null)?.trim()
        val port = prefs.getInt(R.string.pref_key_port, -1)
        val priority =
            prefs.getString(R.string.pref_key_priority, "100")?.takeIf { it.isNotBlank() } ?: "100"
        mFrameRate = prefs.getInt(R.string.pref_key_framerate)

        try {
            val captureQualityStr = prefs.getString(R.string.pref_key_capture_quality, "128")
                ?.takeIf { it.isNotBlank() } ?: "128"
            mCaptureQuality = Integer.parseInt(captureQualityStr)
        } catch (e: NumberFormatException) {
            mCaptureQuality = 128
        }

        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led)
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led)
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color)
        mReconnectEnabled = prefs.getBoolean(R.string.pref_key_reconnect)
        val delay = prefs.getInt(R.string.pref_key_reconnect_delay)
        val baudRate = prefs.getInt(R.string.pref_key_adalight_baudrate)
        val wledColorOrder = prefs.getString(R.string.pref_key_wled_color_order, "rgb")

        val wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
        val wledRgbw = prefs.getBoolean(R.string.pref_key_wled_rgbw, false)
        val wledBrightness = prefs.getInt(R.string.pref_key_wled_brightness, 255)

        val adalightProtocol = prefs.getString(R.string.pref_key_adalight_protocol, "ada") ?: "ada"

        val smoothingEnabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, false)
        val smoothingPreset = prefs.getString(R.string.pref_key_smoothing_preset, "off") ?: "off"
        val settlingTime = prefs.getInt(R.string.pref_key_settling_time, 50)
        val outputDelayMs = prefs.getInt(R.string.pref_key_output_delay, 0).toLong()
        val updateFrequency = prefs.getInt(R.string.pref_key_update_frequency, 60)

        val haToken = prefs.getString(R.string.pref_key_ha_token, "") ?: ""
        val haLamps = prefs.getString(R.string.pref_key_ha_lamps, "") ?: ""
        val haUpdateIntervalMs = prefs.getInt(R.string.pref_key_ha_update_interval, 500)
            .coerceAtLeast(100).toLong()
        val haChangeThreshold = prefs.getInt(R.string.pref_key_ha_change_threshold, 10)
            .coerceIn(0, 255)
        val haTransitionMs = prefs.getInt(R.string.pref_key_ha_transition, 300)
            .coerceIn(0, 10_000)
        val haBrightnessMode = prefs.getString(
            R.string.pref_key_ha_brightness_mode,
            HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
        ) ?: HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
        val haBrightnessMax = prefs.getInt(R.string.pref_key_ha_brightness, 255).coerceIn(1, 255)
        val haDarkOffEnabled = prefs.getBoolean(R.string.pref_key_ha_dark_off, true)
        val haDarkThreshold = prefs.getInt(R.string.pref_key_ha_dark_threshold, 10)
            .coerceIn(1, 255)
        val haTurnOffLights = prefs.getBoolean(R.string.pref_key_ha_turn_off_lights, true)

        val ha2Enabled = prefs.getBoolean(R.string.pref_key_ha2_enabled, false)
        val ha2Host = prefs.getString(R.string.pref_key_ha2_host, "")?.trim() ?: ""
        val ha2Port = prefs.getInt(R.string.pref_key_ha2_port, 8123)
        val ha2Token = prefs.getString(R.string.pref_key_ha2_token, "") ?: ""
        val ha2Lamps = prefs.getString(R.string.pref_key_ha2_lamps, "") ?: ""
        val ha2UpdateIntervalMs = prefs.getInt(R.string.pref_key_ha2_update_interval, 500)
            .coerceAtLeast(100).toLong()
        val ha2ChangeThreshold = prefs.getInt(R.string.pref_key_ha2_change_threshold, 10)
            .coerceIn(0, 255)
        val ha2TransitionMs = prefs.getInt(R.string.pref_key_ha2_transition, 300)
            .coerceIn(0, 10_000)
        val ha2BrightnessMode = prefs.getString(
            R.string.pref_key_ha2_brightness_mode,
            HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
        ) ?: HomeAssistantClient.BRIGHTNESS_MODE_SCREEN
        val ha2BrightnessMax = prefs.getInt(R.string.pref_key_ha2_brightness, 255).coerceIn(1, 255)
        val ha2DarkOffEnabled = prefs.getBoolean(R.string.pref_key_ha2_dark_off, true)
        val ha2DarkThreshold = prefs.getInt(R.string.pref_key_ha2_dark_threshold, 10)
            .coerceIn(1, 255)
        val ha2TurnOffLights = prefs.getBoolean(R.string.pref_key_ha2_turn_off_lights, true)

        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
        if (mMediaProjectionManager == null && method == "media_projection") {
            mStartError = resources.getString(R.string.error_media_projection_denied)
            AnalyticsHelper.logServiceError(baseContext, "media_projection_manager_null", null)
            return false
        }
        // Безопасный парсинг приоритета (на случай пустой или некорректной строки)
        val priorityValue = try {
            priority.toInt()
        } catch (e: NumberFormatException) {
            100
        }

        val finalHost = host ?: "localhost"
        val finalPort = if (port > 0) port else 19400

        val config = ConnectionConfig(
            host = finalHost,
            port = finalPort,
            priority = priorityValue,
            reconnect = mReconnectEnabled,
            reconnectDelaySeconds = delay,
            connectionType = mConnectionType,
            baudRate = baudRate,
            wledColorOrder = wledColorOrder ?: "rgb",
            wledProtocol = wledProtocol,
            wledRgbw = wledRgbw,
            wledBrightness = wledBrightness,
            adalightProtocol = adalightProtocol,
            smoothingEnabled = smoothingEnabled,
            smoothingPreset = smoothingPreset,
            settlingTime = settlingTime,
            outputDelayMs = outputDelayMs,
            updateFrequency = updateFrequency,
            haToken = haToken,
            haLamps = haLamps,
            haUpdateIntervalMs = haUpdateIntervalMs,
            haChangeThreshold = haChangeThreshold,
            haTransitionMs = haTransitionMs,
            haBrightnessMode = haBrightnessMode,
            haBrightnessMax = haBrightnessMax,
            haDarkOffEnabled = haDarkOffEnabled,
            haDarkThreshold = haDarkThreshold,
            haTurnOffLights = haTurnOffLights
        )
        val thread = HyperionThread(mReceiver, baseContext, config)
        mHyperionThread = thread
        thread.start()

        // Дополнительный вывод — необязательная надстройка: недозаполненные настройки просто
        // пропускают его, не роняя уже работающий основной канал
        if (ha2Enabled && ha2Token.isNotBlank() && ha2Host.isNotBlank() &&
            ha2Port in 1..65535 && HomeAssistantLamp.parseList(ha2Lamps).isNotEmpty()
        ) {
            val secondaryConfig = config.copy(
                host = ha2Host,
                port = ha2Port,
                connectionType = "homeassistant",
                haToken = ha2Token,
                haLamps = ha2Lamps,
                haUpdateIntervalMs = ha2UpdateIntervalMs,
                haChangeThreshold = ha2ChangeThreshold,
                haTransitionMs = ha2TransitionMs,
                haBrightnessMode = ha2BrightnessMode,
                haBrightnessMax = ha2BrightnessMax,
                haDarkOffEnabled = ha2DarkOffEnabled,
                haDarkThreshold = ha2DarkThreshold,
                haTurnOffLights = ha2TurnOffLights
            )
            val secondaryThread = HyperionThread(mSecondaryReceiver, baseContext, secondaryConfig)
            mSecondaryHyperionThread = secondaryThread
            secondaryThread.start()
        } else if (ha2Enabled) {
            Log.w(TAG, "Additional Home Assistant output enabled but not fully configured, skipping")
        }
        mOutput = DualHyperionThreadListener(thread.receiver, mSecondaryHyperionThread?.receiver)

        mStartError = null
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.v(TAG, "Start command received")

        // startForeground обязан быть вызван в пределах ~5 секунд после каждого
        // startForegroundService, иначе Android бросит ForegroundServiceDidNotStartInTime.
        ensureForegroundStarted(initialForegroundTypeFor(intent?.action))

        super.onStartCommand(intent, flags, startId)
        if (intent == null || intent.action == null) {
            val nullItem = if (intent == null) "intent" else "action"
            if (DEBUG) Log.v(TAG, "Null $nullItem provided to start command")
            // START_STICKY: система подняла сервис заново после гибели процесса (частая история
            // у ТВ, выгружающих приложения на время сна). Подсветка работала — возвращаем её
            if (intent == null && mHyperionThread == null && AutoStart.shouldResume(this)) {
                val prefs = Preferences(this)
                val source = prefs.getString(R.string.pref_key_capture_source, "screen")
                val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
                val adalight = "adalight".equals(
                    prefs.getString(R.string.pref_key_connection_type, "hyperion"),
                    ignoreCase = true
                )
                if (source == "screen" && method != "media_projection" && !adalight) {
                    Log.i(TAG, "Restarted by the system after the process died, resuming capture")
                    return onStartCommand(Intent(this, javaClass).setAction(ACTION_START), flags, startId)
                }
                // Остальным путям нужен диалог (согласие на запись экрана, разрешения на USB и
                // камеру) — их поднимает CaptureLauncher, когда этот экземпляр уже остановлен
                val app = applicationContext
                mHandler?.postDelayed({ AutoStart.resume(app) }, SESSION_RESTART_DELAY_MS)
            }
            stopSelf()
            return START_NOT_STICKY
        } else {
            val action = intent.action
            if (DEBUG) Log.v(TAG, "Start command action: " + action.toString())
            when (action) {
                ACTION_START -> if (mHyperionThread == null) {
                    mCaptureSource = "screen"
                    val prefs = Preferences(this)
                    val method =
                        prefs.getString(R.string.pref_key_capture_method, "media_projection")
                            ?: "media_projection"

                    val useMediaProjection = method == "media_projection"
                    val foregroundType = if (useMediaProjection)
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

                    val foregroundStarted = tryStartForegroundCompat(foregroundType)

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            mStandby?.acquireWakeLock()
                        }

                        if (useMediaProjection) {
                            try {
                                startScreenRecord(intent)
                            } catch (e: SecurityException) {
                                Log.e(TAG, "Failed to start screen recording: " + e.message)
                                mStartError =
                                    resources.getString(R.string.error_media_projection_denied)
                                AnalyticsHelper.logServiceError(
                                    baseContext,
                                    "security_exception",
                                    e.message
                                )
                                haltStartup()
                                return START_STICKY
                            }
                        } else {
                            startAlternativeRecord(method)
                        }

                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_START_CAMERA -> if (mHyperionThread == null) {
                    mCaptureSource = "camera"
                    val foregroundStarted = tryStartForegroundCamera()

                    val isPrepared = prepared()
                    if (isPrepared) {
                        if (!foregroundStarted && mTclBlocked) {
                            mStandby?.acquireWakeLock()
                        }

                        startCameraCapture()
                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }

                ACTION_DETECT_FRAME -> {
                    // Кнопка автоподстройки: перезапускаем поиск экрана прямо в идущей
                    // сессии камеры.
                    val backend = mActiveBackend
                    if (backend is CameraEncoder) {
                        backend.requestFrameDetection()
                    } else {
                        Log.i(TAG, "ACTION_DETECT_FRAME ignored: camera capture is not running")
                        // Интент мог создать сервис заново (кнопку нажали в момент его
                        // остановки) — не оставляем пустой foreground-сервис висеть.
                        if (mHyperionThread == null) stopSelf()
                    }
                }

                ACTION_STOP -> stopAllCapture()
                ACTION_CLEAR -> {
                    // Один чёрный кадр, но соединение оставляем
                    val backend = mActiveBackend
                    if (backend != null) {
                        if (DEBUG) Log.v(
                            TAG,
                            "ACTION_CLEAR: clearing lights once (${backend.javaClass.simpleName})"
                        )
                        backend.clearLights()
                    } else if (mHyperionThread == null) {
                        // Интент пришёл в незапущенный сервис и создал его — не оставляем
                        // пустой foreground-сервис висеть с уведомлением
                        stopSelf()
                    }
                }

                GET_STATUS -> {
                    notifyActivity()
                    // Запрос статуса мог создать сервис заново (гонка с onDestroy) — не
                    // оставляем пустой foreground-сервис висеть с уведомлением
                    if (mHyperionThread == null) stopSelf()
                }

                ACTION_EXIT -> {
                    // «Выход» из уведомления, плитки и ярлыка — решение пользователя: автозапуск
                    // и сторож больше не должны поднимать подсветку
                    Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        if (DEBUG) Log.v(TAG, "Ending service")

        try {
            unregisterReceiver(mEventReceiver)
        } catch (e: Exception) {
            if (DEBUG) Log.v(TAG, "Wake receiver not registered")
        }

        unregisterColorPrefsListener()
        mHandler?.removeCallbacks(mApplySettings)
        mActiveOptions = null

        mStandby?.releaseAll()
        stopAllCapture()
        stopForeground(true)
        mForegroundStarted = false
        notifyActivity()
        sInstanceRunning = false

        super.onDestroy()
    }

    private fun registerEventReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilter.addAction(Intent.ACTION_REBOOT)
        intentFilter.addAction(Intent.ACTION_SHUTDOWN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mEventReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mEventReceiver, intentFilter)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun initialForegroundTypeFor(action: String?): Int = when (action) {
        ACTION_START_CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ACTION_START -> {
            // Тип mediaProjection без выданного согласия роняет startForeground на 14+
            // (исключение глотается, но сервис остаётся не-foreground до повторной
            // попытки) — для методов без проекции сразу берём mediaPlayback
            val method = Preferences(this)
                .getString(R.string.pref_key_capture_method, "media_projection")
            if (method == "media_projection") {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
        }

        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    /** Идемпотентный startForeground — вызывать можно сколько угодно раз. */
    private fun ensureForegroundStarted(type: Int) {
        if (mForegroundStarted) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "ensureForegroundStarted failed (type=$type): ${e.message}")
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }
    }

    private fun tryStartForegroundCompat(type: Int): Boolean {
        if (mForegroundStarted) {
            mForegroundFailed = false
            mTclBlocked = false
            return true
        }
        mForegroundFailed = false
        mTclBlocked = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    type
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed: " + e.message)
            mForegroundFailed = true
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }

        if (mForegroundFailed) {
            try {
                Thread.sleep(100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        type
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                mForegroundFailed = false
                mTclBlocked = false
                mForegroundStarted = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground retry failed: " + e.message)
                mTclBlocked = true
            }
        }

        notifyTclBlocked()
        return false
    }

    private fun tryStartForegroundCamera(): Boolean {
        if (mForegroundStarted) {
            mForegroundFailed = false
            mTclBlocked = false
            return true
        }
        mForegroundFailed = false
        mTclBlocked = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            mForegroundStarted = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start (camera) failed: " + e.message)
            mForegroundFailed = true
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }

        // Повторная попытка
        if (mForegroundFailed) {
            try {
                Thread.sleep(100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this, NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                mForegroundFailed = false
                mTclBlocked = false
                mForegroundStarted = true
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground retry (camera) failed: " + e.message)
                mTclBlocked = true
            }
        }

        notifyTclBlocked()
        return false
    }

    /** Выход нового энкодера; прежний энкодер при этом отцепляется. */
    private fun newGate(): HyperionThread.HyperionThreadListener {
        mGate?.attached = false
        return OutputGate().also { mGate = it }
    }

    private fun startCameraCapture() {
        if (DEBUG) Log.v(TAG, "Starting camera capture")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start camera capture")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }

        val prefs = Preferences(this)
        val options = buildAppOptions(prefs)

        val cornersStr = prefs.getString(R.string.pref_key_camera_corners, null)
        val corners = CameraEncoder.parseCornersString(cornersStr)

        val encoder = CameraEncoder(
            this,
            newGate(),
            options,
            corners
        )
        mActiveBackend = encoder
        encoder.start()
        encoder.sendStatus()
    }


    private fun notifyTclBlocked() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, false)
        intent.putExtra(BROADCAST_TCL_BLOCKED, true)
        intent.putExtra(BROADCAST_ERROR, "Foreground service blocked by device manufacturer")
        sendStatusBroadcast(intent)
    }

    private fun haltStartup() {
        // Пробуем выйти в foreground, чтобы показать ошибку, но не падаем, если это запрещено
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground during halt: " + e.message)
        }

        notifyActivity()

        shutDownHyperionThread()

        stopSelf()
    }

    /**
     * Гасит HyperionThread вместе с его executor'ами и клиентом. Один interrupt() здесь
     * не работает: после успешного подключения run() уже завершён, и без disconnect()
     * каждый неудачный старт оставлял бы два неубиваемых потока, а клиент Adalight —
     * занятый USB-порт до конца процесса. disconnect() блокирует (awaitTermination,
     * закрытие порта), поэтому уводится с вызывающего потока.
     */
    private fun shutDownHyperionThread() {
        val thread = mHyperionThread
        val secondary = mSecondaryHyperionThread
        mHyperionThread = null
        mSecondaryHyperionThread = null
        mOutput = null
        if (thread == null && secondary == null) return
        thread?.interrupt()
        secondary?.interrupt()
        Thread({
            try {
                thread?.receiver?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "HyperionThread shutdown failed: ${e.message}")
            }
            try {
                secondary?.receiver?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Additional Home Assistant output shutdown failed: ${e.message}")
            }
        }, "hyperion-shutdown").apply { isDaemon = true }.start()
    }

    private fun buildExitButton(): Intent {
        val notificationIntent = Intent(this, this.javaClass)
        notificationIntent.flags = Intent.FLAG_RECEIVER_FOREGROUND
        notificationIntent.action = ACTION_EXIT
        return notificationIntent
    }

    val notification: Notification
        get() {
            val mgr = mNotificationManager
                ?: (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
            if (mgr == null) {
                throw IllegalStateException("NotificationManager is null")
            }
            val notification = AppNotification(this, mgr)
            val label = getString(R.string.notification_exit_button)
            notification.setAction(NOTIFICATION_EXIT_INTENT_ID, label, buildExitButton())
            return notification.buildNotification()
        }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScreenRecord(intent: Intent) {
        if (DEBUG) Log.v(TAG, "Starting screen recorder")
        val projectionManager = mMediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is null; cannot start screen recording")
            mStartError = resources.getString(R.string.error_media_projection_denied)
            haltStartup()
            return
        }
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start screen recording")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        // По возможности берём переданный интент проекции напрямую — так надёжнее на устройствах с ограничениями
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // Сохраняем данные проекции, чтобы восстановиться после сна телевизора
        if (resultData != null) {
            saveProjectionData(resultCode, resultData.extras)
        } else {
            // Запасной путь для старых версий и неожиданных вызовов
            saveProjectionData(resultCode, intent.extras)
        }

        val projectionDataIntent = buildProjectionDataIntent()
        val projectionIntent = resultData ?: projectionDataIntent ?: intent

        val projection = projectionManager.getMediaProjection(
            resultCode,
            projectionIntent
        )
        val window = getSystemService(WINDOW_SERVICE) as WindowManager

        if (projection != null && window != null) {
            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = buildAppOptions(prefs)

            if (DEBUG) Log.v(
                TAG,
                "Creating encoder: " + metrics.widthPixels + "x" + metrics.heightPixels
            )
            val encoder = ScreenEncoder(
                newGate(),
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mActiveBackend = encoder
            encoder.sendStatus()
        } else {
            if (projection == null) {
                Log.e(
                    TAG,
                    "MediaProjection is null (resultCode=$resultCode). Permission likely missing/invalid."
                )
                mStartError = resources.getString(R.string.error_media_projection_denied)
                AnalyticsHelper.logServiceError(
                    baseContext,
                    "media_projection_null",
                    "resultCode: $resultCode"
                )
                haltStartup()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAlternativeRecord(method: String) {
        if (DEBUG) Log.v(TAG, "Starting alternative recorder: $method")
        val thread = mHyperionThread
        if (thread == null) {
            Log.e(TAG, "HyperionThread is null; cannot start recording")
            mStartError = resources.getString(R.string.error_server_unreachable)
            haltStartup()
            return
        }
        val window = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        window.defaultDisplay.getRealMetrics(metrics)

        val prefs = Preferences(this)
        val options = buildAppOptions(prefs)

        if (method == "accessibility") {
            val accessibilityService = AccessibilityCaptureService.getInstance()
            if (accessibilityService != null) {
                if (DEBUG) Log.v(TAG, "Creating Accessibility encoder")
                val encoder = AccessibilityEncoder(
                    accessibilityService,
                    newGate(),
                    metrics.widthPixels,
                    metrics.heightPixels,
                    options
                )
                mActiveBackend = encoder
                encoder.sendStatus()
            } else {
                Log.e(TAG, "Accessibility Service not connected!")
                mStartError = "Accessibility Service not enabled"
                haltStartup()
            }
            return
        }

        if (method == "adb_local") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating ADB encoder on port $adbPort")
            val encoder = AdbEncoder(
                this.applicationContext,
                newGate(),
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort
            )
            mActiveBackend = encoder
            encoder.sendStatus()
            return
        }

        if (method == "adb_stream") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating screenrecord (H.264 stream) encoder on port $adbPort")
            val encoder = ScreenrecordEncoder(
                this.applicationContext,
                newGate(),
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort,
                onFatalError = { errorMsg ->
                    // Колбэк приходит из потока энкодера — состояние сервиса и
                    // startForeground трогаем только с главного
                    mHandler?.post {
                        mStartError = errorMsg
                        haltStartup()
                    }
                }
            )
            mActiveBackend = encoder
            encoder.sendStatus()
            return
        }

        if (method == "scrcpy") {
            val adbPort = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
            if (DEBUG) Log.v(TAG, "Creating scrcpy encoder on port $adbPort")
            val encoder = ScrcpyEncoder(
                this.applicationContext,
                newGate(),
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                adbPort,
                onFatalError = { errorMsg ->
                    // Колбэк приходит из потока энкодера — состояние сервиса и
                    // startForeground трогаем только с главного
                    mHandler?.post {
                        mStartError = errorMsg
                        haltStartup()
                    }
                }
            )
            mActiveBackend = encoder
            encoder.sendStatus()
            return
        }

        if (method == "mtk_thal_capture") {
            if (DEBUG) Log.v(TAG, "Creating MTK THAL Capture encoder")
            val encoder = MtkThalCaptureEncoder(
                this.applicationContext,
                newGate(),
                metrics.widthPixels,
                metrics.heightPixels,
                options,
                onFatalError = { errorMsg ->
                    // Колбэк приходит из потока энкодера — состояние сервиса и
                    // startForeground трогаем только с главного
                    mHandler?.post {
                        mStartError = errorMsg
                        haltStartup()
                    }
                }
            )
            mActiveBackend = encoder
            encoder.sendStatus()
            return
        }

        val useRoot = method == "screencap_root"
        if (DEBUG) Log.v(TAG, "Creating screencap encoder (root=$useRoot)")
        val encoder = ScreencapEncoder(
            this.applicationContext,
            newGate(),
            metrics.widthPixels,
            metrics.heightPixels,
            options,
            useRoot,
            onFatalError = { errorMsg ->
                mStartError = errorMsg
                haltStartup()
            }
        )
        mActiveBackend = encoder
        encoder.sendStatus()
    }

    private fun saveProjectionData(resultCode: Int, extras: android.os.Bundle?) {
        mProjectionResultCode = resultCode
        if (extras != null) {
            val copy = android.os.Bundle(extras)
            copy.remove(EXTRA_RESULT_CODE)
            mProjectionDataExtras = copy
        }
    }

    private fun buildProjectionDataIntent(): Intent? {
        val extras = mProjectionDataExtras ?: return null
        return Intent().apply { replaceExtras(extras) }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun restartEncoderFromSavedProjection() {
        // В режиме screencap восстанавливать нечего — просто возобновляем цикл захвата
        val screencap = mActiveBackend as? ScreencapEncoder
        if (screencap != null) {
            screencap.resumeRecording()
            return
        }
        val resultCode = mProjectionResultCode ?: return
        val projectionIntent = buildProjectionDataIntent() ?: return
        val projectionManager = mMediaProjectionManager ?: return
        val thread = mHyperionThread ?: return

        // Останавливаем старый энкодер без разрыва соединения (важно для keepalive WLED)
        try {
            (mActiveBackend as? ScreenEncoder)?.stopRecordingNoDisconnect()
        } catch (e: Exception) {
            // Систему уже могла забрать проекцию вместе с потрохами энкодера; нам важно
            // только освободить место под новый, поэтому продолжаем в любом случае.
            Log.w(TAG, "Failed to stop the previous encoder before restart", e)
        }
        mActiveBackend = null

        releaseResource()

        try {
            val projection = projectionManager.getMediaProjection(resultCode, projectionIntent)
            val window = getSystemService(WINDOW_SERVICE) as? WindowManager
            if (projection == null || window == null) {
                return
            }

            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = buildAppOptions(prefs)

            val encoder = ScreenEncoder(
                newGate(),
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mActiveBackend = encoder
            encoder.sendStatus()
        } catch (e: Exception) {
            // Токен MediaProjection мог истечь или быть отозван системой; на Android 14+
            // повторное использование согласия запрещено и бросает SecurityException (часть
            // прошивок — IllegalStateException). Падать из приёмника широковещаний нельзя —
            // сообщаем об ошибке и останавливаемся, иначе сервис навсегда завис бы в
            // foreground без захвата.
            Log.e(TAG, "Failed to restart encoder from saved projection: ${e.message}", e)
            mStartError = resources.getString(R.string.error_media_projection_denied)
            mProjectionResultCode = null
            mProjectionDataExtras = null
            releaseResource()
            notifyActivity()
            stopSelf()
            // Старое согласие не годится — просим новое тем же путём, что и при включении ТВ:
            // с выданным через ADB PROJECT_MEDIA диалог подтверждается сам
            if (AutoStart.shouldResume(this)) {
                val app = applicationContext
                mHandler?.postDelayed({ AutoStart.resume(app) }, SESSION_RESTART_DELAY_MS)
            }
        }
    }

    private fun stopAllCapture() {
        if (DEBUG) Log.v(TAG, "Stopping all capture")
        mReconnectEnabled = false
        mNotificationManager?.cancel(NOTIFICATION_ID)

        val backend = mActiveBackend
        if (backend != null) {
            if (DEBUG) Log.v(TAG, "Stopping ${backend.javaClass.simpleName}")
            backend.stopRecording()
            mActiveBackend = null
            // Клиент и executors закроет цепочка stopRecording → listener.disconnect(),
            // она уже дошла и до дополнительного вывода через DualHyperionThreadListener
            mHyperionThread?.interrupt()
            mHyperionThread = null
            mSecondaryHyperionThread?.interrupt()
            mSecondaryHyperionThread = null
            mOutput = null
            mGate = null
        } else {
            // Энкодера нет — закрывать соединение некому, кроме нас
            shutDownHyperionThread()
        }

        releaseResource()
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun releaseResource() {
        sMediaProjection?.stop()
        sMediaProjection = null
    }

    val isCapturing: Boolean
        get() = mActiveBackend?.isCapturing() == true

    val isCommunicating: Boolean
        get() = isCapturing && mHasConnected

    private fun notifyActivity() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, isCommunicating)
        intent.putExtra(BROADCAST_ERROR, mStartError)
        if (DEBUG) {
            Log.v(
                TAG, "Broadcasting status: communicating=" + isCommunicating +
                        if (mStartError != null) ", error=$mStartError" else ""
            )
        }
        sendStatusBroadcast(intent)
    }

    /**
     * Рассылает состояние сервиса своим же компонентам (активити и плитке быстрых настроек).
     * Пакет проставляется явно, а приёмники регистрируются как RECEIVER_NOT_EXPORTED,
     * поэтому широковещание не выходит за пределы приложения.
     */
    private fun sendStatusBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildAppOptions(prefs: Preferences): AppOptions {
        val opts = AppOptions(
            mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality,
            brightness = prefs.getInt(R.string.pref_key_color_brightness, 100),
            contrast = prefs.getInt(R.string.pref_key_color_contrast, 100),
            blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0),
            whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100),
            saturation = prefs.getInt(R.string.pref_key_color_saturation, 100),
            colorProcessingEnabled = prefs.getBoolean(
                R.string.pref_key_color_processing_enabled,
                true
            ),
            brightnessR = prefs.getInt(R.string.pref_key_color_brightness_r, 100),
            brightnessG = prefs.getInt(R.string.pref_key_color_brightness_g, 100),
            brightnessB = prefs.getInt(R.string.pref_key_color_brightness_b, 100),
            gammaR = prefs.getInt(R.string.pref_key_color_gamma_r, 100),
            gammaG = prefs.getInt(R.string.pref_key_color_gamma_g, 100),
            gammaB = prefs.getInt(R.string.pref_key_color_gamma_b, 100),
            borderDetectionEnabled = prefs.getBoolean(
                R.string.pref_key_border_detection_enabled,
                false
            ),
            borderThreshold = prefs.getInt(R.string.pref_key_border_threshold, 18).coerceIn(0, 64),
            borderCheckIntervalFrames = prefs.getInt(R.string.pref_key_border_check_interval, 60)
                .coerceIn(1, 300)
        )
        opts.refreshCameraIdleSettings(prefs)
        mActiveOptions = opts
        registerColorPrefsListener()
        return opts
    }

    private fun registerColorPrefsListener() {
        if (mPrefsListener != null) return
        val sharedPrefs = Preferences.defaultSharedPreferences(this)
        val keyBrightness = getString(R.string.pref_key_color_brightness)
        val keyContrast = getString(R.string.pref_key_color_contrast)
        val keyBlack = getString(R.string.pref_key_color_black_level)
        val keyWhite = getString(R.string.pref_key_color_white_level)
        val keySaturation = getString(R.string.pref_key_color_saturation)
        val keyEnabled = getString(R.string.pref_key_color_processing_enabled)
        val keyBr = getString(R.string.pref_key_color_brightness_r)
        val keyBg = getString(R.string.pref_key_color_brightness_g)
        val keyBb = getString(R.string.pref_key_color_brightness_b)
        val keyGr = getString(R.string.pref_key_color_gamma_r)
        val keyGg = getString(R.string.pref_key_color_gamma_g)
        val keyGb = getString(R.string.pref_key_color_gamma_b)
        val keyBorderOn = getString(R.string.pref_key_border_detection_enabled)
        val keyBorderTh = getString(R.string.pref_key_border_threshold)
        val keyBorderIv = getString(R.string.pref_key_border_check_interval)
        val colorKeys = setOf(
            keyBrightness, keyContrast, keyBlack, keyWhite, keySaturation, keyEnabled,
            keyBr, keyBg, keyBb, keyGr, keyGg, keyGb
        )
        val borderKeys = setOf(keyBorderOn, keyBorderTh, keyBorderIv)
        // Пороги автосна настраиваются только вживую под камерой, поэтому идут тем же путём
        // «правка во время захвата», что и цветовые настройки.
        val cameraIdleKeys = setOf(
            getString(R.string.pref_key_camera_idle_enabled),
            getString(R.string.pref_key_camera_idle_timeout),
            getString(R.string.pref_key_camera_idle_dark_level),
            getString(R.string.pref_key_camera_idle_motion_level),
            getString(R.string.pref_key_camera_idle_static),
        )
        val outputKeys = OUTPUT_KEYS.map { getString(it) }.toSet()
        val captureKeys = CAPTURE_KEYS.map { getString(it) }.toSet()
        val sessionKeys = setOf(
            getString(R.string.pref_key_capture_source),
            getString(R.string.pref_key_capture_method)
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null) return@OnSharedPreferenceChangeListener
                val prefs = Preferences(this)
                if (key in colorKeys) mActiveOptions?.refreshColorSettings(prefs)
                if (key in borderKeys) mActiveOptions?.refreshBorderSettings(prefs)
                if (key in cameraIdleKeys) mActiveOptions?.refreshCameraIdleSettings(prefs)
                // Остальное вступало в силу только после ручного перезапуска подсветки; с
                // телефона правят посреди фильма, и ждать перезапуска там некому
                val output = key in outputKeys
                val capture = key in captureKeys
                val session = key in sessionKeys
                if (output || capture || session) {
                    mPendingOutputRestart = mPendingOutputRestart || output
                    mPendingCaptureRestart = mPendingCaptureRestart || capture
                    mPendingSessionRestart = mPendingSessionRestart || session
                    mHandler?.removeCallbacks(mApplySettings)
                    mHandler?.postDelayed(mApplySettings, APPLY_SETTINGS_DELAY_MS)
                }
            }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        mPrefsListener = listener
    }

    private fun applyPendingSettings() {
        val session = mPendingSessionRestart
        val capture = mPendingCaptureRestart
        val output = mPendingOutputRestart
        mPendingSessionRestart = false
        mPendingCaptureRestart = false
        mPendingOutputRestart = false
        if (mActiveBackend == null || mHyperionThread == null) return

        when {
            session -> restartSession()
            capture -> {
                // Сначала энкодер: ему нужен живой HyperionThread, а restartOutput обнуляет его
                // до конца пересоздания. Шлюз нового энкодера сам подхватит новый вывод
                restartCapture()
                if (output) restartOutput()
            }

            output -> restartOutput()
        }
    }

    /**
     * Новый способ или источник захвата — это другой тип foreground-сервиса и, возможно,
     * новое согласие. Честнее перезапустить подсветку целиком тем же путём, что и кнопка.
     */
    private fun restartSession() {
        Log.i(TAG, "Capture source or method changed, restarting the session")
        stopAllCapture()
        stopSelf()
        val app = applicationContext
        mHandler?.postDelayed({ CaptureLauncher.start(app) }, SESSION_RESTART_DELAY_MS)
    }

    /** Пересоздаёт потоки вывода под работающим энкодером. */
    private fun restartOutput() {
        val oldPrimary = mHyperionThread ?: return
        val oldSecondary = mSecondaryHyperionThread
        Log.i(TAG, "Connection settings changed, restarting the output")
        mOutput = null
        mHyperionThread = null
        mSecondaryHyperionThread = null
        oldPrimary.interrupt()
        oldSecondary?.interrupt()
        Thread({
            // Старый вывод закрываем до нового: Adalight заново открывает тот же USB-порт
            try {
                oldPrimary.receiver.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Old output shutdown failed: ${e.message}")
            }
            try {
                oldSecondary?.receiver?.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Old additional output shutdown failed: ${e.message}")
            }
            mHandler?.post {
                // Пока закрывался старый вывод, подсветку могли выключить
                if (mActiveBackend == null || mHyperionThread != null) return@post
                // Новые настройки проверяются как при старте: при ошибке — тот же текст и остановка
                mHasConnected = false
                if (!prepared()) haltStartup()
            }
        }, "hyperion-restart").apply { isDaemon = true }.start()
    }

    /** Пересоздаёт энкодер с новыми частотой, качеством и числом светодиодов. */
    private fun restartCapture() {
        val backend = mActiveBackend ?: return
        val prefs = Preferences(this)
        mFrameRate = prefs.getInt(R.string.pref_key_framerate)
        mCaptureQuality = prefs.getString(R.string.pref_key_capture_quality, "128")
            ?.toIntOrNull() ?: 128
        mHorizontalLEDCount = prefs.getInt(R.string.pref_key_x_led)
        mVerticalLEDCount = prefs.getInt(R.string.pref_key_y_led)
        mSendAverageColor = prefs.getBoolean(R.string.pref_key_use_avg_color)
        Log.i(TAG, "Capture settings changed, recreating ${backend.javaClass.simpleName}")

        when (backend) {
            is ScreenEncoder -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ не отдаёт вторую проекцию по тому же согласию — новые
                    // параметры захвата вступят в силу при следующем запуске
                    Log.i(TAG, "MediaProjection cannot be reused on this Android, keeping the encoder")
                    return
                }
                restartEncoderFromSavedProjection()
            }

            is CameraEncoder -> {
                mGate?.attached = false
                backend.stopRecordingNoDisconnect()
                mActiveBackend = null
                startCameraCapture()
            }

            else -> {
                mGate?.attached = false
                backend.stopRecording()
                mActiveBackend = null
                val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
                    ?: "media_projection"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startAlternativeRecord(method)
                } else {
                    restartSession()
                }
            }
        }
    }

    private fun unregisterColorPrefsListener() {
        val listener = mPrefsListener ?: return
        try {
            Preferences.defaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(listener)
        } catch (_: Exception) {
            // Сервис уже останавливается; если SharedPreferences успели уйти вместе с
            // контекстом, снимать слушателя не с чего.
        }
        mPrefsListener = null
    }

    interface HyperionThreadBroadcaster {
        fun onConnected()
        fun onConnectionError(errorID: Int, error: String?)
        fun onReceiveStatus(isCapturing: Boolean)
    }

    companion object {
        const val BROADCAST_ERROR = "SERVICE_ERROR"
        const val BROADCAST_TAG = "SERVICE_STATUS"
        const val BROADCAST_FILTER = "SERVICE_FILTER"
        const val BROADCAST_TCL_BLOCKED = "TCL_BLOCKED"
        private const val DEBUG = false
        private const val TAG = "ScreenGrabberService"

        private const val BASE = "com.vasmarfas.UniversalAmbientLight.service."
        const val ACTION_START = BASE + "ACTION_START"
        const val ACTION_START_CAMERA = BASE + "ACTION_START_CAMERA"
        const val ACTION_STOP = BASE + "ACTION_STOP"
        const val ACTION_CLEAR = BASE + "ACTION_CLEAR"
        const val ACTION_DETECT_FRAME = BASE + "ACTION_DETECT_FRAME"
        const val ACTION_EXIT = BASE + "ACTION_EXIT"
        const val GET_STATUS = BASE + "ACTION_STATUS"
        const val EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = BASE + "EXTRA_RESULT_DATA"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_EXIT_INTENT_ID = 2
        private const val APPLY_SETTINGS_DELAY_MS = 1200L
        private const val SESSION_RESTART_DELAY_MS = 700L

        /** Настройки вывода: меняются пересозданием HyperionThread под тем же энкодером. */
        private val OUTPUT_KEYS = intArrayOf(
            R.string.pref_key_connection_type,
            R.string.pref_key_host,
            R.string.pref_key_port,
            R.string.pref_key_priority,
            R.string.pref_key_reconnect,
            R.string.pref_key_reconnect_delay,
            R.string.pref_key_adalight_baudrate,
            R.string.pref_key_adalight_protocol,
            R.string.pref_key_wled_color_order,
            R.string.pref_key_wled_protocol,
            R.string.pref_key_wled_rgbw,
            R.string.pref_key_wled_brightness,
            R.string.pref_key_smoothing_enabled,
            R.string.pref_key_smoothing_preset,
            R.string.pref_key_settling_time,
            R.string.pref_key_output_delay,
            R.string.pref_key_update_frequency,
            R.string.pref_key_ha_token,
            R.string.pref_key_ha_lamps,
            R.string.pref_key_ha_update_interval,
            R.string.pref_key_ha_change_threshold,
            R.string.pref_key_ha_transition,
            R.string.pref_key_ha_brightness_mode,
            R.string.pref_key_ha_brightness,
            R.string.pref_key_ha_dark_off,
            R.string.pref_key_ha_dark_threshold,
            R.string.pref_key_ha_turn_off_lights,
            R.string.pref_key_ha2_enabled,
            R.string.pref_key_ha2_host,
            R.string.pref_key_ha2_port,
            R.string.pref_key_ha2_token,
            R.string.pref_key_ha2_lamps,
            R.string.pref_key_ha2_update_interval,
            R.string.pref_key_ha2_change_threshold,
            R.string.pref_key_ha2_transition,
            R.string.pref_key_ha2_brightness_mode,
            R.string.pref_key_ha2_brightness,
            R.string.pref_key_ha2_dark_off,
            R.string.pref_key_ha2_dark_threshold,
            R.string.pref_key_ha2_turn_off_lights,
        )

        /** Настройки, которые энкодер берёт при создании. */
        private val CAPTURE_KEYS = intArrayOf(
            R.string.pref_key_framerate,
            R.string.pref_key_capture_quality,
            R.string.pref_key_use_avg_color,
            R.string.pref_key_adb_port,
            R.string.pref_key_x_led,
            R.string.pref_key_y_led,
        )

        private var sMediaProjection: MediaProjection? = null

        /** Проблема в настройках, из-за которой захват не начнётся. */
        data class SettingsError(
            val code: String,
            val message: String,
            val details: String? = null,
        )

        /**
         * Проверяет настройки, без которых сервису нечего отправлять, чтобы предупредить
         * пользователя сразу, а не после всех диалогов с разрешениями. Возвращает null,
         * когда настройки пригодны.
         */
        @JvmStatic
        fun validateSettings(context: Context): SettingsError? {
            val prefs = Preferences(context)
            val connectionType =
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

            // Для Adalight адрес и порт не нужны
            if (!"adalight".equals(connectionType, ignoreCase = true)) {
                val host = prefs.getString(R.string.pref_key_host, null)?.trim()
                if (host.isNullOrEmpty() || host == "0.0.0.0") {
                    return SettingsError(
                        "empty_host",
                        context.getString(R.string.error_empty_host)
                    )
                }
                val port = prefs.getInt(R.string.pref_key_port, -1)
                if (port == -1) {
                    return SettingsError(
                        "empty_port",
                        context.getString(R.string.error_empty_port)
                    )
                }
                // Порт должен попадать в диапазон 1-65535
                if (port < 1 || port > 65535) {
                    return SettingsError(
                        "invalid_port",
                        context.getString(R.string.error_invalid_port, port),
                        "port: $port"
                    )
                }
            }

            if ("homeassistant".equals(connectionType, ignoreCase = true)) {
                val token = prefs.getString(R.string.pref_key_ha_token, "")?.trim() ?: ""
                if (token.isEmpty()) {
                    return SettingsError(
                        "ha_token_missing",
                        context.getString(R.string.error_ha_token_missing)
                    )
                }
                val lamps =
                    HomeAssistantLamp.parseList(prefs.getString(R.string.pref_key_ha_lamps, ""))
                if (lamps.isEmpty()) {
                    return SettingsError(
                        "ha_no_lamps",
                        context.getString(R.string.error_ha_no_lamps)
                    )
                }
            }

            // Проверяем то же число, что реально уходит контроллеру: раскладку по сторонам
            // с фолбэком на старые горизонталь/вертикаль. Проверка одних старых ключей
            // отбрасывала валидную посторонную раскладку, если в них когда-то записали 0.
            val layout = LedLayout.from(prefs)
            if (layout.configuredLedCount() <= 0) {
                return SettingsError(
                    "invalid_led_counts",
                    context.getString(R.string.error_invalid_led_counts),
                    "top: ${layout.topLed}, right: ${layout.rightLed}, " +
                            "bottom: ${layout.bottomLed}, left: ${layout.leftLed}, " +
                            "x: ${layout.xLed}, y: ${layout.yLed}"
                )
            }

            return null
        }

        /**
         * Подсветка не запустилась ещё до сервиса (отказ в согласии на запись экрана), и
         * сказать об этом экранам и телефону-пульту, кроме нас, некому.
         */
        @JvmStatic
        fun notifyNotStarted(context: Context, error: String) {
            val intent = Intent(BROADCAST_FILTER)
            intent.putExtra(BROADCAST_TAG, false)
            intent.putExtra(BROADCAST_ERROR, error)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }

        /** True, пока экземпляр сервиса жив (onCreate→onDestroy). */
        @Volatile
        @JvmStatic
        var sInstanceRunning: Boolean = false
            internal set
    }
}
