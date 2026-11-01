package com.aegis.network.tor

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.HttpsRelayAddress
import java.io.IOException
import java.net.CookieHandler
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Прямой транспорт релея по HTTPS — режим «Tor ВЫКЛ». Платформенный
 * [HttpsURLConnection], без сторонних HTTP-библиотек.
 *
 * Что гарантирует:
 *  - только `https://` — адрес проверяется при создании ([create]) и ещё раз
 *    перед каждым соединением; незашифрованного пути нет;
 *  - TLS 1.2+ и пины SPKI проверяются внутри рукопожатия ([RelayTls]), цепочка и
 *    hostname — системой;
 *  - без прокси (это прямое соединение, и проверка трафиком должна видеть ровно
 *    релей), без редиректов, кэша и cookies;
 *  - `Connection: close`: после ответа соединение не остаётся в пуле. Иначе
 *    простаивающий прямой сокет пережил бы включение Tor, и сервер видел бы IP
 *    открытым рядом с тем же трафиком через Tor;
 *  - соединение зарегистрировано в [scope]: смена режима обрывает его сразу;
 *  - в журнал — те же поля, что у Tor-транспорта, путь с редакцией id, без
 *    адресов (имя хоста и IP вырезаются и из текста ошибок).
 */
class HttpsRelayTransport internal constructor(
    private val scope: RelayConnectionScope,
    private val origin: HttpsRelayAddress.Parsed,
    private val socketFactory: SSLSocketFactory,
) : RelayHttpTransport {

    override val kind: RelayTransportKind = RelayTransportKind.HTTPS

    override fun request(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): RelayHttpResponse = scope.enter {
        val startedMs = System.currentTimeMillis()
        try {
            execute(method, path, body, contentType, headers, timeoutMs).also { response ->
                DiagnosticLog.event(
                    RelayHttp.DIAG_TAG, "http",
                    "транспорт" to "https",
                    "метод" to method,
                    "путь" to RelayHttp.redactPath(path),
                    "статус" to response.status,
                    "телоЗапроса" to (body?.size ?: 0),
                    "телоОтвета" to response.body.size,
                    "мс" to (System.currentTimeMillis() - startedMs),
                )
            }
        } catch (e: Exception) {
            DiagnosticLog.w(RelayHttp.DIAG_TAG) {
                "http ОШИБКА транспорт=https метод=$method путь=${RelayHttp.redactPath(path)} " +
                    "мс=${System.currentTimeMillis() - startedMs} ошибка=${RelayHttp.describeError(e, origin.host)}"
            }
            throw e as? IOException ?: IOException(e.javaClass.simpleName, e)
        }
    }

    private fun execute(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): RelayHttpResponse {
        if (!path.startsWith("/")) throw IOException("путь запроса должен начинаться с /")
        if (CookieHandler.getDefault() != null) {
            // Платформа сама подставила бы cookies в запрос к релею — метка для
            // сопоставления запросов. Приложение обработчик не ставит; если он
            // появился, не отправляем ничего.
            throw IOException("в процессе установлен CookieHandler — запрос к релею не отправлен")
        }
        val url = URL(origin.stored + path)
        if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("только https")
        val connection = url.openConnection(Proxy.NO_PROXY) as? HttpsURLConnection
            ?: throw IOException("платформа не выдала HTTPS-соединение")
        val registration = scope.register { connection.disconnect() }
        try {
            connection.sslSocketFactory = socketFactory
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = RelayHttp.CONNECT_TIMEOUT_MS
            connection.readTimeout = timeoutMs
            connection.requestMethod = method
            connection.setRequestProperty("Connection", "close")
            for ((name, value) in RelayHttp.sanitizedHeaders(headers)) {
                connection.setRequestProperty(name, value)
            }
            if (body != null) {
                connection.doOutput = true
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", RelayHttp.sanitizeHeaderValue(contentType))
                }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { RelayHttp.readLimited(it) } ?: ByteArray(0)
            return RelayHttpResponse(status, bytes)
        } finally {
            registration.close()
            connection.disconnect()
        }
    }

    companion object {
        /**
         * Транспорт к [address] (`https://host[:port]`) с пинами [pins]. Всё, что не
         * разбирается как https-origin (включая `http://`), — [IllegalArgumentException]
         * до открытия какого-либо сокета.
         */
        fun create(scope: RelayConnectionScope, address: String, pins: List<String>): HttpsRelayTransport {
            val origin = HttpsRelayAddress.parse(address)
                ?: throw IllegalArgumentException("адрес релея для режима без Tor должен быть https://host[:port]")
            return HttpsRelayTransport(scope, origin, RelayTls.socketFactory(origin.host, pins))
        }
    }
}
