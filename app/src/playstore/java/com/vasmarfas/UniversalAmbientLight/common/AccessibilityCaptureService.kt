package com.vasmarfas.UniversalAmbientLight.common

/**
 * Play Store stub. The "full" flavor ships a real AccessibilityService here; the Google
 * Play flavor must not contain any AccessibilityService, so this is a plain, inert class
 * that keeps shared code compiling. Every entry point reports "unavailable", so all
 * accessibility-dependent code paths stay dormant in this build.
 */
class AccessibilityCaptureService private constructor() {

    companion object {
        fun getInstance(): AccessibilityCaptureService? = null
        fun isAvailable(): Boolean = false
        fun consumeAutoPairPending(): Boolean = false
        fun requestReturnToAppOnConnect() {}
        fun markAutoPairPending() {}
    }
}
