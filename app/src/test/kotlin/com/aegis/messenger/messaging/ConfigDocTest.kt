package com.aegis.messenger.messaging

import com.aegis.core.common.security.TorNetworkSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigDocTest {

    private val onion = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"
    private val pin = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

    @Test
    fun legacyDocumentKeepsHttpsSettings() {
        val doc = ConfigDoc.parse("""{"v":2,"relays":["$onion"],"minAppVersion":1,"turnOnion":"x.onion","turnPort":3478}""")!!
        assertNull(doc.https)
        assertNull(doc.httpsPins)
        val current = TorNetworkSettings.DEFAULT.copy(
            httpsRelayAddress = "https://baked.example",
            httpsPins = listOf("sha256/$pin"),
            configVersion = 1,
        )
        val next = doc.applyTo(current, appVersionCode = 3)!!
        assertEquals(onion, next.onionRelayAddress)
        assertEquals("https://baked.example", next.httpsRelayAddress)
        assertEquals(listOf("sha256/$pin"), next.httpsPins)
        assertEquals(2, next.configVersion)
    }

    @Test
    fun newDocumentSetsHttpsAndPinsTogether() {
        val doc = ConfigDoc.parse(
            """{"v":3,"relays":["$onion"],"relaysHttps":["http://evil.example","https://Relay.Example.com/"],
               "httpsPins":["$pin","junk"],"minAppVersion":0}""",
        )!!
        assertEquals("https://relay.example.com", doc.https)
        assertEquals(listOf("sha256/$pin"), doc.httpsPins)
        val next = doc.applyTo(TorNetworkSettings.DEFAULT, appVersionCode = 3)!!
        assertEquals("https://relay.example.com", next.httpsRelayAddress)
        assertEquals(listOf("sha256/$pin"), next.httpsPins)
    }

    @Test
    fun httpsWithoutPinsClearsOldPins() {
        val doc = ConfigDoc.parse("""{"v":5,"relaysHttps":["https://new.example"]}""")!!
        val current = TorNetworkSettings.DEFAULT.copy(httpsRelayAddress = "https://old.example", httpsPins = listOf("sha256/$pin"))
        val next = doc.applyTo(current, appVersionCode = 3)!!
        assertEquals("https://new.example", next.httpsRelayAddress)
        assertEquals(emptyList<String>(), next.httpsPins)
    }

    @Test
    fun rollbackAndTooOldClientAreRejected() {
        val current = TorNetworkSettings.DEFAULT.copy(configVersion = 7)
        assertNull(ConfigDoc.parse("""{"v":7,"relaysHttps":["https://x.example"]}""")!!.applyTo(current, 3))
        assertNull(ConfigDoc.parse("""{"v":6,"relaysHttps":["https://x.example"]}""")!!.applyTo(current, 3))
        assertNull(ConfigDoc.parse("""{"v":8,"minAppVersion":9}""")!!.applyTo(current, 3))
    }

    @Test
    fun manualAddressesAreNotOverwritten() {
        val manual = TorNetworkSettings.DEFAULT.copy(
            onionRelayAddress = onion,
            httpsRelayAddress = "https://self-hosted.example",
            relayManual = true,
        )
        val next = ConfigDoc.parse("""{"v":2,"relays":["${"a".repeat(56)}.onion"],"relaysHttps":["https://official.example"]}""")!!
            .applyTo(manual, appVersionCode = 3)!!
        assertEquals(onion, next.onionRelayAddress)
        assertEquals("https://self-hosted.example", next.httpsRelayAddress)
        assertEquals(2, next.configVersion)
    }

    @Test
    fun garbageIsNotADocument() {
        assertNull(ConfigDoc.parse("[1,2]"))
        assertNull(ConfigDoc.parse("not json"))
    }
}
