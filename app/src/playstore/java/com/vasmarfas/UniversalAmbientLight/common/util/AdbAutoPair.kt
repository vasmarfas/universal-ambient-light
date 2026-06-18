package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context

/**
 * Play Store stub. Accessibility-assisted auto-pairing relies on the AccessibilityService,
 * which this flavor does not ship. The auto-pair UI is hidden behind
 * BuildConfig.HAS_ACCESSIBILITY, so run() is never invoked here; it returns
 * NeedsAccessibility to stay safe if it ever were.
 */
@Suppress("UNUSED_PARAMETER")
object AdbAutoPair {

    sealed class Result {
        object Paired : Result()
        object NeedsAccessibility : Result()
        object Timeout : Result()
        data class Failed(val message: String) : Result()
    }

    fun run(context: Context, timeoutMs: Long = 90_000L): Result = Result.NeedsAccessibility
}
