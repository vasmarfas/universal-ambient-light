package com.vasmarfas.UniversalAmbientLight.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Состояние управления телевизором, если телефон сейчас пульт; null — экраны работают с
 * самим устройством.
 */
val LocalRemote = compositionLocalOf<RemoteSession.Snapshot?> { null }

@Composable
fun rememberRemoteSnapshot(): RemoteSession.Snapshot {
    var snapshot by remember { mutableStateOf(RemoteSession.snapshot) }
    DisposableEffect(Unit) {
        val listener = RemoteSession.Listener { snapshot = it }
        RemoteSession.addListener(listener)
        // Состояние могло смениться между первым чтением и подпиской
        snapshot = RemoteSession.snapshot
        onDispose { RemoteSession.removeListener(listener) }
    }
    return snapshot
}

/**
 * Настройки, с которыми работает экран: зеркало телевизора в режиме пульта, иначе свои.
 * Новый снимок с ТВ (переподключение) пересоздаёт экран — поля перечитывают значения.
 */
@Composable
fun rememberSettingsPreferences(): Preferences {
    val context = LocalContext.current
    val remote = LocalRemote.current
    return remember(remote?.tv?.id, remote?.revision) {
        if (remote != null) RemoteSession.preferences(context) else Preferences(context)
    }
}
