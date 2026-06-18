package com.vasmarfas.UniversalAmbientLight.common

import com.vasmarfas.UniversalAmbientLight.common.network.HyperionThread
import com.vasmarfas.UniversalAmbientLight.common.util.AppOptions

/**
 * Play Store stub. Mirrors the "full" encoder's public surface so shared code compiles,
 * but is never instantiated here: AccessibilityCaptureService.getInstance() returns null
 * in this flavor, so the capture path that builds this encoder is unreachable.
 */
@Suppress("UNUSED_PARAMETER")
class AccessibilityEncoder(
    service: AccessibilityCaptureService,
    listener: HyperionThread.HyperionThreadListener,
    screenWidth: Int,
    screenHeight: Int,
    options: AppOptions,
) {
    fun isCapturing(): Boolean = false
    fun sendStatus() {}
    fun clearLights() {}
    fun stopRecording() {}
    fun resumeRecording() {}
    fun setOrientation(orientation: Int) {}
}
