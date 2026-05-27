package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Утилитный класс для работы с Firebase Analytics
 */
object AnalyticsHelper {
    /**
     * Получает экземпляр FirebaseAnalytics
     */
    private fun getAnalytics(context: Context): FirebaseAnalytics {
        return FirebaseAnalytics.getInstance(context)
    }

    fun initializeUserProperties(context: Context) {
        val analytics = getAnalytics(context)
        val prefs = Preferences(context)

        val protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        analytics.setUserProperty("connection_protocol", protocol)

        val language = prefs.getString(R.string.pref_key_language, "en") ?: "en"
        analytics.setUserProperty("app_language", language)

        analytics.setUserProperty("android_version", Build.VERSION.SDK_INT.toString())
        analytics.setUserProperty("device_form_factor", deviceFormFactor(context))

        val captureMethod = prefs.getString(R.string.pref_key_capture_method, "media_projection")
            ?: "media_projection"
        analytics.setUserProperty("capture_method", captureMethod)

        analytics.setUserProperty(
            "framerate",
            safeGetInt(prefs, R.string.pref_key_framerate).toString()
        )

        val useAvgColor = prefs.getBoolean(R.string.pref_key_use_avg_color)
        analytics.setUserProperty("use_avg_color", useAvgColor.toString())

        val wledProtocol = prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
        analytics.setUserProperty("wled_protocol", wledProtocol)

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        analytics.setUserProperty("app_version", appVersion)

        val autoReconnect = prefs.getBoolean(R.string.pref_key_reconnect)
        analytics.setUserProperty("auto_reconnect_enabled", autoReconnect.toString())

        val smoothingEnabled = prefs.getBoolean(R.string.pref_key_smoothing_enabled, false)
        analytics.setUserProperty("smoothing_enabled", smoothingEnabled.toString())

        syncCrashlyticsContext(context)
    }

    private fun deviceFormFactor(context: Context): String {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "mobile"
    }

    private fun safeGetInt(prefs: Preferences, keyRes: Int): Int =
        try {
            prefs.getInt(keyRes)
        } catch (e: Exception) {
            0
        }

    /** Mirrors current config into Crashlytics custom keys so crash/ANR reports carry context. */
    fun syncCrashlyticsContext(context: Context) {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            val prefs = Preferences(context)
            crashlytics.setCustomKey(
                "connection_protocol",
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            )
            crashlytics.setCustomKey(
                "capture_method",
                prefs.getString(R.string.pref_key_capture_method, "media_projection")
                    ?: "media_projection"
            )
            crashlytics.setCustomKey("device_form_factor", deviceFormFactor(context))
            crashlytics.setCustomKey("manufacturer", Build.MANUFACTURER ?: "unknown")
            crashlytics.setCustomKey("framerate", safeGetInt(prefs, R.string.pref_key_framerate))
            crashlytics.setCustomKey(
                "use_avg_color",
                prefs.getBoolean(R.string.pref_key_use_avg_color)
            )
            crashlytics.setCustomKey(
                "smoothing_enabled",
                prefs.getBoolean(R.string.pref_key_smoothing_enabled, false)
            )
            crashlytics.setCustomKey(
                "auto_reconnect_enabled",
                prefs.getBoolean(R.string.pref_key_reconnect)
            )
            crashlytics.setCustomKey(
                "wled_protocol",
                prefs.getString(R.string.pref_key_wled_protocol, "ddp") ?: "ddp"
            )
            crashlytics.setCustomKey("led_x", safeGetInt(prefs, R.string.pref_key_x_led))
            crashlytics.setCustomKey("led_y", safeGetInt(prefs, R.string.pref_key_y_led))
        } catch (_: Exception) {
        }
    }

    /**
     * Логирует событие запуска конкретного протокола
     */
    fun logProtocolStarted(context: Context, protocol: String) {
        val bundle = Bundle().apply {
            putString("protocol", protocol)
        }
        getAnalytics(context).logEvent("protocol_started", bundle)
    }

    /**
     * Логирует событие запуска захвата экрана
     */
    fun logScreenCaptureStarted(context: Context, protocol: String) {
        val bundle = Bundle().apply {
            putString("protocol", protocol)
        }
        getAnalytics(context).logEvent("screen_capture_started", bundle)
    }

    /**
     * Логирует событие остановки захвата экрана
     */
    fun logScreenCaptureStopped(context: Context, durationSeconds: Long? = null) {
        val bundle = if (durationSeconds != null) {
            Bundle().apply {
                putLong("duration_seconds", durationSeconds)
            }
        } else null
        getAnalytics(context).logEvent("screen_capture_stopped", bundle)
    }

    /** Summary of a finished capture session: duration, method/source, protocol, connection, end reason. */
    fun logCaptureSessionSummary(
        context: Context,
        durationSeconds: Long,
        captureMethod: String?,
        captureSource: String?,
        protocol: String?,
        connected: Boolean,
        endReason: String,
    ) {
        val bundle = Bundle().apply {
            putLong("duration_seconds", durationSeconds)
            putString("duration_bucket", durationBucket(durationSeconds))
            putString("capture_method", captureMethod ?: "unknown")
            putString("capture_source", captureSource ?: "unknown")
            putString("protocol", protocol ?: "unknown")
            putString("connected", connected.toString())
            putString("end_reason", endReason)
        }
        getAnalytics(context).logEvent("capture_session_summary", bundle)
    }

    private fun durationBucket(seconds: Long): String = when {
        seconds < 10 -> "0-10s"
        seconds < 60 -> "10-60s"
        seconds < 300 -> "1-5m"
        seconds < 1800 -> "5-30m"
        seconds < 7200 -> "30m-2h"
        else -> "2h+"
    }

    /**
     * Логирует событие успешного подключения к серверу
     */
    fun logConnectionSuccess(context: Context, protocol: String, host: String? = null) {
        val bundle = Bundle().apply {
            putString("protocol", protocol)
            if (host != null) {
                putString("host", host)
            }
        }
        getAnalytics(context).logEvent("connection_success", bundle)
    }

    /**
     * Логирует событие ошибки подключения
     */
    fun logConnectionError(context: Context, protocol: String, error: String? = null) {
        val bundle = Bundle().apply {
            putString("protocol", protocol)
            if (error != null) {
                putString("error", error)
            }
        }
        getAnalytics(context).logEvent("connection_error", bundle)
    }

    /**
     * Логирует событие изменения протокола в настройках
     */
    fun logProtocolChanged(context: Context, oldProtocol: String, newProtocol: String) {
        val bundle = Bundle().apply {
            putString("old_protocol", oldProtocol)
            putString("new_protocol", newProtocol)
        }
        getAnalytics(context).logEvent("protocol_changed", bundle)
    }

    /**
     * Логирует событие открытия экрана настроек
     */
    fun logSettingsOpened(context: Context) {
        getAnalytics(context).logEvent("settings_opened", null)
    }

    /**
     * Логирует событие изменения настройки
     */
    fun logSettingChanged(context: Context, settingName: String, value: String? = null) {
        val bundle = Bundle().apply {
            putString("setting_name", settingName)
            if (value != null) {
                putString("setting_value", value)
            }
        }
        getAnalytics(context).logEvent("setting_changed", bundle)
    }

    /**
     * Логирует событие переключения эффекта
     */
    fun logEffectChanged(context: Context, effect: String) {
        val bundle = Bundle().apply {
            putString("effect", effect)
        }
        getAnalytics(context).logEvent("effect_changed", bundle)
    }

    /**
     * Логирует событие запуска приложения
     */
    fun logAppLaunched(context: Context) {
        getAnalytics(context).logEvent("app_launched", null)
    }

    /**
     * Логирует событие изменения языка
     */
    fun logLanguageChanged(context: Context, language: String) {
        val bundle = Bundle().apply {
            putString("language", language)
        }
        getAnalytics(context).logEvent("language_changed", bundle)
    }

    /**
     * Логирует событие открытия экрана LED Layout
     */
    fun logLedLayoutOpened(context: Context) {
        getAnalytics(context).logEvent("led_layout_opened", null)
    }

    /**
     * Логирует событие изменения конфигурации LED
     */
    fun logLedConfigChanged(context: Context, horizontalLeds: Int, verticalLeds: Int) {
        val bundle = Bundle().apply {
            putInt("horizontal_leds", horizontalLeds)
            putInt("vertical_leds", verticalLeds)
        }
        getAnalytics(context).logEvent("led_config_changed", bundle)
    }

    /**
     * Логирует событие использования функции автоподключения
     */
    fun logAutoReconnectEnabled(context: Context, enabled: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("enabled", enabled)
        }
        getAnalytics(context).logEvent("auto_reconnect_changed", bundle)
    }

    /**
     * Логирует событие использования сглаживания цветов
     */
    fun logSmoothingChanged(context: Context, enabled: Boolean, preset: String? = null) {
        val bundle = Bundle().apply {
            putBoolean("enabled", enabled)
            if (preset != null) {
                putString("preset", preset)
            }
        }
        getAnalytics(context).logEvent("smoothing_changed", bundle)
    }

    /**
     * Логирует событие запроса разрешения
     */
    fun logPermissionRequested(context: Context, permission: String) {
        val bundle = Bundle().apply {
            putString("permission", permission)
        }
        getAnalytics(context).logEvent("permission_requested", bundle)
    }

    /**
     * Логирует событие предоставления разрешения
     */
    fun logPermissionGranted(context: Context, permission: String) {
        val bundle = Bundle().apply {
            putString("permission", permission)
        }
        getAnalytics(context).logEvent("permission_granted", bundle)
    }

    /**
     * Логирует событие отклонения разрешения
     */
    fun logPermissionDenied(context: Context, permission: String) {
        val bundle = Bundle().apply {
            putString("permission", permission)
        }
        getAnalytics(context).logEvent("permission_denied", bundle)
    }

    /**
     * Логирует событие использования быстрой плитки (Quick Tile)
     */
    fun logQuickTileUsed(context: Context) {
        getAnalytics(context).logEvent("quick_tile_used", null)
    }

    /**
     * Логирует событие использования автозапуска при загрузке
     */
    fun logBootStartEnabled(context: Context, enabled: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("enabled", enabled)
        }
        getAnalytics(context).logEvent("boot_start_changed", bundle)
    }

    /**
     * Логирует стандартное событие screen_view для отслеживания навигации
     */
    fun logScreenView(context: Context, screenName: String, screenClass: String? = null) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            if (screenClass != null) {
                putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
            }
        }
        getAnalytics(context).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
    }

    /**
     * Логирует событие открытия диалога Help
     */
    fun logHelpDialogOpened(context: Context) {
        getAnalytics(context).logEvent("help_dialog_opened", null)
    }

    /**
     * Логирует событие открытия ссылки Help (GitHub)
     */
    fun logHelpLinkOpened(context: Context) {
        getAnalytics(context).logEvent("help_link_opened", null)
    }

    /**
     * Логирует событие открытия диалога Support
     */
    fun logSupportDialogOpened(context: Context) {
        getAnalytics(context).logEvent("support_dialog_opened", null)
    }

    /**
     * Логирует событие открытия ссылки Support
     */
    fun logSupportLinkOpened(context: Context) {
        getAnalytics(context).logEvent("support_link_opened", null)
    }

    /**
     * Логирует событие запроса обновления приложения
     */
    fun logAppUpdateRequested(context: Context, updateType: String) {
        val bundle = Bundle().apply {
            putString("update_type", updateType)
        }
        getAnalytics(context).logEvent("app_update_requested", bundle)
    }

    /**
     * Логирует событие успешного обновления приложения
     */
    fun logAppUpdateCompleted(context: Context) {
        getAnalytics(context).logEvent("app_update_completed", null)
    }

    /**
     * Логирует событие отмены обновления приложения
     */
    fun logAppUpdateCancelled(context: Context) {
        getAnalytics(context).logEvent("app_update_cancelled", null)
    }

    /**
     * Логирует событие запроса оптимизации батареи
     */
    fun logBatteryOptimizationRequested(context: Context) {
        getAnalytics(context).logEvent("battery_optimization_requested", null)
    }

    /**
     * Логирует событие предоставления оптимизации батареи
     */
    fun logBatteryOptimizationGranted(context: Context) {
        getAnalytics(context).logEvent("battery_optimization_granted", null)
    }

    /**
     * Логирует событие запроса USB разрешения для Adalight
     */
    fun logUsbPermissionRequested(context: Context) {
        getAnalytics(context).logEvent("usb_permission_requested", null)
    }

    /**
     * Логирует событие предоставления USB разрешения
     */
    fun logUsbPermissionGranted(context: Context) {
        getAnalytics(context).logEvent("usb_permission_granted", null)
    }

    /**
     * Логирует событие отклонения USB разрешения
     */
    fun logUsbPermissionDenied(context: Context) {
        getAnalytics(context).logEvent("usb_permission_denied", null)
    }

    /**
     * Логирует событие изменения настройки framerate
     */
    fun logFramerateChanged(context: Context, framerate: Int) {
        val bundle = Bundle().apply {
            putInt("framerate", framerate)
        }
        getAnalytics(context).logEvent("framerate_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки capture_quality
     */
    fun logCaptureQualityChanged(context: Context, quality: Int) {
        val bundle = Bundle().apply {
            putInt("quality", quality)
        }
        getAnalytics(context).logEvent("capture_quality_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки use_avg_color
     */
    fun logUseAvgColorChanged(context: Context, enabled: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("enabled", enabled)
        }
        getAnalytics(context).logEvent("use_avg_color_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки host
     */
    fun logHostChanged(context: Context, host: String) {
        val bundle = Bundle().apply {
            putString("host", host)
        }
        getAnalytics(context).logEvent("host_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки port
     */
    fun logPortChanged(context: Context, port: Int) {
        val bundle = Bundle().apply {
            putInt("port", port)
        }
        getAnalytics(context).logEvent("port_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки priority
     */
    fun logPriorityChanged(context: Context, priority: Int) {
        val bundle = Bundle().apply {
            putInt("priority", priority)
        }
        getAnalytics(context).logEvent("priority_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки baudrate для Adalight
     */
    fun logBaudrateChanged(context: Context, baudrate: Int) {
        val bundle = Bundle().apply {
            putInt("baudrate", baudrate)
        }
        getAnalytics(context).logEvent("baudrate_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки color_order для WLED
     */
    fun logColorOrderChanged(context: Context, colorOrder: String) {
        val bundle = Bundle().apply {
            putString("color_order", colorOrder)
        }
        getAnalytics(context).logEvent("color_order_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки rgbw для WLED
     */
    fun logRgbwChanged(context: Context, enabled: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("enabled", enabled)
        }
        getAnalytics(context).logEvent("rgbw_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки brightness для WLED
     */
    fun logBrightnessChanged(context: Context, brightness: Int) {
        val bundle = Bundle().apply {
            putInt("brightness", brightness)
        }
        getAnalytics(context).logEvent("brightness_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки reconnect_delay
     */
    fun logReconnectDelayChanged(context: Context, delay: Int) {
        val bundle = Bundle().apply {
            putInt("delay", delay)
        }
        getAnalytics(context).logEvent("reconnect_delay_changed", bundle)
    }

    /**
     * Логирует событие изменения настройки adalight_protocol
     */
    fun logAdalightProtocolChanged(context: Context, protocol: String) {
        val bundle = Bundle().apply {
            putString("protocol", protocol)
        }
        getAnalytics(context).logEvent("adalight_protocol_changed", bundle)
    }

    /**
     * Логирует событие критической ошибки сервиса
     */
    fun logServiceError(context: Context, errorType: String, errorMessage: String? = null) {
        val bundle = Bundle().apply {
            putString("error_type", errorType)
            if (errorMessage != null) {
                putString("error_message", errorMessage)
            }
        }
        getAnalytics(context).logEvent("service_error", bundle)
    }

    /**
     * Обновляет user property при изменении протокола
     */
    fun updateProtocolProperty(context: Context, protocol: String) {
        getAnalytics(context).setUserProperty("connection_protocol", protocol)
        try {
            FirebaseCrashlytics.getInstance().setCustomKey("connection_protocol", protocol)
        } catch (_: Exception) {
        }
    }

    /**
     * Обновляет user property при изменении языка
     */
    fun updateLanguageProperty(context: Context, language: String) {
        getAnalytics(context).setUserProperty("app_language", language)
    }

    /**
     * Обновляет user property при изменении автоподключения
     */
    fun updateAutoReconnectProperty(context: Context, enabled: Boolean) {
        getAnalytics(context).setUserProperty("auto_reconnect_enabled", enabled.toString())
        try {
            FirebaseCrashlytics.getInstance().setCustomKey("auto_reconnect_enabled", enabled)
        } catch (_: Exception) {
        }
    }

    /**
     * Обновляет user property при изменении сглаживания
     */
    fun updateSmoothingProperty(context: Context, enabled: Boolean) {
        getAnalytics(context).setUserProperty("smoothing_enabled", enabled.toString())
        try {
            FirebaseCrashlytics.getInstance().setCustomKey("smoothing_enabled", enabled)
        } catch (_: Exception) {
        }
    }
}
