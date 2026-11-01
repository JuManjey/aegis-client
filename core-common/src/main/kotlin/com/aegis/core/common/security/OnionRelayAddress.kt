package com.aegis.core.common.security

/**
 * Разбор адреса onion-релея v3 (§12.2).
 *
 * Пользователь часто вставляет URL целиком (`https://<56>.onion/...`). Старый
 * разбор по последнему `:` превращал это в хост `https` — Arti пытался
 * резолвить имя `https` и отвечал `remote hostname lookup failure`.
 */
object OnionRelayAddress {

    /** Порт onion-релея по умолчанию (совпадает с HiddenServicePort 443 → relay:8080). */
    const val DEFAULT_PORT: Int = 443

    /** Длина публичного ключа onion v3 в base32 (без суффикса `.onion`). */
    const val V3_LABEL_LEN: Int = 56

    data class Parsed(val host: String, val port: Int) {
        /** Каноническая форма для хранения: `host.onion` или `host.onion:port`. */
        val stored: String
            get() = if (port == DEFAULT_PORT) host else "$host:$port"
    }

    /**
     * Нормализует ввод к канонической строке. Пустое → пустое; нераспознанное
     * оставляем как есть (чтобы не молча выкидывать то, что пользователь ввёл).
     */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return parse(trimmed)?.stored ?: trimmed
    }

    /**
     * Разбирает `https://…onion[:port]/path`, `host.onion` и `host.onion:443`.
     * `null`, если это не onion v3.
     */
    fun parse(raw: String): Parsed? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        val scheme = SCHEME.find(s)
        if (scheme != null) s = s.substring(scheme.range.last + 1)

        val at = s.indexOf('@')
        if (at >= 0) s = s.substring(at + 1)

        s = s.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        if (s.isEmpty()) return null

        val host: String
        val port: Int
        val colon = s.lastIndexOf(':')
        if (colon > 0) {
            val portPart = s.substring(colon + 1)
            val parsedPort = portPart.toIntOrNull()
            if (parsedPort != null) {
                if (parsedPort !in 1..65535) return null
                host = s.substring(0, colon)
                port = parsedPort
            } else {
                return null
            }
        } else {
            host = s
            port = DEFAULT_PORT
        }

        val normalizedHost = host.trim().trimEnd('.').lowercase()
        if (!isOnionV3(normalizedHost)) return null
        return Parsed(normalizedHost, port)
    }

    fun isOnionV3(host: String): Boolean {
        if (!host.endsWith(".onion")) return false
        val label = host.removeSuffix(".onion")
        if (label.length != V3_LABEL_LEN) return false
        return label.all { it in 'a'..'z' || it in '2'..'7' }
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
