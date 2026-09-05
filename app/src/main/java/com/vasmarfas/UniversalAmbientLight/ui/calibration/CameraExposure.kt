package com.vasmarfas.UniversalAmbientLight.ui.calibration

import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import kotlin.math.roundToInt

private const val TAG = "CameraExposure"
private const val EXPOSURE_SETTLE_MS = 700L

/**
 * Экспозицию меряем по экрану ТВ и фиксируем: автоматика камеры иначе подстраивалась бы под
 * каждую смену сцены, одновременно на экране и на стене, и это совпадение тянуло бы ответ к
 * нулю. Минус одна ступень - чтобы яркие сцены на экране не упирались в белое.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun lockExposure(camera: Camera?, analysis: ImageAnalysis?, x: Float, y: Float) {
    camera ?: return
    analysis ?: return
    val control = camera.cameraControl
    val exposure = camera.cameraInfo.exposureState
    if (exposure.isExposureCompensationSupported) {
        val step = exposure.exposureCompensationStep.toFloat()
        val index = -(1f / step).roundToInt()
        control.setExposureCompensationIndex(index.coerceIn(exposure.exposureCompensationRange.lower, 0))
    }
    val point = SurfaceOrientedMeteringPointFactory(1f, 1f, analysis).createPoint(x, y)
    control.startFocusAndMetering(
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
    )
    Handler(Looper.getMainLooper()).postDelayed({
        try {
            Camera2CameraControl.from(control).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                    .build()
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot lock exposure: ${e.message}")
        }
    }, EXPOSURE_SETTLE_MS)
}
