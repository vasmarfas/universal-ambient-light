package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

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
     * Логирует событие остановки захвата экрана
     */
    fun logScreenCaptureStopped(context: Context) {
        getAnalytics(context).logEvent("screen_capture_stopped", null)
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
}
