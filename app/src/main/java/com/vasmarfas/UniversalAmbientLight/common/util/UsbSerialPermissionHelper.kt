package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Helper for requesting USB permission for the first (or specific) USB-Serial device.
 *
 * Goals:
 * - Make "one tap start" work from MainActivity/BootActivity/ToggleActivity/QuickTile flows.
 * - Optionally request permission automatically when device is attached while app is in foreground.
 */
object UsbSerialPermissionHelper {
    private const val TAG = "UsbSerialPermission"

    // Keep this action stable (already used in MainActivity)
    const val ACTION_USB_PERMISSION = "com.vasmarfas.UniversalAmbientLight.USB_PERMISSION"

    @Volatile
    private var lastRequestedDeviceId: Int? = null

    fun hasAnySerialDevice(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).isNotEmpty()
    }

    fun findFirstSerialDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return drivers.firstOrNull()?.device
    }

    fun isSerialDevice(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        return UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .any { it.device.deviceId == device.deviceId }
    }

    /**
     * Ensures USB permission for an Adalight USB-Serial device.
     *
     * If permission is already granted -> calls [onReady] immediately.
     * If not granted -> requests permission and calls [onReady] after user accepts.
     *
     * @return true if already ready (permission granted), false if a request was initiated.
     */
    fun ensurePermissionForSerialDevice(
        context: Context,
        device: UsbDevice?,
        onReady: () -> Unit,
        onDenied: (() -> Unit)? = null,
        showToast: Boolean = true
    ): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            if (showToast) Toast.makeText(context, "USB service is not available on this device", Toast.LENGTH_LONG).show()
            onDenied?.invoke()
            return false
        }

        val target = device ?: findFirstSerialDevice(context)
        if (target == null) {
            if (showToast) Toast.makeText(context, "No USB serial devices found. Connect your device via USB OTG", Toast.LENGTH_LONG).show()
            onDenied?.invoke()
            return false
        }

        // Avoid prompting for non-serial devices (e.g., random USB accessories)
        if (!isSerialDevice(context, target)) {
            onDenied?.invoke()
            return false
        }

        if (usbManager.hasPermission(target)) {
            onReady()
            return true
        }

        // Avoid spamming the same prompt
        if (lastRequestedDeviceId == target.deviceId) {
            return false
        }
        lastRequestedDeviceId = target.deviceId

        AnalyticsHelper.logUsbPermissionRequested(context)

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    ctx.unregisterReceiver(this)
                } catch (_: Exception) {
                }

                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    AnalyticsHelper.logUsbPermissionGranted(ctx)
                    onReady()
                } else {
                    AnalyticsHelper.logUsbPermissionDenied(ctx)
                    if (showToast) {
                        Toast.makeText(
                            ctx,
                            "USB device permission denied. Please allow USB access.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    onDenied?.invoke()
                }
            }
        }

        // Register receiver (not exported)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            usbManager.requestPermission(target, permissionIntent)
            if (showToast) Toast.makeText(context, "Подтвердите доступ к USB устройству", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed: ${e.message}", e)
            onDenied?.invoke()
        }

        return false
    }
}

