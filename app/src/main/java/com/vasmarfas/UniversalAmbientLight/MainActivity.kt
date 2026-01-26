package com.vasmarfas.UniversalAmbientLight

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vasmarfas.UniversalAmbientLight.common.BootActivity
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import com.vasmarfas.UniversalAmbientLight.ui.theme.AppTheme
import androidx.compose.animation.core.*
import androidx.compose.foundation.focusable
import androidx.navigation.compose.rememberNavController
import com.vasmarfas.UniversalAmbientLight.ui.navigation.AppNavHost
import kotlin.math.sqrt
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private var mRecorderRunning by mutableStateOf(false)
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mPermissionDeniedCount = 0
    private var mTclWarningShown = false
    private lateinit var appUpdateManager: AppUpdateManager

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val checked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
            mRecorderRunning = checked
            val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            val tclBlocked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TCL_BLOCKED, false)

            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true
                TclBypass.showTclHelpDialog(this@MainActivity) { requestScreenCapture() }
            } else if (error != null &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                        !QuickTileService.isListening)
            ) {
                Toast.makeText(baseContext, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        onToggleClick = { toggleScreenCapture() }
                    )
                }
            }
        }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
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
            requestScreenCapture()
        } else {
            stopScreenRecorder()
            mRecorderRunning = false
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UPDATE_CODE) {
            if (resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "Update flow failed! Result code: $resultCode")
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
                startScreenRecorder(resultCode, (data.clone() as Intent))
                mRecorderRunning = true
            }
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // Small delay before requesting capture - helps on some devices
            window.decorView.postDelayed({ requestScreenCapture() }, 500)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission is needed for the foreground service", Toast.LENGTH_LONG).show()
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
        private const val TAG = "DEBUG"
    }
}

@Composable
fun MainScreen(
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Rainbow Background
        if (isRunning) {
            val infiniteTransition = rememberInfiniteTransition(label = "rainbow")
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
                        // Calculate diagonal to cover the screen with a circle
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

        // Settings Button
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .focusable() // For TV D-pad navigation
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = if (isRunning) Color.White else MaterialTheme.colorScheme.onBackground
            )
        }

        // Center Content
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Power Button
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
                    .padding(4.dp) // Border width
                    .background(MaterialTheme.colorScheme.background, CircleShape)
            ) {
                IconButton(
                    onClick = onToggleClick,
                    modifier = Modifier
                        .size(112.dp)
                        .focusable() // For TV D-pad navigation
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

            Spacer(modifier = Modifier.height(24.dp))

            // Status Text
            if (isRunning) {
                Text(
                    text = "Grabber Started",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
