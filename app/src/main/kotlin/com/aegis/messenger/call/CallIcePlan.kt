package com.aegis.messenger.call

import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.security.TorMode
import com.aegis.network.tor.TurnInfo

/**
 * Маршрут медиа звонка, выведенный из режима сети. Чистая логика без WebRTC —
 * её проверяют JVM-тесты, а движок только исполняет готовый план.
 *
 *  - Tor ВКЛ: ICE `RELAY`, единственный сервер — TCP-TURN на локальном
 *    форвардере в onion coturn, тайминги ICE под задержки Tor. Ни STUN, ни
 *    host-кандидатов, ни одного сервера вне loopback: всё это пошло бы мимо Tor.
 *    Без такого TURN звонок не начинается.
 *  - Tor ВЫКЛ: ICE `ALL`, STUN и TURN своего сервера напрямую (TURN — резерв на
 *    случай, когда прямого пути нет), обычные тайминги WebRTC.
 */
data class CallIcePlan(
    val mediaOverTor: Boolean,
    /** ICE `RELAY` (только ретранслятор) вместо `ALL`. */
    val relayOnly: Boolean,
    /** Тайминги ICE под RTT Tor — только для TURN через Tor. */
    val torIceTimings: Boolean,
    val servers: List<IceTurnServer>,
) {
    /** Есть ли ретранслятор (а не только STUN). */
    val hasRelay: Boolean get() = servers.any { !isStun(it.uri) }

    /** Режим Tor без onion-TURN: звонок не начинается, чтобы медиа не ушло мимо Tor. */
    class RelayRequiredException : Exception("медиа через Tor требует onion-TURN")

    companion object {
        /**
         * План для [mode] по выданным ICE-серверам [servers] (аренда TURN).
         * @throws RelayRequiredException Tor ВКЛ, а годного onion-TURN нет.
         */
        fun forMode(mode: TorMode, servers: List<IceTurnServer>): CallIcePlan = when (mode) {
            TorMode.ALWAYS -> {
                if (servers.isEmpty() || !servers.all(::isTorForwarderTurn)) throw RelayRequiredException()
                CallIcePlan(mediaOverTor = true, relayOnly = true, torIceTimings = true, servers = servers)
            }
            TorMode.OFF -> CallIcePlan(mediaOverTor = false, relayOnly = false, torIceTimings = false, servers = servers)
        }

        /** STUN-ли это URI: он не ретранслирует медиа и при RELAY не нужен. */
        fun isStun(uri: String): Boolean = uri.startsWith("stun:", ignoreCase = true)

        /**
         * Единственный допустимый сервер в режиме Tor: `turn:` по TCP на loopback,
         * то есть на наш форвардер в Tor. Всё остальное означало бы прямое
         * соединение с реального адреса.
         */
        fun isTorForwarderTurn(server: IceTurnServer): Boolean {
            val uri = server.uri
            if (!uri.startsWith("turn:", ignoreCase = true)) return false
            if (!uri.contains("transport=tcp", ignoreCase = true)) return false
            return CallTrace.iceUriHost(uri).startsWith("127.")
        }

        /**
         * Клирнет ICE-серверы своего релея для режима без Tor из ответа `/v1/turn`.
         * Берутся только `stun:`/`turn:`/`turns:` на домене релея [relayHost] или его
         * поддоменах (общий домен второго уровня): публичные STUN третьих сторон
         * получили бы IP пользователя, а недоверенный сервер не должен заставить
         * телефон стучаться в loopback, локальную сеть или onion-имя через
         * системный DNS. У старого релея `uris` нет — тогда остаётся устаревшее
         * поле `stun`, если оно на том же домене.
         */
        fun clearnetServers(turn: TurnInfo, relayHost: String): List<IceTurnServer> {
            val out = LinkedHashMap<String, IceTurnServer>()
            fun allowed(uri: String): Boolean {
                val host = CallTrace.iceUriHost(uri).trim().trimEnd('.').lowercase()
                return isPublicHost(host) && sameSite(host, relayHost.trim().trimEnd('.').lowercase())
            }
            for (raw in turn.uris) {
                val uri = raw.trim()
                val scheme = uri.substringBefore(':', "").lowercase()
                if (scheme !in CLEARNET_SCHEMES) continue
                if (!allowed(uri)) continue
                out[uri] = if (scheme == "stun") {
                    IceTurnServer(uri, username = "", credential = "")
                } else {
                    IceTurnServer(uri, turn.username, turn.credential)
                }
            }
            if (out.values.none { isStun(it.uri) } && turn.stun.isNotEmpty()) {
                val legacy = "stun:${turn.stun}"
                if (allowed(legacy)) out[legacy] = IceTurnServer(legacy, "", "")
            }
            // STUN первым: srflx-кандидаты собираются параллельно с аллокацией TURN.
            return out.values.sortedBy { if (isStun(it.uri)) 0 else 1 }
        }

        /** Тот же хост, его поддомен или общий домен второго уровня; IP — только точное совпадение. */
        private fun sameSite(host: String, relayHost: String): Boolean {
            if (relayHost.isEmpty()) return false
            if (host == relayHost) return true
            if (isIpv4(host) || isIpv4(relayHost)) return false
            if (host.endsWith(".$relayHost")) return true
            val labels = relayHost.split('.')
            if (labels.size < 2) return false
            val base = labels.takeLast(2).joinToString(".")
            return host == base || host.endsWith(".$base")
        }

        private fun isIpv4(host: String): Boolean =
            host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }

        private fun isPublicHost(host: String): Boolean {
            val h = host.trim().trimEnd('.').lowercase()
            if (h.isEmpty() || h == "localhost" || h.endsWith(".localhost") || h.endsWith(".onion")) return false
            if (h.contains(':')) return false
            val octets = h.split('.').map { it.toIntOrNull() }
            if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
                val (a, b) = octets.map { it!! }
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) ||
                    (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) ||
                    (a == 192 && b == 168))
            }
            return h.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }
        }

        private val CLEARNET_SCHEMES = setOf("stun", "turn", "turns")
    }
}
