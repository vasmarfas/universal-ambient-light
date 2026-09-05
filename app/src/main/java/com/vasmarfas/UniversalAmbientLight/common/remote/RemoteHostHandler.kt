package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.vasmarfas.UniversalAmbientLight.BuildConfig
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.CaptureLauncher
import com.vasmarfas.UniversalAmbientLight.common.MtkThalCaptureEncoder
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.input.TvApps
import com.vasmarfas.UniversalAmbientLight.common.util.AdbAutoPair
import com.vasmarfas.UniversalAmbientLight.common.util.AdbSetup
import com.vasmarfas.UniversalAmbientLight.common.util.DebugInfoHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DelayProfiles
import com.vasmarfas.UniversalAmbientLight.common.util.DevOptionsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DeviceProfile
import com.vasmarfas.UniversalAmbientLight.common.util.ForegroundApp
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Команды телефона на стороне ТВ. Работает в потоках сервера, поэтому всё, что трогает
 * активити, выполняется через главный поток.
 */
internal class RemoteHostHandler(
    context: Context,
    private val config: RemoteHostConfig,
    private val status: () -> JSONObject,
    private val clientsListener: (List<RemoteServer.Client>) -> Unit,
) : RemoteServer.Handler {

    private val mContext = context.applicationContext
    private val mMainHandler = Handler(Looper.getMainLooper())

    // Кто начал замер задержки: если этот телефон пропадёт, замер надо снять, а не ждать
    // таймаута сервиса с лентой без сглаживания
    @Volatile
    private var mCalibrationClient: RemoteServer.Client? = null

    override fun onClientsChanged(clients: List<RemoteServer.Client>) = clientsListener(clients)

    override fun onClientGone(client: RemoteServer.Client) {
        if (mCalibrationClient === client) {
            mCalibrationClient = null
            sendToService(ScreenGrabberService.ACTION_CALIBRATION) {
                putExtra(ScreenGrabberService.EXTRA_CALIBRATION, ScreenGrabberService.CALIBRATION_END)
            }
        }
    }

    override fun handle(client: RemoteServer.Client, op: String, request: JSONObject): JSONObject =
        when (op) {
            RemoteProtocol.OP_HELLO -> hello(request)
            RemoteProtocol.OP_PING -> JSONObject()
            RemoteProtocol.OP_SNAPSHOT -> JSONObject()
                .put("prefs", PrefsCodec.encodeAll(Preferences.defaultSharedPreferences(mContext)))
                .put("status", status())
                .put("caps", capabilities())

            RemoteProtocol.OP_SET_PREFS -> setPrefs(client, request)
            RemoteProtocol.OP_CAPTURE -> capture(request)
            RemoteProtocol.OP_CLEAR -> {
                sendToService(ScreenGrabberService.ACTION_CLEAR)
                JSONObject()
            }

            RemoteProtocol.OP_DETECT_FRAME -> {
                if (!ScreenGrabberService.sInstanceRunning) {
                    throw RemoteCommandException(
                        RemoteProtocol.ERR_FAILED,
                        mContext.getString(R.string.remote_error_camera_not_running)
                    )
                }
                sendToService(ScreenGrabberService.ACTION_DETECT_FRAME)
                JSONObject()
            }

            RemoteProtocol.OP_APPS -> JSONObject().put("apps", TvApps.list(mContext))
            RemoteProtocol.OP_CALIBRATION -> calibration(client, request)

            RemoteProtocol.OP_ADB -> adb(request)
            RemoteProtocol.OP_DEBUG_INFO -> JSONObject().put("text", DebugInfoHelper.getDebugInfo(mContext))
            else -> throw RemoteCommandException(RemoteProtocol.ERR_BAD_REQUEST, "Unknown op: $op")
        }

    private fun hello(request: JSONObject): JSONObject {
        val proto = request.optInt("proto", -1)
        if (proto != RemoteProtocol.VERSION) {
            throw RemoteCommandException(
                RemoteProtocol.ERR_BAD_REQUEST,
                mContext.getString(R.string.remote_error_version)
            )
        }
        return JSONObject()
            .put("id", config.tvId.toString())
            .put("name", config.displayName)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun capabilities(): JSONObject {
        val methods = mContext.resources.getStringArray(R.array.pref_list_capture_method_values)
            .filter { it != "mtk_thal_capture" || MtkThalCaptureEncoder.isAvailable() }
        return JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("app", BuildConfig.VERSION_NAME)
            .put("tv", DeviceProfile.isTv(mContext))
            .put("accessibility", BuildConfig.HAS_ACCESSIBILITY)
            .put("accessibilityOn", AccessibilityCaptureService.getInstance() != null)
            .put("projectMedia", PermissionHelper.hasProjectMediaPermission(mContext))
            .put("overlay", PermissionHelper.canDrawOverlays(mContext))
            .put("methods", JSONArray(methods))
            .put("features", JSONArray(FEATURES))
            .put("usageAccess", ForegroundApp.hasAccess(mContext))
    }

    private fun calibration(client: RemoteServer.Client, request: JSONObject): JSONObject {
        val action = request.optString("a")
        val prefs = Preferences(mContext)
        val reply = JSONObject()
        when (action) {
            ScreenGrabberService.CALIBRATION_BEGIN -> {
                if (!ScreenGrabberService.sInstanceRunning) {
                    throw RemoteCommandException(
                        RemoteProtocol.ERR_FAILED,
                        mContext.getString(R.string.calibration_error_not_running)
                    )
                }
                if (prefs.getString(R.string.pref_key_capture_source, "screen") != "screen") {
                    throw RemoteCommandException(
                        RemoteProtocol.ERR_FAILED,
                        mContext.getString(R.string.calibration_error_not_screen)
                    )
                }
                mCalibrationClient = client
                // Своё приложение на экране ТВ - не то, для чего подбирают задержку
                val app = ForegroundApp(mContext).current()?.takeIf { it != mContext.packageName }
                reply.put("app", app)
                    .put("label", app?.let { TvApps.label(mContext, it) })
                    .put("delay", DelayProfiles.effectiveDelay(prefs, app))
                    .put("usageAccess", ForegroundApp.hasAccess(mContext))
            }

            ScreenGrabberService.CALIBRATION_END -> mCalibrationClient = null
            ScreenGrabberService.CALIBRATION_DARK -> {}
            else -> throw RemoteCommandException(RemoteProtocol.ERR_BAD_REQUEST, "Unknown action")
        }
        sendToService(ScreenGrabberService.ACTION_CALIBRATION) {
            putExtra(ScreenGrabberService.EXTRA_CALIBRATION, action)
            putExtra(ScreenGrabberService.EXTRA_DARK_MS, request.optLong("ms"))
        }
        return reply
    }

    private fun setPrefs(client: RemoteServer.Client, request: JSONObject): JSONObject {
        val entries = request.optJSONArray("set") ?: JSONArray()
        val keys = ArrayList<String>()
        Preferences.defaultSharedPreferences(mContext).edit(commit = true) {
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val key = entry.optString("k")
                if (!PrefsCodec.isSynced(key)) continue
                val value = PrefsCodec.decodeValue(entry)
                PrefsCodec.put(this, key, value)
                client.sentValues[key] = value ?: REMOVED
                keys += key
            }
        }
        // Стороны и разрыв снизу меняют число светодиодов на ходу; на ТВ экран раскладки
        // делает то же самое — гасит ленту разом, иначе хвост старой раскладки остаётся гореть
        if (keys.any { it in clearOnChangeKeys }) sendToService(ScreenGrabberService.ACTION_CLEAR)
        return JSONObject()
    }

    private val clearOnChangeKeys by lazy {
        listOf(
            R.string.pref_key_led_side_top,
            R.string.pref_key_led_side_right,
            R.string.pref_key_led_side_bottom,
            R.string.pref_key_led_side_left,
            R.string.pref_key_bottom_gap
        ).map { mContext.getString(it) }.toSet()
    }

    private fun capture(request: JSONObject): JSONObject {
        return when (request.optString("action")) {
            RemoteProtocol.CAPTURE_START -> {
                val result = onMain { CaptureLauncher.start(mContext) }
                if (result.outcome == CaptureLauncher.Outcome.FAILED) {
                    throw RemoteCommandException(RemoteProtocol.ERR_FAILED, result.message.orEmpty())
                }
                JSONObject()
                    .put("confirm", result.outcome == CaptureLauncher.Outcome.NEEDS_CONFIRMATION)
                    .put("msg", result.message)
            }

            RemoteProtocol.CAPTURE_STOP -> {
                onMain { CaptureLauncher.stop(mContext) }
                JSONObject()
            }

            else -> throw RemoteCommandException(RemoteProtocol.ERR_BAD_REQUEST, "Unknown action")
        }
    }

    private fun adb(request: JSONObject): JSONObject {
        val port = Preferences(mContext).getString(R.string.pref_key_adb_port, "5555")
            ?.toIntOrNull() ?: 5555
        val outcome: AdbSetup.Outcome = when (request.optString("action")) {
            RemoteProtocol.ADB_STATUS -> {
                val status = AdbSetup.status(mContext)
                return JSONObject()
                    .put("dev", status.developerOptions)
                    .put("adb", status.adbEnabled)
                    .put("sdk", status.sdkInt)
                    .put("autopair", BuildConfig.HAS_ACCESSIBILITY)
                    .put("accessibilityOn", AccessibilityCaptureService.isAvailable())
            }

            RemoteProtocol.ADB_TEST -> AdbSetup.testConnection(mContext, port)
            RemoteProtocol.ADB_LEGACY -> AdbSetup.legacyConnect(mContext, port)
            RemoteProtocol.ADB_PAIR -> {
                val code = request.optString("code").filter { it.isDigit() }
                if (code.length != 6) {
                    throw RemoteCommandException(RemoteProtocol.ERR_BAD_REQUEST, "Bad pairing code")
                }
                AdbSetup.pair(mContext, request.optInt("port", -1).takeIf { it > 0 }, code)
            }

            RemoteProtocol.ADB_AUTOPAIR -> autoPair()
            RemoteProtocol.ADB_GRANT -> AdbSetup.grantPermissions(mContext)
            RemoteProtocol.ADB_OPEN_DEV -> opened {
                DevOptionsHelper.openDeveloperOptions(mContext)
            }

            RemoteProtocol.ADB_OPEN_WIRELESS -> opened {
                DevOptionsHelper.openWirelessDebugging(mContext)
            }

            RemoteProtocol.ADB_OPEN_ABOUT -> opened {
                DevOptionsHelper.openAboutDeviceForBuildNumber(mContext)
            }

            else -> throw RemoteCommandException(RemoteProtocol.ERR_BAD_REQUEST, "Unknown action")
        }
        return JSONObject().put("success", outcome.success).put("msg", outcome.message)
    }

    private fun autoPair(): AdbSetup.Outcome {
        onMain { DevOptionsHelper.openWirelessDebugging(mContext) }
        return when (val result = AdbAutoPair.run(mContext)) {
            is AdbAutoPair.Result.Paired ->
                AdbSetup.Outcome(true, mContext.getString(R.string.adb_pair_success))

            is AdbAutoPair.Result.NeedsAccessibility ->
                AdbSetup.Outcome(false, mContext.getString(R.string.remote_adb_need_accessibility))

            is AdbAutoPair.Result.Timeout ->
                AdbSetup.Outcome(false, mContext.getString(R.string.adb_autopair_timeout))

            is AdbAutoPair.Result.Failed ->
                AdbSetup.Outcome(false, mContext.getString(R.string.adb_pair_failed, result.message))
        }
    }

    /** Открывает экран настроек на ТВ; активити стартуют только с главного потока. */
    private fun opened(open: () -> Boolean): AdbSetup.Outcome {
        val success = onMain(open)
        val message = if (success) R.string.remote_opened_on_tv else R.string.remote_error_activity_blocked
        return AdbSetup.Outcome(success, mContext.getString(message))
    }

    private fun sendToService(action: String, extras: Intent.() -> Unit = {}) {
        // Без работающего сервиса команду некому выполнить, а startService поднял бы пустой
        // foreground-сервис с уведомлением
        if (!ScreenGrabberService.sInstanceRunning) return
        val intent = Intent(mContext, ScreenGrabberService::class.java)
        intent.action = action
        intent.extras()
        mMainHandler.post {
            try {
                mContext.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Service command $action rejected: ${e.message}")
            }
        }
    }

    /** Выполняет [block] на главном потоке и ждёт результат. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        mMainHandler.post {
            result = runCatching(block)
            done.countDown()
        }
        if (!done.await(MAIN_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw RemoteCommandException(RemoteProtocol.ERR_FAILED, "Main thread is busy")
        }
        return checkNotNull(result) { "result is set before the latch opens" }.getOrThrow()
    }

    companion object {
        private const val TAG = "RemoteHostHandler"
        private const val MAIN_TIMEOUT_S = 10L

        /** Отметка удалённого ключа в [RemoteServer.Client.sentValues]: null туда не положить. */
        val REMOVED = Any()

        private val FEATURES = listOf(
            RemoteProtocol.FEATURE_EFFECTS,
            RemoteProtocol.FEATURE_CALIBRATION,
        )
    }
}
