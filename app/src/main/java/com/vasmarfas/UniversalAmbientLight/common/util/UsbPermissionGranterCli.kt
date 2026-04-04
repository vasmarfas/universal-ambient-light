@file:JvmName("UsbPermissionGranterCli")

package com.vasmarfas.UniversalAmbientLight.common.util

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.IBinder

/**
 * Standalone CLI entry point executed via:
 *   su -c "CLASSPATH=<apk> app_process /system/bin
 *          com.vasmarfas.UniversalAmbientLight.common.util.UsbPermissionGranterCli
 *          <device_name> <uid>"
 *
 * Runs as root (uid 0) and calls the hidden IUsbManager.grantDevicePermission()
 * to grant USB permission without user interaction.
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: UsbPermissionGranterCli <device_name> <uid>")
        System.exit(1)
        return
    }

    val targetDeviceName = args[0]  // e.g., "/dev/bus/usb/002/003"
    val targetUid = args[1].toInt()

    try {
        // Get USB service via reflection (hidden API)
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getService = serviceManagerClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "usb") as IBinder

        val stubClass = Class.forName("android.hardware.usb.IUsbManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val usbService = asInterface.invoke(null, binder)!!

        // Get device list: void getDeviceList(out Bundle devices)
        val getDeviceList = usbService.javaClass.getMethod("getDeviceList", Bundle::class.java)
        val bundle = Bundle()
        getDeviceList.invoke(usbService, bundle)

        // Find the target device
        // Bundle keys are device names, values are UsbDevice parcels
        bundle.classLoader = UsbDevice::class.java.classLoader
        var granted = false
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            val device = bundle.getParcelable<UsbDevice>(key) ?: continue
            if (device.deviceName == targetDeviceName) {
                // Try grantDevicePermission — signature varies by Android version:
                //   API ≤33: grantDevicePermission(UsbDevice, int)
                //   API ≥34: grantDevicePermission(UsbDevice, int, UserHandle)
                try {
                    val grantMethod = usbService.javaClass.getMethod(
                        "grantDevicePermission",
                        UsbDevice::class.java,
                        Int::class.javaPrimitiveType
                    )
                    grantMethod.invoke(usbService, device, targetUid)
                } catch (_: NoSuchMethodException) {
                    val userHandleClass = Class.forName("android.os.UserHandle")
                    val ofMethod = userHandleClass.getMethod("of", Int::class.javaPrimitiveType)
                    val userHandle = ofMethod.invoke(null, targetUid / 100000) // userId from uid
                    val grantMethod = usbService.javaClass.getMethod(
                        "grantDevicePermission",
                        UsbDevice::class.java,
                        Int::class.javaPrimitiveType,
                        userHandleClass
                    )
                    grantMethod.invoke(usbService, device, targetUid, userHandle)
                }
                println("OK: granted permission for $targetDeviceName to uid $targetUid")
                granted = true
                break
            }
        }

        if (!granted) {
            System.err.println("Device $targetDeviceName not found in USB device list")
            System.exit(2)
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        e.printStackTrace(System.err)
        System.exit(3)
    }
}
