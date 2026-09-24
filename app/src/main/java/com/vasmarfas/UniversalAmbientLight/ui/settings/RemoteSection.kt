package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.common.util.DeviceProfile
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Удалённое управление: на ТВ — пустить телефон, на телефоне — управлять телевизором.
 * Доступны оба пункта на любом устройстве, но своё для устройства идёт первым.
 */
@Composable
internal fun ColumnScope.RemoteSection(onRemoteHostClick: () -> Unit, onRemoteTvsClick: () -> Unit) {
    val context = LocalContext.current
    val isTv = remember { DeviceProfile.isTv(context) }
    SettingsGroup(title = stringResource(R.string.pref_group_remote)) {
        val host: @Composable () -> Unit = {
            ClickablePreference(
                title = stringResource(R.string.remote_host_title),
                summary = stringResource(R.string.remote_host_summary),
                onClick = onRemoteHostClick
            )
        }
        val client: @Composable () -> Unit = {
            ClickablePreference(
                title = stringResource(R.string.remote_tvs_title),
                summary = stringResource(R.string.remote_tvs_summary),
                onClick = onRemoteTvsClick
            )
        }
        if (isTv) {
            host()
            client()
        } else {
            client()
            host()
        }
    }
}
