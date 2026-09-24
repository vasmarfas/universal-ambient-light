package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Личность телевизора для управления с телефона: постоянный id, код сопряжения и порт.
 *
 * Отдельный файл, а не общие настройки: общие настройки телефон читает и пишет целиком,
 * и код сопряжения туда попадать не должен.
 */
class RemoteHostConfig(context: Context) {

    private val mContext = context.applicationContext
    private val mStore = mContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val tvId: UUID
        @Synchronized get() {
            mStore.getString(KEY_ID, null)?.let { stored ->
                runCatching { return UUID.fromString(stored) }
            }
            val id = UUID.randomUUID()
            mStore.edit(commit = true) { putString(KEY_ID, id.toString()) }
            return id
        }

    /** Код сопряжения в каноническом виде, без дефисов. */
    val code: String
        @Synchronized get() {
            mStore.getString(KEY_CODE, null)?.let { PairingCode.normalize(it) }?.let { return it }
            return resetCode()
        }

    val secret: ByteArray
        get() = checkNotNull(PairingCode.decode(code)) { "stored pairing code is always canonical" }

    /** Новый код: все сопряжённые телефоны теряют доступ до повторного сканирования. */
    @Synchronized
    fun resetCode(): String {
        val code = PairingCode.generate()
        mStore.edit(commit = true) { putString(KEY_CODE, code) }
        return code
    }

    /** Порт прошлого запуска: телефон первым делом стучится по сохранённому адресу. */
    var port: Int
        get() = mStore.getInt(KEY_PORT, RemoteProtocol.DEFAULT_PORT)
        set(value) = mStore.edit { putInt(KEY_PORT, value) }

    /** Имя из системных настроек («Телевизор в гостиной»), иначе модель. */
    val displayName: String
        get() {
            val name = runCatching {
                Settings.Global.getString(mContext.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
            return name?.takeIf { it.isNotBlank() } ?: Build.MODEL
        }

    fun payload(port: Int): PairingPayload =
        PairingPayload(tvId.toString(), displayName, localAddresses(), port, code)

    companion object {
        private const val FILE = "remote_host"
        private const val KEY_ID = "tv_id"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_PORT = "port"

        /**
         * IPv4-адреса локальной сети. У приставки их бывает два (Wi-Fi и Ethernet); проводной
         * интерфейс первым — он стабильнее, и телефон пробует адреса по порядку.
         */
        fun localAddresses(): List<String> {
            val result = ArrayList<Pair<String, String>>()
            try {
                for (nif in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
                    if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                    for (address in nif.inetAddresses) {
                        if (address is Inet4Address && address.isSiteLocalAddress) {
                            result += nif.name to (address.hostAddress ?: continue)
                        }
                    }
                }
            } catch (_: Exception) {
                // Перечисление интерфейсов падает на части прошивок без сети — адресов нет.
            }
            return result
                .sortedBy { (name, _) -> if (name.startsWith("eth")) 0 else 1 }
                .map { it.second }
                .distinct()
        }
    }
}
