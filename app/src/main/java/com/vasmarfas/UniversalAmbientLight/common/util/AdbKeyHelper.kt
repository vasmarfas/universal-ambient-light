package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyHelper {
    
    fun getKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")
        
        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }
        
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
