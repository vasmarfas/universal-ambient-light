package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Инициализируем user properties при запуске приложения
        AnalyticsHelper.initializeUserProperties(this)
    }
}
