package com.aegis.messenger.messaging

import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Выбор транспорта строго по режиму. Главное свойство — отсутствие перекрёстных
 * откатов: Tor не поднят — HTTPS не используется; Tor выключен — onion и SOCKS
 * не используются, даже если они есть.
 */
class RelayRouteSelectorTest {

    private val onion = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"
    private val both = TorNetworkSettings.DEFAULT.copy(
        onionRelayAddress = onion,
        httpsRelayAddress = "https://relay.example.com",
        httpsPins = listOf("sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="),
    )

    @Test
    fun torModeUsesOnionThroughSocks() {
        assertEquals(
            RelayRoute.Tor(onion, 443, 9050),
            RelayRouteSelector.select(TorMode.ALWAYS, both, socksPort = 9050, switching = false),
        )
    }

    @Test
    fun torModeWithoutTorHasNoClientEvenIfHttpsIsSet() {
        assertEquals(
            RelayRoute.Unavailable(RelayUnavailableReason.TOR_NOT_READY),
            RelayRouteSelector.select(TorMode.ALWAYS, both, socksPort = null, switching = false),
        )
    }

    @Test
    fun torModeWithoutOnionNeverFallsBackToHttps() {
        val onlyHttps = both.copy(onionRelayAddress = "")
        assertEquals(
            RelayRoute.Unavailable(RelayUnavailableReason.ONION_NOT_SET),
            RelayRouteSelector.select(TorMode.ALWAYS, onlyHttps, socksPort = 9050, switching = false),
        )
        assertEquals(
            RelayRoute.Unavailable(RelayUnavailableReason.ONION_INVALID),
            RelayRouteSelector.select(TorMode.ALWAYS, onlyHttps.copy(onionRelayAddress = "bad.onion"), 9050, false),
        )
    }

    @Test
    fun directModeUsesHttpsWithPinsAndIgnoresSocks() {
        assertEquals(
            RelayRoute.Https("https://relay.example.com", both.httpsPins),
            RelayRouteSelector.select(TorMode.OFF, both, socksPort = 9050, switching = false),
        )
    }

    @Test
    fun directModeWithoutHttpsNeverFallsBackToTor() {
        val onlyOnion = both.copy(httpsRelayAddress = "", httpsPins = emptyList())
        assertEquals(
            RelayRoute.Unavailable(RelayUnavailableReason.HTTPS_NOT_SET),
            RelayRouteSelector.select(TorMode.OFF, onlyOnion, socksPort = 9050, switching = false),
        )
        assertEquals(
            RelayRoute.Unavailable(RelayUnavailableReason.HTTPS_INVALID),
            RelayRouteSelector.select(TorMode.OFF, onlyOnion.copy(httpsRelayAddress = "http://relay.example.com"), 9050, false),
        )
    }

    @Test
    fun manualHttpsAddressIsUsedWithoutPins() {
        val manual = both.copy(relayManual = true)
        assertEquals(
            RelayRoute.Https("https://relay.example.com", emptyList()),
            RelayRouteSelector.select(TorMode.OFF, manual, socksPort = null, switching = false),
        )
    }

    @Test
    fun switchingBlocksBothModes() {
        for (mode in TorMode.entries) {
            assertEquals(
                RelayRoute.Unavailable(RelayUnavailableReason.SWITCHING),
                RelayRouteSelector.select(mode, both, socksPort = 9050, switching = true),
            )
        }
    }
}
