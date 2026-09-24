package com.vasmarfas.UniversalAmbientLight.common.remote

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Содержимое QR-кода сопряжения:
 * `uamblight://pair?v=1&id=<id ТВ>&n=<имя>&h=<адрес[,адрес]>&p=<порт>&k=<код>`.
 *
 * Ссылка, а не голый JSON: системная камера телефона тоже распознает её и откроет
 * приложение. Адресов может быть несколько — у приставки бывают и Wi-Fi, и Ethernet.
 * android.net.Uri здесь не используется намеренно: разбор нужен и в JVM-тестах.
 */
data class PairingPayload(
    val tvId: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val code: String,
) {
    fun toUri(): String {
        val query = listOf(
            "v" to RemoteProtocol.VERSION.toString(),
            "id" to tvId,
            "n" to name,
            "h" to hosts.joinToString(","),
            "p" to port.toString(),
            "k" to code
        ).joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }
        return "${RemoteProtocol.URI_SCHEME}://${RemoteProtocol.URI_HOST}?$query"
    }

    companion object {
        fun parse(text: String): PairingPayload? {
            val prefix = "${RemoteProtocol.URI_SCHEME}://${RemoteProtocol.URI_HOST}?"
            val trimmed = text.trim()
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null

            val params = HashMap<String, String>()
            for (part in trimmed.substring(prefix.length).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val value = try {
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (_: IllegalArgumentException) {
                    return null
                }
                params[part.substring(0, eq)] = value
            }

            params["v"]?.toIntOrNull() ?: return null
            val tvId = params["id"]?.takeIf { it.isNotBlank() } ?: return null
            val hosts = params["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = params["k"]?.let { PairingCode.normalize(it) } ?: return null
            if (hosts.isEmpty()) return null
            return PairingPayload(tvId, params["n"].orEmpty(), hosts, port, code)
        }
    }
}
