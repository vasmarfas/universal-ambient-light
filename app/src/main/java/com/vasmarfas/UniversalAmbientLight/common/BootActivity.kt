package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity

class BootActivity : AppCompatActivity() {

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
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
            intent.putExtras(data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
