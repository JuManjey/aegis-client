package com.aegis.core.common.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TorNetworkSettingsTest {

    @Test
    fun onionChangeDoesNotAffectTorRestartToken() {
        val base = TorNetworkSettings.DEFAULT
        val withOnion = base.copy(
            onionRelayAddress = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion",
            relayManual = true,
            configVersion = 3,
        )
        assertEquals(base.torClientRestartToken(), withOnion.torClientRestartToken())
    }

    @Test
    fun bakedRelayFillsBlankAutomaticAddress() {
        val baked = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"
        val filled = TorNetworkSettings.DEFAULT.withBakedRelayIfNeeded(baked)
        assertEquals(baked, filled.onionRelayAddress)
        assertEquals(false, filled.relayManual)
    }

    @Test
    fun bakedRelayDoesNotOverrideManualOrExisting() {
        val baked = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"
        val existing = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"
        val manual = TorNetworkSettings.DEFAULT.copy(onionRelayAddress = "", relayManual = true)
        assertEquals("", manual.withBakedRelayIfNeeded(baked).onionRelayAddress)
        val already = TorNetworkSettings.DEFAULT.copy(onionRelayAddress = existing)
        assertEquals(existing, already.withBakedRelayIfNeeded(baked).onionRelayAddress)
        assertEquals("", TorNetworkSettings.DEFAULT.withBakedRelayIfNeeded("").onionRelayAddress)
    }

    @Test
    fun bridgeChangeAffectsTorRestartToken() {
        val base = TorNetworkSettings.DEFAULT
        val withBridges = base.copy(bridgesEnabled = true, builtInBridge = BuiltInBridge.OBFS4)
        assertNotEquals(base.torClientRestartToken(), withBridges.torClientRestartToken())
    }

    @Test
    fun httpsChangeDoesNotAffectTorRestartToken() {
        val base = TorNetworkSettings.DEFAULT
        val withHttps = base.copy(httpsRelayAddress = "https://relay.example.com", httpsPins = listOf("sha256/x"))
        assertEquals(base.torClientRestartToken(), withHttps.torClientRestartToken())
    }

    @Test
    fun bakedHttpsAndPinsFillAutomaticAddress() {
        val pin = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
        val filled = TorNetworkSettings.DEFAULT.withBakedRelayIfNeeded(
            bakedOnionRaw = ONION,
            bakedHttpsRaw = "relay.example.com",
            bakedPins = listOf(pin, "junk"),
        )
        assertEquals(ONION, filled.onionRelayAddress)
        assertEquals("https://relay.example.com", filled.httpsRelayAddress)
        assertEquals(listOf("sha256/$pin"), filled.httpsPins)
        assertEquals(listOf("sha256/$pin"), filled.effectiveHttpsPins())
    }

    @Test
    fun bakedHttpsIsNotPairedWithManualRelay() {
        val manual = TorNetworkSettings.DEFAULT.copy(onionRelayAddress = ONION, relayManual = true)
        val after = manual.withBakedRelayIfNeeded(ONION, "https://relay.example.com", listOf("sha256/x"))
        assertEquals("", after.httpsRelayAddress)
        assertEquals(emptyList<String>(), after.effectiveHttpsPins())
    }

    @Test
    fun bakedPlainHttpIsIgnored() {
        val after = TorNetworkSettings.DEFAULT.withBakedRelayIfNeeded("", "http://relay.example.com")
        assertEquals("", after.httpsRelayAddress)
    }

    @Test
    fun relayConfiguredDependsOnMode() {
        val onlyOnion = TorNetworkSettings.DEFAULT.copy(onionRelayAddress = ONION)
        assertEquals(true, onlyOnion.relayConfiguredFor(TorMode.ALWAYS))
        assertEquals(false, onlyOnion.relayConfiguredFor(TorMode.OFF))
        val onlyHttps = TorNetworkSettings.DEFAULT.copy(httpsRelayAddress = "https://relay.example.com")
        assertEquals(false, onlyHttps.relayConfiguredFor(TorMode.ALWAYS))
        assertEquals(true, onlyHttps.relayConfiguredFor(TorMode.OFF))
    }

    private companion object {
        const val ONION = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"
    }
}
