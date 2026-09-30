package com.vasmarfas.UniversalAmbientLight.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vasmarfas.UniversalAmbientLight.common.util.LocalNetworkAccess

/**
 * Просит доступ к локальной сети при открытии экрана, которому он нужен, если его ещё нет.
 * [onResult] получает ответ пользователя; когда разрешение не требуется или уже выдано,
 * не вызывается.
 */
@Composable
fun RequestLocalNetworkAccess(onResult: (granted: Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)
    LaunchedEffect(Unit) {
        if (!LocalNetworkAccess.isGranted(context)) launcher.launch(LocalNetworkAccess.PERMISSION)
    }
}
