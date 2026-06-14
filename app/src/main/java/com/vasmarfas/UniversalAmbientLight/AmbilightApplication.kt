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
        // NOTE: previously called HiddenApiBypass.addHiddenApiExemptions("L") here, but Google
        // Play rejects the org.lsposed.hiddenapibypass SDK (it breaks on the new ART / Android 16).
        // Removed. Features relying on blocklisted hidden APIs may degrade on newer Android;
        // greylisted APIs and the non-ADB capture paths (MediaProjection/accessibility) are unaffected.
    }

    override fun onCreate() {
        super.onCreate()
        installFrameworkBugFilter()
        seedXmlDefaults()
        migratePreferences()
        // Off the main thread: reads SharedPreferences (first-access disk I/O) and
        // touches Firebase — doing it inline in onCreate adds to cold-start ANRs.
        Thread { AnalyticsHelper.initializeUserProperties(this) }
            .apply { name = "analytics-init"; isDaemon = true }
            .start()
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
     * Swallows a handful of platform/OEM bugs that surface as uncaught exceptions
     * on threads we don't control. Each predicate is tightly scoped so genuine app
     * crashes still propagate to the previous handler (Crashlytics).
     */
    private fun installFrameworkBugFilter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val reason = when {
                isMediaCodecDisplayListenerNpe(throwable) -> "MediaCodec.onDisplayChanged NPE"
                isReportSizeConfigurationsBug(throwable) -> "ActivityThread.reportSizeConfigurations race"
                isForegroundServiceTimeout(throwable) -> "ForegroundServiceDidNotStartInTime (OEM blocked FGS)"
                else -> null
            }
            if (reason != null) {
                Log.w("AmbilightApplication", "Swallowed framework bug ($reason) on ${thread.name}", throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Android-framework NPE emitted from MediaCodec's internal DisplayListener on
     * display removal (NVIDIA SHIELD HDMI unplug, etc.). Listener outlives release()
     * on affected firmware; no app frames in stack.
     */
    private fun isMediaCodecDisplayListenerNpe(t: Throwable): Boolean {
        if (t !is NullPointerException) return false
        val frames = t.stackTrace
        if (frames.isEmpty()) return false
        val top = frames[0]
        if (!top.className.startsWith("android.media.MediaCodec") || top.methodName != "onDisplayChanged") return false
        return frames.any { it.className.startsWith("android.hardware.display.DisplayManagerGlobal") }
    }

    /**
     * AOSP race where the system reports an Activity's size configurations after its
     * ActivityRecord is already gone, surfaced as
     * `IllegalArgumentException: reportSizeConfigurations: ActivityRecord not found`.
     * Triggered by short-lived trampoline activities (BootActivity); originates
     * entirely in system_server, no app frame to blame.
     */
    private fun isReportSizeConfigurationsBug(t: Throwable): Boolean {
        if (t !is IllegalArgumentException) return false
        if (t.message?.contains("reportSizeConfigurations") == true) return true
        return t.stackTrace.any {
            it.className == "android.app.ActivityThread" && it.methodName == "reportSizeConfigurations"
        }
    }

    /**
     * `ForegroundServiceDidNotStartInTimeException` for our own capture service. On
     * some OEM firmware (e.g. TCL) startForeground() is blocked, so the service
     * intentionally keeps running without a foreground notification and the platform
     * later force-throws this exception. Scoped to our package so a genuine
     * "forgot to call startForeground" bug elsewhere still surfaces.
     */
    private fun isForegroundServiceTimeout(t: Throwable): Boolean {
        if (!t.javaClass.name.contains("ForegroundServiceDidNotStartInTimeException")) return false
        return t.message?.contains(packageName) == true
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
