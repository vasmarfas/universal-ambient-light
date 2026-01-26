package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import com.vasmarfas.UniversalAmbientLight.R
import java.util.Locale

object LocaleHelper {

    fun onAttach(context: Context): Context {
        val lang = getPersistedData(context, "default")
        return setLocale(context, lang)
    }

    fun getLanguage(context: Context): String {
        return getPersistedData(context, "default")
    }

    fun setLocale(context: Context, language: String): Context {
        persist(context, language)

        if (language == "default") {
            return context
        }

        return updateResources(context, language)
    }

    private fun getPersistedData(context: Context, defaultLanguage: String): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString("language", defaultLanguage) ?: defaultLanguage
    }

    private fun persist(context: Context, language: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().putString("language", language).apply()
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            configuration.setLocales(localeList)
        } else {
            configuration.setLocale(locale)
        }
        
        return context.createConfigurationContext(configuration)
    }
}
