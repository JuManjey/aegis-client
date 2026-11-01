package com.aegis.messenger.messaging

import com.aegis.core.common.security.HttpsRelayAddress
import com.aegis.core.common.security.OnionRelayAddress
import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.network.tor.RelayConnectionScope

/** Сессии транспорта, которыми управляет [NetworkModeController] ([RelayGateway] в приложении). */
interface RelaySessions {
    /** Закрыть текущую сессию (клиентов больше нет, соединения обрываются) и вернуть её соединения. */
    fun closeSession(): RelayConnectionScope?

    /** Открыть сессию для [mode]. */
    fun openSession(mode: TorMode)
}

/** Куда и каким транспортом идти к релею в текущем режиме сети. */
sealed interface RelayRoute {
    /** Tor ВКЛ: onion-релей через SOCKS встроенного Tor. */
    data class Tor(val host: String, val port: Int, val socksPort: Int) : RelayRoute

    /** Tor ВЫКЛ: тот же релей по HTTPS. */
    data class Https(val origin: String, val pins: List<String>) : RelayRoute

    /** Клиента нет — и почему (для журнала и UI). */
    data class Unavailable(val reason: RelayUnavailableReason) : RelayRoute
}

/** Почему сейчас нельзя сходить к релею. */
enum class RelayUnavailableReason {
    /** Идёт смена режима сети: старые соединения закрываются, новые ещё не открыты. */
    SWITCHING,

    /** Tor ВКЛ, а onion-адрес релея не задан. */
    ONION_NOT_SET,

    /** Tor ВКЛ, а onion-адрес не разбирается как v3. */
    ONION_INVALID,

    /** Tor ВКЛ, но Tor ещё не поднят (нет порта SOCKS). */
    TOR_NOT_READY,

    /** Tor ВЫКЛ, а HTTPS-адрес релея не задан: «сервер без Tor не настроен». */
    HTTPS_NOT_SET,

    /** Tor ВЫКЛ, а адрес не разбирается как `https://host[:port]`. */
    HTTPS_INVALID,
}

/**
 * Выбор маршрута строго по режиму, без перекрёстных откатов: в режиме Tor
 * HTTPS-адрес не используется никогда (даже если Tor не поднят и HTTPS задан),
 * в режиме без Tor — onion и SOCKS не используются никогда.
 */
object RelayRouteSelector {

    fun select(
        mode: TorMode,
        settings: TorNetworkSettings,
        socksPort: Int?,
        switching: Boolean,
    ): RelayRoute {
        if (switching) return RelayRoute.Unavailable(RelayUnavailableReason.SWITCHING)
        return when (mode) {
            TorMode.ALWAYS -> {
                val raw = settings.onionRelayAddress.trim()
                if (raw.isEmpty()) return RelayRoute.Unavailable(RelayUnavailableReason.ONION_NOT_SET)
                val onion = OnionRelayAddress.parse(raw)
                    ?: return RelayRoute.Unavailable(RelayUnavailableReason.ONION_INVALID)
                val socks = socksPort ?: return RelayRoute.Unavailable(RelayUnavailableReason.TOR_NOT_READY)
                RelayRoute.Tor(onion.host, onion.port, socks)
            }
            TorMode.OFF -> {
                val raw = settings.httpsRelayAddress.trim()
                if (raw.isEmpty()) return RelayRoute.Unavailable(RelayUnavailableReason.HTTPS_NOT_SET)
                val https = HttpsRelayAddress.parse(raw)
                    ?: return RelayRoute.Unavailable(RelayUnavailableReason.HTTPS_INVALID)
                RelayRoute.Https(https.stored, settings.effectiveHttpsPins())
            }
        }
    }
}
