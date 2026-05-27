package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper around SharedPreferences with defaults centralised in resources.
 * Numeric prefs are stored as Strings to play nicely with EditTextPreference.
 */
class Preferences(context: Context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val resources = context.resources

    fun contains(@StringRes keyResourceId: Int): Boolean = preferences.contains(key(keyResourceId))

    fun getString(@StringRes keyResourceId: Int, default: String? = null): String? {
        return try {
            preferences.getString(key(keyResourceId), default)
        } catch (_: ClassCastException) {
            // Value at this key was stored under a different type (legacy install).
            default
        }
    }

    fun putString(@StringRes keyResourceId: Int, value: String) {
        preferences.edit { putString(key(keyResourceId), value) }
    }

    fun getInt(@StringRes keyResourceId: Int): Int {
        val defaultResId = defaultKey(keyResourceId, "integer")
        val default = if (defaultResId == 0) 0 else try {
            resources.getInteger(defaultResId)
        } catch (_: Resources.NotFoundException) {
            0
        }
        return getInt(keyResourceId, default)
    }

    fun getInt(@StringRes keyResourceId: Int, default: Int = 0): Int {
        val raw = try {
            preferences.getString(key(keyResourceId), null)?.trim()
        } catch (_: ClassCastException) {
            // Value was stored directly as Int (e.g. via putInt on raw SharedPreferences elsewhere).
            return try {
                preferences.getInt(key(keyResourceId), default)
            } catch (_: ClassCastException) {
                default
            }
        }
        return raw?.toIntOrNull() ?: default
    }

    fun putInt(@StringRes keyResourceId: Int, value: Int) {
        putString(keyResourceId, value.toString())
    }

    fun getBoolean(@StringRes keyResourceId: Int): Boolean {
        val defaultResId = defaultKey(keyResourceId, "bool")
        val default = if (defaultResId == 0) false else try {
            resources.getBoolean(defaultResId)
        } catch (_: Resources.NotFoundException) {
            false
        }
        return getBoolean(keyResourceId, default)
    }

    fun getBoolean(@StringRes keyResourceId: Int, default: Boolean): Boolean {
        return try {
            preferences.getBoolean(key(keyResourceId), default)
        } catch (_: ClassCastException) {
            default
        }
    }

    fun putBoolean(@StringRes keyResourceId: Int, value: Boolean) {
        preferences.edit { putBoolean(key(keyResourceId), value) }
    }

    private fun key(keyResourceId: Int) = resources.getString(keyResourceId)

    private fun defaultKey(keyResourceId: Int, type: String): Int {
        val cacheKey = (keyResourceId.toLong() shl 8) or typeTag(type).toLong()
        sDefaultKeyCache[cacheKey]?.let { return it }

        val name =
            resources.getResourceEntryName(keyResourceId).replace("pref_key_", "pref_default_")
        val pkg = resources.getResourcePackageName(keyResourceId)
        val resolved = resources.getIdentifier(name, type, pkg)
        sDefaultKeyCache[cacheKey] = resolved
        return resolved
    }

    private fun typeTag(type: String): Int = when (type) {
        "integer" -> 1
        "bool" -> 2
        else -> 0
    }

    companion object {
        private val sDefaultKeyCache = ConcurrentHashMap<Long, Int>()
    }
}