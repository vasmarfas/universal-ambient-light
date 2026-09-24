package com.vasmarfas.UniversalAmbientLight.common

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper

/**
 * Запуск и остановка подсветки без экрана приложения: команда с телефона, автозапуск после
 * загрузки, пробуждения ТВ или обновления. Решения те же, что у кнопки на главном экране,
 * но спросить пользователя здесь нечем — поэтому всё, что требует диалога (согласие
 * MediaProjection, разрешение на USB), уходит в [BootActivity], а остальное стартует сервис
 * напрямую: активити из фона Android 10+ может и не показать.
 */
object CaptureLauncher {
    private const val TAG = "CaptureLauncher"

    enum class Outcome {
        /** Сервис запущен; результат подключения придёт его статусом. */
        STARTED,

        /** Открыт системный диалог на экране ТВ — нужно подтвердить пультом. */
        NEEDS_CONFIRMATION,

        FAILED,
    }

    class Result(val outcome: Outcome, val message: String? = null)

    fun start(context: Context): Result {
        val app = context.applicationContext
        ScreenGrabberService.validateSettings(app)?.let { return Result(Outcome.FAILED, it.message) }

        val prefs = Preferences(app)
        prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
        if (ScreenGrabberService.sInstanceRunning) return Result(Outcome.STARTED)

        val source = prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
        if (source == "camera") {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return Result(Outcome.FAILED, app.getString(R.string.camera_permission_required))
            }
            return startService(app, ScreenGrabberService.ACTION_START_CAMERA)
        }

        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")
            ?: "media_projection"
        if (method == "accessibility" && AccessibilityCaptureService.getInstance() == null) {
            return Result(Outcome.FAILED, app.getString(R.string.accessibility_enable_prompt))
        }

        val adalight = "adalight".equals(
            prefs.getString(R.string.pref_key_connection_type, "hyperion"),
            ignoreCase = true
        )
        val needsUsbPermission = adalight && !hasUsbPermission(app)
        if (method != "media_projection" && !needsUsbPermission) {
            return startService(app, ScreenGrabberService.ACTION_START)
        }

        if (!launchBootActivity(app)) {
            return Result(Outcome.FAILED, app.getString(R.string.remote_error_activity_blocked))
        }
        val silent = !needsUsbPermission && PermissionHelper.hasProjectMediaPermission(app)
        return if (silent) {
            Result(Outcome.STARTED)
        } else {
            Result(Outcome.NEEDS_CONFIRMATION, app.getString(R.string.remote_confirm_on_tv))
        }
    }

    /** Остановка по воле пользователя: автозапуск больше не должен поднимать подсветку. */
    fun stop(context: Context) {
        val app = context.applicationContext
        Preferences(app).putBoolean(R.string.pref_key_lighting_was_active, false)
        if (!ScreenGrabberService.sInstanceRunning) return
        val intent = Intent(app, ScreenGrabberService::class.java)
        intent.action = ScreenGrabberService.ACTION_EXIT
        try {
            app.startService(intent)
        } catch (e: IllegalStateException) {
            // Сервис уже работает на переднем плане, но фоновый startService на части
            // прошивок всё равно отклоняется — просим остановиться через foreground-путь
            Log.w(TAG, "startService(EXIT) rejected, retrying as foreground: ${e.message}")
            ContextCompat.startForegroundService(app, intent)
        }
    }

    private fun startService(context: Context, action: String): Result {
        val intent = Intent(context, ScreenGrabberService::class.java)
        intent.action = action
        return try {
            ContextCompat.startForegroundService(context, intent)
            Result(Outcome.STARTED)
        } catch (e: Exception) {
            // Android 12+ запрещает поднимать foreground-сервис из фона без исключения из
            // энергосбережения; сюда же попадают ограничения прошивок на автозапуск
            Log.w(TAG, "Cannot start capture service from background: ${e.message}")
            Result(Outcome.FAILED, context.getString(R.string.remote_error_background_start))
        }
    }

    private fun launchBootActivity(context: Context): Boolean {
        val intent = Intent(context, BootActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    or Intent.FLAG_ACTIVITY_NO_HISTORY
        )
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start BootActivity: ${e.message}")
            return false
        }
        // Запрет фонового старта активити (Android 10+) молчаливый: исключения нет, окно
        // просто не появляется. Судим по тому, что снимает запрет: своё окно на экране,
        // разрешение на наложение или привязанная системой служба доступности.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                isInForeground() ||
                PermissionHelper.canDrawOverlays(context) ||
                AccessibilityCaptureService.isAvailable()
    }

    private fun isInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun hasUsbPermission(context: Context): Boolean {
        val device = UsbSerialPermissionHelper.findFirstSerialDevice(context) ?: return false
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return usbManager.hasPermission(device)
    }
}
