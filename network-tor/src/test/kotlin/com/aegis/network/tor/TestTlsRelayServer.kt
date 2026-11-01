package com.aegis.network.tor

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Локальный TLS-сервер для тестов [HttpsRelayTransport]: голый HTTP/1.1 поверх
 * `SSLServerSocket` (com.sun.net.httpserver недоступен в classpath Android-тестов).
 * Сертификат самоподписанный (`relay-test.p12`, CN/SAN = localhost) и служит
 * корнем доверия только внутри теста — в продакшн-коде доверие всегда системное.
 */
class TestTlsRelayServer(bindAddress: String = "127.0.0.1") : AutoCloseable {

    /** Записанный запрос; имена заголовков приведены к нижнему регистру. */
    data class Recorded(val method: String, val path: String, val headers: Map<String, List<String>>)

    val requests: MutableList<Recorded> = CopyOnWriteArrayList()

    /** Сервер получил запрос `/v1/slow` и держит его. */
    val slowArrived = CountDownLatch(1)

    /** Отпустить висящий `/v1/slow`. */
    val slowRelease = CountDownLatch(1)

    private val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        TestTlsRelayServer::class.java.getResourceAsStream("/relay-test.p12")!!.use { load(it, PASSWORD) }
    }

    val certificate: X509Certificate = keyStore.getCertificate("relay") as X509Certificate

    private val serverSocket: SSLServerSocket = run {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, PASSWORD)
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName(bindAddress)) as SSLServerSocket
    }

    private val clients: MutableList<Socket> = CopyOnWriteArrayList()

    private val acceptor = Thread {
        while (!serverSocket.isClosed) {
            val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
            clients += client
            Thread { runCatching { serve(client) }; runCatching { client.close() } }.apply { isDaemon = true }.start()
        }
    }.apply {
        isDaemon = true
        start()
    }

    val port: Int get() = serverSocket.localPort

    /** Менеджер доверия, для которого тестовый сертификат — корень. */
    fun trustManager(): X509TrustManager {
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("relay", certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun requestsTo(path: String): List<Recorded> = requests.filter { it.path.substringBefore('?') == path }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(' ').let { it[0] to it[1] }
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() }
                .add(line.substring(colon + 1).trim())
        }
        headers["content-length"]?.firstOrNull()?.toIntOrNull()?.let { length -> readExactly(input, length) }
        requests += Recorded(method, path, headers)

        val out = client.getOutputStream()
        fun respond(status: Int, body: String, extra: List<String> = emptyList()) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val text = buildString {
                append("HTTP/1.1 ").append(status).append(" X\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
        when (path.substringBefore('?')) {
            "/v1/health" -> respond(200, """{"status":"ok","service":"aegis-relay"}""")
            "/v1/redirect" -> respond(302, "", listOf("Location: https://localhost:$port/v1/after-redirect"))
            "/v1/cookie" -> respond(200, "{}", listOf("Set-Cookie: track=1; Path=/"))
            "/v1/slow" -> {
                slowArrived.countDown()
                slowRelease.await(30, TimeUnit.SECONDS)
                respond(200, "{}")
            }
            "/v1/error" -> respond(400, """{"error":"плохой запрос"}""")
            else -> respond(200, "{}")
        }
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return buffer.toString(Charsets.US_ASCII.name()).removeSuffix("\r\n\r\n")
    }

    private fun readExactly(input: InputStream, length: Int) {
        var left = length
        val chunk = ByteArray(4096)
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(chunk.size, left))
            if (n < 0) return
            left -= n
        }
    }

    override fun close() {
        slowRelease.countDown()
        runCatching { serverSocket.close() }
        clients.forEach { runCatching { it.close() } }
        acceptor.join(2_000)
    }

    private companion object {
        val PASSWORD = "relaytest".toCharArray()
    }
}
