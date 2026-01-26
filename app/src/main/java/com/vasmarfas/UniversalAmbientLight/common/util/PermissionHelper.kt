package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast

object PermissionHelper {
    private const val TAG = "PermissionHelper"

    fun canDrawOverlays(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context)
        }
        return true
    }

    fun requestOverlayPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(activity)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.packageName)
                )
                activity.startActivityForResult(intent, requestCode)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot request overlay permission", e)
                openAppSettings(activity)
            }
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:" + activity.packageName)
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot request battery optimization exemption", e)
            }
        }
    }

    fun hasProjectMediaPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.checkOpNoThrow(
                    "android:project_media",
                    Process.myUid(), context.packageName
                )
                return mode == AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                Log.w(TAG, "Cannot check PROJECT_MEDIA permission", e)
            }
        }
        return true
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open app settings", e)
            Toast.makeText(
                context,
                "Please open Settings > Apps > Hyperion Grabber manually",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun tryGrantProjectMediaViaShell(context: Context) {
        Thread {
            try {
                val pkg = context.packageName
                val commands = arrayOf(
                    "appops set $pkg PROJECT_MEDIA allow",
                    "appops set $pkg android:project_media allow",
                    "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                    "pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW"
                )

                for (cmd in commands) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        process.waitFor()
                        Log.d(TAG, "Executed: $cmd (exit: ${process.exitValue()})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Command failed: $cmd")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shell commands failed", e)
            }
        }.start()
    }

    fun showFullPermissionDialog(activity: Activity, onRetry: Runnable?) {
        val deviceInfo = "Device: " + Build.MANUFACTURER + " " + Build.MODEL

        val message = """
            Screen recording permission could not be obtained.
            
            Your TV may be blocking the permission dialog.
            
            SOLUTIONS:
            
            1. OVERLAY PERMISSION:
               Settings > Apps > Special access > Display over other apps
            
            2. APP PERMISSIONS:
               Settings > Apps > Hyperion Grabber > Permissions
            
            3. AUTO-START (TCL/Smart TVs):
               Settings > Privacy > Special app access > Auto-start
               OR Settings > Apps > App management
            
            4. BATTERY OPTIMIZATION:
               Settings > Apps > Special access > Battery optimization
            
            5. ADB COMMAND (requires computer):
               adb shell appops set ${activity.packageName} PROJECT_MEDIA allow
            
            $deviceInfo
            """.trimIndent()

        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Open App Settings") { _, _ -> openAppSettings(activity) }
            .setNeutralButton("Try Overlay") { _, _ ->
                requestOverlayPermission(activity, 999)
            }
            .setNegativeButton("Retry") { _, _ ->
                tryGrantProjectMediaViaShell(activity)
                if (onRetry != null) {
                    activity.window.decorView.postDelayed({
                        onRetry.run()
                    }, 1000)
                }
            }
            .setCancelable(true)
            .show()
    }
}
