package com.vasmarfas.UniversalAmbientLight.common

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Service for capturing screen content via Accessibility API (Android 11+).
 * This is a workaround for devices where MediaProjection is blocked.
 *
 * Also used to auto-read the 6-digit ADB wireless pairing code from the system
 * "Pair device with pairing code" screen, so the app can pair without the user
 * typing it (and without leaving that screen, which would cancel pairing).
 *
 * Note: the class is not annotated @RequiresApi(30) on purpose — the service runs on
 * API 26+, and the only API 30 call (takeScreenshot) is guarded at runtime below. Marking
 * the whole class @RequiresApi would force every (API-safe) companion call site across the
 * app to require API 30.
 */
class AccessibilityCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service connected")
        instance.set(this)
        returnToAppIfRequested()
    }

    /**
     * If the user was just sent here to enable this service for auto-pair, bring our app
     * back to the foreground so they don't have to press Back through Settings. Best-effort:
     * background activity start may be blocked on some OS versions (then Back still works).
     */
    private fun returnToAppIfRequested() {
        val ts = returnRequestedAt
        if (ts == 0L || System.currentTimeMillis() - ts > 10 * 60 * 1000L) return
        returnRequestedAt = 0L
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (intent != null) startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private var lastClickLabel = ""
    private var lastClickTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pairingWatch) return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        scanActiveWindowInternal()
    }

    /**
     * Reads the pairing code if the dialog is up, otherwise walks the Settings menus
     * forward by tapping "Pair with code" / "Wireless debugging" rows (never toggles).
     * Driven both by events and by polling from the coordinator, because static screens
     * (e.g. Developer Options) emit no events after the watch starts.
     */
    private fun scanActiveWindowInternal() {
        if (!pairingWatch) return
        try {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString() ?: ""
            // Only act inside the system Settings app.
            if (!pkg.contains("settings", ignoreCase = true)) return

            val code = findSixDigitCode(root)
            if (code != null) {
                detectedCode.set(code)
                return
            }
            if (clickByKeywords(root, PAIR_KEYWORDS, "pair")) return
            clickByKeywords(root, WIRELESS_DEBUG_KEYWORDS, "wd")
        } catch (_: Exception) {
        }
    }

    private fun findSixDigitCode(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString()?.trim()
        if (text != null && SIX_DIGITS.matches(text)) return text
        for (i in 0 until node.childCount) {
            findSixDigitCode(node.getChild(i))?.let { return it }
        }
        return null
    }

    /** Finds a node whose text/description contains any keyword and clicks its row. */
    private fun clickByKeywords(root: AccessibilityNodeInfo?, keywords: List<String>, label: String): Boolean {
        val now = System.currentTimeMillis()
        if (label == lastClickLabel && now - lastClickTime < 3000) return false
        val node = findByKeywords(root, keywords) ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            // Click the list-row container, never a switch/toggle (would flip the setting).
            if (n.isClickable && !isToggle(n)) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastClickLabel = label
                    lastClickTime = now
                    return true
                }
                return false
            }
            n = n.parent
        }
        return false
    }

    private fun findByKeywords(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val hay =
            ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: ""))
                .lowercase()
        if (hay.isNotBlank() && keywords.any { hay.contains(it) }) return node
        for (i in 0 until node.childCount) {
            findByKeywords(node.getChild(i), keywords)?.let { return it }
        }
        return null
    }

    private fun isToggle(n: AccessibilityNodeInfo): Boolean {
        val cn = n.className?.toString() ?: ""
        return cn.contains("Switch") || cn.contains("Toggle") || cn.contains("CompoundButton")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility Service interrupted")
        instance.set(null)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance.set(null)
        return super.onUnbind(intent)
    }

    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var copy: Bitmap? = null
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        // Copy to a software-readable bitmap. wrapHardwareBuffer-backed
                        // bitmaps must not be recycled — closing the buffer is enough.
                        copy = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot conversion failed", e)
                    } finally {
                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                        }
                    }
                    callback(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    callback(null)
                }
            })
        } else {
            callback(null)
        }
    }

    companion object {
        private const val TAG = "AccessibilityCapture"
        private val instance = AtomicReference<AccessibilityCaptureService?>(null)
        private val SIX_DIGITS = Regex("^\\d{6}$")

        // Lowercased substrings to recognise the relevant Settings rows across locales.
        private val PAIR_KEYWORDS = listOf(
            "pair device with pairing code", "pairing code", "pair using",
            "кода подключения", "помощью кода", "код сопряж"
        )
        private val WIRELESS_DEBUG_KEYWORDS = listOf(
            "wireless debugging", "отладка по wi", "беспроводн"
        )

        @Volatile
        private var pairingWatch = false
        private val detectedCode = AtomicReference<String?>(null)

        @Volatile
        private var returnRequestedAt = 0L

        /** Call right before sending the user to enable this service, to auto-return on grant. */
        fun requestReturnToAppOnConnect() {
            returnRequestedAt = System.currentTimeMillis()
        }

        @Volatile
        private var autoPairPending = false

        /** Mark that auto-pair should resume (show consent) once the user returns to the app. */
        fun markAutoPairPending() {
            autoPairPending = true
        }

        /** Returns true once if auto-pair was pending, then clears the flag. */
        fun consumeAutoPairPending(): Boolean {
            val v = autoPairPending
            autoPairPending = false
            return v
        }

        fun getInstance(): AccessibilityCaptureService? = instance.get()

        /** True if the accessibility service is currently connected (can read the screen). */
        fun isAvailable(): Boolean = instance.get() != null

        /** Begin watching the system pairing screen for the 6-digit code. */
        fun startPairingWatch() {
            detectedCode.set(null)
            pairingWatch = true
        }

        fun stopPairingWatch() {
            pairingWatch = false
            detectedCode.set(null)
        }

        /** The last 6-digit code read from the pairing screen, or null. */
        fun detectedPairingCode(): String? = detectedCode.get()

        /** Polled scan trigger — events don't fire on static Settings screens. */
        fun scanActiveWindow() {
            instance.get()?.scanActiveWindowInternal()
        }
    }
}
