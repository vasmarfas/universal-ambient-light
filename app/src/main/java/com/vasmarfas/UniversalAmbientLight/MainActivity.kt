package com.vasmarfas.UniversalAmbientLight

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.compose.rememberNavController
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.vasmarfas.UniversalAmbientLight.common.BootActivity
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.ReviewHelper
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper
import android.content.ActivityNotFoundException
import android.net.Uri
import com.vasmarfas.UniversalAmbientLight.ui.navigation.AppNavHost
import com.vasmarfas.UniversalAmbientLight.ui.theme.AppTheme
import kotlin.math.sqrt
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private var mRecorderRunning by mutableStateOf(false)
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mPermissionDeniedCount = 0
    private var mTclWarningShown = false
    private lateinit var appUpdateManager: AppUpdateManager
    private var currentEffect by mutableStateOf(EffectMode.RAINBOW)
    private var mSessionStartTime: Long? = null

    private var usbPermissionReceiverRegistered = false
    private var usbAttachReceiverRegistered = false

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED != intent.action) return

            val prefs = Preferences(this@MainActivity)
            val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            if (!"adalight".equals(connectionType, ignoreCase = true)) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            // Only request permission for devices that our USB-Serial prober recognizes
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
            
            if (wasRunning && !checked && mSessionStartTime != null) {
                val durationSeconds = ((System.currentTimeMillis() - mSessionStartTime!!) / 1000).coerceAtLeast(0)
                AnalyticsHelper.logScreenCaptureStopped(this@MainActivity, durationSeconds)
                mSessionStartTime = null
            }
            
            val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            val tclBlocked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TCL_BLOCKED, false)

            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true
                TclBypass.showTclHelpDialog(this@MainActivity) { requestScreenCapture() }
            } else if (error != null &&
                (false ||
                        !QuickTileService.isListening)
            ) {
                Toast.makeText(baseContext, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable Edge-to-Edge mode manually to avoid using deprecated LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // which Google Play flags in Android 15. Android 15+ (API 35+) requires LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS by default.
        // This mode is available from Android 11 (API 30).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        AnalyticsHelper.logAppLaunched(this)

        mMediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize Play In-App Updates
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdates()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            mMessageReceiver, IntentFilter(ScreenGrabberService.BROADCAST_FILTER)
        )
        checkForInstance()

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        maybeRequestBatteryOptimizationExemption()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        navController = navController,
                        isRunning = mRecorderRunning,
                        onToggleClick = { toggleScreenCapture() },
                        onEffectsClick = { 
                            currentEffect = currentEffect.next()
                            AnalyticsHelper.logEffectChanged(this@MainActivity, currentEffect.name.lowercase())
                        },
                        effectMode = currentEffect
                    )
                }
            }
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        if (PermissionHelper.isIgnoringBatteryOptimizations(this)) return

        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val keyLastAttempt = "battery_opt_exemption_last_attempt_ms"
        val lastAttempt = prefs.getLong(keyLastAttempt, 0L)
        val now = System.currentTimeMillis()
        val cooldownMs = 24L * 60L * 60L * 1000L // 24h
        if (now - lastAttempt < cooldownMs) return

        prefs.edit { putLong(keyLastAttempt, now) }

        AnalyticsHelper.logBatteryOptimizationRequested(this)

        PermissionHelper.requestIgnoreBatteryOptimizations(this)
    }

    override fun onResume() {
        super.onResume()

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability()
                    == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {
                    // If an in-app update is already running, resume the update.
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        REQUEST_UPDATE_CODE
                    )
                }
            }

        // Auto-request USB permission when a USB-Serial device is already connected while app is open.
        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)

        if (usbAttachReceiverRegistered) {
            try {
                unregisterReceiver(usbAttachReceiver)
            } catch (_: Exception) {
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
            }
            usbAttachReceiverRegistered = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun toggleScreenCapture() {
        if (!mRecorderRunning) {
            val prefs = Preferences(this)
            val captureSource = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"

            if (captureSource == "camera") {
                requestCameraCapture()
            } else {
                ensureUsbPermissionForAdalight {
                    requestScreenCapture()
                }
            }
        } else {
            stopScreenRecorder()
            mRecorderRunning = false
            val durationSeconds = mSessionStartTime?.let { startTime ->
                ((System.currentTimeMillis() - startTime) / 1000).coerceAtLeast(0)
            }
            AnalyticsHelper.logScreenCaptureStopped(this, durationSeconds)
            mSessionStartTime = null
        }
    }

    private fun requestScreenCapture() {
        // On TCL and other restricted devices, try shell bypass first
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.d(TAG, "Detected TCL/restricted device, trying shell bypass")
            TclBypass.tryShellBypass(this)
        }

        // Also try general shell permissions
        PermissionHelper.tryGrantProjectMediaViaShell(this)

        // Check overlay permission on first attempt
        if (mPermissionDeniedCount == 0 && !PermissionHelper.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission first")
            AnalyticsHelper.logPermissionRequested(this, "SYSTEM_ALERT_WINDOW")
            PermissionHelper.requestOverlayPermission(this, REQUEST_OVERLAY_PERMISSION)
            return
        }

        try {
            val captureIntent = mMediaProjectionManager!!.createScreenCaptureIntent()
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
            Toast.makeText(this, "Failed to request screen recording: " + e.message, Toast.LENGTH_LONG).show()
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
        mSessionStartTime = System.currentTimeMillis()

        ReviewHelper.onLightingStarted(this)
    }

    /**
     * Before starting screen capture for Adalight, check and request USB permission.
     * For Hyperion/WLED, just execute [onReady].
     */
    private fun ensureUsbPermissionForAdalight(onReady: () -> Unit) {
        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        if (connectionType != "adalight") {
            onReady()
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            Toast.makeText(this, "USB service is not available on this device", Toast.LENGTH_LONG).show()
            onReady()
            return
        }

        val drivers = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Toast.makeText(this, "No USB serial devices found. Please connect your Adalight device via USB OTG cable", Toast.LENGTH_LONG).show()
            onReady()
            return
        }

        val device = drivers[0].device
        if (usbManager.hasPermission(device)) {
            onReady()
            return
        }

        AnalyticsHelper.logUsbPermissionRequested(this)

        val permissionIntent = android.app.PendingIntent.getBroadcast(
            this,
            0,
            Intent("com.vasmarfas.UniversalAmbientLight.USB_PERMISSION"),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        if (!usbPermissionReceiverRegistered) {
            val filter = IntentFilter("com.vasmarfas.UniversalAmbientLight.USB_PERMISSION")
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    unregisterReceiver(this)
                    usbPermissionReceiverRegistered = false

                    if (granted) {
                        AnalyticsHelper.logUsbPermissionGranted(this@MainActivity)
                        onReady()
                    } else {
                        AnalyticsHelper.logUsbPermissionDenied(this@MainActivity)
                        Toast.makeText(
                            this@MainActivity,
                            "USB device permission denied. Please allow USB access or grant it in Android Settings > Apps > Hyperion Grabber > Permissions",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            usbPermissionReceiverRegistered = true
        }

        usbManager.requestPermission(device, permissionIntent)
        Toast.makeText(this, "Please confirm USB access for your Adalight device", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UPDATE_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                AnalyticsHelper.logAppUpdateCompleted(this)
            } else {
                Log.e(TAG, "Update flow failed! Result code: $resultCode")
                AnalyticsHelper.logAppUpdateCancelled(this)
                // If the update is cancelled or fails,
                // you can request to start the update again.
            }
        }
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                mPermissionDeniedCount++
                mRecorderRunning = false
                if (mPermissionDeniedCount >= 2) {
                    if (TclBypass.isTclDevice()) {
                        TclBypass.showTclHelpDialog(this) { requestScreenCapture() }
                    } else {
                        PermissionHelper.showFullPermissionDialog(this) { requestScreenCapture() }
                    }
                } else {
                    Toast.makeText(this, "Screen recording permission was denied. Tap again to retry.", Toast.LENGTH_LONG).show()
                }
                return
            }
            mPermissionDeniedCount = 0
            mTclWarningShown = false
            Log.i(TAG, "Starting screen capture")
            if (data != null) {
                val prefs = Preferences(this)
                val protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
                AnalyticsHelper.logProtocolStarted(this, protocol)
                AnalyticsHelper.logScreenCaptureStarted(this, protocol)
                
                startScreenRecorder(resultCode, (data.clone() as Intent))
                mRecorderRunning = true
                mSessionStartTime = System.currentTimeMillis()
                
                ReviewHelper.onLightingStarted(this)
            }
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (PermissionHelper.canDrawOverlays(this)) {
                AnalyticsHelper.logPermissionGranted(this, "SYSTEM_ALERT_WINDOW")
            } else {
                AnalyticsHelper.logPermissionDenied(this, "SYSTEM_ALERT_WINDOW")
            }
            window.decorView.postDelayed({ requestScreenCapture() }, 500)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AnalyticsHelper.logPermissionGranted(this, "POST_NOTIFICATIONS")
                } else {
                    AnalyticsHelper.logPermissionDenied(this, "POST_NOTIFICATIONS")
                    Toast.makeText(this, "Notification permission is needed for the foreground service", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AnalyticsHelper.logPermissionGranted(this, "CAMERA")
                startCameraGrabber()
            } else {
                AnalyticsHelper.logPermissionDenied(this, "CAMERA")
                Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
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
                    if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                // This example applies an immediate update. To apply a flexible update
                // instead, pass in AppUpdateType.FLEXIBLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Request the update
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

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (ScreenGrabberService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_NOTIFICATION_PERMISSION = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
        private const val REQUEST_UPDATE_CODE = 4
        private const val REQUEST_CAMERA_PERMISSION = 5
        private const val TAG = "DEBUG"
    }
}

enum class EffectMode {
    RAINBOW,
    SIDE_COLORS,
    MOVING_BAR,
    SOLID_WHITE,
    SOLID_RED,
    SOLID_GREEN,
    SOLID_BLUE,
    BREATHING,
    VERTICAL_BARS,
    HORIZONTAL_BARS;
}

private fun EffectMode.next(): EffectMode =
    when (this) {
        EffectMode.RAINBOW -> EffectMode.SIDE_COLORS
        EffectMode.SIDE_COLORS -> EffectMode.MOVING_BAR
        EffectMode.MOVING_BAR -> EffectMode.SOLID_WHITE
        EffectMode.SOLID_WHITE -> EffectMode.SOLID_RED
        EffectMode.SOLID_RED -> EffectMode.SOLID_GREEN
        EffectMode.SOLID_GREEN -> EffectMode.SOLID_BLUE
        EffectMode.SOLID_BLUE -> EffectMode.BREATHING
        EffectMode.BREATHING -> EffectMode.VERTICAL_BARS
        EffectMode.VERTICAL_BARS -> EffectMode.HORIZONTAL_BARS
        EffectMode.HORIZONTAL_BARS -> EffectMode.RAINBOW
    }

@Composable
fun MainScreen(
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEffectsClick: () -> Unit,
    effectMode: EffectMode,
    captureSource: String = "screen",
    onHelpClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
    onLeaveReviewClick: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera mode background — show camera preview with corners
        if (captureSource == "camera") {
            com.vasmarfas.UniversalAmbientLight.ui.camera.CameraPreviewBackground(isCapturing = isRunning)
        }

        // Screen mode: Effects Background (only when running AND screen mode)
        if (isRunning && captureSource != "camera") {
            val infiniteTransition = rememberInfiniteTransition(label = "effects")

            when (effectMode) {
                EffectMode.RAINBOW -> {
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val diagonal = sqrt(size.width * size.width + size.height * size.height)

                                rotate(angle) {
                                    drawCircle(
                                        brush = Brush.sweepGradient(
                                            colors = listOf(
                                                Color.Red,
                                                Color.Magenta,
                                                Color.Blue,
                                                Color.Cyan,
                                                Color.Green,
                                                Color.Yellow,
                                                Color.Red
                                            )
                                        ),
                                        radius = diagonal / 2
                                    )
                                }
                            }
                    )
                }

                EffectMode.SIDE_COLORS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val thickness = h * 0.12f

                                // Top - Red
                                drawRect(
                                    color = Color.Red,
                                    size = androidx.compose.ui.geometry.Size(w, thickness)
                                )
                                // Bottom - Blue
                                drawRect(
                                    color = Color.Blue,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, h - thickness),
                                    size = androidx.compose.ui.geometry.Size(w, thickness)
                                )
                                // Left - Yellow
                                drawRect(
                                    color = Color.Yellow,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    size = androidx.compose.ui.geometry.Size(thickness, h)
                                )
                                // Right - Green
                                drawRect(
                                    color = Color.Green,
                                    topLeft = androidx.compose.ui.geometry.Offset(w - thickness, 0f),
                                    size = androidx.compose.ui.geometry.Size(thickness, h)
                                )
                            }
                    )
                }

                EffectMode.MOVING_BAR -> {
                    val offset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(3000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "movingBar"
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val barWidth = w * 0.12f
                                val x = (w + barWidth) * offset - barWidth

                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta)
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                                    size = androidx.compose.ui.geometry.Size(barWidth, h)
                                )
                            }
                    )
                }

                EffectMode.SOLID_WHITE,
                EffectMode.SOLID_RED,
                EffectMode.SOLID_GREEN,
                EffectMode.SOLID_BLUE,
                    -> {
                    val color = when (effectMode) {
                        EffectMode.SOLID_WHITE -> Color.White
                        EffectMode.SOLID_RED -> Color.Red
                        EffectMode.SOLID_GREEN -> Color.Green
                        EffectMode.SOLID_BLUE -> Color.Blue
                        else -> Color.White
                    }
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color)
                    )
                }

                EffectMode.BREATHING -> {
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "breathing"
                    )
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Cyan.copy(alpha = alpha))
                    )
                }

                EffectMode.VERTICAL_BARS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val colors = listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta
                                )
                                val barWidth = w / colors.size
                                colors.forEachIndexed { index, c ->
                                    drawRect(
                                        color = c,
                                        topLeft = androidx.compose.ui.geometry.Offset(index * barWidth, 0f),
                                        size = androidx.compose.ui.geometry.Size(barWidth, h)
                                    )
                                }
                            }
                    )
                }

                EffectMode.HORIZONTAL_BARS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val colors = listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta
                                )
                                val barHeight = h / colors.size
                                colors.forEachIndexed { index, c ->
                                    drawRect(
                                        color = c,
                                        topLeft = androidx.compose.ui.geometry.Offset(0f, index * barHeight),
                                        size = androidx.compose.ui.geometry.Size(w, barHeight)
                                    )
                                }
                            }
                    )
                }
            }
        }

        // Center Content with control buttons row
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var effectsFocused by remember { mutableStateOf(false) }
            var powerFocused by remember { mutableStateOf(false) }
            var settingsFocused by remember { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Effects Button (left)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .border(
                            width = if (effectsFocused) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onEffectsClick,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { effectsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Effects",
                            modifier = Modifier.size(40.dp),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Power Button (center, largest)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            brush = if (isRunning) Brush.sweepGradient(
                                listOf(
                                    Color.Red,
                                    Color.Magenta,
                                    Color.Blue,
                                    Color.Cyan,
                                    Color.Green,
                                    Color.Yellow,
                                    Color.Red
                                )
                            ) else Brush.linearGradient(listOf(Color.Gray, Color.Gray)),
                            shape = CircleShape
                        )
                        .border(
                            width = if (powerFocused) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .padding(4.dp) // Border width
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onToggleClick,
                        modifier = Modifier
                            .size(112.dp)
                            .onFocusChanged { powerFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle Power",
                            modifier = Modifier
                                .size(64.dp)
                                .alpha(if (isRunning) 1f else 0.25f),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Settings Button (right, smaller than power)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .border(
                            width = if (settingsFocused) 3.dp else 0.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { settingsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(40.dp),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status Text
            if (isRunning) {
                Text(
                    text = stringResource(id = R.string.status_grabber_running),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Help and Support buttons column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                var helpFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onHelpClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { helpFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = stringResource(R.string.help),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.help))
                }

                 var supportFocused by remember { mutableStateOf(false) }
                 OutlinedButton(
                     onClick = onSupportClick,
                     modifier = Modifier
                         .fillMaxWidth()
                         .onFocusChanged { supportFocused = it.isFocused }
                 ) {
                     Icon(
                         imageVector = Icons.Default.Favorite,
                         contentDescription = stringResource(R.string.support_project),
                         modifier = Modifier.size(20.dp),
                         tint = MaterialTheme.colorScheme.error
                     )
                     Spacer(modifier = Modifier.width(8.dp))
                     Text(stringResource(R.string.support_project))
                 }

                var reportIssueFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onReportIssueClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { reportIssueFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = stringResource(R.string.report_issue),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.report_issue))
                }

                var leaveReviewFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onLeaveReviewClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { leaveReviewFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.leave_review),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.leave_review))
                }
            }
        }
    }
}

@Composable
fun HelpDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.help_title))
        },
        text = {
            Text(stringResource(R.string.help_message))
        },
        confirmButton = {
            TextButton(onClick = onOpenGitHub) {
                Text(stringResource(R.string.help_open_github))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

/**
 * Opens GitHub issues/new page
 */
fun openGitHubIssues(context: Context) {
    val url = context.getString(R.string.github_issues_url)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Fallback: try to open in browser
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            context.startActivity(browserIntent)
        } catch (e2: Exception) {
            // If all else fails, show toast or log
            android.util.Log.e("MainActivity", "Failed to open GitHub issues: ${e2.message}")
        }
    }
}

/**
 * Opens Google Play review dialog
 */
fun openGooglePlayReview(context: Context) {
    if (context is Activity) {
        ReviewHelper.forceShowReview(context)
    }
}

/**
 * Rating dialog with 1-5 stars
 */
@Composable
fun RatingDialog(
    onDismiss: () -> Unit,
    onRatingSelected: (Int) -> Unit
) {
    var selectedRating by remember { mutableStateOf(0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.rating_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.rating_dialog_message),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 1..5) {
                        IconButton(
                            onClick = { selectedRating = i }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "$i stars",
                                tint = if (i <= selectedRating) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                },
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedRating > 0) {
                        onRatingSelected(selectedRating)
                    }
                },
                enabled = selectedRating > 0
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rating_dialog_cancel))
            }
        }
    )
}

/**
 * Dialog shown after low rating (1-3 stars)
 */
@Composable
fun LowRatingDialog(
    onDismiss: () -> Unit,
    onReportIssue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.rating_dialog_low_rating_title))
        },
        text = {
            Text(stringResource(R.string.rating_dialog_low_rating_message))
        },
        confirmButton = {
            TextButton(onClick = onReportIssue) {
                Text(stringResource(R.string.rating_dialog_report_issue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rating_dialog_cancel))
            }
        }
    )
}

@Composable
fun SupportDialog(
    onDismiss: () -> Unit,
    onOpenSupport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.support_project_title))
        },
        text = {
            Text(stringResource(R.string.support_project_message))
        },
        confirmButton = {
            TextButton(onClick = onOpenSupport) {
                Text(stringResource(R.string.support_open_details))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

@Composable
fun UrlDialog(
    url: String,
    onDismiss: () -> Unit,
    onOpenLink: (() -> Unit)? = null
) {
    val qrBitmap = remember(url) { generateQRCode(url, 400) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.url_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.url_dialog_message),
                    textAlign = TextAlign.Center
                )
                
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = stringResource(R.string.url_dialog_qr_description),
                        modifier = Modifier.size(250.dp)
                    )
                }
                
                SelectionContainer {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (onOpenLink != null) {
                TextButton(onClick = onOpenLink) {
                    Text(stringResource(R.string.url_dialog_open_link))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

private fun generateQRCode(content: String, size: Int): ImageBitmap? {
    return try {
        val hints = hashMapOf<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }
        
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.Black.toArgb() else Color.White.toArgb())
            }
        }
        
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        Log.e("UrlDialog", "Failed to generate QR code", e)
        null
    }
}
