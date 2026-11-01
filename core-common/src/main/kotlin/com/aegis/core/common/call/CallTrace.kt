package com.aegis.core.common.call

/**
 * Безопасные для журнала сводки SDP и TURN URI: длины, типы, признаки —
 * без адресов, отпечатков, ufrag/pwd и секретов.
 */
object CallTrace {

    fun sdpFields(sdp: String): Array<Pair<String, Any?>> {
        var mAudio = 0
        var mVideo = 0
        var mApp = 0
        var mOther = 0
        var bundle = 0
        var fingerprint = 0
        var setup = ""
        var host = 0
        var srflx = 0
        var relay = 0
        var prflx = 0
        var otherCand = 0
        for (raw in sdp.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("m=audio") -> mAudio++
                line.startsWith("m=video") -> mVideo++
                line.startsWith("m=application") -> mApp++
                line.startsWith("m=") -> mOther++
                line.startsWith("a=group:BUNDLE") -> bundle++
                line.startsWith("a=fingerprint:") -> fingerprint++
                line.startsWith("a=setup:") && setup.isEmpty() -> setup = line.substringAfter(':')
                line.startsWith("a=candidate:") -> when (candidateType(line)) {
                    "host" -> host++
                    "srflx" -> srflx++
                    "relay" -> relay++
                    "prflx" -> prflx++
                    else -> otherCand++
                }
            }
        }
        return arrayOf(
            "sdpБайт" to sdp.length,
            "mAudio" to mAudio,
            "mVideo" to mVideo,
            "mApp" to mApp,
            "mOther" to mOther,
            "bundle" to bundle,
            "fingerprint" to fingerprint,
            "setup" to setup.ifEmpty { "нет" },
            "candHost" to host,
            "candSrflx" to srflx,
            "candRelay" to relay,
            "candPrflx" to prflx,
            "candOther" to otherCand,
        )
    }

    fun iceUriFields(uri: String): Array<Pair<String, Any?>> {
        val scheme = uri.substringBefore(':').lowercase()
        val rest = uri.substringAfter(':', missingDelimiterValue = "")
        val hostPort = rest.substringBefore('?')
        val query = rest.substringAfter('?', missingDelimiterValue = "")
        val host = hostPort.substringBeforeLast(':')
            .removePrefix("[")
            .removeSuffix("]")
        val port = hostPort.substringAfterLast(':', missingDelimiterValue = "")
        val transport = query.split('&')
            .firstOrNull { it.startsWith("transport=") }
            ?.substringAfter('=')
            ?: "нет"
        return arrayOf(
            "схема" to scheme.ifEmpty { "нет" },
            "hostKind" to hostKind(host),
            "портОк" to (port.toIntOrNull()?.let { it in 1..65535 } == true),
            "транспорт" to transport,
        )
    }

    fun hostKind(host: String): String = when {
        host.isEmpty() -> "empty"
        host == "127.0.0.1" || host == "::1" || host == "localhost" -> "loopback"
        host.startsWith("127.") -> "loopbackAlt"
        IPV4.matches(host) -> "ipv4"
        host.contains(':') -> "ipv6"
        else -> "name"
    }

    fun iceUriHost(uri: String): String {
        val rest = uri.substringAfter(':', missingDelimiterValue = "")
        val hostPort = rest.substringBefore('?')
        return hostPort.substringBeforeLast(':')
            .removePrefix("[")
            .removeSuffix("]")
    }

    fun candidateType(sdp: String): String {
        val idx = sdp.indexOf("typ ")
        if (idx < 0) return "unknown"
        val rest = sdp.substring(idx + 4)
        val end = rest.indexOf(' ').let { if (it < 0) rest.length else it }
        return rest.substring(0, end).ifEmpty { "unknown" }
    }

    /**
     * Прячет IP в native-логах WebRTC, оставляя имена файлов, C++ `Class::Method`
     * и коды ошибок. Старый шаблон IPv6 с `{0,4}` съедал `::` в
     * `AudioProcessing::Config` → `AudioProcessingipv6Config` и ломал разбор.
     */
    fun redactNet(message: String): String {
        val withoutV4 = IPV4.replace(message, "x.x.x.x")
        return IPV6_CANDIDATE.replace(withoutV4) { match ->
            if (looksLikeIpv6(match.value)) "ipv6" else match.value
        }
    }

    /**
     * Несёт ли строка native-лога байты ключей. webrtc-sdk пишет на уровне INFO
     * исходный и производный ключ кадрового слоя (`raw_key [..] derived_key [..]`
     * в `DerivePBKDF2KeyFromRawKey`), а [redactNet] их не трогает: это списки
     * чисел, а не адреса. Такие строки нельзя ни писать в журнал, ни класть в
     * «хлебные крошки» — только отбрасывать целиком.
     */
    fun carriesKeyMaterial(message: String): Boolean =
        KEY_MARKERS.any { message.contains(it, ignoreCase = true) } || BYTE_LIST.containsMatchIn(message)

    internal fun looksLikeIpv6(value: String): Boolean {
        if (value == "::" || value.isEmpty()) return false
        if (value.equals("::1", ignoreCase = true)) return true
        if (value.any { it !in IPV6_CHARS }) return false
        val hexParts = value.split(':').filter { it.isNotEmpty() }
        if (hexParts.isEmpty() || hexParts.any { it.length > 4 }) return false
        return value.contains("::") || value.count { it == ':' } >= 3
    }

    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    // Не трогаем C++ `Class::Method`: кандидат только на границе слова.
    private val IPV6_CANDIDATE = Regex("""(?<![A-Za-z0-9])[0-9a-fA-F]*:[0-9a-fA-F:]+(?![A-Za-z0-9])""")
    private const val IPV6_CHARS = "0123456789abcdefABCDEF:"
    private val KEY_MARKERS = listOf("raw_key", "derived_key")
    // 16+ байт подряд в виде `[n,n,…` — так webrtc-sdk печатает ключи и соли.
    private val BYTE_LIST = Regex("""\[\s*(?:\d{1,3}\s*,\s*){15,}\d{1,3}""")
}
