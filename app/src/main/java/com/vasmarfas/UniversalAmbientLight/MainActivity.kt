package com.vasmarfas.UniversalAmbientLight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.Manifest
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.compose.rememberNavController
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.BootActivity
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingPayload
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteCallException
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteClient
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteControlService
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.ReviewHelper
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper
import com.vasmarfas.UniversalAmbientLight.ui.home.EffectMode
import com.vasmarfas.UniversalAmbientLight.ui.home.next
import com.vasmarfas.UniversalAmbientLight.ui.navigation.AppNavHost
import com.vasmarfas.UniversalAmbientLight.ui.navigation.Screen
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberRemoteSnapshot
import com.vasmarfas.UniversalAmbientLight.ui.theme.AppTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private var mRecorderRunning by mutableStateOf(false)

    // Последняя ошибка сервиса — на карточке состояния главного экрана до следующего запуска
    private var mLastError by mutableStateOf<String?>(null)
    // Отказ телевизора выполнить команду пульта; своя ошибка у ТВ приходит его статусом
    private var mRemoteError by mutableStateOf<String?>(null)
    private var mRemotePending by mutableStateOf(false)
    private var mPendingPayload by mutableStateOf<PairingPayload?>(null)

    // Ошибка ТВ, которая уже висела до нажатия: ожидание снимает только новая
    private var mStaleRemoteError: String? = null

    private val mRemoteListener = RemoteSession.Listener { snapshot ->
        val freshError = snapshot.error != null && snapshot.error != mStaleRemoteError
        if (snapshot.running || freshError || snapshot.tv == null) mRemotePending = false
    }

    private var mSetupRequiredMessage by mutableStateOf<String?>(null)
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mPermissionDeniedCount = 0
    private var mOverlayRequested = false
    private var mTclWarningShown = false
    private lateinit var appUpdateManager: AppUpdateManager
    private var currentEffect by mutableStateOf(EffectMode.RAINBOW)
    private var mSessionStartTime: Long? = null
    private var mSessionEverConnected: Boolean = false
    private var mSessionMethod: String? = null
    private var mSessionSource: String? = null
    private var mSessionProtocol: String? = null

    private var usbPermissionReceiverRegistered = false
    private var usbAttachReceiverRegistered = false

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED != intent.action) return

            val prefs = Preferences(this@MainActivity)
            val connectionType =
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            if (!"adalight".equals(connectionType, ignoreCase = true)) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            // Разрешение спрашиваем только для устройств, которые распознаёт наш USB-Serial prober
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this@MainActivity,
                device = device,
                onReady = { /* permission granted, nothing else to do here */ },
                onDenied = null,
                showToast = true
            )
        }
    }

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val checked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
            val wasRunning = mRecorderRunning
            mRecorderRunning = checked

            val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            mLastError = if (checked) null else error
            val tclBlocked =
                intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TCL_BLOCKED, false)

            if (checked) mSessionEverConnected = true

            if (wasRunning && !checked) {
                val reason = when {
                    tclBlocked -> "tcl_blocked"
                    error != null -> "error"
                    else -> "service_stop"
                }
                finishCaptureSession(reason)
            }

            // Диалог и toast откладываем: onReceive выполняется на главном потоке, и показ
            // окна прямо здесь задерживает доставку остальных широковещаний. К моменту
            // выполнения активити могла начать закрываться — показ диалога на ней дал бы
            // BadTokenException.
            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true
                window.decorView.post {
                    if (!isFinishing && !isDestroyed) {
                        TclBypass.showTclHelpDialog(this@MainActivity) { requestScreenCapture() }
                    }
                }
            } else if (error != null && !QuickTileService.isListening) {
                val errorMessage = error
                window.decorView.post {
                    Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // До super.onCreate(), как рекомендует сама androidx: иначе на части устройств первый
        // кадр успевает отрисоваться с ещё включённым fitsSystemWindows. enableEdgeToEdge()
        // не трогает deprecated Window.setStatusBarColor/setNavigationBarColor, на присутствие
        // которых в байткоде ругается Google Play (даже под API-гейтом).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS — то, что с Android 15 (API 35) требуется по
        // умолчанию для выреза дисплея; доступен с Android 11 (API 30), на более старых —
        // SHORT_EDGES.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        AnalyticsHelper.logAppLaunched(this)

        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

        // Проверку обновлений Play откладываем на следующий цикл главного цикла — onCreate на ТВ должен быть дешёвым.
        appUpdateManager = AppUpdateManagerFactory.create(this)
        window.decorView.post { checkForUpdates() }

        ContextCompat.registerReceiver(
            this,
            mMessageReceiver,
            IntentFilter(ScreenGrabberService.BROADCAST_FILTER),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        checkForInstance()

        // Разрешение на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        maybeRequestBatteryOptimizationExemption()

        RemoteSession.init(this)
        RemoteSession.addListener(mRemoteListener)
        // Сервер для телефона живёт своим foreground-сервисом; открытие приложения — повод
        // поднять его, если система его выгрузила
        RemoteControlService.startIfEnabled(this)
        handlePairingLink(intent)

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val remote = rememberRemoteSnapshot()
                val remoteActive = remote.tv != null
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompositionLocalProvider(LocalRemote provides remote.takeIf { remoteActive }) {
                        AppNavHost(
                            navController = navController,
                            isRunning = mRecorderRunning,
                            onToggleClick = {
                                if (remoteActive) toggleRemoteCapture() else toggleScreenCapture()
                            },
                            onEffectsClick = {
                                currentEffect = currentEffect.next()
                                AnalyticsHelper.logEffectChanged(
                                    this@MainActivity,
                                    currentEffect.name.lowercase()
                                )
                            },
                            effectMode = currentEffect,
                            lastError = if (remoteActive) mRemoteError else mLastError,
                            remotePending = mRemotePending,
                            pendingPayload = mPendingPayload,
                            onPayloadConsumed = { mPendingPayload = null }
                        )
                    }

                    mSetupRequiredMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { mSetupRequiredMessage = null },
                            title = { Text(stringResource(R.string.setup_required_title)) },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = {
                                    mSetupRequiredMessage = null
                                    navController.navigate(Screen.Settings.route)
                                }) {
                                    Text(stringResource(R.string.setup_required_open_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { mSetupRequiredMessage = null }) {
                                    Text(stringResource(R.string.setup_required_cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        if (PermissionHelper.isIgnoringBatteryOptimizations(this)) return

        val prefs = Preferences.defaultSharedPreferences(this)
        val keyLastAttempt = "battery_opt_exemption_last_attempt_ms"
        val lastAttempt = prefs.getLong(keyLastAttempt, 0L)
        val now = System.currentTimeMillis()
        val cooldownMs = 24L * 60L * 60L * 1000L // 24h
        if (now - lastAttempt < cooldownMs) return

        prefs.edit { putLong(keyLastAttempt, now) }

        AnalyticsHelper.logBatteryOptimizationRequested(this)

        PermissionHelper.requestIgnoreBatteryOptimizations(this)
    }

    override fun onStart() {
        super.onStart()
        RemoteSession.onForeground()
    }

    override fun onStop() {
        super.onStop()
        RemoteSession.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** QR с телевизора, распознанный системной камерой, открывает приложение ссылкой. */
    private fun handlePairingLink(intent: Intent?) {
        val link = intent?.dataString ?: return
        PairingPayload.parse(link)?.let { mPendingPayload = it }
    }

    override fun onResume() {
        super.onResume()

        // Факт выдачи исключения из энергосбережения логируем один раз на установку
        run {
            val p = Preferences.defaultSharedPreferences(this)
            val loggedKey = "battery_opt_granted_logged"
            if (!p.getBoolean(loggedKey, false) && PermissionHelper.isIgnoringBatteryOptimizations(
                    this
                )
            ) {
                AnalyticsHelper.logBatteryOptimizationGranted(this)
                p.edit { putBoolean(loggedKey, true) }
            }
        }

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                // Ответ Play Core мог прийти после закрытия активити — запуск IntentSender
                // на мёртвом экземпляре бросает IllegalStateException
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (appUpdateInfo.updateAvailability()
                    == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {
                    // Обновление уже идёт — продолжаем его.
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        REQUEST_UPDATE_CODE
                    )
                }
            }

        // Если USB-устройство уже подключено при открытом приложении, сразу просим разрешение.
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        if ("adalight".equals(connectionType, ignoreCase = true)) {
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this,
                device = null,
                onReady = { /* already granted */ },
                onDenied = null,
                showToast = false
            )
        }

        if (!usbAttachReceiverRegistered) {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            ContextCompat.registerReceiver(
                this,
                usbAttachReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            usbAttachReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RemoteSession.removeListener(mRemoteListener)
        try {
            unregisterReceiver(mMessageReceiver)
        } catch (_: IllegalArgumentException) {
            // Context.unregisterReceiver, в отличие от LocalBroadcastManager, бросает
            // исключение, если приёмник не был зарегистрирован; для нас это не ошибка.
        }

        if (usbAttachReceiverRegistered) {
            try {
                unregisterReceiver(usbAttachReceiver)
            } catch (_: Exception) {
                // onPause и onDestroy могут снять приёмник оба; повторное снятие бросает
                // IllegalArgumentException, и это не ошибка.
            }
            usbAttachReceiverRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (usbAttachReceiverRegistered) {
            try {
                unregisterReceiver(usbAttachReceiver)
            } catch (_: Exception) {
                // onPause и onDestroy могут снять приёмник оба; повторное снятие бросает
                // IllegalArgumentException, и это не ошибка.
            }
            usbAttachReceiverRegistered = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        // shouldShowRequestPermissionRationale возвращает false и при первом запросе, и после
        // «Больше не спрашивать» — различаем их одноразовой настройкой, чтобы не докучать.
        val prefs = Preferences.defaultSharedPreferences(this)
        val askedBefore = prefs.getBoolean(PREF_NOTIF_PERMISSION_ASKED, false)
        if (askedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
        ) return

        prefs.edit { putBoolean(PREF_NOTIF_PERMISSION_ASKED, true) }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun beginCaptureSession(source: String, method: String, protocol: String) {
        mSessionStartTime = System.currentTimeMillis()
        mSessionEverConnected = false
        mSessionSource = source
        mSessionMethod = method
        mSessionProtocol = protocol
    }

    private fun finishCaptureSession(endReason: String) {
        val start = mSessionStartTime ?: return
        val durationSeconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        AnalyticsHelper.logScreenCaptureStopped(this, durationSeconds)
        AnalyticsHelper.logCaptureSessionSummary(
            this, durationSeconds,
            mSessionMethod, mSessionSource, mSessionProtocol,
            mSessionEverConnected, endReason
        )
        mSessionStartTime = null
        mSessionMethod = null
        mSessionSource = null
        mSessionProtocol = null
        mSessionEverConnected = false
    }

    /**
     * Кнопка питания в режиме пульта: команду выполняет телевизор. Ответ ждём в фоне —
     * запуск с согласием MediaProjection на ТВ длится, пока пользователь не нажмёт OK пультом.
     */
    private fun toggleRemoteCapture() {
        val snapshot = RemoteSession.snapshot
        if (snapshot.connection != RemoteSession.Connection.CONNECTED) {
            Toast.makeText(this, R.string.remote_error_offline, Toast.LENGTH_SHORT).show()
            RemoteSession.reconnectNow()
            return
        }
        val start = !(snapshot.running || snapshot.alive)
        mRemotePending = start
        mRemoteError = null
        mStaleRemoteError = snapshot.error
        val action = if (start) RemoteProtocol.CAPTURE_START else RemoteProtocol.CAPTURE_STOP
        Thread({
            val result = runCatching {
                RemoteSession.call(
                    RemoteProtocol.OP_CAPTURE,
                    JSONObject().put("action", action),
                    RemoteClient.LONG_TIMEOUT_MS
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { reply ->
                    if (reply.optBoolean("confirm")) {
                        Toast.makeText(this, reply.optString("msg"), Toast.LENGTH_LONG).show()
                    }
                    // Диалог на ТВ могли отклонить или не заметить — статус тогда не придёт
                    if (start) {
                        window.decorView.postDelayed(
                            { mRemotePending = false },
                            REMOTE_PENDING_TIMEOUT_MS
                        )
                    }
                }.onFailure { error ->
                    mRemotePending = false
                    val message = error.message ?: getString(R.string.remote_error_offline)
                    // Отказ ТВ из-за настроек (пустой адрес и т.п.) ведёт в настройки — это
                    // настройки телевизора, и поправить их можно прямо отсюда
                    if (start && error is RemoteCallException) {
                        mSetupRequiredMessage = message
                    } else {
                        mRemoteError = message
                    }
                }
            }
        }, "remote-capture").start()
    }

    private fun toggleScreenCapture() {
        if (!mRecorderRunning) {
            // Ловим отсутствующие адрес, порт и количество светодиодов здесь, иначе пользователь
            // пройдёт диалоги наложения и MediaProjection только ради toast от сервиса.
            ScreenGrabberService.validateSettings(this)?.let { error ->
                Log.d(TAG, "Start aborted, settings incomplete: ${error.code}")
                AnalyticsHelper.logServiceError(this, "setup_required_${error.code}", error.details)
                mSetupRequiredMessage = error.message
                return
            }

            val prefs = Preferences(this)
            prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            mLastError = null
            val captureSource =
                prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"

            if (captureSource == "camera") {
                requestCameraCapture()
            } else {
                ensureUsbPermissionForAdalight {
                    requestScreenCapture()
                }
            }
        } else {
            Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
            stopScreenRecorder()
            mRecorderRunning = false
            finishCaptureSession("user_stop")
        }
    }

    private fun requestScreenCapture() {
        val prefs = Preferences(this)
        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")

        if (method == "accessibility") {
            if (AccessibilityCaptureService.getInstance() == null) {
                Toast.makeText(
                    this,
                    getString(R.string.accessibility_enable_prompt),
                    Toast.LENGTH_LONG
                ).show()
                openAccessibilitySettings(this)
                return
            }
        }

        if (method != "media_projection") {
            Log.d(TAG, "Alternative capture mode ($method) enabled — starting service directly")
            // startScreencapRecorder обрабатывает все альтернативные методы; имя оставлено прежним ради совместимости с BootActivity
            BootActivity.startAlternativeRecorder(this)
            mRecorderRunning = true
            beginCaptureSession(
                source = "screen",
                method = method ?: "unknown",
                protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion")
                    ?: "hyperion"
            )
            return
        }

        // На TCL и других устройствах с ограничениями сначала пробуем shell-обход
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.d(TAG, "Detected TCL/restricted device, trying shell bypass")
            TclBypass.tryShellBypass(this)
        }

        // Заодно пробуем общие shell-разрешения
        PermissionHelper.tryGrantProjectMediaViaShell(this)

        // Наложение спрашиваем один раз: если его не выдали, идём дальше к диалогу захвата.
        // Иначе на Fire TV Stick (Fire OS 7), где наложение не выдано, кнопка запуска
        // каждый раз возвращается к запросу overlay и до MediaProjection не доходит.
        if (mPermissionDeniedCount == 0 && !mOverlayRequested
            && !PermissionHelper.canDrawOverlays(this)
        ) {
            mOverlayRequested = true
            Log.d(TAG, "Requesting overlay permission first")
            AnalyticsHelper.logPermissionRequested(this, "SYSTEM_ALERT_WINDOW")
            PermissionHelper.requestOverlayPermission(this, REQUEST_OVERLAY_PERMISSION)
            return
        }

        val projectionManager = mMediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is null; cannot request screen capture")
            return
        }

        try {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: SecurityException) {
            Log.e(TAG, "Screen capture permission denied: " + e.message)
            mPermissionDeniedCount++
            if (TclBypass.isTclDevice()) {
                TclBypass.showTclHelpDialog(this) { requestScreenCapture() }
            } else {
                PermissionHelper.showFullPermissionDialog(this) { requestScreenCapture() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request screen capture: " + e.message)
            Toast.makeText(
                this,
                "Failed to request screen recording: " + e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AnalyticsHelper.logPermissionRequested(this, "CAMERA")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCameraGrabber()
        }
    }

    private fun startCameraGrabber() {
        val prefs = Preferences(this)
        val protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        AnalyticsHelper.logProtocolStarted(this, protocol)
        AnalyticsHelper.logScreenCaptureStarted(this, "camera")

        val intent = Intent(this, ScreenGrabberService::class.java)
        intent.action = ScreenGrabberService.ACTION_START_CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        mRecorderRunning = true
        beginCaptureSession("camera", "camera", protocol)

        ReviewHelper.onLightingStarted(this)
    }

    /**
     * Перед запуском захвата для Adalight проверяем и запрашиваем разрешение на USB.
     * Для Hyperion и WLED просто выполняем [onReady].
     */
    private fun ensureUsbPermissionForAdalight(onReady: () -> Unit) {
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        if (connectionType != "adalight") {
            onReady()
            return
        }

        UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
            context = this,
            device = null,
            onReady = onReady,
            onDenied = null,
            showToast = true,
            force = true
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UPDATE_CODE) {
            if (resultCode == RESULT_OK) {
                AnalyticsHelper.logAppUpdateCompleted(this)
            } else {
                Log.e(TAG, "Update flow failed! Result code: $resultCode")
                AnalyticsHelper.logAppUpdateCancelled(this)
                // Если обновление отменено или не удалось, его можно запросить снова.
            }
        }
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK) {
                mPermissionDeniedCount++
                mRecorderRunning = false
                // Подсветка так и не включилась — автозапуску нечего возвращать
                Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
                if (mPermissionDeniedCount >= 2) {
                    if (TclBypass.isTclDevice()) {
                        TclBypass.showTclHelpDialog(this) { requestScreenCapture() }
                    } else {
                        PermissionHelper.showFullPermissionDialog(this) { requestScreenCapture() }
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Screen recording permission was denied. Tap again to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
            mPermissionDeniedCount = 0
            mTclWarningShown = false
            Log.i(TAG, "Starting screen capture")
            if (data != null) {
                val prefs = Preferences(this)
                val protocol =
                    prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
                AnalyticsHelper.logProtocolStarted(this, protocol)
                AnalyticsHelper.logScreenCaptureStarted(this, protocol)

                startScreenRecorder(resultCode, data)
                mRecorderRunning = true
                beginCaptureSession("screen", "media_projection", protocol)

                ReviewHelper.onLightingStarted(this)
            }
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (PermissionHelper.canDrawOverlays(this)) {
                AnalyticsHelper.logPermissionGranted(this, "SYSTEM_ALERT_WINDOW")
            } else {
                AnalyticsHelper.logPermissionDenied(this, "SYSTEM_ALERT_WINDOW")
            }
            // За полсекунды активити могут закрыть или пересоздать — колбэк на мёртвом
            // экземпляре запустил бы диалог с BadTokenException
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) requestScreenCapture()
            }, 500)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AnalyticsHelper.logPermissionGranted(this, "POST_NOTIFICATIONS")
                } else {
                    AnalyticsHelper.logPermissionDenied(this, "POST_NOTIFICATIONS")
                    Toast.makeText(
                        this,
                        "Notification permission is needed for the foreground service",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AnalyticsHelper.logPermissionGranted(this, "CAMERA")
                startCameraGrabber()
            } else {
                AnalyticsHelper.logPermissionDenied(this, "CAMERA")
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkForInstance() {
        if (isServiceRunning()) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.GET_STATUS
            startService(intent)
        }
    }

    fun startScreenRecorder(resultCode: Int, data: Intent) {
        BootActivity.startScreenRecorder(this, resultCode, data)
    }

    fun stopScreenRecorder() {
        if (mRecorderRunning) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_EXIT
            startService(intent)
        }
    }

    private fun checkForUpdates() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            // Задача Play Core переживает активити: ответ на медленной сети приходит уже
            // после пересоздания, и запуск IntentSender на мёртвом экземпляре теряет результат
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                // Здесь применяется немедленное обновление; для гибкого нужно передать
                // AppUpdateType.FLEXIBLE.
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Запрашиваем обновление
                try {
                    AnalyticsHelper.logAppUpdateRequested(this, "immediate")
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        REQUEST_UPDATE_CODE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start update flow", e)
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean = ScreenGrabberService.sInstanceRunning

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_NOTIFICATION_PERMISSION = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
        private const val REQUEST_UPDATE_CODE = 4
        private const val REQUEST_CAMERA_PERMISSION = 5
        private const val TAG = "MainActivity"
        private const val PREF_NOTIF_PERMISSION_ASKED = "notif_permission_asked"
        private const val REMOTE_PENDING_TIMEOUT_MS = 45_000L
    }
}
