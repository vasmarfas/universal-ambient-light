package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.vasmarfas.UniversalAmbientLight.BuildConfig
import java.io.File
import java.util.Locale

object DebugInfoHelper {

    fun getDebugInfo(context: Context): String {
        val sb = StringBuilder()

        sb.append("=== DEVICE INFO ===\n")
        sb.append("Manufacturer: ${Build.MANUFACTURER}\n")
        sb.append("Model: ${Build.MODEL}\n")
        sb.append("Brand: ${Build.BRAND}\n")
        sb.append("Device: ${Build.DEVICE}\n")
        sb.append("Product: ${Build.PRODUCT}\n")
        sb.append("Board: ${Build.BOARD}\n")
        sb.append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Fingerprint: ${Build.FINGERPRINT}\n\n")

        sb.append("=== APP INFO ===\n")
        sb.append("Version Name: ${BuildConfig.VERSION_NAME}\n")
        sb.append("Version Code: ${BuildConfig.VERSION_CODE}\n")
        sb.append("Package: ${context.packageName}\n\n")

        appendCpuInfo(sb)
        appendMemoryInfo(sb, context)
        appendGpuInfo(sb)
        appendHardwareCodecs(sb)

        sb.append("=== PERMISSIONS ===\n")
        sb.append("Overlay (Settings): ${Settings.canDrawOverlays(context)}\n")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

                val overlayOp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW
                } else {
                    "android:system_alert_window"
                }

                val modeOverlay = appOps.checkOpNoThrow(
                    overlayOp,
                    Process.myUid(),
                    context.packageName
                )
                sb.append("Overlay (AppOps): ${modeToName(modeOverlay)} ($modeOverlay)\n")

                val modeProjectMedia = appOps.checkOpNoThrow(
                    "android:project_media",
                    Process.myUid(),
                    context.packageName
                )
                sb.append("Project Media (AppOps): ${modeToName(modeProjectMedia)} ($modeProjectMedia)\n")

            } catch (e: Exception) {
                sb.append("AppOps Check Failed: ${e.message}\n")
            }
        }

        sb.append("TCL/Restricted Bypass: ${TclBypass.isRestrictedManufacturer()}\n")
        sb.append("Is TCL Device: ${TclBypass.isTclDevice()}\n")

        return sb.toString()
    }

    // ── CPU ─────────────────────────────────────────────────────────────────
    private fun appendCpuInfo(sb: StringBuilder) {
        sb.append("=== CPU ===\n")
        val cores = Runtime.getRuntime().availableProcessors()
        sb.append("Cores: $cores\n")
        sb.append("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")

        // Load average (instant, no sampling): 1 / 5 / 15 min run-queue length.
        try {
            val loadavg = File("/proc/loadavg").readText().trim().split(Regex("\\s+"))
            if (loadavg.size >= 3) {
                sb.append("Load avg (1/5/15m): ${loadavg[0]} / ${loadavg[1]} / ${loadavg[2]}\n")
            }
        } catch (e: Exception) {
            sb.append("Load avg: n/a (${e.javaClass.simpleName})\n")
        }

        // Per-core current/max frequency (kHz → MHz). May be blocked by SELinux.
        val freqs = StringBuilder()
        var freqReadable = false
        for (i in 0 until cores) {
            val cur = readCpuKHz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val maxF = readCpuKHz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (cur != null || maxF != null) {
                freqReadable = true
                val curMhz = cur?.let { it / 1000 }?.toString() ?: "?"
                val maxMhz = maxF?.let { it / 1000 }?.toString() ?: "?"
                if (freqs.isNotEmpty()) freqs.append("  ")
                freqs.append("c$i:$curMhz/$maxMhz")
            }
        }
        if (freqReadable) {
            sb.append("Freqs cur/max MHz: $freqs\n")
        } else {
            sb.append("Freqs: n/a (restricted)\n")
        }
        sb.append("\n")
    }

    private fun readCpuKHz(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (e: Exception) {
        null
    }

    // ── Memory ──────────────────────────────────────────────────────────────
    private fun appendMemoryInfo(sb: StringBuilder, context: Context) {
        sb.append("=== MEMORY (RAM) ===\n")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("Total: ${formatBytes(mi.totalMem)}\n")
            sb.append("Available: ${formatBytes(mi.availMem)}\n")
            sb.append("Low-mem threshold: ${formatBytes(mi.threshold)}\n")
            sb.append("Low memory: ${mi.lowMemory}\n")
        } catch (e: Exception) {
            sb.append("System RAM: n/a (${e.javaClass.simpleName})\n")
        }
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        sb.append("App heap used/max: ${formatBytes(usedHeap)} / ${formatBytes(rt.maxMemory())}\n\n")
    }

    // ── GPU / video core ──────────────────────────────────────────────────────
    private fun appendGpuInfo(sb: StringBuilder) {
        sb.append("=== GPU ===\n")
        val gpu = queryGpu()
        if (gpu != null) {
            sb.append("Vendor: ${gpu.vendor}\n")
            sb.append("Renderer: ${gpu.renderer}\n")
            sb.append("GL Version: ${gpu.version}\n")
        } else {
            sb.append("GPU: n/a (could not create EGL context)\n")
        }
        sb.append("\n")
    }

    private data class GpuInfo(val vendor: String, val renderer: String, val version: String)

    /** Spins up a tiny off-screen EGL/GLES2 context to read the GL driver strings. */
    private fun queryGpu(): GpuInfo? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null

        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) return null
            val config = configs[0] ?: return null

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            return GpuInfo(
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "?",
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "?",
                GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
            )
        } catch (e: Exception) {
            return null
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    // ── Video codecs / hardware-encode capability ─────────────────────────────
    private fun appendHardwareCodecs(sb: StringBuilder) {
        sb.append("=== VIDEO CODECS ===\n")
        try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos

            val hwEncoders = ArrayList<String>()
            val swEncoders = ArrayList<String>()
            val hwDecoderTypes = sortedSetOf<String>()

            for (info in codecs) {
                val videoTypes =
                    info.supportedTypes.filter { it.startsWith("video/", ignoreCase = true) }
                if (videoTypes.isEmpty()) continue

                val hw = isHardwareCodec(info)
                if (info.isEncoder) {
                    val line = "${info.name}: ${videoTypes.joinToString(", ")}"
                    if (hw) hwEncoders.add(line) else swEncoders.add(line)
                } else if (hw) {
                    hwDecoderTypes.addAll(videoTypes)
                }
            }

            // Verdict — this is what decides whether smooth on-device (scrcpy) capture is possible.
            if (hwEncoders.isEmpty()) {
                sb.append("HW video ENCODER: NONE\n")
                sb.append("  -> on-device encode (scrcpy/screenrecord) is software only = heavy\n")
            } else {
                sb.append("HW video ENCODER: YES (${hwEncoders.size})\n")
                sb.append("  -> hardware scrcpy/screenrecord capture is possible\n")
            }

            if (hwEncoders.isNotEmpty()) {
                sb.append("HW encoders:\n")
                for (l in hwEncoders) sb.append("  [hw] $l\n")
            }
            if (swEncoders.isNotEmpty()) {
                sb.append("SW encoders:\n")
                for (l in swEncoders) sb.append("  [sw] $l\n")
            }
            sb.append(
                "HW decoders: ${
                    if (hwDecoderTypes.isEmpty()) "none" else hwDecoderTypes.joinToString(
                        ", "
                    )
                }\n"
            )
        } catch (e: Exception) {
            sb.append("Codec query failed: ${e.message}\n")
        }
        sb.append("\n")
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            // Pre-Q heuristic: Google/AOSP software codecs use these prefixes.
            val n = info.name.lowercase(Locale.ROOT)
            !n.startsWith("omx.google.") && !n.startsWith("c2.android.")
        }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    private fun modeToName(mode: Int): String {
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "ALLOWED"
            AppOpsManager.MODE_IGNORED -> "IGNORED"
            AppOpsManager.MODE_ERRORED -> "ERRORED"
            AppOpsManager.MODE_DEFAULT -> "DEFAULT"
            else -> "UNKNOWN ($mode)"
        }
    }
}
