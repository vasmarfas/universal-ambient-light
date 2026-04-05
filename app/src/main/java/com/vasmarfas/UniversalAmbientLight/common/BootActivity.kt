package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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

        @JvmStatic
        fun startScreenRecorder(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_START
            intent.putExtra(ScreenGrabberService.EXTRA_RESULT_CODE, resultCode)
            intent.putExtra(ScreenGrabberService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun startAlternativeRecorder(context: Context) {
            val intent = Intent(context, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
