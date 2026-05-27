package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * Wireless ADB connection for Android 11+ (TLS + pairing-by-code), backed by
 * libadb-android. Connects to this device's own ADB daemon over loopback.
 *
 * Legacy devices (API < 30) keep using the dadb/RSA path inside the encoders.
 */
class AppAdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val material = AdbCertHelper.getOrCreate(context)

    init {
        api = Build.VERSION.SDK_INT
        hostAddress = "127.0.0.1"
    }

    override fun getPrivateKey(): PrivateKey = material.privateKey

    override fun getCertificate(): Certificate = material.certificate

    override fun getDeviceName(): String = "UniversalAmbientLight"

    companion object {
        @Volatile
        private var instance: AppAdbConnectionManager? = null

        @Synchronized
        fun getInstance(context: Context): AppAdbConnectionManager {
            return instance ?: AppAdbConnectionManager(context.applicationContext).also {
                instance = it
            }
        }
    }
}
