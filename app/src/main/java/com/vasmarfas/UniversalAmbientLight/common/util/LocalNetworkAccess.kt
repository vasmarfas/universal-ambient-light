package com.vasmarfas.UniversalAmbientLight.common.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Доступ к локальной сети. Android 17 закрывает её приложениям с targetSdk 37, пока не
 * выдано ACCESS_LOCAL_NETWORK: не уходят ни UDP, ни TCP к адресам сети, молчит mDNS,
 * входящие подключения телефона к ТВ тоже не проходят. На Android 16 и более ранних
 * разрешение не нужно.
 */
object LocalNetworkAccess {

    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun isRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
