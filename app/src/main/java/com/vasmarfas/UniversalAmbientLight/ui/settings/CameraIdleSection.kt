package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Группа автоматического сна камеры (issue #38); показывается только для камеры.
 */
@Composable
internal fun ColumnScope.CameraIdleSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    // Автосон камеры (issue #38). Только для камеры: экранные источники получают сигнал
    // о простое из ACTION_SCREEN_OFF.
    if (state.captureSource == "camera") {
        SettingsGroup(title = stringResource(R.string.pref_group_camera_idle)) {
            val secondsUnit = stringResource(R.string.unit_seconds)
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_enabled,
                title = stringResource(R.string.pref_title_camera_idle_enabled),
                summary = stringResource(R.string.pref_summary_camera_idle_enabled),
                onValueChange = { enabled ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_enabled",
                        enabled.toString()
                    )
                }
            )
            // Диапазоны те же, в которые AppOptions зажимает значения при чтении
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_timeout,
                title = stringResource(R.string.pref_title_camera_idle_timeout),
                range = 5..3600,
                step = 5,
                summaryProvider = { "$it $secondsUnit" },
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_timeout",
                        newValue.toString()
                    )
                }
            )
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_dark_level,
                title = stringResource(R.string.pref_title_camera_idle_dark_level),
                range = 0..96,
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_dark_level",
                        newValue.toString()
                    )
                }
            )
            SliderPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_motion_level,
                title = stringResource(R.string.pref_title_camera_idle_motion_level),
                range = 1..64,
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_motion_level",
                        newValue.toString()
                    )
                }
            )
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_static,
                title = stringResource(R.string.pref_title_camera_idle_static),
                summary = stringResource(R.string.pref_summary_camera_idle_static),
                onValueChange = { enabled ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_static",
                        enabled.toString()
                    )
                }
            )
        }
    }
}
