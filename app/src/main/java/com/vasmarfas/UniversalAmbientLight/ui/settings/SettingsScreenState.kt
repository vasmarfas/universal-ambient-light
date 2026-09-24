package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Состояние экрана настроек. Значения продублированы из [Preferences] в виде состояния
 * Compose, потому что от них зависит состав самого экрана: тип подключения решает, какие
 * группы показывать, источник захвата — нужна ли группа камеры, и так далее.
 *
 * Держится в [SettingsScreen] и передаётся секциям целиком: иначе каждой пришлось бы
 * отдавать по десятку значений вместе с сеттерами.
 */
@Stable
class SettingsScreenState(prefs: Preferences) {

    var captureSource by mutableStateOf(
        prefs.getString(R.string.pref_key_capture_source) ?: "screen"
    )

    var connectionType by mutableStateOf(
        prefs.getString(R.string.pref_key_connection_type) ?: "hyperion"
    )

    var reconnectEnabled by mutableStateOf(prefs.getBoolean(R.string.pref_key_reconnect))

    var wledProtocol by mutableStateOf(
        prefs.getString(R.string.pref_key_wled_protocol) ?: "udp_raw"
    )

    var smoothingPreset by mutableStateOf(
        prefs.getString(R.string.pref_key_smoothing_preset) ?: "off"
    )

    var currentHost by mutableStateOf(prefs.getString(R.string.pref_key_host) ?: "")

    var currentPort by mutableStateOf(prefs.getString(R.string.pref_key_port) ?: "")

    var colorProcessingEnabled by mutableStateOf(
        prefs.getBoolean(R.string.pref_key_color_processing_enabled, true)
    )

    var captureMethod by mutableStateOf(
        prefs.getString(R.string.pref_key_capture_method) ?: "media_projection"
    )

    /** Метод захвата до открытия предупреждения о доступности — на случай отказа. */
    var previousCaptureMethod by mutableStateOf(captureMethod)

    /**
     * Растёт при откате выбора метода: список уже показал новое значение, и без смены ключа
     * так бы и показывал его, хотя в настройках остался прежний метод.
     */
    var captureMethodRevision by mutableIntStateOf(0)

    /** Лампы Home Assistant с зонами — держится здесь ради живой сводки в настройках. */
    var haLampsSpec by mutableStateOf(prefs.getString(R.string.pref_key_ha_lamps) ?: "")

    /** Дополнительное подключение Home Assistant — работает параллельно с основным. */
    var ha2Enabled by mutableStateOf(prefs.getBoolean(R.string.pref_key_ha2_enabled, false))
    var ha2LampsSpec by mutableStateOf(prefs.getString(R.string.pref_key_ha2_lamps) ?: "")

    var showScanDialog by mutableStateOf(false)
    var showDebugDialog by mutableStateOf(false)
    var showAdbPairingDialog by mutableStateOf(false)
    var showAccessibilityDisclosure by mutableStateOf(false)
    var showHaLampsDialog by mutableStateOf(false)
    var showHa2LampsDialog by mutableStateOf(false)
}
