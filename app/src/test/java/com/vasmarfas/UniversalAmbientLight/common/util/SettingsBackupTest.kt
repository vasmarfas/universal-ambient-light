package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val methods = listOf("media_projection", "scrcpy")

    private class FakePrefs(vararg initial: Pair<String, Any?>) : SharedPreferences {
        val values = linkedMapOf(*initial)

        override fun getAll(): Map<String, *> = values
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun contains(key: String?) = key in values
        override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
        override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?) = defValues
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private fun set(key: String?, value: Any?) = apply { values[checkNotNull(key)] = value }

        override fun putString(key: String?, value: String?) = set(key, value)
        override fun putStringSet(key: String?, value: Set<String>?) = set(key, value)
        override fun putInt(key: String?, value: Int) = set(key, value)
        override fun putLong(key: String?, value: Long) = set(key, value)
        override fun putFloat(key: String?, value: Float) = set(key, value)
        override fun putBoolean(key: String?, value: Boolean) = set(key, value)
        override fun remove(key: String?) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }

    @Test
    fun `values from the file replace the current ones`() {
        val prefs = FakePrefs("pref_key_host" to "10.0.0.1")

        SettingsBackup.restore(prefs, mapOf("pref_key_host" to "192.168.1.5"), methods)

        assertEquals("192.168.1.5", prefs.values["pref_key_host"])
    }

    @Test
    fun `a setting missing from the file goes back to its default`() {
        val prefs = FakePrefs("pref_key_host" to "10.0.0.1")

        SettingsBackup.restore(prefs, emptyMap(), methods)

        assertFalse("pref_key_host" in prefs.values)
    }

    @Test
    fun `settings of an extra controller are restored`() {
        val key = ExtraOutputs.keyPrefix(1) + "pref_key_host"
        val prefs = FakePrefs()

        SettingsBackup.restore(prefs, mapOf(key to "10.0.0.2"), methods)

        assertEquals("10.0.0.2", prefs.values[key])
    }

    @Test
    fun `app state stays when the file does not have it`() {
        val prefs = FakePrefs(
            "pref_key_lighting_was_active" to true,
            "battery_opt_exemption_last_attempt_ms" to 5L,
        )

        SettingsBackup.restore(prefs, emptyMap(), methods)

        assertEquals(2, prefs.values.size)
    }

    @Test
    fun `app state in the file is ignored`() {
        val prefs = FakePrefs("pref_key_remote_access" to false)

        SettingsBackup.restore(prefs, mapOf("pref_key_remote_access" to true), methods)

        assertEquals(false, prefs.values["pref_key_remote_access"])
    }

    @Test
    fun `the interface language stays as it is on this device`() {
        val prefs = FakePrefs("pref_key_language" to "ru")

        SettingsBackup.restore(prefs, mapOf("pref_key_language" to "en"), methods)

        assertEquals("ru", prefs.values["pref_key_language"])
    }

    @Test
    fun `a capture method available here is restored`() {
        val prefs = FakePrefs("pref_key_capture_method" to "media_projection")

        SettingsBackup.restore(prefs, mapOf("pref_key_capture_method" to "scrcpy"), methods)

        assertEquals("scrcpy", prefs.values["pref_key_capture_method"])
    }

    @Test
    fun `a capture method missing here leaves the current one`() {
        val prefs = FakePrefs("pref_key_capture_method" to "scrcpy")

        SettingsBackup.restore(prefs, mapOf("pref_key_capture_method" to "accessibility"), methods)

        assertEquals("scrcpy", prefs.values["pref_key_capture_method"])
    }

    @Test
    fun `other settings are restored when the capture method is skipped`() {
        val prefs = FakePrefs("pref_key_host" to "10.0.0.1")

        SettingsBackup.restore(
            prefs,
            mapOf("pref_key_capture_method" to "accessibility", "pref_key_host" to "192.168.1.5"),
            methods,
        )

        assertEquals("192.168.1.5", prefs.values["pref_key_host"])
    }

    @Test
    fun `a skipped capture method is reported`() {
        val restored = SettingsBackup.restore(
            FakePrefs(),
            mapOf("pref_key_capture_method" to "accessibility"),
            methods,
        )

        assertFalse(restored)
    }

    @Test
    fun `a file without a capture method reports nothing skipped`() {
        assertTrue(SettingsBackup.restore(FakePrefs(), emptyMap(), methods))
    }
}
