package com.aegis.network.tor

import java.io.IOException

/**
 * Каким транспортом запрос дошёл до релея. [label] пишется в след сообщения
 * (`MessageCryptoInfo.transport`) и показывается в «Подробнее о сообщении».
 */
enum class RelayTransportKind(val label: String) {
    /** Встроенный Tor: SOCKS5 → onion-релей. */
    TOR("Tor (SOCKS5 → onion-релей)"),

    /** Режим «Tor ВЫКЛ»: TLS напрямую к тому же релею. */
    HTTPS("HTTPS (TLS → релей, без Tor)"),
}

/** Ответ релея: код статуса и тело целиком. */
class RelayHttpResponse(val status: Int, val body: ByteArray) {
    val isSuccess: Boolean get() = status in 200..299

    fun bodyString(): String = String(body, Charsets.UTF_8)

    /**
     * Текст ошибки из тела `{"error": "…"}`, которым релей отвечает на 4xx.
     * Пустая строка, если тело не в этом формате. Нужен, чтобы UI показал
     * человеку конкретную причину отказа, а не «ошибка сети».
     */
    fun errorMessage(): String = RelayJson.parseObject(bodyString())?.optString("error").orEmpty()
}

/**
 * HTTP-транспорт до релея: через Tor ([TorRelayTransport]) или напрямую по HTTPS
 * ([HttpsRelayTransport]). [RelayClient] работает поверх любого.
 *
 * Транспорт принадлежит сессии ([RelayConnectionScope]) одного режима сети:
 * после закрытия сессии запрос бросает [IOException] до открытия сокета, а уже
 * идущие запросы обрываются. Методы блокирующие — вызывать на IO-диспетчере.
 */
interface RelayHttpTransport {
    val kind: RelayTransportKind

    /**
     * @param path путь с query, начинается с `/v1/`.
     * @param headers дополнительные заголовки; имена и значения санитизируются.
     * @param timeoutMs таймаут чтения (для long-poll — с запасом над `wait`).
     */
    @Throws(IOException::class)
    fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = RelayHttp.DEFAULT_TIMEOUT_MS,
    ): RelayHttpResponse
}

/** Запрос через сессию, которая уже закрыта сменой режима сети. */
class ClosedTransportException(kind: RelayTransportKind) :
    IOException("транспорт ${kind.name} закрыт: режим сети сменился")
