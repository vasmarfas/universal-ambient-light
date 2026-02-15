package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.vasmarfas.UniversalAmbientLight.BuildConfig

object DebugInfoHelper {

    fun getDebugInfo(context: Context): String {
        val sb = StringBuilder()

        sb.append("=== DEVICE INFO ===\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Board: ${Build.BOARD}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Fingerprint: ${Build.FINGERPRINT}\n\n")

        sb.append("=== APP INFO ===\n")
        sb.append("Version Name: ${BuildConfig.VERSION_NAME}\n")
        sb.append("Version Code: ${BuildConfig.VERSION_CODE}\n")
        sb.append("Package: ${context.packageName}\n\n")

        sb.append("=== PERMISSIONS ===\n")
        sb.append("Overlay (Settings): ${Settings.canDrawOverlays(context)}\n")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                
                val overlayOp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW
                } else {
                    "android:system_alert_window"
                }
                
                val modeOverlay = appOps.checkOpNoThrow(
                    overlayOp,
                    Process.myUid(), 
                    context.packageName
                )
                sb.append("Overlay (AppOps): ${modeToName(modeOverlay)} ($modeOverlay)\n")
                
                val modeProjectMedia = appOps.checkOpNoThrow(
                    "android:project_media",
                    Process.myUid(), 
                    context.packageName
                )
                sb.append("Project Media (AppOps): ${modeToName(modeProjectMedia)} ($modeProjectMedia)\n")
                
            } catch (e: Exception) {
                sb.append("AppOps Check Failed: ${e.message}\n")
            }
        }
        
        sb.append("TCL/Restricted Bypass: ${TclBypass.isRestrictedManufacturer()}\n")
        sb.append("Is TCL Device: ${TclBypass.isTclDevice()}\n")

        return sb.toString()
    }

    private fun modeToName(mode: Int): String {
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "ALLOWED"
            AppOpsManager.MODE_IGNORED -> "IGNORED"
            AppOpsManager.MODE_ERRORED -> "ERRORED"
            AppOpsManager.MODE_DEFAULT -> "DEFAULT"
            else -> "UNKNOWN ($mode)"
        }
    }
}
