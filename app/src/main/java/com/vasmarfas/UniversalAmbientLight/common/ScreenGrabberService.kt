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
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.net.wifi.WifiManager
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.R
import java.util.Objects

class ScreenGrabberService : Service() {

    private var mForegroundFailed = false
    private var mTclBlocked = false
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null
    private var mHandler: Handler? = null

    private var mReconnectEnabled = false
    private var mHasConnected = false
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mHyperionThread: HyperionThread? = null
    private var mFrameRate: Int = 0
    private var mCaptureQuality: Int = 0
    private var mHorizontalLEDCount: Int = 0
    private var mVerticalLEDCount: Int = 0
    private var mSendAverageColor: Boolean = false
    private var mScreenEncoder: ScreenEncoder? = null
    private var mCameraEncoder: CameraEncoder? = null
    private var mCaptureSource: String = "screen" // "screen" or "camera"
    private var mNotificationManager: NotificationManager? = null
    private var mStartError: String? = null
    private var mConnectionType = "hyperion"
    private var mProjectionResultCode: Int? = null
    private var mProjectionDataExtras: android.os.Bundle? = null

    private val mReceiver = object : HyperionThreadBroadcaster {
        override fun onConnected() {
            if (DEBUG) Log.d(TAG, "Connected to Hyperion server")
            mHasConnected = true
            val prefs = Preferences(baseContext)
            val host = prefs.getString(R.string.pref_key_host, null)
            AnalyticsHelper.logConnectionSuccess(baseContext, mConnectionType, host)
            notifyActivity()
        }

        override fun onConnectionError(errorID: Int, error: String?) {
            Log.e(TAG, "Connection error: " + (error ?: "unknown"))
            AnalyticsHelper.logConnectionError(baseContext, mConnectionType, error)
            if (!mHasConnected) {
                // Use appropriate error message based on connection type
                if ("adalight".equals(mConnectionType, ignoreCase = true)) {
                    mStartError = resources.getString(R.string.error_adalight_unreachable)
                } else {
                    mStartError = resources.getString(R.string.error_server_unreachable)
                }
                haltStartup()
            } else if (mReconnectEnabled) {
                Log.i(TAG, "Attempting automatic reconnect...")
            } else {
                // Use appropriate error message based on connection type
                if ("adalight".equals(mConnectionType, ignoreCase = true)) {
                    mStartError = resources.getString(R.string.error_adalight_connection_lost)
                } else {
                    mStartError = resources.getString(R.string.error_connection_lost)
                }
                stopSelf()
            }
        }

        override fun onReceiveStatus(isCapturing: Boolean) {
            if (DEBUG) Log.v(TAG, "Received status: capturing=$isCapturing")
            notifyActivity()
        }
    }

    private val mEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (Objects.requireNonNull(intent.action)) {
                Intent.ACTION_SCREEN_ON -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_ON intent received")
                    releaseWakeLock()
                    releaseWifiLock()
                    
                    // Reset WLED client data send block after EPERM error to resume sending on screen wake
                    mHyperionThread?.resetBlockedIfWLED()
                    
                    if (mCaptureSource == "camera") {
                        // Camera mode: just resume camera if not capturing
                        if (mCameraEncoder != null && !isCapturing) {
                            mCameraEncoder!!.resumeRecording()
                        }
                    } else {
                        if (mScreenEncoder != null && !isCapturing) {
                            if (DEBUG) Log.v(TAG, "Encoder not grabbing, attempting to resume")
                            mScreenEncoder!!.resumeRecording()
                        }

                        // If MediaProjection was stopped by system (sleep), resumeRecording() won't help.
                        // Recreate encoder from saved projection data.
                        if (!isCapturing) {
                            if (DEBUG) Log.v(TAG, "Still not capturing after resume; trying to restart encoder")
                            restartEncoderFromSavedProjection()
                        }
                    }
                    notifyActivity()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SCREEN_OFF intent received")
                    // On some TVs CPU goes into deep sleep and keepalive threads stop sending packets,
                    // causing WLED to revert to default effect after ~10s. PARTIAL_WAKE_LOCK keeps CPU alive for keepalive.
                    acquireWakeLock()
                    acquireWifiLock()
                    if (mScreenEncoder != null) {
                        if (DEBUG) Log.v(TAG, "Clearing current light data (screen mode)")
                        mScreenEncoder!!.clearLights()
                    }
                    // Camera mode: keep running — camera captures external TV, screen sleep is irrelevant
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    if (DEBUG) Log.v(TAG, "ACTION_CONFIGURATION_CHANGED intent received")
                    if (mScreenEncoder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        if (DEBUG) Log.v(TAG, "Configuration changed, checking orientation")
                        mScreenEncoder!!.setOrientation(resources.configuration.orientation)
                    }
                    mCameraEncoder?.setOrientation(resources.configuration.orientation)
                }
                Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                    if (DEBUG) Log.v(TAG, "ACTION_SHUTDOWN|ACTION_REBOOT intent received")
                    stopAllCapture()
                }
            }
        }
    }

    override fun onCreate() {
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        mHandler = Handler(Looper.getMainLooper())

        // Try shell bypass on startup for TCL devices
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.i(TAG, "Detected restricted manufacturer, attempting shell bypass")
            TclBypass.tryShellBypass(this)
        }

        super.onCreate()
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun prepared(): Boolean {
        val prefs = Preferences(baseContext)
        mConnectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        val host = prefs.getString(R.string.pref_key_host, null)
        val port = prefs.getInt(R.string.pref_key_port, -1)
        val priority = prefs.getString(R.string.pref_key_priority, "100")?.takeIf { it.isNotBlank() } ?: "100"
        mFrameRate = prefs.getInt(R.string.pref_key_framerate)

        try {
            val captureQualityStr = prefs.getString(R.string.pref_key_capture_quality, "128")?.takeIf { it.isNotBlank() } ?: "128"
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

        // For Adalight, host and port are not required
        if (!"adalight".equals(mConnectionType, ignoreCase = true)) {
            if (host == null || host == "0.0.0.0" || host == "") {
                mStartError = resources.getString(R.string.error_empty_host)
                AnalyticsHelper.logServiceError(baseContext, "empty_host", null)
                return false
            }
            if (port == -1) {
                mStartError = resources.getString(R.string.error_empty_port)
                AnalyticsHelper.logServiceError(baseContext, "empty_port", null)
                return false
            }
            // Validate port range (1-65535)
            if (port < 1 || port > 65535) {
                mStartError = "Invalid port: $port (must be between 1 and 65535)"
                AnalyticsHelper.logServiceError(baseContext, "invalid_port", "port: $port")
                return false
            }
        }

        if (mHorizontalLEDCount <= 0 || mVerticalLEDCount <= 0) {
            mStartError = resources.getString(R.string.error_invalid_led_counts)
            AnalyticsHelper.logServiceError(baseContext, "invalid_led_counts", "horizontal: $mHorizontalLEDCount, vertical: $mVerticalLEDCount")
            return false
        }
        mMediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mMediaProjectionManager == null) {
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

        mHyperionThread = HyperionThread(
            mReceiver, finalHost, finalPort, priorityValue,
            mReconnectEnabled, delay, mConnectionType, baseContext, baudRate, wledColorOrder,
            wledProtocol, wledRgbw, wledBrightness, adalightProtocol,
            smoothingEnabled, smoothingPreset, settlingTime, outputDelayMs, updateFrequency
        )
        mHyperionThread!!.start()
        mStartError = null
        return true
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.v(TAG, "Start command received")
        super.onStartCommand(intent, flags, startId)
        if (intent == null || intent.action == null) {
            val nullItem = if (intent == null) "intent" else "action"
            if (DEBUG) Log.v(TAG, "Null $nullItem provided to start command")
        } else {
            val action = intent.action
            if (DEBUG) Log.v(TAG, "Start command action: " + action.toString())
            when (action) {
                ACTION_START -> if (mHyperionThread == null) {
                    mCaptureSource = "screen"
                    val isPrepared = prepared()
                    if (isPrepared) {
                        val foregroundStarted = tryStartForeground()

                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        try {
                            startScreenRecord(intent)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Failed to start screen recording: " + e.message)
                            mStartError = resources.getString(R.string.error_media_projection_denied)
                            AnalyticsHelper.logServiceError(baseContext, "security_exception", e.message)
                            haltStartup()
                            return START_STICKY
                        }

                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }
                ACTION_START_CAMERA -> if (mHyperionThread == null) {
                    mCaptureSource = "camera"
                    val isPrepared = prepared()
                    if (isPrepared) {
                        val foregroundStarted = tryStartForegroundCamera()

                        if (!foregroundStarted && mTclBlocked) {
                            acquireWakeLock()
                        }

                        startCameraCapture()
                        registerEventReceiver()
                    } else {
                        haltStartup()
                    }
                }
                ACTION_STOP -> stopAllCapture()
                ACTION_CLEAR -> {
                    // Send one black frame but keep connection
                    if (mScreenEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (screen)")
                        mScreenEncoder!!.clearLights()
                    }
                    if (mCameraEncoder != null) {
                        if (DEBUG) Log.v(TAG, "ACTION_CLEAR: clearing lights once (camera)")
                        mCameraEncoder!!.clearLights()
                    }
                }
                GET_STATUS -> notifyActivity()
                ACTION_EXIT -> stopSelf()
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

        releaseWakeLock()
        stopAllCapture()
        stopForeground(true)
        notifyActivity()

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
            registerReceiver(mEventReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mEventReceiver, intentFilter)
        }
    }

    private fun tryStartForeground(): Boolean {
        mForegroundFailed = false
        mTclBlocked = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                mForegroundFailed = false
                mTclBlocked = false
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
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start (camera) failed: " + e.message)
            mForegroundFailed = true
            val msg = e.message
            if (msg != null && (msg.contains("TclAppBoot") || msg.contains("forbid"))) {
                mTclBlocked = true
            }
        }

        // Retry
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
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Foreground retry (camera) failed: " + e.message)
                mTclBlocked = true
            }
        }

        notifyTclBlocked()
        return false
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
        val options = AppOptions(
            mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality,
            brightness = prefs.getInt(R.string.pref_key_color_brightness, 100),
            contrast = prefs.getInt(R.string.pref_key_color_contrast, 100),
            blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0),
            whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100),
            saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
        )

        val cornersStr = prefs.getString(R.string.pref_key_camera_corners, null)
        val corners = CameraEncoder.parseCornersString(cornersStr)

        mCameraEncoder = CameraEncoder(
            this,
            thread.receiver,
            options,
            corners
        )
        mCameraEncoder!!.start()
        mCameraEncoder!!.sendStatus()
    }

    private fun acquireWakeLock() {
        if (mWakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ScreenGrabberService::ScreenCapture"
                    )
                    mWakeLock!!.acquire(60 * 60 * 1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        if (mWakeLock != null && mWakeLock!!.isHeld) {
            try {
                mWakeLock!!.release()
                Log.i(TAG, "Wake lock released")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release wake lock", e)
            }
            mWakeLock = null
        }
    }

    private fun acquireWifiLock() {
        if (mWifiLock != null && mWifiLock!!.isHeld) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                // HighPerf to prevent UDP throttling during idle (helps on some Android TV firmwares)
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScreenGrabberService::Wifi")
                mWifiLock?.setReferenceCounted(false)
                mWifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifi lock", e)
        }
    }

    private fun releaseWifiLock() {
        val lock = mWifiLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifi lock", e)
        }
        mWifiLock = null
    }

    private fun notifyTclBlocked() {
        val intent = Intent(BROADCAST_FILTER)
        intent.putExtra(BROADCAST_TAG, false)
        intent.putExtra(BROADCAST_TCL_BLOCKED, true)
        intent.putExtra(BROADCAST_ERROR, "Foreground service blocked by device manufacturer")
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun haltStartup() {
        // Try to start foreground to show error, but don't fail if blocked
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

        if (mHyperionThread != null) {
            mHyperionThread!!.interrupt()
            mHyperionThread = null
        }

        stopSelf()
    }

    private fun buildExitButton(): Intent {
        val notificationIntent = Intent(this, this.javaClass)
        notificationIntent.flags = Intent.FLAG_RECEIVER_FOREGROUND
        notificationIntent.action = ACTION_EXIT
        return notificationIntent
    }

    val notification: Notification
        get() {
            val mgr = mNotificationManager ?: (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
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
        // Save projection data to restore after sleep/wake on TV
        saveProjectionData(resultCode, intent.extras)

        val projectionDataIntent = buildProjectionDataIntent()
        val projection = projectionManager.getMediaProjection(
            resultCode,
            projectionDataIntent ?: intent
        )
        val window = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (projection != null && window != null) {
            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = AppOptions(
                mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality,
                brightness = prefs.getInt(R.string.pref_key_color_brightness, 100),
                contrast = prefs.getInt(R.string.pref_key_color_contrast, 100),
                blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0),
                whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100),
                saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
            )

            if (DEBUG) Log.v(TAG, "Creating encoder: " + metrics.widthPixels + "x" + metrics.heightPixels)
            mScreenEncoder = ScreenEncoder(
                thread.receiver,
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mScreenEncoder!!.sendStatus()
        } else {
            if (projection == null) {
                Log.e(TAG, "MediaProjection is null (resultCode=$resultCode). Permission likely missing/invalid.")
                mStartError = resources.getString(R.string.error_media_projection_denied)
                AnalyticsHelper.logServiceError(baseContext, "media_projection_null", "resultCode: $resultCode")
                haltStartup()
            }
        }
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
        val resultCode = mProjectionResultCode ?: return
        val projectionIntent = buildProjectionDataIntent() ?: return
        if (mMediaProjectionManager == null) return
        if (mHyperionThread == null) return

        // Stop old encoder without disconnecting (important for WLED keepalive)
        try {
            mScreenEncoder?.stopRecordingNoDisconnect()
        } catch (e: Exception) {
            // no-op
        }
        mScreenEncoder = null

        releaseResource()

        try {
            val projection = mMediaProjectionManager!!.getMediaProjection(resultCode, projectionIntent)
            val window = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (projection == null || window == null) {
                return
            }

            sMediaProjection = projection
            val metrics = DisplayMetrics()
            window.defaultDisplay.getRealMetrics(metrics)

            val prefs = Preferences(this)
            val options = AppOptions(
                mHorizontalLEDCount, mVerticalLEDCount, mFrameRate, mSendAverageColor, mCaptureQuality,
                brightness = prefs.getInt(R.string.pref_key_color_brightness, 100),
                contrast = prefs.getInt(R.string.pref_key_color_contrast, 100),
                blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0),
                whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100),
                saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
            )

            mScreenEncoder = ScreenEncoder(
                mHyperionThread!!.receiver,
                projection,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                options
            )
            mScreenEncoder!!.sendStatus()
        } catch (e: SecurityException) {
            // MediaProjection token may have expired or been revoked by system.
            // Don't crash from broadcast receiver, just log error and stop.
            Log.e(TAG, "Failed to restart encoder from saved projection: ${e.message}", e)
            mStartError = resources.getString(R.string.error_media_projection_denied)
            mProjectionResultCode = null
            mProjectionDataExtras = null
            releaseResource()
        }
    }

    private fun stopAllCapture() {
        if (DEBUG) Log.v(TAG, "Stopping all capture")
        mReconnectEnabled = false
        mNotificationManager?.cancel(NOTIFICATION_ID)

        if (mScreenEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping screen encoder")
            mScreenEncoder!!.stopRecording()
            mScreenEncoder = null
        }

        if (mCameraEncoder != null) {
            if (DEBUG) Log.v(TAG, "Stopping camera encoder")
            mCameraEncoder!!.stopRecording()
            mCameraEncoder = null
        }

        releaseResource()

        if (mHyperionThread != null) {
            mHyperionThread!!.interrupt()
            mHyperionThread = null
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun releaseResource() {
        if (sMediaProjection != null) {
            sMediaProjection!!.stop()
            sMediaProjection = null
        }
    }

    val isCapturing: Boolean
        get() = (mScreenEncoder != null && mScreenEncoder!!.isCapturing()) ||
                (mCameraEncoder != null && mCameraEncoder!!.isCapturing())

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
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    interface HyperionThreadBroadcaster {
        //        void onResponse(String response);
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
        const val ACTION_EXIT = BASE + "ACTION_EXIT"
        const val GET_STATUS = BASE + "ACTION_STATUS"
        const val EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_EXIT_INTENT_ID = 2

        private var sMediaProjection: MediaProjection? = null
    }
}
