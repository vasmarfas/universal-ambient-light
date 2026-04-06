package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper

class ToggleActivity : AppCompatActivity() {

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceRunning = checkForInstance()

        if (serviceRunning) {
            stopService()
            finish()
        } else {
            requestPermission()
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

    /** @return whether the service is running
     */
    private fun checkForInstance(): Boolean {
        if (isServiceRunning) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.GET_STATUS
            startService(intent)
            return true
        } else {
            return false
        }
    }

    private val isServiceRunning: Boolean
        get() {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (ScreenGrabberService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun requestPermission() {
        val prefs = Preferences(this)
        val connectionType = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        val requestMediaProjection = {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (manager != null) {
                try {
                    startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
                } catch (e: ActivityNotFoundException) {
                    // Some custom Android TV firmware doesn't ship the standard SystemUI
                    // MediaProjectionPermissionActivity. Fall back to launching the service
                    // directly without MediaProjection if the configured method supports it.
                    Log.w(TAG, "MediaProjection permission dialog unavailable: ${e.message}")
                    val captureMethod = prefs.getString(R.string.pref_key_capture_method, "media_projection")
                    if (captureMethod != "media_projection") {
                        startScreenRecorderDirect(this)
                    }
                    finish()
                }
            } else {
                finish()
            }
        }

        // ToggleActivity is used from launcher shortcut / external actions.
        // If Adalight is selected, ensure USB permission first to avoid "tap twice" UX.
        if ("adalight".equals(connectionType, ignoreCase = true)) {
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this,
                device = null,
                onReady = requestMediaProjection,
                onDenied = { finish() },
                showToast = true,
                force = true
            )
        } else {
            requestMediaProjection()
        }
    }

    /** stop recording & stop service  */

    private fun stopService() {
        val stopIntent = Intent(this@ToggleActivity, ScreenGrabberService::class.java)
        stopIntent.action = ScreenGrabberService.ACTION_EXIT
        startService(stopIntent)
    }

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val TAG = "ToggleActivity"

        private fun startScreenRecorder(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_START
            intent.putExtra(ScreenGrabberService.EXTRA_RESULT_CODE, resultCode)
            intent.putExtras(data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Starts the service directly without MediaProjection token — for alternative
        // capture methods (screencap, adb, accessibility, etc.) on devices where the
        // system MediaProjection permission dialog is unavailable.
        private fun startScreenRecorderDirect(context: Context) {
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
