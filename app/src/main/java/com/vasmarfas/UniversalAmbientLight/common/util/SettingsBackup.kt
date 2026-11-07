package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.vasmarfas.UniversalAmbientLight.common.remote.PrefsCodec
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Настройки в файл и обратно. Состав тот же, что у зеркала на телефоне: код сопряжения и
 * ключи ADB в файл не попадают, токены и пароли ламп попадают открытым текстом.
 */
object SettingsBackup {

    const val MIME_TYPE = "application/json"

    private const val FORMAT = "universal-ambient-light-settings"
    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 1024 * 1024

    // Язык относится к интерфейсу этого устройства и применяется через LocaleHelper
    private const val KEY_LANGUAGE = "pref_key_language"
    private const val KEY_CAPTURE_METHOD = "pref_key_capture_method"

    fun fileName(): String {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        return "universal-ambient-light-$time.json"
    }

    fun encode(prefs: SharedPreferences): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("prefs", PrefsCodec.encodeAll(prefs))
        .toString(2)

    /** Значения из файла; null, если файл не наш или записан более новой версией формата. */
    fun decode(text: String): Map<String, Any?>? {
        val root = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        if (root.optString("format") != FORMAT || root.optInt("version") !in 1..VERSION) return null
        val entries = root.optJSONArray("prefs") ?: return null
        val values = LinkedHashMap<String, Any?>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            values[entry.optString("k")] = PrefsCodec.decodeValue(entry)
        }
        return values
    }

    /**
     * Настройки, которых нет в файле, стираются: в момент сохранения у них было умолчание.
     * Метод захвата из файла применяется, только если он есть в [captureMethods]: Accessibility
     * нет в сборке для Google Play, MTK и MStar привязаны к чипу. Иначе подсветка перестала бы
     * запускаться, поэтому остаётся текущий метод.
     *
     * @return false, если метод из файла пропущен
     */
    fun restore(
        prefs: SharedPreferences,
        values: Map<String, Any?>,
        captureMethods: Collection<String>,
    ): Boolean {
        val method = values[KEY_CAPTURE_METHOD]
        val methodFits = method == null || method in captureMethods
        val stale = prefs.all.keys.filter { isRestored(it) && it !in values }
        prefs.edit(commit = true) {
            stale.forEach { remove(it) }
            values.forEach { (key, value) ->
                if (isRestored(key) && (methodFits || key != KEY_CAPTURE_METHOD)) {
                    PrefsCodec.put(this, key, value)
                }
            }
        }
        return methodFits
    }

    /**
     * Файл в «Загрузках» без окна выбора: на Android TV вместо него стоит системная заглушка
     * (TCL, `com.android.tv.frameworkpackagestubs`), которая ничего не сохраняет.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createInDownloads(context: Context, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME_TYPE)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused $name")
    }

    fun write(context: Context, uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("No output stream for $uri")
        output.use { it.write(text.toByteArray()) }
    }

    /** На ТВ файл выбирают и в видеоплеере: фильм целиком в память читать нельзя. */
    fun read(context: Context, uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("No input stream for $uri")
        val buffer = ByteArray(MAX_FILE_BYTES + 1)
        var size = 0
        input.use {
            while (size < buffer.size) {
                val count = it.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
        }
        if (size > MAX_FILE_BYTES) throw IOException("File is too large for settings")
        return String(buffer, 0, size)
    }

    private fun isRestored(key: String) = PrefsCodec.isSynced(key) && key != KEY_LANGUAGE
}
