package com.aegis.core.common.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpsRelayAddressTest {

    @Test
    fun parsesOriginWithAndWithoutScheme() {
        assertEquals("https://relay.example.com", HttpsRelayAddress.parse("https://relay.example.com")!!.stored)
        assertEquals("https://relay.example.com", HttpsRelayAddress.parse("relay.example.com")!!.stored)
        assertEquals("https://relay.example.com", HttpsRelayAddress.parse(" HTTPS://Relay.Example.COM/ ")!!.stored)
        assertEquals("https://relay.example.com", HttpsRelayAddress.parse("https://relay.example.com:443")!!.stored)
    }

    @Test
    fun keepsCustomPort() {
        val parsed = HttpsRelayAddress.parse("https://relay.example.org:8443")!!
        assertEquals("relay.example.org", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("https://relay.example.org:8443", parsed.stored)
    }

    @Test
    fun rejectsPlainHttpAndOtherSchemes() {
        assertNull(HttpsRelayAddress.parse("http://relay.example.com"))
        assertNull(HttpsRelayAddress.parse("HTTP://relay.example.com"))
        assertNull(HttpsRelayAddress.parse("ftp://relay.example.com"))
        assertNull(HttpsRelayAddress.parse("wss://relay.example.com"))
    }

    @Test
    fun rejectsUserinfoPathQueryAndFragment() {
        assertNull(HttpsRelayAddress.parse("https://user@relay.example.com"))
        assertNull(HttpsRelayAddress.parse("https://relay.example.com/v1/health"))
        assertNull(HttpsRelayAddress.parse("https://relay.example.com?x=1"))
        assertNull(HttpsRelayAddress.parse("https://relay.example.com#frag"))
    }

    @Test
    fun rejectsOnionAndMalformedHosts() {
        assertNull(HttpsRelayAddress.parse("https://q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion"))
        assertNull(HttpsRelayAddress.parse("https://-bad.example"))
        assertNull(HttpsRelayAddress.parse("https://bad_host.example"))
        assertNull(HttpsRelayAddress.parse("https://relay.example.com:0"))
        assertNull(HttpsRelayAddress.parse("https://relay.example.com:70000"))
        assertNull(HttpsRelayAddress.parse("https://"))
        assertNull(HttpsRelayAddress.parse(""))
    }

    @Test
    fun acceptsIpv4Literal() {
        assertEquals("https://203.0.113.10", HttpsRelayAddress.parse("203.0.113.10")!!.stored)
    }

    @Test
    fun normalizeKeepsUnrecognizedInput() {
        assertEquals("https://relay.example.com", HttpsRelayAddress.normalize("relay.example.com/"))
        assertEquals("http://x.example", HttpsRelayAddress.normalize(" http://x.example "))
        assertEquals("", HttpsRelayAddress.normalize("   "))
    }

    @Test
    fun pinsAreNormalizedAndValidated() {
        val raw = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
        assertEquals("sha256/$raw", HttpsRelayAddress.normalizePin(raw))
        assertEquals("sha256/$raw", HttpsRelayAddress.normalizePin(" sha256/$raw "))
        assertNull(HttpsRelayAddress.normalizePin("sha256/dG9vLXNob3J0"))
        assertNull(HttpsRelayAddress.normalizePin("not base64 !"))
        assertEquals(listOf("sha256/$raw"), HttpsRelayAddress.normalizePins(listOf(raw, "sha256/$raw", "junk")))
    }
}
