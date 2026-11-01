package com.aegis.core.common.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionRelayAddressTest {

    private val onion = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"

    @Test
    fun parseBareV3() {
        val parsed = OnionRelayAddress.parse(onion)!!
        assertEquals(onion, parsed.host)
        assertEquals(443, parsed.port)
        assertEquals(onion, parsed.stored)
    }

    @Test
    fun parseHttpsUrlWithoutPort() {
        val parsed = OnionRelayAddress.parse("https://$onion")!!
        assertEquals(onion, parsed.host)
        assertEquals(443, parsed.port)
    }

    @Test
    fun parseHttpsUrlWithPathAndPort() {
        val parsed = OnionRelayAddress.parse("https://$onion:443/v1/health")!!
        assertEquals(onion, parsed.host)
        assertEquals(443, parsed.port)
        assertEquals(onion, parsed.stored)
    }

    @Test
    fun parseCustomPortKeptInStored() {
        val parsed = OnionRelayAddress.parse("http://$onion:8080/")!!
        assertEquals(8080, parsed.port)
        assertEquals("$onion:8080", parsed.stored)
    }

    @Test
    fun parseRejectsHttpsAsHost() {
        // Старый substringBeforeLast(':') превращал URL в хост "https".
        assertNull(OnionRelayAddress.parse("https"))
        assertTrue(OnionRelayAddress.isOnionV3(onion))
    }

    @Test
    fun parseRejectsWrongLength() {
        assertNull(OnionRelayAddress.parse("aaaaaaaaaaaaaaaa.onion"))
        assertNull(OnionRelayAddress.parse("not-an-onion.example"))
        assertNull(OnionRelayAddress.parse(""))
    }

    @Test
    fun normalizeStripsScheme() {
        assertEquals(onion, OnionRelayAddress.normalize("  HTTPS://$onion/  "))
        assertEquals("", OnionRelayAddress.normalize("   "))
    }
}
