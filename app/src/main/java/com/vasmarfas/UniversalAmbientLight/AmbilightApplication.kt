package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
        // libadb-android (wireless ADB) touches hidden APIs; Android 9+ blocks them by default.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installFrameworkBugFilter()
        seedXmlDefaults()
        migratePreferences()
        // Инициализируем user properties при запуске приложения
        AnalyticsHelper.initializeUserProperties(this)
    }

    /**
     * `Preferences.getString(key, default)` ignores the xml `pref_default_*` resource
     * — callers pass explicit Kotlin defaults, so any debug-only override placed in
     * `pref_values.xml` would never be observed. Same story for `getBoolean`. This
     * routine writes xml defaults into SharedPreferences on first run (idempotent —
     * only fills missing keys), so resource overrides actually take effect.
     */
    private fun seedXmlDefaults() {
        val prefs = Preferences(this)

        listOf(
            R.string.pref_key_connection_type to R.string.pref_default_connection_type,
            R.string.pref_key_wled_protocol to R.string.pref_default_wled_protocol
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching { prefs.putString(keyRes, getString(defRes)) }
            }
        }

        listOf(
            R.string.pref_key_color_processing_enabled to R.bool.pref_default_color_processing_enabled
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching { prefs.putBoolean(keyRes, resources.getBoolean(defRes)) }
            }
        }

        // Integer prefs whose callers pass hardcoded Kotlin defaults to getInt() and
        // therefore never observe the xml resource default unless we seed them.
        listOf(
            R.string.pref_key_port to R.integer.pref_default_port
        ).forEach { (keyRes, defRes) ->
            if (!prefs.contains(keyRes)) {
                runCatching {
                    // Numeric prefs are stored as strings (matches EditTextPreference behaviour).
                    prefs.putString(keyRes, resources.getInteger(defRes).toString())
                }
            }
        }
    }

    /**
     * Swallows the Android-framework NPE emitted from MediaCodec's internal
     * DisplayListener on display removal (NVIDIA SHIELD HDMI unplug, etc.).
     * Listener outlives release() on affected firmware; no app frames in stack.
     */
    private fun installFrameworkBugFilter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isMediaCodecDisplayListenerNpe(throwable)) {
                Log.w(
                    "AmbilightApplication",
                    "Swallowed MediaCodec.onDisplayChanged NPE on ${thread.name}",
                    throwable
                )
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun isMediaCodecDisplayListenerNpe(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        val top = frames[0]
        if (!top.className.startsWith("android.media.MediaCodec") || top.methodName != "onDisplayChanged") return false
        return frames.any { it.className.startsWith("android.hardware.display.DisplayManagerGlobal") }
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
