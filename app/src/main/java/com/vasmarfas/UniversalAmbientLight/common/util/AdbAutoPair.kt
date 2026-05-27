package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService

/**
 * "One-button" wireless ADB pairing for Android 11+.
 *
 * Reads the 6-digit pairing code from the system "Pair device with pairing code"
 * screen via the accessibility service and finds the pairing port via mDNS, then
 * pairs in the background — so the user never leaves that screen (leaving it would
 * cancel pairing) and never types the code.
 *
 * Blocking; call off the main thread.
 */
object AdbAutoPair {
    private const val TAG = "AdbAutoPair"

    sealed class Result {
        object Paired : Result()
        object NeedsAccessibility : Result()
        object Timeout : Result()
        data class Failed(val message: String) : Result()
    }

    fun run(context: Context, timeoutMs: Long = 90_000L): Result {
        if (!AccessibilityCaptureService.isAvailable()) return Result.NeedsAccessibility

        AccessibilityCaptureService.startPairingWatch()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var host: String? = null
            var port = -1

            while (System.currentTimeMillis() < deadline) {
                // Poll-scan the current Settings screen: read the code, or tap forward
                // through "Wireless debugging" → "Pair with code" (static screens emit no events).
                AccessibilityCaptureService.scanActiveWindow()

                val code = AccessibilityCaptureService.detectedPairingCode()

                if (port <= 0) {
                    val svc = AdbMdns.discover(context, AdbMdns.TLS_PAIRING, 2500)
                    if (svc != null) {
                        host = svc.host.hostAddress
                        port = svc.port
                    }
                }

                if (code != null && port > 0) {
                    val mgr = AppAdbConnectionManager.getInstance(context)
                    val pairHost = host ?: "127.0.0.1"
                    val ok = try {
                        mgr.pair(pairHost, port, code)
                    } catch (e: Throwable) {
                        Log.w(TAG, "pair failed: ${e.message}")
                        return Result.Failed(e.message ?: "pair error")
                    }
                    if (!ok) return Result.Failed("pairing rejected")
                    // Establish the working connection right away (best-effort).
                    try {
                        mgr.autoConnect(context, 8000)
                    } catch (_: Throwable) {
                    }
                    return Result.Paired
                }

                Thread.sleep(400)
            }
            return Result.Timeout
        } finally {
            AccessibilityCaptureService.stopPairingWatch()
        }
    }
}
