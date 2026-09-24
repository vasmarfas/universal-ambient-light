package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import com.vasmarfas.UniversalAmbientLight.BuildConfig
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteProtocol
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AdbAutoPair
import com.vasmarfas.UniversalAmbientLight.common.util.AdbSetup
import com.vasmarfas.UniversalAmbientLight.common.util.DevOptionsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import org.json.JSONObject

/** Что показывает диалог ADB в шапке и какие пути ему доступны. */
class AdbDialogStatus(
    val developerOptions: Boolean,
    val adbEnabled: Boolean,
    val sdkInt: Int,
    val autoPairAvailable: Boolean,
)

/**
 * Где выполняются действия диалога ADB: на этом устройстве или на телевизоре, которым
 * телефон управляет. Все методы блокирующие — звать из фонового потока.
 */
interface AdbActions {
    val isRemote: Boolean

    fun status(): AdbDialogStatus

    fun openDeveloperOptions(): Boolean

    fun openAboutDevice(): Boolean

    fun openWirelessDebugging(): Boolean

    fun legacyConnect(): AdbSetup.Outcome

    fun testConnection(): AdbSetup.Outcome

    fun pair(port: Int?, code: String): AdbSetup.Outcome

    fun autoPair(): AdbSetup.Outcome

    fun grantPermissions(): AdbSetup.Outcome
}

class LocalAdbActions(context: Context, private val prefs: Preferences) : AdbActions {
    private val mContext = context

    override val isRemote = false

    private val port: Int
        get() = prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555

    override fun status(): AdbDialogStatus {
        val status = AdbSetup.status(mContext)
        return AdbDialogStatus(
            status.developerOptions,
            status.adbEnabled,
            status.sdkInt,
            BuildConfig.HAS_ACCESSIBILITY
        )
    }

    override fun openDeveloperOptions() = DevOptionsHelper.openDeveloperOptions(mContext)

    override fun openAboutDevice() = DevOptionsHelper.openAboutDeviceForBuildNumber(mContext)

    override fun openWirelessDebugging() = DevOptionsHelper.openWirelessDebugging(mContext)

    override fun legacyConnect() = AdbSetup.legacyConnect(mContext, port)

    override fun testConnection() = AdbSetup.testConnection(mContext, port)

    override fun pair(port: Int?, code: String) = AdbSetup.pair(mContext, port, code)

    override fun autoPair(): AdbSetup.Outcome = when (val result = AdbAutoPair.run(mContext)) {
        is AdbAutoPair.Result.Paired ->
            AdbSetup.Outcome(true, mContext.getString(R.string.adb_pair_success))

        is AdbAutoPair.Result.NeedsAccessibility ->
            AdbSetup.Outcome(false, mContext.getString(R.string.adb_autopair_need_accessibility))

        is AdbAutoPair.Result.Timeout ->
            AdbSetup.Outcome(false, mContext.getString(R.string.adb_autopair_timeout))

        is AdbAutoPair.Result.Failed ->
            AdbSetup.Outcome(false, mContext.getString(R.string.adb_pair_failed, result.message))
    }

    override fun grantPermissions() = AdbSetup.grantPermissions(mContext)

    /** Автосопряжению нужна включённая служба доступности на этом устройстве. */
    fun accessibilityReady() = AccessibilityCaptureService.isAvailable()
}

/** Те же действия, но выполняет их телевизор; тексты результатов приходят с ТВ. */
class RemoteAdbActions : AdbActions {
    override val isRemote = true

    override fun status(): AdbDialogStatus {
        val reply = RemoteSession.adb(RemoteProtocol.ADB_STATUS)
        return AdbDialogStatus(
            reply.optBoolean("dev"),
            reply.optBoolean("adb"),
            reply.optInt("sdk"),
            reply.optBoolean("autopair")
        )
    }

    override fun openDeveloperOptions() = opened(RemoteProtocol.ADB_OPEN_DEV)

    override fun openAboutDevice() = opened(RemoteProtocol.ADB_OPEN_ABOUT)

    override fun openWirelessDebugging() = opened(RemoteProtocol.ADB_OPEN_WIRELESS)

    override fun legacyConnect() = outcome(RemoteProtocol.ADB_LEGACY)

    override fun testConnection() = outcome(RemoteProtocol.ADB_TEST)

    override fun pair(port: Int?, code: String) = outcome(
        RemoteProtocol.ADB_PAIR,
        JSONObject().put("code", code).put("port", port ?: -1)
    )

    override fun autoPair() = outcome(RemoteProtocol.ADB_AUTOPAIR)

    override fun grantPermissions() = outcome(RemoteProtocol.ADB_GRANT)

    private fun opened(action: String) = outcome(action).success

    private fun outcome(action: String, extra: JSONObject = JSONObject()): AdbSetup.Outcome = try {
        val reply = RemoteSession.adb(action, extra)
        AdbSetup.Outcome(reply.optBoolean("success"), reply.optString("msg"))
    } catch (e: Exception) {
        AdbSetup.Outcome(false, e.message ?: e.javaClass.simpleName)
    }
}
