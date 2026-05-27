package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Helpers for navigating the user to Android developer settings so they can
 * enable USB / wireless debugging without leaving the app.
 */
object DevOptionsHelper {
    private const val TAG = "DevOptionsHelper"

    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the Developer Options screen. Returns false if not reachable. */
    fun openDeveloperOptions(context: Context): Boolean {
        return tryOpen(
            context,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.DevelopmentSettings")
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.system.development.DevelopmentActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.system.DevelopmentFragment"
                )
            )
        )
    }

    /**
     * Tries to open the Wireless Debugging screen (Android 11+).
     * If a direct route is unavailable, falls back to Developer Options scrolled
     * to the wireless-debugging toggle (using the SettingsActivity fragment-args
     * extras). Last resort — plain Developer Options.
     */
    fun openWirelessDebugging(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val direct = tryOpen(
                context,
                // Public action constant on API 31+, also recognised on AOSP Android 11.
                Intent("android.settings.ADB_WIRELESS_SETTINGS"),
                // Explicit Pixel/AOSP activity.
                Intent().setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$AdbWirelessSettingsActivity"
                    )
                ),
                // Some OEM variants.
                Intent().setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.development.WirelessDebuggingActivity"
                    )
                ),
                // Android TV (com.android.tv.settings).
                Intent().setComponent(
                    ComponentName(
                        "com.android.tv.settings",
                        "com.android.tv.settings.system.development.WirelessDebuggingActivity"
                    )
                )
            )
            if (direct) return true
        }

        // Fragment-args fallback: opens Developer Options with the wireless-debugging
        // toggle highlighted/scrolled into view. Works on most AOSP-derived Settings apps.
        // The key matches the preference id in development_settings.xml across versions.
        val highlightIntents = listOf(
            "toggle_adb_wireless",
            "adb_wireless",
            "wireless_debugging"
        ).map { key ->
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", key)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", key)
                })
            }
        }
        if (tryOpen(context, *highlightIntents.toTypedArray())) return true

        return openDeveloperOptions(context)
    }

    /**
     * Opens the "About phone" / "Device info" screen so the user can tap "Build number"
     * seven times to unlock Developer Options.
     */
    fun openAboutDeviceForBuildNumber(context: Context): Boolean {
        return tryOpen(
            context,
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.DeviceInfoSettings")
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.about.AboutFragment"
                )
            )
        )
    }

    private fun tryOpen(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (context.packageManager.resolveActivity(intent, 0) == null) continue
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (e: Exception) {
                Log.d(TAG, "Intent ${intent.component ?: intent.action} failed: ${e.message}")
            }
        }
        return false
    }
}
