package com.aegis.network.tor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64

/**
 * Клиент API релея AEGIS (§7.6/§12.2) поверх транспорта [RelayHttpTransport]:
 * через встроенный Tor к onion-адресу ([TorRelayTransport]) или по HTTPS к тому
 * же релею в режиме «Tor ВЫКЛ» ([HttpsRelayTransport]). Реализует контракт
 * `aegis-server/PROTOCOL.md`: анонимный дроп конверта, long-poll приём,
 * подтверждение (ack), prekey-бандлы, блобы, каталог, алиасы, TURN, конфиг.
 *
 * Сервер видит ТОЛЬКО непрозрачный mailbox-id (выведен из PSK) и шифртекст;
 * личности и связь «кто-кому» ему недоступны (zero-trust, §10.9). Методы
 * блокирующие — вызывать на IO-диспетчере.
 */
class RelayClient(private val transport: RelayHttpTransport) {

    /** Каким транспортом ходит этот клиент — для следа сообщения и журнала. */
    val transportKind: RelayTransportKind get() = transport.kind

    /** Один принятый из очереди конверт. */
    data class RelayItem(val seq: Long, val data: ByteArray)

    /** Результат long-poll: принятые конверты и курсор [next] для следующего запроса. */
    data class PollResult(val items: List<RelayItem>, val next: Long)

    /**
     * Кладёт конверт [payload] в очередь [mailbox] (анонимный дроп). Возвращает
     * присвоенный порядковый номер или `null` при неуспехе (best-effort, §12.2).
     */
    fun post(mailbox: String, payload: ByteArray): Long? {
        val resp = transport.request(
            method = "POST",
            path = "/v1/mailbox/$mailbox",
            body = payload,
            contentType = "application/octet-stream",
        )
        if (!resp.isSuccess) return null
        return RelayJson.parseObject(resp.bodyString())?.optLong("seq", -1L)?.takeIf { it >= 0 }
    }

    /**
     * Long-poll приём: возвращает конверты с `seq > after`. Если новых нет, сервер
     * держит соединение до [waitSec] секунд. Таймаут сокета берётся с запасом над
     * [waitSec], чтобы дождаться ответа сервера, а не оборвать его локально.
     */
    fun poll(mailbox: String, after: Long, waitSec: Int): PollResult {
        val resp = transport.request(
            method = "GET",
            path = "/v1/mailbox/$mailbox?after=$after&wait=$waitSec",
            timeoutMs = (waitSec + SOCKET_SLACK_SEC) * 1000,
        )
        if (!resp.isSuccess) return PollResult(emptyList(), after)
        val obj = RelayJson.parseObject(resp.bodyString()) ?: throw IOException("poll: ответ релея не JSON")
        val next = obj.optLong("next", after)
        val arr = obj.optArray("items") ?: return PollResult(emptyList(), next)
        val items = ArrayList<RelayItem>(arr.size)
        for (element in arr) {
            val item = element as? JsonObject ?: continue
            val seq = item.optLong("seq", -1L)
            val dataB64 = item.optString("dataB64")
            if (seq >= 0 && dataB64.isNotEmpty()) {
                val data = runCatching { Base64.getDecoder().decode(dataB64) }
                    .getOrElse { throw IOException("poll: dataB64 не base64", it) }
                items += RelayItem(seq, data)
            }
        }
        return PollResult(items, next)
    }

    /** Подтверждает обработку конвертов `seq <= upto` (сервер их удаляет). */
    fun ack(mailbox: String, upto: Long): Boolean =
        transport.request(
            method = "POST",
            path = "/v1/mailbox/$mailbox/ack",
            body = "{\"upto\":$upto}".toByteArray(Charsets.UTF_8),
            contentType = "application/json",
        ).isSuccess

    /** Публикует публичный prekey-бандл (опция keys.overSignal, §8/§10.12). */
    fun putPrekey(id: String, bundleJson: String): Boolean =
        transport.request(
            method = "PUT",
            path = "/v1/prekeys/$id",
            body = bundleJson.toByteArray(Charsets.UTF_8),
            contentType = "application/json",
        ).isSuccess

    /**
     * Загружает зашифрованный блоб вложения (§14.4) под опаковым [id] (256 бит,
     * сгенерирован клиентом). Тело — непрозрачный шифртекст (каскад слоя A на
     * случайном ключе); сервер его не разбирает. `true` при успехе.
     */
    fun putBlob(id: String, ciphertext: ByteArray): Boolean =
        transport.request(
            method = "PUT",
            path = "/v1/blob/$id",
            body = ciphertext,
            contentType = "application/octet-stream",
            timeoutMs = BLOB_TIMEOUT_MS,
        ).isSuccess

    /**
     * Скачивает зашифрованный блоб вложения по [id]. Возвращает шифртекст или
     * `null`, если блоба нет (404 / просрочен / удалён) либо запрос не удался.
     */
    fun getBlob(id: String): ByteArray? {
        val resp = transport.request(method = "GET", path = "/v1/blob/$id", timeoutMs = BLOB_TIMEOUT_MS)
        return if (resp.isSuccess) resp.body else null
    }

    /** Удаляет блоб (получатель подтверждает приём — минимизация хранения §7.3). */
    fun deleteBlob(id: String): Boolean =
        transport.request(method = "DELETE", path = "/v1/blob/$id", timeoutMs = BLOB_TIMEOUT_MS).isSuccess

    /** Получает опубликованный prekey-бандл или `null`, если его нет. */
    fun getPrekey(id: String): String? {
        val resp = transport.request(method = "GET", path = "/v1/prekeys/$id")
        return if (resp.isSuccess) resp.bodyString() else null
    }

    /**
     * Запрашивает эфемерные TURN-креды для медиа звонков (§7.5, `GET /v1/turn`).
     * В режиме Tor нужен onion-адрес coturn ([TurnInfo.hasOnion]), без Tor —
     * клирнет-URI ([TurnInfo.uris]); что из этого использовать, решает вызывающий.
     * `null`, если TURN на сервере не сконфигурирован (404) либо запрос не удался.
     */
    fun getTurn(): TurnInfo? {
        val resp = transport.request(method = "GET", path = "/v1/turn")
        if (!resp.isSuccess) return null
        return parseTurn(resp.bodyString())
    }

    // --- Каталог обнаружения (удалённое добавление контакта, PROTOCOL.md §4.7) ---

    /**
     * Публикует ЗАПЕЧАТАННУЮ запись каталога под опаковым [index]
     * (`PUT /v1/directory/{index}?ttl=`). Тело — шифртекст AEAD на ключе,
     * выведенном из тега обнаружения: сервер запись не читает и не знает, чья
     * она. [ttlSec] — желаемый срок публикации, сервер клемпит его своим лимитом.
     * `false`, если каталог выключен на релее (404) либо запрос не удался.
     */
    fun putDirectory(index: String, sealedRecord: ByteArray, ttlSec: Long): Boolean =
        transport.request(
            method = "PUT",
            path = "/v1/directory/$index?ttl=$ttlSec",
            body = sealedRecord,
            contentType = "application/octet-stream",
        ).isSuccess

    /**
     * Тянет запечатанную запись каталога по [index]. `null` — записи нет
     * (404 / просрочена / каталог выключен) либо запрос не удался. Расшифровку
     * и ПРОВЕРКУ подписи/привязки accountId выполняет вызывающий (fail-closed).
     */
    fun getDirectory(index: String): ByteArray? {
        val resp = transport.request(method = "GET", path = "/v1/directory/$index")
        return if (resp.isSuccess) resp.body else null
    }

    /** Снимает запись каталога с публикации. Идемпотентно, best-effort. */
    fun deleteDirectory(index: String): Boolean =
        transport.request(method = "DELETE", path = "/v1/directory/$index").isSuccess

    /**
     * Занимает или продлевает ПУБЛИЧНЫЙ алиас (`PUT /v1/alias/{index}`).
     *
     * [claimB64] — каноничные байты заявки в base64, [sigB64] — Ed25519-подпись
     * над ними. [powHeader] — решение proof-of-work в формате `<epoch>:<nonce-hex>`
     * (см. [RelayParams.powBits]); пустая строка, если PoW на релее выключен.
     *
     * Различаем итоги, потому что UI обязан показать разное: занято другим
     * ключом, отклонено проверкой, требуется PoW, недоступно.
     */
    fun putAlias(
        index: String,
        claimB64: String,
        sigB64: String,
        powHeader: String,
    ): AliasPutResult {
        val body = buildJsonObject {
            put("claim", claimB64)
            put("sig", sigB64)
        }.toString()
        val resp = transport.request(
            method = "PUT",
            path = "/v1/alias/$index",
            body = body.toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            headers = powHeaders(powHeader),
        )
        return when {
            resp.isSuccess -> AliasPutResult.Ok
            resp.status == 409 -> AliasPutResult.Taken
            resp.status == 429 -> AliasPutResult.PowRequired
            resp.status == 404 -> AliasPutResult.Unavailable
            resp.status == 400 -> AliasPutResult.Rejected(resp.errorMessage())
            else -> AliasPutResult.Rejected("HTTP ${resp.status}")
        }
    }

    /**
     * Получает подписанную заявку на алиас. `null` — алиас не занят (404),
     * публичный каталог выключен либо не пройден PoW. Клиент ОБЯЗАН перепроверить
     * подпись и привязку `accountId ↔ ed25519` сам: заявка самодостаточна.
     */
    fun getAlias(index: String, powHeader: String): SignedAliasClaim? {
        val resp = transport.request(method = "GET", path = "/v1/alias/$index", headers = powHeaders(powHeader))
        if (!resp.isSuccess) return null
        val o = RelayJson.parseObject(resp.bodyString()) ?: return null
        val claim = o.optString("claim")
        val sig = o.optString("sig")
        return if (claim.isEmpty() || sig.isEmpty()) null else SignedAliasClaim(claim, sig)
    }

    /**
     * Параметры релея (`GET /v1/params`): что включено и какая сложность PoW.
     * `null` на старом релее без этого маршрута — тогда вызывающий считает
     * каталог недоступным и откатывается на обмен по QR/NFC.
     */
    fun getParams(): RelayParams? {
        val resp = transport.request(method = "GET", path = "/v1/params")
        if (!resp.isSuccess) return null
        val o = RelayJson.parseObject(resp.bodyString()) ?: return null
        return RelayParams(
            directoryEnabled = o.optBoolean("directory", false),
            aliasEnabled = o.optBoolean("alias", false),
            powBits = o.optInt("powBits", 0),
            powEpochSecs = o.optLong("powEpochSecs", 600L),
            directoryMaxBytes = o.optInt("directoryMaxBytes", 0),
            directoryMaxTtlSec = o.optLong("directoryMaxTtlSecs", 0L),
        )
    }

    private fun powHeaders(powHeader: String): Map<String, String> =
        if (powHeader.isBlank()) emptyMap() else mapOf(POW_HEADER to powHeader)

    /**
     * Запрашивает подписанный конфиг-документ релея (§12.2, `GET /v1/config`) для
     * авто-обнаружения адресов. Возвращает сырые base64-строки `{doc, sig}` или
     * `null`, если раздача не сконфигурирована (404) либо запрос не удался.
     * Проверку подписи выполняет вызывающий (зашитым в сборку публичным ключом).
     */
    fun getConfig(): SignedConfig? {
        val resp = transport.request(method = "GET", path = "/v1/config")
        if (!resp.isSuccess) return null
        val o = RelayJson.parseObject(resp.bodyString()) ?: return null
        val doc = o.optString("doc")
        val sig = o.optString("sig")
        return if (doc.isEmpty() || sig.isEmpty()) null else SignedConfig(doc, sig)
    }

    /**
     * Проверка соединения (`GET /v1/health`): отвечает ли по адресу именно релей
     * AEGIS. Сетевые ошибки бросает — вызывающему нужна их причина для UI.
     */
    fun health(): RelayHealth {
        val resp = transport.request(method = "GET", path = "/v1/health")
        if (!resp.isSuccess) return RelayHealth.HttpError(resp.status)
        val service = RelayJson.parseObject(resp.bodyString())?.optString("service").orEmpty()
        return if (service == RELAY_SERVICE) RelayHealth.Ok else RelayHealth.NotARelay
    }

    companion object {
        private const val SOCKET_SLACK_SEC = 20

        // Блобы вложений крупнее конвертов — даём щедрый таймаут (Tor медленный).
        private const val BLOB_TIMEOUT_MS = 120_000

        /** Заголовок с решением proof-of-work публичных маршрутов алиасов. */
        private const val POW_HEADER = "X-Aegis-Pow"

        /** Значение поля `service` в `/v1/health` релея. */
        private const val RELAY_SERVICE = "aegis-relay"

        /**
         * Разбор `GET /v1/turn`. Аддитивные поля (`stun`, `uris`) у старого релея
         * отсутствуют — тогда они пустые. Без кред или без единого способа
         * подключиться ответ бесполезен — `null`.
         */
        internal fun parseTurn(body: String): TurnInfo? {
            val o = RelayJson.parseObject(body) ?: return null
            val username = o.optString("username")
            val credential = o.optString("credential")
            if (username.isEmpty() || credential.isEmpty()) return null
            val info = TurnInfo(
                onion = o.optString("onion").trim(),
                port = o.optInt("port", 0),
                username = username,
                credential = credential,
                realm = o.optString("realm"),
                ttlSec = o.optLong("ttl", 0L),
                stun = o.optString("stun").trim(),
                uris = o.optArray("uris")?.strings()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
            return if (!info.hasOnion && info.uris.isEmpty() && info.stun.isEmpty()) null else info
        }
    }
}

/** Итог проверки соединения с релеем. */
sealed interface RelayHealth {
    /** Отвечает релей AEGIS. */
    data object Ok : RelayHealth

    /** Ответ есть, но это не релей AEGIS (например, домен указывает на сайт). */
    data object NotARelay : RelayHealth

    /** Сервер ответил ошибкой HTTP. */
    data class HttpError(val status: Int) : RelayHealth
}

/** Итог попытки занять/продлить публичный алиас. */
sealed interface AliasPutResult {
    /** Заявка принята. */
    data object Ok : AliasPutResult

    /** Алиас занят ДРУГИМ ключом (или присланная заявка не новее действующей). */
    data object Taken : AliasPutResult

    /** Нужен proof-of-work: посчитать и повторить. */
    data object PowRequired : AliasPutResult

    /** Публичный каталог выключен на этом релее. */
    data object Unavailable : AliasPutResult

    /** Заявка не прошла проверку на сервере (подпись/привязка/срок). */
    data class Rejected(val reason: String) : AliasPutResult
}

/** Подписанная заявка на алиас, как её отдал релей (проверяет клиент сам). */
data class SignedAliasClaim(val claimB64: String, val sigB64: String)

/**
 * Параметры релея (`GET /v1/params`). Нужны, чтобы клиент не угадывал: без них
 * он либо считал бы proof-of-work зря, либо получал 429/400 и молча деградировал.
 */
data class RelayParams(
    /** Приватный каталог обнаружения включён (иначе удалённое добавление невозможно). */
    val directoryEnabled: Boolean,
    /** Публичный каталог алиасов включён (поиск по `@нику`). */
    val aliasEnabled: Boolean,
    /** Требуемая сложность PoW в ведущих нулевых битах; 0 — не требуется. */
    val powBits: Int,
    /** Длина окна PoW в секундах (для вычисления epoch). */
    val powEpochSecs: Long,
    /** Потолок размера запечатанной записи каталога (байт). */
    val directoryMaxBytes: Int,
    /** Потолок срока публикации записи каталога (сек). */
    val directoryMaxTtlSec: Long,
)

/**
 * Эфемерные TURN-креды от релея (§7.5). [username]/[credential] — производные от
 * общего секрета (клиент секрет не знает). Медиа остаётся E2EE (DTLS-SRTP).
 */
data class TurnInfo(
    /** Onion-адрес coturn для режима Tor (TCP через форвардер); пусто — не настроен. */
    val onion: String,
    val port: Int,
    val username: String,
    val credential: String,
    val realm: String,
    val ttlSec: Long,
    /**
     * Устаревшее поле: публичный STUN `host:port`. Новым клиентам он приходит в
     * [uris]; поле читается для совместимости с релеем, где `uris` ещё нет.
     */
    val stun: String = "",
    /**
     * Клирнет ICE-серверы своего релея для режима «Tor ВЫКЛ»: `stun:`,
     * `turn:…?transport=udp|tcp`, `turns:`. Пусто на старом релее. В режиме Tor не
     * используются никогда: обращение к ним шло бы мимо Tor.
     */
    val uris: List<String> = emptyList(),
) {
    /** Есть ли onion-TURN для режима Tor. */
    val hasOnion: Boolean get() = onion.isNotEmpty() && port > 0
}

/**
 * Сырой подписанный конфиг-документ релея (§12.2). [docB64] — base64 каноничных
 * байт JSON payload (подписанных офлайн-ключом), [sigB64] — base64 Ed25519-подписи
 * над ними. Подпись проверяется зашитым в сборку публичным ключом ДО разбора JSON.
 */
data class SignedConfig(val docB64: String, val sigB64: String)
