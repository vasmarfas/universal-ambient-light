package com.vasmarfas.UniversalAmbientLight.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings

class BootActivity : AppCompatActivity() {

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запуск по USB_DEVICE_ATTACHED — обрабатываем подключение USB-устройства
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent?.action) {
            handleUsbDeviceAttached()
            return
        }

        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        val captureMethod = prefs.getString(R.string.pref_key_capture_method, "media_projection")

        if (captureMethod == "accessibility") {
            if (AccessibilityCaptureService.getInstance() == null) {
                Toast.makeText(
                    this,
                    getString(R.string.accessibility_enable_prompt),
                    Toast.LENGTH_LONG
                ).show()
                openAccessibilitySettings(this)
                finish()
                return
            }
        }

        // Для Adalight разрешение на USB получаем ДО запуска любого режима захвата
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
     * Обрабатывает запуск по интенту USB_DEVICE_ATTACHED.
     * Для Adalight добивается разрешения на подключённое устройство.
     * Если включён автозапуск, поднимает сервис захвата.
     */
    private fun handleUsbDeviceAttached() {
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

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
                // Автозапуск включён и подсветка работала до перезагрузки — поднимаем сервис захвата
                val autoStart = prefs.getBoolean(R.string.pref_key_boot)
                val wasActive = prefs.getBoolean(R.string.pref_key_lighting_was_active)
                if (autoStart && wasActive) {
                    val captureMethod =
                        prefs.getString(R.string.pref_key_capture_method, "media_projection")
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
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (manager != null) {
            try {
                startActivityForResult(
                    manager.createScreenCaptureIntent(),
                    REQUEST_MEDIA_PROJECTION
                )
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.error_screen_recording_not_available,
                    Toast.LENGTH_LONG
                ).show()
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
            // Часть прошивок (Amazon Fire TV на Android 9) бросает IAE внутри AMS.isTopOfTask
            // до того, как super.onResume выставит mCalled, и следом прилетает
            // SuperNotCalledException. Ставим mCalled рефлексией и закрываемся штатно.
            Log.w(TAG, "onResume: super threw IAE, recovering: ${e.message}")
            try {
                val field = Activity::class.java.getDeclaredField("mCalled")
                field.isAccessible = true
                field.setBoolean(this, true)
            } catch (refEx: Throwable) {
                Log.w(TAG, "Could not patch mCalled via reflection: ${refEx.message}")
            }
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                startScreenRecorder(this, resultCode, data)
            } else {
                // Отказ в диалоге — тоже решение пользователя: иначе сторож автозапуска
                // показывал бы этот диалог снова каждые несколько минут
                Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
                ScreenGrabberService.notifyNotStarted(
                    this,
                    getString(R.string.error_media_projection_denied)
                )
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

        // startForegroundService() из onActivityResult на Android 12+ падает с
        // ForegroundServiceStartNotAllowedException: onActivityResult приходит раньше, чем
        // активити окончательно окажется на переднем плане (mAllowStartForeground = false).
        // Повторяем на следующем цикле главного looper'а, когда система уже считает приложение
        // активным. Если и это не сработало, в последнюю очередь пробуем startService().
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
                    Log.w(
                        TAG,
                        "startForegroundService not allowed yet, retrying on next loop: ${e.message}"
                    )
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            context.startForegroundService(intent)
                        } catch (e2: Exception) {
                            Log.e(
                                TAG,
                                "startForegroundService retry failed, falling back to startService",
                                e2
                            )
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
