package com.openfx.core.sync

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 接收端 QR Code 的內容：`openfx://pair?h=192.168.0.10&p=8989&k=123456&id=...&n=...`
 *
 * 手機掃描後直接連線並記住配對碼，不用再手動輸入 IP 與 PIN。
 * 配對碼本來就顯示在同一個畫面上，放進 QR Code 不會多洩漏什麼。
 */
data class PairingCode(
    val host: String,
    val port: Int,
    val pin: String,
    val deviceId: String? = null,
    val deviceName: String? = null
) {
    fun toUri(): String = buildString {
        append(PREFIX)
        append("h=").append(enc(host))
        append("&p=").append(port)
        append("&k=").append(pin)
        deviceId?.takeIf { it.isNotBlank() }?.let { append("&id=").append(enc(it)) }
        deviceName?.takeIf { it.isNotBlank() }?.let { append("&n=").append(enc(it)) }
    }

    companion object {
        const val SCHEME = "openfx"
        private const val PREFIX = "openfx://pair?"
        private val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")
        private val PIN = Regex("^\\d{6}$")

        /** 格式不對 (不是 OpenFX 的 QR Code、欄位缺漏或不合法) 回傳 null */
        fun parse(text: String): PairingCode? {
            val raw = text.trim()
            if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
            val params = raw.substring(PREFIX.length).split('&')
                .mapNotNull { part ->
                    val key = part.substringBefore('=', "")
                    if (key.isEmpty()) null else key to dec(part.substringAfter('='))
                }
                .toMap()
            val host = params["h"]?.takeIf { HOST.matches(it) } ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val pin = params["k"]?.takeIf { PIN.matches(it) } ?: return null
            return PairingCode(host, port, pin, params["id"], params["n"])
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault("")
    }
}
