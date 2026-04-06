package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.provider.Settings
import android.widget.Toast
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings

class BootActivity : AppCompatActivity() {

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launched by USB_DEVICE_ATTACHED — handle USB device attachment
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent?.action) {
            handleUsbDeviceAttached()
            return
        }

        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        val captureMethod = prefs.getString(R.string.pref_key_capture_method, "media_projection")

        if (captureMethod == "accessibility") {
            if (AccessibilityCaptureService.getInstance() == null) {
                Toast.makeText(this, getString(R.string.accessibility_enable_prompt), Toast.LENGTH_LONG).show()
                openAccessibilitySettings(this)
                finish()
                return
            }
        }

        // For Adalight: ensure USB permission BEFORE starting any capture mode
        if ("adalight".equals(connectionType, ignoreCase = true)) {
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this,
                device = null,
                onReady = { startCaptureAfterPermission(captureMethod) },
                onDenied = { finish() },
                showToast = true
            )
        } else {
            startCaptureAfterPermission(captureMethod)
        }
    }

    private fun startCaptureAfterPermission(captureMethod: String?) {
        if (captureMethod != "media_projection") {
            startAlternativeRecorder(this)
            finish()
            return
        }
        requestMediaProjection()
    }

    /**
     * Handles launch from USB_DEVICE_ATTACHED intent.
     * If connection type is adalight: ensures USB permission for the attached device.
     * If auto-boot is enabled: starts the capture service.
     */
    private fun handleUsbDeviceAttached() {
        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        if (!"adalight".equals(connectionType, ignoreCase = true)) {
            finish()
            return
        }

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
            context = this,
            device = device,
            onReady = {
                // If auto-boot is enabled and lighting was active before reboot, start the capture service
                val autoStart = prefs.getBoolean(R.string.pref_key_boot)
                val wasActive = prefs.getBoolean(R.string.pref_key_lighting_was_active)
                if (autoStart && wasActive) {
                    val captureMethod = prefs.getString(R.string.pref_key_capture_method, "media_projection")
                    if (captureMethod != "media_projection") {
                        startAlternativeRecorder(this)
                        finish()
                    } else {
                        requestMediaProjection()
                    }
                } else {
                    finish()
                }
            },
            onDenied = { finish() },
            showToast = false
        )
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager != null) {
            try {
                startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, R.string.error_screen_recording_not_available, android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onResume() {
        try {
            super.onResume()
        } catch (e: IllegalArgumentException) {
            // On some firmware versions ActivityManagerService.isTopOfTask() throws
            // IllegalArgumentException via Binder when the task is in an unexpected state.
            // BootActivity is a no-history pass-through activity, so finishing here is safe.
            Log.w(TAG, "onResume: isTopOfTask() Binder exception, finishing: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startScreenRecorder(this, resultCode, data)
            }
            finish()
        }
    }

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val TAG = "BootActivity"

        @JvmStatic
        fun startScreenRecorder(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_START
            intent.putExtra(ScreenGrabberService.EXTRA_RESULT_CODE, resultCode)
            intent.putExtra(ScreenGrabberService.EXTRA_RESULT_DATA, data)
            startForegroundServiceCompat(context, intent)
        }

        @JvmStatic
        fun startAlternativeRecorder(context: Context) {
            val intent = Intent(context, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_START
            startForegroundServiceCompat(context, intent)
        }

        // startForegroundService() called from onActivityResult can fail on Android 12+
        // with ForegroundServiceStartNotAllowedException because onActivityResult is
        // delivered before the activity is fully foregrounded (mAllowStartForeground = false).
        // Retry on the next main looper cycle, by which time the system considers the app
        // foreground again. If the retry also fails, fall back to startService() as last resort.
        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                context.startService(intent)
                return
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "startForegroundService not allowed yet, retrying on next loop: ${e.message}")
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            context.startForegroundService(intent)
                        } catch (e2: Exception) {
                            Log.e(TAG, "startForegroundService retry failed, falling back to startService", e2)
                            context.startService(intent)
                        }
                    }, 200)
                } else {
                    Log.e(TAG, "startForegroundService failed", e)
                }
            }
        }
    }
}
