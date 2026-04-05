package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }

    override fun onCreate() {
        super.onCreate()
        migratePreferences()
        // Инициализируем user properties при запуске приложения
        AnalyticsHelper.initializeUserProperties(this)
    }

    private fun migratePreferences() {
        val prefs = Preferences(this)
        // Migration: pref_key_lighting_was_active was added later.
        // For users who had auto-boot enabled before this preference existed,
        // assume lighting was active so boot-start keeps working after update.
        if (!prefs.contains(R.string.pref_key_lighting_was_active)) {
            if (prefs.getBoolean(R.string.pref_key_boot)) {
                prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            }
        }
    }
}
