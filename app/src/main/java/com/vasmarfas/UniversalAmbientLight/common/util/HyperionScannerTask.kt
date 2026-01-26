package com.vasmarfas.UniversalAmbientLight.common.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vasmarfas.UniversalAmbientLight.common.network.NetworkScanner
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Starts a network scan for a running Hyperion server
 * and posts progress and results
 */
class HyperionScannerTask(listener: Listener) {
    private val weakListener: WeakReference<Listener> = WeakReference(listener)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun execute() {
        executor.execute {
            val result = doInBackground()
            mainHandler.post { onPostExecute(result) }
        }
    }

    private fun doInBackground(): String? {
        Log.d("Hyperion scanner", "starting scan")
        val networkScanner = NetworkScanner()

        var result: String?
        while (networkScanner.hasNextAttempt()) {
            result = networkScanner.tryNext()

            if (result != null) {
                return result
            }

            val progress = networkScanner.progress
            mainHandler.post { onProgressUpdate(progress) }
        }

        return null
    }

    private fun onProgressUpdate(value: Float) {
        Log.d("Hyperion scanner", "scan progress: $value")
        weakListener.get()?.onScannerProgress(value)
    }

    private fun onPostExecute(result: String?) {
        Log.d("Hyperion scanner", "scan result: $result")
        weakListener.get()?.onScannerCompleted(result)
    }

    interface Listener {
        fun onScannerProgress(progress: Float)
        fun onScannerCompleted(foundIpAddress: String?)
    }
}
