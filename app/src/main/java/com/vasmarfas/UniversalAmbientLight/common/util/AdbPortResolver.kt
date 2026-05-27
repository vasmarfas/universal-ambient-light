package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Resolves the ADB port for the dadb-based capture encoders (scrcpy / adb).
 *
 * dadb speaks only the legacy RSA ADB protocol. Android 11+ "Wireless debugging" exposes
 * a TLS-only connect port (which also rotates on every toggle/reboot), so dadb cannot use
 * it at all — the handshake fails. To work around this we reuse the TLS-capable
 * [AppAdbConnectionManager] (the same path the "Test connection" button uses) to flip adbd
 * into plain `tcpip` mode on a fixed port, which dadb can then use with its RSA key.
 *
 * Blocking (TLS connect + tcpip + TCP probes). Call from a worker thread, never the main thread.
 */
object AdbPortResolver {
    private const val TAG = "AdbPortResolver"
    private const val PROBE_TIMEOUT_MS = 600
    private const val TCPIP_PORT = 5555
    private const val TCPIP_WAIT_TRIES = 20
    private const val TCPIP_WAIT_STEP_MS = 250L

    /**
     * @param savedPort the port configured in preferences
     * @return a port that the dadb (legacy RSA) client can actually connect to
     */
    fun resolveForDadb(context: Context, savedPort: Int): Int {
        // Android <= 10: the configured RSA port (usually 5555) works with dadb directly.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return savedPort
        }

        // Android 11+: dadb needs a plain (non-TLS) port.
        if (isPortAlive(TCPIP_PORT)) {
            // adbd is already in tcpip mode (e.g. previous run or `adb tcpip 5555`).
            return TCPIP_PORT
        }
        if (switchAdbdToTcpip(context, TCPIP_PORT)) {
            // adbd restarts and rebinds; wait for the plain port to come up.
            repeat(TCPIP_WAIT_TRIES) {
                if (isPortAlive(TCPIP_PORT)) {
                    Log.i(TAG, "adbd now in tcpip mode on $TCPIP_PORT")
                    return TCPIP_PORT
                }
                try {
                    Thread.sleep(TCPIP_WAIT_STEP_MS)
                } catch (_: InterruptedException) {
                    return savedPort
                }
            }
            Log.w(TAG, "tcpip:$TCPIP_PORT requested but the port did not come up")
        }
        // Last resort: let dadb try the configured port (will likely fail on a TLS port).
        return savedPort
    }

    /**
     * Opens a TLS connection (auto-discovering the wireless-debugging port via mDNS) and runs
     * the `tcpip:<port>` service, which makes adbd restart listening on a plain RSA port.
     *
     * Requires the device to have been paired already (otherwise autoConnect throws).
     */
    private fun switchAdbdToTcpip(context: Context, port: Int): Boolean {
        return try {
            val mgr = AppAdbConnectionManager.getInstance(context)
            if (!mgr.isConnected) {
                val auto = try {
                    mgr.autoConnect(context, 8000)
                } catch (e: Throwable) {
                    Log.w(TAG, "TLS autoConnect failed (paired?): ${e.message}")
                    false
                }
                if (!auto) return false
            }
            Log.i(TAG, "Requesting adbd 'tcpip:$port' over TLS…")
            val stream = mgr.openStream("tcpip:$port")
            // Drain the short ack so adbd processes the request; it then restarts (stream closes).
            try {
                stream.openInputStream().readBytes()
            } catch (_: Exception) {
            }
            try {
                stream.close()
            } catch (_: Exception) {
            }
            try {
                mgr.disconnect()
            } catch (_: Exception) {
            } // the TLS link drops on adbd restart anyway
            true
        } catch (e: Throwable) {
            Log.w(TAG, "tcpip switch failed: ${e.message}")
            false
        }
    }

    private fun isPortAlive(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }
}
