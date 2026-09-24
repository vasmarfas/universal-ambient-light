package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceProfile {

    /**
     * Телевизор или приставка с пультом. Китайские AOSP-приставки часто не объявляют ни
     * leanback, ни телевизионный режим интерфейса, поэтому ТВ считается и любое устройство
     * без сенсорного экрана.
     */
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
}
