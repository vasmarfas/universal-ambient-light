package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbCameraUtil(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ACTION_USB_PERMISSION = "com.vasmarfas.UniversalAmbientLight.USB_PERMISSION"

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun getConnectedWebcams(): List<UsbDevice> {
        val deviceList = usbManager.deviceList
        return deviceList.values.filter { isWebcam(it) }
    }

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val deviceExtra: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (deviceExtra?.deviceName == device.deviceName) {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            onResult(granted)
                        }
                    }
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        Log.e("UsbCameraUtil", "Failed to unregister receiver", e)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    private fun isWebcam(device: UsbDevice): Boolean {
        // Check for Video Class (0x0E) in device class or interfaces
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        if (device.deviceClass == UsbConstants.USB_CLASS_MISC) return true // Sometimes composite devices

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }
}
