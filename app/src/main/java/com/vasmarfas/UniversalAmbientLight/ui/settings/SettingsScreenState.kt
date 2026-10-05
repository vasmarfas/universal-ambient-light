package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType
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

    /** Лампы основного подключения с зонами: ключ зависит от типа подключения. */
    var lampsSpec by mutableStateOf(
        OutputType.of(connectionType).lampsKey?.let { prefs.getString(it) }.orEmpty()
    )

    /** Ключ доступа к мосту Hue или панелям Nanoleaf; пустой - ещё не подключались. */
    var pairingKey by mutableStateOf(
        when (OutputType.of(connectionType)) {
            OutputType.HUE -> prefs.getString(R.string.pref_key_hue_username).orEmpty()
            OutputType.NANOLEAF -> prefs.getString(R.string.pref_key_nanoleaf_token).orEmpty()
            else -> ""
        }
    )

    /** Зона развлечений Hue: номер и имя; пустой номер - лампы по одной. */
    var hueArea by mutableStateOf(prefs.getString(R.string.pref_key_hue_area).orEmpty())
    var hueAreaName by mutableStateOf(prefs.getString(R.string.pref_key_hue_area_name).orEmpty())

    /** Дополнительное подключение Home Assistant — работает параллельно с основным. */
    var ha2Enabled by mutableStateOf(prefs.getBoolean(R.string.pref_key_ha2_enabled, false))
    var ha2LampsSpec by mutableStateOf(prefs.getString(R.string.pref_key_ha2_lamps) ?: "")

    var resetRevision by mutableIntStateOf(0)

    fun resetPicture(prefs: Preferences) {
        prefs.remove(*PICTURE_KEYS)
        prefs.putBoolean(
            R.string.pref_key_color_processing_enabled,
            prefs.getBoolean(R.string.pref_key_color_processing_enabled)
        )
        smoothingPreset = prefs.getString(R.string.pref_key_smoothing_preset) ?: "off"
        colorProcessingEnabled = prefs.getBoolean(R.string.pref_key_color_processing_enabled, true)
        resetRevision++
    }

    var showDebugDialog by mutableStateOf(false)
    var showAdbPairingDialog by mutableStateOf(false)
    var showAccessibilityDisclosure by mutableStateOf(false)
    var showHaLampsDialog by mutableStateOf(false)
    var showHa2LampsDialog by mutableStateOf(false)
    var showLampsDialog by mutableStateOf(false)
    var showPairDialog by mutableStateOf(false)
    var showHueAreaDialog by mutableStateOf(false)
    var showResetPictureDialog by mutableStateOf(false)

    private companion object {
        val PICTURE_KEYS = intArrayOf(
            R.string.pref_key_framerate,
            R.string.pref_key_capture_quality,
            R.string.pref_key_use_avg_color,
            R.string.pref_key_color_processing_enabled,
            R.string.pref_key_color_brightness,
            R.string.pref_key_color_contrast,
            R.string.pref_key_color_black_level,
            R.string.pref_key_color_white_level,
            R.string.pref_key_color_saturation,
            R.string.pref_key_color_brightness_r,
            R.string.pref_key_color_brightness_g,
            R.string.pref_key_color_brightness_b,
            R.string.pref_key_color_gamma_r,
            R.string.pref_key_color_gamma_g,
            R.string.pref_key_color_gamma_b,
            R.string.pref_key_smoothing_enabled,
            R.string.pref_key_smoothing_preset,
            R.string.pref_key_settling_time,
            R.string.pref_key_output_delay,
            R.string.pref_key_update_frequency,
            R.string.pref_key_border_detection_enabled,
            R.string.pref_key_border_threshold,
            R.string.pref_key_border_check_interval,
        )
    }
}
