package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object TclBypass {
    private const val TAG = "TclBypass"

    fun isTclDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()

        return manufacturer.contains("tcl") ||
                brand.contains("tcl") ||
                device.startsWith("g10") ||
                model.contains("tcl") ||
                model.contains("smart tv")
    }

    fun isRestrictedManufacturer(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("tcl") ||
                manufacturer.contains("xiaomi") ||
                manufacturer.contains("huawei") ||
                manufacturer.contains("oppo") ||
                manufacturer.contains("vivo") ||
                manufacturer.contains("realme") ||
                manufacturer.contains("samsung")
    }

    fun openTclAutoStartSettings(context: Context): Boolean {
        val intents = arrayOf(
            // TCL specific intents
            Intent().setComponent(
                ComponentName(
                    "com.tcl.guard",
                    "com.tcl.guard.activity.AutostartActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.tcl.guard",
                    "com.tcl.guard.activity.AppAutoStartManagerActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.tcl.settings.TclAutoStartSettingsActivity"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.tcl.tvweishi",
                    "com.tcl.tvweishi.settings.AutoBootManageActivity"
                )
            ),
            // TCL TV Settings
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.MainSettings"
                )
            ),
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.device.apps.AppsActivity"
                )
            ),
            // Generic auto-start intents
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName))
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isIntentAvailable(context, intent)) {
                    context.startActivity(intent)
                    Log.d(TAG, "Opened: " + intent.component)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to open: $intent", e)
            }
        }
        return false
    }

    fun openSpecialAppAccess(context: Context): Boolean {
        val intents = arrayOf(
            // Special app access
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:" + context.packageName)),
            Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION")
                .setData(Uri.parse("package:" + context.packageName)),
            // TV Settings apps section
            Intent().setComponent(
                ComponentName(
                    "com.android.tv.settings",
                    "com.android.tv.settings.device.apps.AppManagementActivity"
                )
            )
                .putExtra("packageName", context.packageName)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isIntentAvailable(context, intent)) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed: $intent", e)
            }
        }
        return false
    }

    fun tryShellBypass(context: Context) {
        Thread {
            val pkg = context.packageName
            val commands = arrayOf(
                "appops set $pkg PROJECT_MEDIA allow",
                "appops set $pkg android:project_media allow",
                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "settings put global auto_start_$pkg 1",
                "settings put secure auto_start_$pkg 1",
                "am broadcast -a com.tcl.action.ALLOW_AUTO_START -e package $pkg",
                "am broadcast -a com.tcl.appboot.action.SET_ALLOW -e package $pkg",
                "settings put global tcl_app_boot_$pkg allow",
                "settings put secure tcl_app_boot_$pkg allow",
                "cmd deviceidle whitelist +$pkg",
                "dumpsys deviceidle whitelist +$pkg",
                "appops set $pkg RUN_IN_BACKGROUND allow",
                "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                "appops set $pkg START_FOREGROUND allow",
                "appops set $pkg INSTANT_APP_START_FOREGROUND allow"
            )

            for (cmd in commands) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    process.waitFor()
                    val exit = process.exitValue()
                    if (exit == 0) {
                        Log.d(TAG, "Success: $cmd")
                    }
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    fun showTclHelpDialog(activity: Activity, onRetry: Runnable?) {
        val pkg = activity.packageName

        val message = "Your TV is blocking the screen recording service.\n\n" +
                "USE ADB TO FIX (from computer):\n\n" +
                "adb shell appops set $pkg PROJECT_MEDIA allow\n\n" +
                "adb shell appops set $pkg START_FOREGROUND allow\n\n" +
                "Or reinstall with permissions:\n" +
                "adb install -g -r hyperion.apk"

        AlertDialog.Builder(activity)
            .setTitle("TCL Blocked")
            .setMessage(message)
            .setPositiveButton("Retry") { d, w ->
                tryShellBypass(activity)
                if (onRetry != null) {
                    activity.window.decorView.postDelayed({ onRetry.run() }, 1000)
                }
            }
            .setNegativeButton("Close", null)
            .setCancelable(true)
            .show()
    }

    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return list != null && !list.isEmpty()
    }
}
