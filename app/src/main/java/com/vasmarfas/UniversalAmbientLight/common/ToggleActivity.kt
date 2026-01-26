package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

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
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
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
    }
}
