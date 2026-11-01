package com.aegis.network.tor

import com.aegis.core.common.security.HttpsRelayAddress
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

class HttpsRelayTransportTest {

    private val server = TestTlsRelayServer()
    private val scope = RelayConnectionScope(RelayTransportKind.HTTPS)

    @After
    fun tearDown() {
        scope.close()
        server.close()
    }

    private fun transport(pins: List<String> = emptyList()): HttpsRelayTransport =
        HttpsRelayTransport(
            scope,
            HttpsRelayAddress.Parsed("localhost", server.port),
            RelayTls.socketFactory("localhost", pins, systemTrust = server.trustManager()),
        )

    @Test
    fun plainHttpIsRejectedBeforeAnySocket() {
        for (address in listOf("http://localhost:${server.port}", "ftp://localhost", "https://localhost/v1/health")) {
            try {
                HttpsRelayTransport.create(scope, address, emptyList())
                fail("адрес $address должен быть отклонён")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun healthOverTls() {
        val client = RelayClient(transport())
        assertEquals(RelayHealth.Ok, client.health())
        assertEquals(RelayTransportKind.HTTPS, client.transportKind)
    }

    @Test
    fun redirectIsNotFollowed() {
        val response = transport().request("GET", "/v1/redirect")
        assertEquals(302, response.status)
        assertTrue(server.requestsTo("/v1/after-redirect").isEmpty())
    }

    @Test
    fun cookiesAreNeverSent() {
        transport().request("GET", "/v1/cookie")
        transport().request("GET", "/v1/echo")
        val echo = server.requestsTo("/v1/echo").single()
        assertNull(echo.headers["cookie"])
    }

    @Test
    fun installedCookieHandlerBlocksRequest() {
        CookieHandler.setDefault(CookieManager())
        try {
            transport().request("GET", "/v1/echo")
            fail("запрос не должен уходить при установленном CookieHandler")
        } catch (_: IOException) {
        } finally {
            CookieHandler.setDefault(null)
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun headersAreSanitizedOnTheWire() {
        transport().request(
            "GET",
            "/v1/echo",
            headers = mapOf(
                "X-Aegis-Pow" to "1:ab\r\nX-Evil: 1",
                "Bad Name:" to "v",
                "X-Empty" to "\r\n",
            ),
        )
        val headers = server.requestsTo("/v1/echo").single().headers
        assertEquals(listOf("1:abX-Evil: 1"), headers["x-aegis-pow"])
        assertNull(headers["x-evil"])
        assertEquals(listOf("v"), headers["badname"])
        assertNull(headers["x-empty"])
        assertEquals(listOf("close"), headers["connection"])
    }

    @Test
    fun errorBodyIsReturned() {
        val response = transport().request("POST", "/v1/error", body = "x".toByteArray(), contentType = "application/json")
        assertEquals(400, response.status)
        assertEquals("плохой запрос", response.errorMessage())
    }

    @Test
    fun closingScopeAbortsHangingLongPollAndBlocksNewRequests() {
        val transport = transport()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                transport.request("GET", "/v1/slow?after=0&wait=30", timeoutMs = 50_000)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        assertTrue(server.slowArrived.await(10, TimeUnit.SECONDS))
        val startedAt = System.currentTimeMillis()
        scope.close()
        assertTrue("запрос не завершился после закрытия сессии", scope.awaitIdle(5_000))
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IOException)
        assertTrue(System.currentTimeMillis() - startedAt < 5_000)

        val before = server.requests.size
        try {
            transport.request("GET", "/v1/echo")
            fail("закрытая сессия не должна пускать новые запросы")
        } catch (_: ClosedTransportException) {
        }
        assertEquals(before, server.requests.size)
    }

    @Test
    fun matchingPinIsAccepted() {
        val pin = HttpsRelayAddress.PIN_PREFIX + RelayTls.spkiSha256(server.certificate)
        assertEquals(RelayHealth.Ok, RelayClient(transport(listOf(pin))).health())
    }

    @Test
    fun pinMismatchAbortsBeforeRequestIsSent() {
        val wrongPin = HttpsRelayAddress.PIN_PREFIX + Base64.getEncoder().encodeToString(ByteArray(32))
        try {
            transport(listOf(wrongPin)).request("GET", "/v1/health")
            fail("несовпадение пина должно обрывать соединение")
        } catch (_: IOException) {
        }
        assertTrue("запрос дошёл до сервера при несовпавшем пине", server.requests.isEmpty())
    }

    @Test
    fun hostnameMismatchIsRejectedBeforeRequest() {
        // Доверенный сертификат выписан на localhost/127.0.0.1, а адрес — другой.
        val other = runCatching { TestTlsRelayServer(bindAddress = "127.0.0.2") }.getOrNull()
        Assume.assumeNotNull(other)
        other!!.use { server2 ->
            val transport = HttpsRelayTransport(
                scope,
                HttpsRelayAddress.Parsed("127.0.0.2", server2.port),
                RelayTls.socketFactory("127.0.0.2", emptyList(), systemTrust = server2.trustManager()),
            )
            try {
                transport.request("GET", "/v1/health")
                fail("сертификат на другое имя должен отклоняться")
            } catch (_: IOException) {
            }
            assertTrue("запрос дошёл до сервера с чужим именем", server2.requests.isEmpty())
        }
    }

    @Test
    fun protocolsBelowTls12AreRefused() {
        for (protocol in listOf("TLSv1.2", "TLSv1.3")) GuardedTrustManager.requireModernProtocol(protocol)
        for (protocol in listOf("TLSv1.1", "TLSv1", "SSLv3", "", null)) {
            try {
                GuardedTrustManager.requireModernProtocol(protocol)
                fail("протокол ${protocol ?: "null"} должен отклоняться")
            } catch (_: CertificateException) {
            }
        }
    }

    @Test
    fun untrustedCertificateIsRejected() {
        val transport = HttpsRelayTransport(
            scope,
            HttpsRelayAddress.Parsed("localhost", server.port),
            RelayTls.socketFactory("localhost", emptyList()),
        )
        try {
            transport.request("GET", "/v1/health")
            fail("самоподписанный сертификат вне системного доверия должен отклоняться")
        } catch (_: IOException) {
        }
        assertTrue(server.requests.isEmpty())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun allowConnectionHeader() {
            // HttpURLConnection JDK по умолчанию молча выбрасывает заголовок
            // Connection (Android его отправляет). Без этого тест не увидел бы его.
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        }
    }
}
