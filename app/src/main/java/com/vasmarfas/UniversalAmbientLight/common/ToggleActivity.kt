package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
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

        // Явный action приходит от внешней автоматизации (KeyMapper, Tasker, adb);
        // без action активность остаётся переключателем, как ярлык на рабочем столе.
        when (intent?.action) {
            ACTION_TURN_ON -> if (serviceRunning) finish() else requestPermission()
            ACTION_TURN_OFF -> {
                if (serviceRunning) stopService()
                finish()
            }
            else -> if (serviceRunning) {
                stopService()
                finish()
            } else {
                requestPermission()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startScreenRecorder(this, resultCode, data)
            } else {
                Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
            }

            finish()
        }
    }

    /** @return запущен ли сервис
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
        get() = ScreenGrabberService.sInstanceRunning

    private fun requestPermission() {
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        val requestMediaProjection = {
            val captureMethod =
                prefs.getString(R.string.pref_key_capture_method, "media_projection")
            if (captureMethod != "media_projection") {
                // Методы без MediaProjection поднимаем сразу, как в BootActivity: иначе
                // включение с пульта или ярлыка каждый раз упиралось бы в диалог захвата.
                startScreenRecorderDirect(this)
                finish()
            } else {
                val manager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                if (manager != null) {
                    try {
                        startActivityForResult(
                            manager.createScreenCaptureIntent(),
                            REQUEST_MEDIA_PROJECTION
                        )
                    } catch (e: ActivityNotFoundException) {
                        // В части сторонних прошивок Android TV нет системной
                        // MediaProjectionPermissionActivity из SystemUI — стартовать нечем.
                        Log.w(TAG, "MediaProjection permission dialog unavailable: ${e.message}")
                        finish()
                    }
                } else {
                    finish()
                }
            }
        }

        // ToggleActivity вызывают ярлыком с рабочего стола и внешними действиями.
        // Для Adalight сначала берём разрешение на USB, иначе пришлось бы нажимать дважды.
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

    /** Останавливает запись и сам сервис  */

    private fun stopService() {
        val stopIntent = Intent(this@ToggleActivity, ScreenGrabberService::class.java)
        stopIntent.action = ScreenGrabberService.ACTION_EXIT
        startService(stopIntent)
    }

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val TAG = "ToggleActivity"

        const val ACTION_TURN_ON = "com.vasmarfas.UniversalAmbientLight.action.TURN_ON"
        const val ACTION_TURN_OFF = "com.vasmarfas.UniversalAmbientLight.action.TURN_OFF"

        // Запуск сервиса — через обёртку BootActivity: на Android 12+ голый
        // startForegroundService из onActivityResult падает с
        // ForegroundServiceStartNotAllowedException, а там этот случай уже обработан.
        private fun startScreenRecorder(context: Context, resultCode: Int, data: Intent) {
            BootActivity.startScreenRecorder(context, resultCode, data)
        }

        // Запускает сервис сразу, без токена MediaProjection — для альтернативных способов
        // захвата (screencap, adb, accessibility и прочих) на устройствах, где системного
        // диалога разрешения MediaProjection нет.
        private fun startScreenRecorderDirect(context: Context) {
            BootActivity.startAlternativeRecorder(context)
        }
    }
}
