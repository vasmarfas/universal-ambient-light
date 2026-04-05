package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.TimeUnit

/**
 * Grants USB device permission via root (su + app_process) so that
 * the app can access USB serial devices without a user dialog.
 *
 * Uses the hidden IUsbManager.grantDevicePermission() API, called from
 * a root process via app_process. This grants runtime permission directly
 * in UsbService's memory — works immediately, no reboot needed.
 *
 * Requires Magisk or similar root solution.
 */
object UsbRootPermissionHelper {
    private const val TAG = "UsbRootPermission"
    private const val ROOT_CHECK_TIMEOUT_SEC = 2L

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * Checks if root (su) is available on this device.
     * Result is cached for the lifetime of the process.
     */
    fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val result = checkRootAvailable()
        cachedRootAvailable = result
        return result
    }

    private fun checkRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val completed = process.waitFor(ROOT_CHECK_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                Log.w(TAG, "Root check timed out after ${ROOT_CHECK_TIMEOUT_SEC}s")
                return false
            }
            val result = process.inputStream.bufferedReader().readText()
            result.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Grants USB permission for all connected serial devices via root.
     * Uses app_process to call the hidden IUsbManager.grantDevicePermission() API.
     *
     * @return true if at least one device permission was granted
     */
    fun grantPermissionViaRoot(context: Context): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB serial devices found")
            return false
        }

        val uid = android.os.Process.myUid()
        var anyGranted = false

        for (driver in drivers) {
            val device = driver.device
            if (usbManager.hasPermission(device)) {
                Log.d(TAG, "Already have permission for ${device.deviceName}")
                anyGranted = true
                continue
            }

            if (grantSingleDevice(context, device.deviceName, uid)) {
                anyGranted = true
                Log.i(TAG, "Root-granted USB permission for ${device.deviceName} (uid=$uid)")
            }
        }

        return anyGranted
    }

    private fun grantSingleDevice(context: Context, deviceName: String, uid: Int): Boolean {
        val apkPath = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                "com.vasmarfas.UniversalAmbientLight.common.util.UsbPermissionGranterCli " +
                "$deviceName $uid"

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.i(TAG, "app_process grant OK: $stdout")
                true
            } else {
                Log.e(TAG, "app_process grant failed (exit=$exitCode): $stderr")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run app_process: ${e.message}", e)
            false
        }
    }
}
