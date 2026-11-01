package com.aegis.core.common.security

import java.util.Base64

/**
 * Разбор HTTPS-адреса релея для режима «Tor ВЫКЛ» (§12.2).
 *
 * Принимается только origin: `https://host[:port]` (можно без схемы — тогда это
 * всё равно https). Отклоняются:
 *  - `http://` и любые другие схемы — незашифрованного транспорта нет ни в каком режиме;
 *  - userinfo (`user@host`) — им подменяют видимый адрес;
 *  - путь, query и fragment — маршруты релея начинаются с `/v1/`, а молча
 *    отбрасывать введённый путь значило бы ходить не туда, куда просили;
 *  - `.onion` — в режиме без Tor системный резолвер получил бы onion-имя в
 *    открытом DNS-запросе.
 */
object HttpsRelayAddress {

    const val DEFAULT_PORT: Int = 443

    data class Parsed(val host: String, val port: Int) {
        /** Каноническая форма для хранения: `https://host` или `https://host:port`. */
        val stored: String
            get() = if (port == DEFAULT_PORT) "$SCHEME$host" else "$SCHEME$host:$port"
    }

    /**
     * Нормализует ввод к канонической строке. Пустое → пустое; нераспознанное
     * оставляем как есть, чтобы не терять то, что ввёл пользователь (клиент по
     * такому адресу не пойдёт — [parse] вернёт `null`).
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return parse(trimmed)?.stored ?: trimmed
    }

    fun parse(raw: String): Parsed? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        val scheme = SCHEME_RE.find(s)
        if (scheme != null) {
            if (!scheme.value.equals(SCHEME, ignoreCase = true)) return null
            s = s.substring(scheme.range.last + 1)
        }
        if ('@' in s || '?' in s || '#' in s) return null
        val slash = s.indexOf('/')
        if (slash >= 0) {
            if (s.substring(slash) != "/") return null
            s = s.substring(0, slash)
        }
        if (s.isEmpty()) return null

        val host: String
        val port: Int
        val colon = s.lastIndexOf(':')
        if (colon >= 0) {
            port = s.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            host = s.substring(0, colon)
        } else {
            host = s
            port = DEFAULT_PORT
        }
        val normalizedHost = host.trimEnd('.').lowercase()
        if (!isValidHost(normalizedHost)) return null
        return Parsed(normalizedHost, port)
    }

    /**
     * Пин SPKI в каноническом виде `sha256/<base64 32 байт>` или `null`.
     * Принимается и голый base64 — префикс подставится.
     */
    fun normalizePin(raw: String): String? {
        val value = raw.trim().removePrefix(PIN_PREFIX).trim()
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return null
        if (bytes.size != SHA256_BYTES) return null
        return PIN_PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    /** Нормализует список пинов, отбрасывая нераспознанные и повторы. */
    fun normalizePins(raw: Collection<String>): List<String> =
        raw.mapNotNull { normalizePin(it) }.distinct()

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.endsWith(".onion")) return false
        val labels = host.split('.')
        return labels.all { label ->
            label.length in 1..63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        }
    }

    const val PIN_PREFIX: String = "sha256/"
    private const val SHA256_BYTES = 32
    private const val SCHEME = "https://"
    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
