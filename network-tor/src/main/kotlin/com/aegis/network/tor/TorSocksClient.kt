package com.aegis.network.tor

import com.aegis.core.common.diag.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket

/**
 * Минимальный HTTP/1.1-клиент поверх локального SOCKS5-прокси встроенного Tor
 * (Arti, §7.2). Позволяет обменяться запрос/ответ с onion-сервисом релея v3
 * (§12.2): дроп конверта, long-poll приём, ack (см. [RelayClient]).
 *
 * Только адресация по доменному имени (ATYP=0x03) — `.onion`-адреса резолвит сам
 * Tor, приложение никогда не делает локальный DNS (иначе утечка, §7.2).
 * Аутентификация SOCKS не используется (Arti её не требует на localhost). TLS
 * поверх onion не нужен: шифрование даёт сам Tor, полезная нагрузка вдобавок E2EE.
 */
object TorSocksClient {

    /**
     * Выполняет HTTP-запрос к [host]:[port] через SOCKS5-туннель Tor и возвращает
     * ответ. Использует `Connection: close` (сервер закрывает поток после ответа —
     * читаем до EOF), с поддержкой `Transfer-Encoding: chunked`.
     *
     * @param socksPort порт локального SOCKS5-прокси Tor.
     * @param host целевой хост (обычно `<...>.onion`).
     * @param method HTTP-метод ("GET"/"POST"/"PUT"/"DELETE").
     * @param path путь с query (например `/v1/mailbox/<id>?after=0&wait=30`).
     * @param body тело запроса или `null` (для GET).
     * @param contentType заголовок Content-Type тела (если есть тело).
     * @param headers дополнительные заголовки (например решение proof-of-work),
     *   санитизируются [RelayHttp.sanitizedHeaders].
     * @param timeoutMs таймаут соединения и чтения (для long-poll задавать с запасом).
     * @param scope сессия транспорта: сокет регистрируется в ней, чтобы смена
     *   режима сети могла оборвать запрос.
     */
    fun httpRequest(
        socksPort: Int,
        host: String,
        port: Int,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = RelayHttp.DEFAULT_TIMEOUT_MS,
        scope: RelayConnectionScope? = null,
    ): RelayHttpResponse {
        val startedMs = System.currentTimeMillis()
        var registration: RelayConnectionScope.Handle? = null
        try {
            Socks5.connect(
                socksPort = socksPort,
                host = host,
                port = port,
                connectTimeoutMs = RelayHttp.CONNECT_TIMEOUT_MS,
                soTimeoutMs = timeoutMs,
                onSocketCreated = { socket: Socket ->
                    registration = scope?.register { runCatching { socket.close() } }
                },
            ).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()

                val header = StringBuilder()
                header.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                header.append("Host: ").append(host).append("\r\n")
                header.append("Connection: close\r\n")
                for ((name, value) in RelayHttp.sanitizedHeaders(headers)) {
                    header.append(name).append(": ").append(value).append("\r\n")
                }
                if (body != null) {
                    if (contentType != null) {
                        header.append("Content-Type: ").append(RelayHttp.sanitizeHeaderValue(contentType)).append("\r\n")
                    }
                    header.append("Content-Length: ").append(body.size).append("\r\n")
                }
                header.append("\r\n")
                out.write(header.toString().toByteArray(Charsets.US_ASCII))
                if (body != null) out.write(body)
                out.flush()

                val raw = RelayHttp.readLimited(input)
                val response = parseResponse(raw)
                DiagnosticLog.event(
                    RelayHttp.DIAG_TAG, "http",
                    "транспорт" to "tor",
                    "метод" to method,
                    "путь" to RelayHttp.redactPath(path),
                    "статус" to response.status,
                    "телоЗапроса" to (body?.size ?: 0),
                    "телоОтвета" to response.body.size,
                    "мс" to (System.currentTimeMillis() - startedMs),
                )
                return response
            }
        } catch (e: Exception) {
            DiagnosticLog.e(RelayHttp.DIAG_TAG, e) {
                "http ОШИБКА транспорт=tor метод=$method путь=${RelayHttp.redactPath(path)} " +
                    "socks=$socksPort мс=${System.currentTimeMillis() - startedMs}"
            }
            throw e
        } finally {
            registration?.close()
        }
    }

    // --- HTTP-парсинг --------------------------------------------------------

    private fun parseResponse(raw: ByteArray): RelayHttpResponse {
        val sep = indexOfCrlfCrlf(raw)
        if (sep < 0) throw IOException("HTTP: не найден разделитель заголовков")
        val headerText = String(raw, 0, sep, Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val statusLine = lines.firstOrNull() ?: throw IOException("HTTP: пустой ответ")
        // "HTTP/1.1 200 OK"
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("HTTP: не разобран статус ($statusLine)")
        val chunked = lines.drop(1).any {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                it.contains("chunked", ignoreCase = true)
        }
        val bodyStart = sep + 4
        val bodyRaw = raw.copyOfRange(bodyStart, raw.size)
        val body = if (chunked) dechunk(bodyRaw) else bodyRaw
        return RelayHttpResponse(status, body)
    }

    private fun indexOfCrlfCrlf(data: ByteArray): Int {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == '\r'.code.toByte() && data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() && data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var pos = 0
        while (pos < data.size) {
            var lineEnd = pos
            while (lineEnd + 1 < data.size &&
                !(data[lineEnd] == '\r'.code.toByte() && data[lineEnd + 1] == '\n'.code.toByte())
            ) {
                lineEnd++
            }
            val sizeHex = String(data, pos, lineEnd - pos, Charsets.US_ASCII).trim()
                .substringBefore(';')
            val size = sizeHex.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (size == 0) break
            if (pos + size > data.size) break
            out.write(data, pos, size)
            pos += size + 2 // пропускаем CRLF после чанка
        }
        return out.toByteArray()
    }
}

/**
 * Транспорт релея через встроенный Tor: HTTP/1.1 к onion-сервису поверх SOCKS5.
 * Режим «Tor ВКЛ».
 */
class TorRelayTransport(
    private val scope: RelayConnectionScope,
    private val socksPort: Int,
    private val host: String,
    private val port: Int,
) : RelayHttpTransport {
    override val kind: RelayTransportKind = RelayTransportKind.TOR

    override fun request(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): RelayHttpResponse = scope.enter {
        TorSocksClient.httpRequest(
            socksPort = socksPort,
            host = host,
            port = port,
            method = method,
            path = path,
            body = body,
            contentType = contentType,
            headers = headers,
            timeoutMs = timeoutMs,
            scope = scope,
        )
    }
}
