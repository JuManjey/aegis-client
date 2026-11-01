package com.aegis.network.tor

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHttpTest {

    private val mailbox = "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH0123456789a"

    @Test
    fun capabilityIdsAreRedactedQueryKept() {
        val redacted = RelayHttp.redactPath("/v1/mailbox/$mailbox?after=7&wait=30")
        assertFalse(redacted.contains(mailbox))
        assertTrue(redacted.startsWith("/v1/mailbox/"))
        assertTrue(redacted.endsWith("?after=7&wait=30"))
        assertFalse(RelayHttp.redactPath("/v1/mailbox/$mailbox/ack").contains(mailbox))
        assertTrue(RelayHttp.redactPath("/v1/mailbox/$mailbox/ack").endsWith("/ack"))
        assertEquals("/v1/health", RelayHttp.redactPath("/v1/health"))
    }

    @Test
    fun headerValuesLoseControlCharacters() {
        assertEquals("1:abX-Evil: 1", RelayHttp.sanitizeHeaderValue("1:ab\r\nX-Evil: 1"))
        assertEquals("ok", RelayHttp.sanitizeHeaderValue(" ok\u0000 "))
    }

    @Test
    fun headerNamesKeepOnlyTokenCharacters() {
        assertEquals("X-Aegis-Pow", RelayHttp.sanitizeHeaderName("X-Aegis-Pow"))
        assertEquals("BadName", RelayHttp.sanitizeHeaderName("Bad Name:"))
        assertEquals(
            listOf("X-Aegis-Pow" to "v"),
            RelayHttp.sanitizedHeaders(mapOf("X-Aegis-Pow" to "v", "X-Empty" to "\r\n", ":" to "x")),
        )
    }

    @Test
    fun errorDescriptionHasNoHostOrAddress() {
        val error = ConnectException("failed to connect to relay.example.com/203.0.113.10 (port 443) after 30000ms")
        val described = RelayHttp.describeError(IOException("wrap", error), "relay.example.com")
        assertFalse(described.contains("relay.example.com"))
        assertFalse(described.contains("203.0.113.10"))
        assertTrue(described.contains("ConnectException"))
        val ipv6 = RelayHttp.describeError(IOException("to 2001:db8::1 refused"), "")
        assertFalse(ipv6.contains("2001:db8::1"))
    }

    @Test
    fun responseSizeIsCapped() {
        assertEquals(10, RelayHttp.readLimited(ByteArrayInputStream(ByteArray(10)), limit = 10).size)
        try {
            RelayHttp.readLimited(ByteArrayInputStream(ByteArray(11)), limit = 10)
            throw AssertionError("ожидалось исключение")
        } catch (_: IOException) {
        }
    }

    @Test
    fun onlyTls12AndNewerPassHandshakeGuard() {
        GuardedTrustManager.requireModernProtocol("TLSv1.3")
        GuardedTrustManager.requireModernProtocol("TLSv1.2")
        for (weak in listOf("TLSv1.1", "TLSv1", "SSLv3", "NONE", null)) {
            try {
                GuardedTrustManager.requireModernProtocol(weak)
                throw AssertionError("$weak должен отклоняться")
            } catch (_: CertificateException) {
            }
        }
    }
}
