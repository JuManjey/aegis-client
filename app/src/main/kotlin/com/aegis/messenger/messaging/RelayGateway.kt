package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.HttpsRelayAddress
import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.psk.PskManager
import com.aegis.network.tor.AliasPutResult
import com.aegis.network.tor.HttpsRelayTransport
import com.aegis.network.tor.PowSolver
import com.aegis.network.tor.RelayClient
import com.aegis.network.tor.RelayConnectionScope
import com.aegis.network.tor.RelayHttpTransport
import com.aegis.network.tor.RelayParams
import com.aegis.network.tor.RelayTransportKind
import com.aegis.network.tor.SignedAliasClaim
import com.aegis.network.tor.SignedConfig
import com.aegis.network.tor.TorRelayTransport
import com.aegis.network.tor.TorTransport
import com.aegis.network.tor.TurnInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Шлюз к релею (§7.6/§12.2): единая точка построения [RelayClient] и
 * вычисления mailbox-адресов из PSK контакта. Используется и отправкой
 * ([MessagePipeline]/[com.aegis.messenger.call.CallSignaling]), и приёмом
 * ([RelayReceiver]).
 *
 * Транспорт выбирается строго по режиму сети ([RelayRouteSelector]): Tor ВКЛ —
 * только onion через SOCKS, Tor ВЫКЛ — только HTTPS к тому же релею; откатов
 * между ними нет. Клиенты принадлежат СЕССИИ транспорта, которую открывает и
 * закрывает [NetworkModeController]: закрытие обрывает все её соединения, так что
 * запрос, начатый в одном режиме, не завершится в другом. Нет маршрута — операции
 * no-op (best-effort доставка, E2EE уже гарантировано), причина видна в
 * [unavailable].
 */
class RelayGateway(
    private val pskManager: PskManager,
    private val torTransport: TorTransport,
    private val modeProvider: () -> TorMode,
    private val networkSettingsProvider: () -> TorNetworkSettings,
    private val accountIdProvider: suspend () -> String?,
    private val scope: CoroutineScope,
    /**
     * Смягчение тайминг-корреляции fan-out (§12, `meta.minimize` §8): рандомизация
     * порядка + джиттер per-device дропов. По умолчанию инертен (BASIC ⇒ прежнее
     * поведение) — реальный провайдер уровня прокидывается из [AppContainer].
     */
    private val fanoutJitter: FanoutJitter =
        FanoutJitter({ com.aegis.core.common.security.MetadataMinimization.BASIC }),
    /**
     * Прикрытие факта/объёма отправки (§12, §3a): подмешивает фиктивные дропы к
     * реальному fan-out батчу. По умолчанию инертен (BASIC ⇒ 0 паддинга, прежнее
     * поведение) — реальный инстанс прокидывается из [AppContainer].
     */
    private val coverTraffic: CoverTraffic =
        CoverTraffic({ com.aegis.core.common.security.MetadataMinimization.BASIC }),
) : RelaySessions {
    /** Один дроп fan-out: [real] отличает настоящую доставку от decoy-паддинга. */
    private data class Drop(val mailbox: String, val payload: ByteArray, val real: Boolean)

    /** Итог fan-out: сколько реальных дропов принято и каким транспортом они ушли. */
    data class FanoutResult(val sent: Int, val transport: RelayTransportKind?)

    /** Сессия транспорта одного режима: её закрытие обрывает все её соединения. */
    private class Session(val mode: TorMode) {
        val connections = RelayConnectionScope(
            if (mode == TorMode.ALWAYS) RelayTransportKind.TOR else RelayTransportKind.HTTPS,
        )

        /** Транспорт к последнему маршруту — переиспользуется, пока маршрут тот же. */
        @Volatile
        var cached: Pair<RelayRoute, RelayHttpTransport>? = null
    }

    private companion object {
        /** Тег записей шлюза релея в журнале диагностики. */
        const val DIAG_TAG = "relay"

        /**
         * Срок кэша `GET /v1/params`. Параметры меняются только при
         * перенастройке сервера, а каждый запрос через Tor стоит секунды.
         */
        const val PARAMS_TTL_MS = 30 * 60_000L
    }

    /** До первой [openSession] клиентов нет: режим ещё не применён. */
    @Volatile
    private var session: Session? = null

    private val _unavailable = MutableStateFlow<RelayUnavailableReason?>(RelayUnavailableReason.SWITCHING)

    /** Почему сейчас нет клиента релея (`null` — клиент есть). Для баннеров и статуса в UI. */
    val unavailable: StateFlow<RelayUnavailableReason?> = _unavailable.asStateFlow()

    /**
     * Закрывает текущую сессию: с этого момента [clientOrNull] возвращает `null`,
     * а все её соединения обрываются. Возвращает её соединения, чтобы вызывающий
     * дождался выхода последнего запроса ([RelayConnectionScope.awaitIdle]).
     */
    override fun closeSession(): RelayConnectionScope? {
        val closing = session
        session = null
        paramsCache = null
        closing?.connections?.close()
        publishUnavailable(RelayUnavailableReason.SWITCHING)
        DiagnosticLog.event(
            DIAG_TAG, "сессия транспорта закрыта",
            "режим" to closing?.mode?.name,
            "запросовВПути" to closing?.connections?.inFlightCount,
        )
        return closing?.connections
    }

    /** Открывает сессию для [mode]: клиенты выдаются только для этого режима. */
    override fun openSession(mode: TorMode) {
        session = Session(mode)
        DiagnosticLog.event(DIAG_TAG, "сессия транспорта открыта", "режим" to mode.name)
        clientOrNull()
    }

    /** Транспорт текущей сессии или `null`, пока режим переключается. */
    fun currentTransportKind(): RelayTransportKind? =
        session?.takeIf { it.mode == modeProvider() && !it.connections.isClosed }?.connections?.kind

    /**
     * Строит [RelayClient] для текущего режима сети или `null`.
     *
     * Это узкое место ВСЕЙ сети: если здесь `null`, то ни бандл слоя B, ни
     * сообщения, ни keyx физически никуда не уходят, сколько бы раз обмен ни
     * повторяли. Поэтому причина отказа пишется в журнал — но только при СМЕНЕ
     * состояния ([lastClientState]), иначе long-poll циклы залили бы журнал.
     */
    fun clientOrNull(): RelayClient? {
        val current = session
        val mode = modeProvider()
        val switching = current == null || current.mode != mode || current.connections.isClosed
        val route = RelayRouteSelector.select(
            mode = mode,
            settings = networkSettingsProvider(),
            socksPort = if (mode == TorMode.ALWAYS) torTransport.socksPort else null,
            switching = switching,
        )
        if (route is RelayRoute.Unavailable) {
            publishUnavailable(route.reason)
            logClientState(route.reason.name) { logUnavailable(route.reason) }
            return null
        }
        val transport = transportFor(current!!, route) ?: return null
        publishUnavailable(null)
        logClientState("готов:${transport.kind}") {
            DiagnosticLog.event(
                DIAG_TAG, "клиент релея готов",
                "транспорт" to transport.kind.name,
                "хост" to DiagnosticLog.fp(
                    when (route) {
                        is RelayRoute.Tor -> route.host
                        is RelayRoute.Https -> route.origin
                        else -> ""
                    },
                ),
                "пинов" to (route as? RelayRoute.Https)?.pins?.size,
            )
        }
        return RelayClient(transport)
    }

    private fun transportFor(session: Session, route: RelayRoute): RelayHttpTransport? {
        session.cached?.let { (cachedRoute, transport) -> if (cachedRoute == route) return transport }
        val transport = when (route) {
            is RelayRoute.Tor -> TorRelayTransport(session.connections, route.socksPort, route.host, route.port)
            is RelayRoute.Https -> runCatching {
                HttpsRelayTransport.create(session.connections, route.origin, route.pins)
            }.getOrElse { e ->
                DiagnosticLog.w(DIAG_TAG) { "HTTPS-транспорт не создан: ${e.javaClass.simpleName}" }
                return null
            }
            is RelayRoute.Unavailable -> return null
        }
        session.cached = route to transport
        return transport
    }

    /**
     * HTTPS-транспорт текущей сессии к произвольному адресу — для проверки
     * соединения из настроек. Только в режиме без Tor: в режиме Tor проверка
     * открыла бы прямое соединение и раскрыла бы IP. Соединение принадлежит
     * текущей сессии, поэтому смена режима оборвёт и его.
     */
    fun probeHttpsTransport(address: String, pins: List<String>): HttpsRelayTransport? {
        val current = session ?: return null
        if (current.mode != TorMode.OFF || modeProvider() != TorMode.OFF || current.connections.isClosed) return null
        return runCatching { HttpsRelayTransport.create(current.connections, address, pins) }.getOrNull()
    }

    private fun publishUnavailable(reason: RelayUnavailableReason?) {
        if (_unavailable.value != reason) _unavailable.value = reason
    }

    private fun logUnavailable(reason: RelayUnavailableReason) {
        DiagnosticLog.w(DIAG_TAG) {
            when (reason) {
                RelayUnavailableReason.SWITCHING -> "клиент недоступен: идёт смена режима сети"
                RelayUnavailableReason.ONION_NOT_SET ->
                    "клиент недоступен: адрес onion-релея НЕ ЗАДАН — в режиме Tor сеть не работает совсем " +
                        "(задайте адрес в «Настройки → Сеть и Tor» или соберите APK с -Paegis.defaultRelayOnion=<...>.onion)"
                RelayUnavailableReason.ONION_INVALID ->
                    "клиент недоступен: адрес onion-релея некорректный (нужен v3 .onion)"
                RelayUnavailableReason.TOR_NOT_READY ->
                    "клиент недоступен: Tor не поднят (нет порта SOCKS), статус=${torTransport.status.value}"
                RelayUnavailableReason.HTTPS_NOT_SET ->
                    "клиент недоступен: сервер без Tor не настроен — HTTPS-адрес релея не задан " +
                        "(конфиг сервера, -Paegis.defaultRelayHttps или ручной ввод)"
                RelayUnavailableReason.HTTPS_INVALID ->
                    "клиент недоступен: HTTPS-адрес релея некорректный (нужен https://host[:port])"
            }
        }
    }

    /** Пишет в журнал только при смене состояния клиента (иначе — спам из циклов). */
    private inline fun logClientState(state: String, log: () -> Unit) {
        if (!DiagnosticLog.isEnabled) return
        if (lastClientState == state) return
        lastClientState = state
        log()
    }

    @Volatile
    private var lastClientState: String? = null

    /** accountId ЭТОГО устройства (общий на все мои устройства) или `null`. */
    private suspend fun myAccountId(): String? = runCatching { accountIdProvider() }.getOrNull()

    /**
     * Мои per-device входящие подканалы v2 для переписки с [contact] (§4.1): по
     * одному на КАЖДОЕ моё устройство из [myDeviceIds]. Их слушает [RelayReceiver]
     * (у каждого устройства свой ящик ⇒ деструктивный ack не задевает другие,
     * MD-2/§9). Пусто, если нет PSK/identity/релея.
     */
    suspend fun conversationInboxesForMe(contact: StoredContact, myDeviceIds: List<Int>): List<String> {
        val account = myAccountId() ?: return emptyList()
        return myDeviceIds.mapNotNull { deviceId ->
            pskManager.convMailboxFor(contact.contact.id, account, deviceId)
        }
    }

    /**
     * Fan-out доставка (§6): постит уже готовые per-device конверты во входящие
     * подканалы v2 каждого адресата-устройства контакта. [envelopes] — карта
     * `deviceId → payload` (свой шифртекст на устройство, MD-1). Best-effort:
     * без клиента релея тихо no-op. Возвращает число успешных POST.
     */
    suspend fun postToPeerDevices(
        contact: StoredContact,
        envelopes: Map<Int, ByteArray>,
    ): Int = deliverToPeerDevices(contact, envelopes).sent

    /** То же, что [postToPeerDevices], плюс фактический транспорт доставки. */
    suspend fun deliverToPeerDevices(
        contact: StoredContact,
        envelopes: Map<Int, ByteArray>,
    ): FanoutResult = withContext(Dispatchers.IO) {
        if (envelopes.isEmpty()) return@withContext FanoutResult(0, null)
        val client = clientOrNull() ?: run {
            DiagnosticLog.w(DIAG_TAG) {
                "отправка отменена: нет клиента релея, конвертов=${envelopes.size} " +
                    "контакт=${DiagnosticLog.shortId(contact.contact.id)}"
            }
            return@withContext FanoutResult(0, null)
        }
        val ownerAccount = contact.contact.id
        // Реальные per-device дропы (mailbox выводим заранее — далее всё едет одной
        // перемешанной очередью вместе с decoy-паддингом).
        val realDrops = envelopes.mapNotNull { (deviceId, payload) ->
            val mailbox = pskManager.convMailboxFor(contact.contact.id, ownerAccount, deviceId)
                ?: return@mapNotNull null
            Drop(mailbox, payload, real = true)
        }
        if (realDrops.isEmpty()) {
            DiagnosticLog.w(DIAG_TAG) {
                "отправка отменена: не удалось вывести ни один адрес ящика (нет PSK?) " +
                    "контакт=${DiagnosticLog.shortId(contact.contact.id)}"
            }
            return@withContext FanoutResult(0, client.transportKind)
        }
        // §3a: разбавляем фиктивными дропами, чтобы число дропов не раскрывало число
        // устройств-адресатов (при MAXIMUM; иначе padCount()=0 ⇒ прежнее поведение).
        val decoyDrops = coverTraffic.decoyDrops(coverTraffic.padCount())
            .map { (mailbox, payload) -> Drop(mailbox, payload, real = false) }
        var sent = 0
        // §12: рандомизируем порядок и джиттерим дропы, чтобы реальные и фиктивные
        // POST-ы не уходили детерминированной «пачкой» (тайминг-корреляция fan-out).
        for (drop in fanoutJitter.order(realDrops + decoyDrops)) {
            fanoutJitter.awaitJitter()
            // Успех — это ПРИСВОЕННЫЙ релеем seq, а не отсутствие исключения:
            // [RelayClient.post] на неуспешный HTTP-ответ возвращает null, ничего
            // не бросая. Прежняя проверка `runCatching{…}.isSuccess` считала
            // доставленным и отказ сервера (500, переполнение очереди), из-за чего
            // сообщение помечалось отправленным, не попав ни в один ящик.
            val ok = runCatching { client.post(drop.mailbox, drop.payload) }.getOrNull() != null
            if (ok && drop.real) sent++
            if (drop.real) {
                DiagnosticLog.event(
                    DIAG_TAG, "дроп",
                    "ящик" to DiagnosticLog.mailbox(drop.mailbox),
                    "байт" to drop.payload.size,
                    "ок" to ok,
                )
            }
        }
        DiagnosticLog.event(
            DIAG_TAG, "fan-out завершён",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "устройств" to envelopes.size,
            "доставлено" to sent,
            "транспорт" to client.transportKind.name,
        )
        FanoutResult(sent, client.transportKind)
    }

    /**
     * Загружает зашифрованный блоб вложения (§14.4) под опаковым [objectId].
     * Блокирующая — вызывать из корутины. `true` при успехе; `false`, если
     * Tor/релей недоступны или загрузка не удалась (E2EE от этого не страдает).
     */
    suspend fun uploadBlob(objectId: String, ciphertext: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val result = runCatching { clientOrNull()?.putBlob(objectId, ciphertext) ?: false }
            DiagnosticLog.event(
                DIAG_TAG, "блоб: загрузка",
                "id" to DiagnosticLog.fp(objectId),
                "байт" to ciphertext.size,
                "ок" to result.getOrDefault(false),
                "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
            )
            result.getOrDefault(false)
        }

    /** Скачивает зашифрованный блоб вложения по [objectId] или `null`. */
    suspend fun downloadBlob(objectId: String): ByteArray? = withContext(Dispatchers.IO) {
        val result = runCatching { clientOrNull()?.getBlob(objectId) }
        DiagnosticLog.event(
            DIAG_TAG, "блоб: скачивание",
            "id" to DiagnosticLog.fp(objectId),
            "байт" to (result.getOrNull()?.size ?: 0),
            "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
        )
        result.getOrNull()
    }

    /**
     * Публикует ПУБЛИЧНЫЙ prekey-бандл слоя B на релее (`PUT /v1/prekeys/{id}`),
     * чтобы доставить его собеседнику при обмене по QR (в QR бандл не влезает,
     * §6.2). На релей уходит ТОЛЬКО публичный бандл; PSK (секрет слоя A) остаётся
     * в QR и в сеть не уходит. Best-effort: без Tor/релея тихо `false`.
     */
    suspend fun putPrekeyBundle(id: String, bundleJson: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = runCatching { clientOrNull()?.putPrekey(id, bundleJson) ?: false }
            DiagnosticLog.event(
                DIAG_TAG, "публикация бандла",
                "id" to DiagnosticLog.fp(id),
                "байт" to bundleJson.length,
                "ок" to result.getOrDefault(false),
                "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
            )
            result.getOrDefault(false)
        }

    /** Тянет опубликованный публичный prekey-бандл по [id] или `null`. */
    suspend fun getPrekeyBundle(id: String): String? = withContext(Dispatchers.IO) {
        val result = runCatching { clientOrNull()?.getPrekey(id) }
        DiagnosticLog.event(
            DIAG_TAG, "загрузка бандла",
            "id" to DiagnosticLog.fp(id),
            "байт" to (result.getOrNull()?.length ?: 0),
            "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
        )
        result.getOrNull()
    }

    // --- Каталог обнаружения (удалённое добавление контакта, §4.7/§4.8) ------

    /**
     * Параметры релея (`GET /v1/params`): включён ли каталог, нужен ли PoW.
     * Кэшируются на [PARAMS_TTL_MS] — они меняются только при перенастройке
     * сервера, а каждый лишний запрос через Tor стоит секунды.
     */
    suspend fun relayParams(): RelayParams? = withContext(Dispatchers.IO) {
        val cached = paramsCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.second < PARAMS_TTL_MS) return@withContext cached.first
        val fetched = runCatching { clientOrNull()?.getParams() }.getOrNull()
        if (fetched != null) {
            paramsCache = fetched to now
            DiagnosticLog.event(
                DIAG_TAG, "параметры релея",
                "каталог" to fetched.directoryEnabled,
                "алиасы" to fetched.aliasEnabled,
                "powБит" to fetched.powBits,
            )
        } else {
            DiagnosticLog.w(DIAG_TAG) {
                "параметры релея недоступны (старый релей или нет транспорта) — " +
                    "удалённое добавление контакта работать не будет"
            }
        }
        fetched
    }

    @Volatile
    private var paramsCache: Pair<RelayParams, Long>? = null

    /**
     * Публикует ЗАПЕЧАТАННУЮ запись каталога. На релей уходит только шифртекст
     * (ключ выведен из тега, которого у сервера нет) — сервер не знает ни чья
     * запись, ни что внутри. Best-effort: без Tor/релея тихо `false`.
     */
    suspend fun putDirectoryRecord(index: String, sealed: ByteArray, ttlSec: Long): Boolean =
        withContext(Dispatchers.IO) {
            val ok = runCatching { clientOrNull()?.putDirectory(index, sealed, ttlSec) ?: false }
            DiagnosticLog.event(
                DIAG_TAG, "публикация записи каталога",
                "индекс" to DiagnosticLog.fp(index),
                "байт" to sealed.size,
                "ок" to ok.getOrDefault(false),
                "ошибка" to ok.exceptionOrNull()?.javaClass?.simpleName,
            )
            ok.getOrDefault(false)
        }

    /** Тянет запечатанную запись каталога по индексу. Проверяет её вызывающий. */
    suspend fun getDirectoryRecord(index: String): ByteArray? = withContext(Dispatchers.IO) {
        val result = runCatching { clientOrNull()?.getDirectory(index) }
        DiagnosticLog.event(
            DIAG_TAG, "загрузка записи каталога",
            "индекс" to DiagnosticLog.fp(index),
            "байт" to (result.getOrNull()?.size ?: 0),
            "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
        )
        result.getOrNull()
    }

    /** Снимает свою запись каталога с публикации (ротация/отключение). */
    suspend fun deleteDirectoryRecord(index: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { clientOrNull()?.deleteDirectory(index) ?: false }.getOrDefault(false)
    }

    /**
     * Занимает/продлевает публичный алиас. Решение proof-of-work считается здесь
     * (сложность берётся из [relayParams]); при отказе `429` попытка повторяется
     * один раз с новым решением — окно PoW могло сдвинуться, пока шёл запрос
     * через Tor.
     */
    suspend fun putAliasClaim(index: String, claimB64: String, sigB64: String): AliasPutResult =
        withContext(Dispatchers.IO) {
            val client = clientOrNull() ?: return@withContext AliasPutResult.Unavailable
            val params = relayParams() ?: return@withContext AliasPutResult.Unavailable
            if (!params.aliasEnabled) return@withContext AliasPutResult.Unavailable
            val path = "/v1/alias/$index"
            var result = client.putAlias(index, claimB64, sigB64, solvePow(path, params))
            if (result is AliasPutResult.PowRequired) {
                result = client.putAlias(index, claimB64, sigB64, solvePow(path, params))
            }
            DiagnosticLog.event(
                DIAG_TAG, "заявка на алиас",
                "индекс" to DiagnosticLog.fp(index),
                "итог" to result.javaClass.simpleName,
            )
            result
        }

    /** Тянет подписанную заявку на алиас. Проверяет подпись вызывающий. */
    suspend fun getAliasClaim(index: String): SignedAliasClaim? = withContext(Dispatchers.IO) {
        val client = clientOrNull() ?: return@withContext null
        val params = relayParams() ?: return@withContext null
        if (!params.aliasEnabled) {
            DiagnosticLog.w(DIAG_TAG) { "публичные алиасы выключены на релее — поиск по нику недоступен" }
            return@withContext null
        }
        val path = "/v1/alias/$index"
        runCatching { client.getAlias(index, solvePow(path, params)) }.getOrNull()
            ?: runCatching { client.getAlias(index, solvePow(path, params)) }.getOrNull()
    }

    /** Решение PoW или пустая строка, если он не требуется/не нашёлся. */
    private fun solvePow(path: String, params: RelayParams): String =
        PowSolver.solve(path, params.powBits, epochSecs = params.powEpochSecs).orEmpty()

    /** Best-effort удаление блоба на релее (получатель подтверждает приём, §7.3). */
    fun deleteBlob(objectId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching { clientOrNull()?.deleteBlob(objectId) }
        }
    }

    /**
     * Best-effort запрос эфемерных TURN-кред у релея для медиа звонков (§7.5,
     * `GET /v1/turn`) текущим транспортом. `null`, если клиента релея нет или
     * TURN не сконфигурирован на сервере.
     */
    suspend fun fetchTurn(): TurnInfo? = withContext(Dispatchers.IO) {
        val client = clientOrNull()
        val result = runCatching { client?.getTurn() }
        DiagnosticLog.sync(
            DIAG_TAG, "TURN",
            "транспорт" to client?.transportKind?.name,
            "ок" to (result.getOrNull() != null),
            "onion" to (result.getOrNull()?.hasOnion == true),
            "uri" to (result.getOrNull()?.uris?.size ?: 0),
            "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
        )
        result.getOrNull()
    }

    /**
     * Порт SOCKS5 встроенного Tor (для локального TURN-форвардера) или `null`.
     * Вне режима Tor — всегда `null`: туннелировать там нечего и некуда.
     */
    fun socksPortOrNull(): Int? = if (modeProvider() == TorMode.ALWAYS) torTransport.socksPort else null

    /**
     * Хост HTTPS-релея в режиме без Tor — чтобы принимать STUN/TURN только с его
     * домена. В режиме Tor — `null`.
     */
    fun httpsRelayHost(): String? =
        if (modeProvider() == TorMode.OFF) HttpsRelayAddress.parse(networkSettingsProvider().httpsRelayAddress)?.host else null

    /**
     * Best-effort запрос подписанного конфиг-документа релея (§12.2,
     * `GET /v1/config`) для авто-обнаружения адресов. `null`, если клиента релея
     * нет или раздача не сконфигурирована. Подпись проверяет вызывающий
     * ([com.aegis.messenger.messaging.ConfigSync]) зашитым в сборку ключом.
     */
    suspend fun fetchServerConfig(): SignedConfig? = withContext(Dispatchers.IO) {
        val result = runCatching { clientOrNull()?.getConfig() }
        DiagnosticLog.event(
            DIAG_TAG, "конфиг релея",
            "ок" to (result.getOrNull() != null),
            "ошибка" to result.exceptionOrNull()?.javaClass?.simpleName,
        )
        result.getOrNull()
    }
}
