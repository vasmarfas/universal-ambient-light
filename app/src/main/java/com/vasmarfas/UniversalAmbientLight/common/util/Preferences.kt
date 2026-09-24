package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/**
 * Обёртка над SharedPreferences: значения по умолчанию собраны в ресурсах.
 * Числа хранятся строками — так их понимает EditTextPreference.
 *
 * [preferences] подменяется на зеркало настроек телевизора, когда экран настроек управляет
 * им с телефона: ключи и умолчания те же, меняется только хранилище.
 */
class Preferences(
    context: Context,
    private val preferences: SharedPreferences = defaultSharedPreferences(context),
) {

    private val resources = context.resources

    fun contains(@StringRes keyResourceId: Int): Boolean = preferences.contains(key(keyResourceId))

    fun getString(@StringRes keyResourceId: Int, default: String? = null): String? {
        return try {
            preferences.getString(key(keyResourceId), default)
        } catch (_: ClassCastException) {
            // По этому ключу лежит значение другого типа (наследие старой установки).
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
            // Значение записали прямо как Int (например, putInt мимо этой обёртки).
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
        if (resolved == 0) {
            // Ресурса нет — настройка молча получит 0/false. Так уже случалось, когда
            // шринкер вырезал pref_default_* из release-сборки (см. res/raw/keep.xml).
            Log.w(TAG, "No $type default resource for $name, falling back to zero")
        }
        sDefaultKeyCache[cacheKey] = resolved
        return resolved
    }

    private fun typeTag(type: String): Int = when (type) {
        "integer" -> 1
        "bool" -> 2
        else -> 0
    }

    companion object {
        private const val TAG = "Preferences"
        private val sDefaultKeyCache = ConcurrentHashMap<Long, Int>()

        fun defaultSharedPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences(
                context.packageName + "_preferences",
                Context.MODE_PRIVATE
            )
    }
}