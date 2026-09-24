package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DeviceProfile
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote

/**
 * Общие настройки и отладочная информация.
 */
@Composable
internal fun ColumnScope.GeneralSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    val remote = LocalRemote.current
    SettingsGroup(title = stringResource(R.string.pref_group_general)) {
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_boot,
            title = stringResource(R.string.pref_title_boot),
            summary = stringResource(R.string.pref_summary_boot),
            onValueChange = { enabled ->
                AnalyticsHelper.logBootStartEnabled(context, enabled)
                AnalyticsHelper.logSettingChanged(context, "boot_start", enabled.toString())
            }
        )
        // Без согласия на запись экрана и права на окна поверх других Android 10+ не даёт
        // включить подсветку самой после сна ТВ — на телефоне это обычный диалог и не нужно
        val isTv = remember { DeviceProfile.isTv(context) }
        if (remote != null || isTv) {
            val ready = remember(remote?.caps) {
                val caps = remote?.caps
                if (remote != null) {
                    caps != null && caps.projectMedia && caps.overlay
                } else {
                    PermissionHelper.hasProjectMediaPermission(context) &&
                            PermissionHelper.canDrawOverlays(context)
                }
            }
            ClickablePreference(
                title = stringResource(R.string.adb_grant_title),
                summary = stringResource(
                    if (ready) R.string.autostart_permissions_ok else R.string.autostart_permissions_missing
                ),
                onClick = { state.showAdbPairingDialog = true }
            )
        }
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_standby_keepalive,
            title = stringResource(R.string.pref_title_standby_keepalive),
            summary = stringResource(R.string.pref_summary_standby_keepalive),
            onValueChange = { enabled ->
                AnalyticsHelper.logSettingChanged(
                    context,
                    "standby_keepalive",
                    enabled.toString()
                )
            }
        )
        // Язык — интерфейса этого устройства, телевизору с телефона его не задают
        if (remote == null) ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_language,
            title = stringResource(R.string.pref_title_language),
            entriesRes = R.array.pref_list_language,
            entryValuesRes = R.array.pref_list_language_values,
            onValueChange = { language ->
                AnalyticsHelper.logLanguageChanged(context, language)
                AnalyticsHelper.logSettingChanged(context, "language", language)
                AnalyticsHelper.updateLanguageProperty(context, language)
                LocaleHelper.setLocale(context, language)
                (context as? Activity)?.recreate()
            }
        )
    }

    // Отладка
    SettingsGroup(title = stringResource(R.string.pref_category_debug)) {
        ClickablePreference(
            title = stringResource(R.string.pref_title_device_info),
            summary = stringResource(R.string.pref_summary_device_info),
            onClick = { state.showDebugDialog = true }
        )
    }
}
