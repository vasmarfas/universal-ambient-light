package com.vasmarfas.UniversalAmbientLight.common.remote

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Телевизор, с которым сопряжён телефон. [hosts] — последние известные адреса, по порядку. */
data class PairedTv(
    val id: String,
    val name: String,
    val code: String,
    val hosts: List<String>,
    val port: Int,
)

/** Сопряжённые телевизоры на телефоне. Код хранится здесь же — это ключ к управлению ТВ. */
class PairedTvStore(context: Context) {

    private val mStore = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PairedTv> {
        val array = try {
            JSONArray(mStore.getString(KEY_TVS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val hosts = item.optJSONArray("hosts") ?: JSONArray()
            PairedTv(
                id = item.optString("id").ifEmpty { return@mapNotNull null },
                name = item.optString("name"),
                code = PairingCode.normalize(item.optString("code")) ?: return@mapNotNull null,
                hosts = (0 until hosts.length()).map { hosts.optString(it) }.filter { it.isNotEmpty() },
                port = item.optInt("port", RemoteProtocol.DEFAULT_PORT)
            )
        }
    }

    fun get(id: String): PairedTv? = all().firstOrNull { it.id == id }

    /** Добавляет или обновляет ТВ; последний сохранённый — первый в списке. */
    @Synchronized
    fun save(tv: PairedTv) {
        write(listOf(tv) + all().filter { it.id != tv.id })
    }

    @Synchronized
    fun remove(id: String) {
        write(all().filter { it.id != id })
        if (activeId == id) activeId = null
    }

    /** ТВ, которым телефон управлял, когда приложение закрыли: к нему переподключаемся. */
    var activeId: String?
        get() = mStore.getString(KEY_ACTIVE, null)
        set(value) = mStore.edit { if (value == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, value) }

    private fun write(tvs: List<PairedTv>) {
        val array = JSONArray()
        for (tv in tvs) {
            array.put(
                JSONObject()
                    .put("id", tv.id)
                    .put("name", tv.name)
                    .put("code", tv.code)
                    .put("hosts", JSONArray(tv.hosts))
                    .put("port", tv.port)
            )
        }
        mStore.edit(commit = true) { putString(KEY_TVS, array.toString()) }
    }

    companion object {
        private const val FILE = "remote_tvs"
        private const val KEY_TVS = "tvs"
        private const val KEY_ACTIVE = "active"
    }
}
