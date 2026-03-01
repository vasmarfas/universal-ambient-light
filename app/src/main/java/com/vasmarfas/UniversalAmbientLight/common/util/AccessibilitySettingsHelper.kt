package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService

/**
 * Opens the Accessibility settings as deep as possible for our service:
 *  - Android 13+  → exact service detail page (undocumented intent, works on AOSP)
 *  - Android 9–12 → general page scrolled/highlighted to our component via fragment args
 *  - Fallback     → plain ACTION_ACCESSIBILITY_SETTINGS
 */
fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(
        context.packageName,
        AccessibilityCaptureService::class.java.name
    )
    val componentKey = component.flattenToString()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Android 13+: deep-link to exact service settings page
        // Action string is not a public SDK constant, use the raw string
        try {
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            intent.putExtra("accessibility_service", componentKey)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {}
    }

    // Android 9–12: fragment args trick — scrolls & highlights our service in the list
    try {
        val fragmentArgs = Bundle()
        fragmentArgs.putString(":settings:fragment_args_key", componentKey)
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.putExtra(":settings:fragment_args_key", componentKey)
        intent.putExtra(":settings:show_fragment_args", fragmentArgs)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    // Fallback: plain accessibility settings
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {}
}
