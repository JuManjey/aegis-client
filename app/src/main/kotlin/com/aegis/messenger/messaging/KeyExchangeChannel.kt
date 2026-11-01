package com.aegis.messenger.messaging

import android.util.Base64
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.wire.WireChannel
import com.aegis.data.storage.ContactStore
import com.aegis.data.storage.PeerDevice
import com.aegis.data.storage.PeerDeviceStore
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.psk.PrekeyBundle
import com.aegis.messenger.psk.PskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Догоняющий обмен ПУБЛИЧНЫМИ ключами слоя B поверх ОДНОГО слоя A (§6.2).
 *
 * ЗАЧЕМ. Личный обмен (§6.2) даёт обеим сторонам PSK — секрет слоя A. Но полный
 * PQXDH prekey-бандл (~2.9 КБ из-за Kyber1024) в QR не влезает, поэтому по QR
 * едет только `bundleId`, а сам бандл идёт через релей. Если в момент показа/
 * сканирования QR Tor ещё не поднят (а при личной встрече это обычное дело),
 * бандл не публикуется и не скачивается: контакт создаётся с `bundleJson = null`,
 * слой B не стартует, и каждая отправка падает в `FAILED` («ошибка E2EE»).
 *
 * Раньше выхода из этого состояния не было вовсе: единственный канал доставки
 * бандла «потом» — control-канал ([ControlChannel] / [DeviceAnnouncer]) — сам
 * шифруется слоем B, то есть требует уже установленной сессии. Замкнутый круг.
 *
 * РЕШЕНИЕ. Этот канал разрывает круг: он использует ТОЛЬКО слой A. Так можно,
 * потому что PSK получен личным жестом (XOR половин из двух QR) и никогда не
 * уходил в сеть, а каскад слоя A — AEAD. Значит конверт keyx:
 *  - серверу не читается и не подделывается (конфиденциальность + целостность);
 *  - аутентифицирован «личной встречей»: положить конверт в per-device mailbox
 *    (адрес выводится из PSK, `docs/multidevice.md` §4.1) и расшифровать его
 *    может только владелец PSK.
 * Поэтому доставленный так ПУБЛИЧНЫЙ бандл доверенне, чем скачанный с релея по
 * `bundleId`: там анти-MITM держится на сверке identity, здесь — на самом PSK.
 * Секрет слоя A по-прежнему НЕ уходит в сеть: наружу едут только публичные
 * identity + prekey-бандл.
 *
 * ПРОТОКОЛ. Один раунд, симметричный (кто угодно начинает):
 * ```
 *  A ──[keyx REQUEST: identity_A + bundle_A]──▶ B   (B сохраняет ключи A)
 *  A ◀──[keyx ANNOUNCE: identity_B + bundle_B]── B   (A сохраняет ключи B)
 * ```
 * `REQUEST` = «вот мои ключи, пришли свои», `ANNOUNCE` = «вот мои ключи» (ответ,
 * без повторной просьбы — иначе стороны пинг-понговали бы вечно).
 *
 * ЗАПУСК. [announce] вызывается сразу после личного обмена, при неудачной
 * отправке из-за отсутствия ключей ([MessagePipeline.onMissingPeerKeys]) и
 * периодически из [start] — пока у контакта с установленным PSK нет бандла.
 * Всё best-effort: без Tor/релея просто ничего не произойдёт, а E2EE не
 * ослабляется — сообщения так и остаются недоставленными, а не отправленными
 * в открытом виде.
 */
class KeyExchangeChannel(
    private val contactStore: ContactStore,
    private val peerDeviceStore: PeerDeviceStore,
    private val sessionManager: SignalSessionManager,
    private val pskManager: PskManager,
    private val relayGateway: RelayGateway,
    private val scope: CoroutineScope,
    /** device id ЭТОГО устройства — уходит в конверт как `senderDevice` (§6). */
    private val localDeviceIdProvider: () -> Int = { 1 },
    /** Разблокирован ли аккаунт и доступен ли слой B (иначе цикл простаивает). */
    private val enabledProvider: () -> Boolean = { true },
    private val json: Json = WireJson,
) {
    /**
     * Ключи контакта обновились — слой B для него только что стал возможен.
     * Задаётся из DI: перечитывает чаты (индикатор E2EE) и повторяет отправку
     * сообщений, которые не ушли из-за отсутствия ключей.
     */
    var onKeysReceived: (suspend (String) -> Unit)? = null

    /**
     * Мой ПУБЛИЧНЫЙ бандл на время процесса. Кэш обязателен: каждый вызов
     * [SignalSessionManager.publishBundleJson] генерирует НОВЫЙ комплект prekeys
     * и дописывает приватные части в состояние (`publish_prekeys` в
     * `signal.rs`), поэтому анонс из периодического цикла без кэша раздувал бы
     * blob состояния. Один опубликованный бандл рассылается всем, кому нужен.
     */
    @Volatile
    private var myKeys: MyKeys? = null

    @Volatile
    private var sweeper: Job? = null

    /**
     * Когда контакту последний раз уходил анонс и через сколько можно следующий.
     * Пока собеседник офлайн, конверты копятся в его ящике, поэтому цикл добора
     * не долбит его каждые [SWEEP_INTERVAL_MS], а расширяет паузу вдвое до
     * [MAX_BACKOFF_MS]. Явные поводы (обмен только что состоялся, кнопка повтора,
     * ответ на запрос) идут вне очереди — см. `force` в [announce].
     */
    private val backoff = ConcurrentHashMap<String, Backoff>()

    private data class MyKeys(val identityB64: String, val bundleJson: String)

    private data class Backoff(val lastMs: Long, val waitMs: Long)

    /**
     * Периодический добор ключей: пока есть контакты с PSK, но без бандла слоя B,
     * рассылает им [TYPE_REQUEST]. Идемпотентно; цикл сам простаивает, когда
     * добирать нечего или нет Tor/релея.
     */
    @Synchronized
    fun start() {
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (isActive) {
                runCatching { sweep() }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    /** Останавливает цикл добора и забывает опубликованный бандл (блокировка). */
    @Synchronized
    fun stop() {
        sweeper?.cancel()
        sweeper = null
        myKeys = null
        backoff.clear()
    }

    /**
     * Один проход добора: незаблокированным контактам без ключей слоя B отправляет
     * запрос (с учётом персонального [backoff]). Возвращает число контактов,
     * которым ключи ещё нужны.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        if (!enabledProvider()) return@withContext 0
        if (relayGateway.clientOrNull() == null) return@withContext 0
        val needy = contactStore.all().filter { it.needsPeerKeys() }
        if (needy.isNotEmpty()) {
            DiagnosticLog.event(
                DIAG_TAG, "цикл добора ключей",
                "нуждаются" to needy.size,
                "контакты" to needy.joinToString(",") { DiagnosticLog.shortId(it.contact.id) },
            )
        }
        for (contact in needy) {
            announce(contact, request = true)
        }
        needy.size
    }

    /**
     * Отправляет контакту МОИ публичные ключи слоя B под слоем A. [request] —
     * попросить в ответ его ключи (сходится за один раунд, кто бы ни начал).
     * [force] — игнорировать [backoff] (явный повод: только что состоялся обмен,
     * пользователь нажал «повторить», отвечаем на запрос).
     * `true`, если конверт лёг хотя бы в один ящик контакта.
     */
    suspend fun announce(contact: StoredContact, request: Boolean, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val id = DiagnosticLog.shortId(contact.contact.id)
            if (!sessionManager.isAvailable) {
                DiagnosticLog.w(DIAG_TAG) { "анонс отменён: слой B недоступен в сборке, контакт=$id" }
                return@withContext false
            }
            if (!force && !claimAttempt(contact.contact.id)) {
                DiagnosticLog.d(DIAG_TAG) { "анонс отложен по backoff, контакт=$id" }
                return@withContext false
            }
            // Слой A обязателен: без PSK нечем шифровать и не из чего вывести
            // адрес ящика — по сети такой обмен не пойдёт (и не должен).
            val cascade = pskManager.cipherFor(contact.contact.id) ?: run {
                DiagnosticLog.w(DIAG_TAG) { "анонс отменён: нет PSK контакта, контакт=$id" }
                return@withContext false
            }
            val keys = myKeys() ?: run {
                DiagnosticLog.w(DIAG_TAG) { "анонс отменён: не удалось опубликовать свои ключи, контакт=$id" }
                return@withContext false
            }
            val payload = KeyxPayload(
                type = if (request) TYPE_REQUEST else TYPE_ANNOUNCE,
                deviceId = localDeviceIdProvider(),
                identityB64 = keys.identityB64,
                bundleJson = keys.bundleJson,
                atMs = System.currentTimeMillis(),
            )
            val inner = cascade.encrypt(
                json.encodeToString(KeyxPayload.serializer(), payload).toByteArray(Charsets.UTF_8),
            )
            val wire = json.encodeToString(
                KeyxWireEnvelope.serializer(),
                KeyxWireEnvelope(
                    ciphertextB64 = Base64.encodeToString(inner, Base64.NO_WRAP),
                    senderDevice = localDeviceIdProvider(),
                ),
            ).toByteArray(Charsets.UTF_8)
            // Слой A общий на переписку, поэтому per-device шифртекст здесь не
            // нужен — один и тот же конверт во все известные ящики контакта.
            val envelopes = contact.activeDevices().associate { it.deviceId to wire }
            val sent = relayGateway.postToPeerDevices(contact, envelopes)
            DiagnosticLog.event(
                DIAG_TAG, "анонс своих ключей отправлен",
                "контакт" to id,
                "тип" to payload.type,
                "своёУстройство" to payload.deviceId,
                "устройствАдресатов" to envelopes.size,
                "доставлено" to sent,
                "конвертБайт" to wire.size,
                "отпечатокБандла" to DiagnosticLog.fp(keys.bundleJson),
            )
            sent > 0
        }

    /** Как [announce], но по id контакта (после личного обмена/кнопки повтора). */
    suspend fun announceTo(contactId: String, request: Boolean = true, force: Boolean = true): Boolean {
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: return false
        return announce(contact, request, force)
    }

    /**
     * Пускать ли анонс контакту сейчас: `true` — пауза выдержана (окно сдвигается
     * и удваивается до [MAX_BACKOFF_MS]), `false` — ещё рано. Первый анонс всегда
     * проходит.
     */
    private fun claimAttempt(contactId: String): Boolean {
        val now = System.currentTimeMillis()
        val current = backoff[contactId]
        if (current != null && now - current.lastMs < current.waitMs) return false
        val next = (current?.waitMs ?: MIN_BACKOFF_MS).let { (it * 2).coerceAtMost(MAX_BACKOFF_MS) }
        backoff[contactId] = Backoff(lastMs = now, waitMs = if (current == null) MIN_BACKOFF_MS else next)
        return true
    }

    /**
     * Принимает конверт keyx-канала: снимает слой A, сохраняет публичные ключи
     * устройства-отправителя и, если это был запрос, отвечает своими ключами.
     *
     * АНТИ-MITM. Если identity-ключ контакта уже закреплён лично (пришёл в QR/NFC
     * при обмене), присланный бандл принимается ТОЛЬКО когда его `identity_key`
     * совпадает с закреплённым — подменить ключи слоя B нельзя даже владельцу
     * PSK-ящика. Если закреплённого ключа нет (обмен со сборкой без слоя B),
     * доверяем самому каналу: писать в этот ящик может только владелец PSK.
     */
    suspend fun onWire(contact: StoredContact, wireJson: String) = withContext(Dispatchers.IO) {
        val id = DiagnosticLog.shortId(contact.contact.id)
        DiagnosticLog.event(DIAG_TAG, "принят конверт keyx", "контакт" to id, "байт" to wireJson.length)
        val wire = json.decodeFromString(KeyxWireEnvelope.serializer(), wireJson)
        val cascade = pskManager.cipherFor(contact.contact.id) ?: run {
            DiagnosticLog.e(DIAG_TAG) { "конверт keyx не расшифровать: нет PSK, контакт=$id" }
            error("Нет PSK для расшифровки keyx-канала")
        }
        val plaintext = try {
            cascade.decrypt(Base64.decode(wire.ciphertextB64, Base64.NO_WRAP))
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "слой A не снялся с конверта keyx (PSK не совпал?), контакт=$id" }
            throw e
        }
        val payload = json.decodeFromString(KeyxPayload.serializer(), String(plaintext, Charsets.UTF_8))
        DiagnosticLog.event(
            DIAG_TAG, "разобран keyx",
            "контакт" to id,
            "тип" to payload.type,
            "устройство" to payload.deviceId,
            "бандлБайт" to payload.bundleJson.length,
            "отпечатокБандла" to DiagnosticLog.fp(payload.bundleJson),
        )
        if (payload.bundleJson.isBlank()) {
            DiagnosticLog.w(DIAG_TAG) { "keyx без бандла — игнорируем, контакт=$id" }
            return@withContext
        }

        val senderDevice = payload.deviceId.takeIf { it in 1..DeviceSettings.MAX_DEVICE_ID }
            ?: wire.senderDevice.takeIf { it in 1..DeviceSettings.MAX_DEVICE_ID }
            ?: contact.remoteDevice
        val stored = storeKeys(contact, senderDevice, payload)

        // На запрос отвечаем своими ключами — без повторной просьбы, иначе
        // стороны обменивались бы ответами бесконечно. Контакт перечитываем:
        // [storeKeys] мог только что зарегистрировать новое устройство
        // собеседника, и ответ должен уйти и в его ящик тоже.
        if (payload.type == TYPE_REQUEST) {
            val refreshed = contactStore.get(contact.contact.id) ?: contact
            runCatching { announce(refreshed, request = false, force = true) }
        }
        if (stored) {
            DiagnosticLog.event(
                DIAG_TAG, "КЛЮЧИ СЛОЯ B СОХРАНЕНЫ",
                "контакт" to id,
                "устройство" to senderDevice,
            )
            backoff.remove(contact.contact.id)
            runCatching { onKeysReceived?.invoke(contact.contact.id) }
        } else {
            DiagnosticLog.w(DIAG_TAG) {
                "ключи ОТБРОШЕНЫ сверкой identity, контакт=$id устройство=$senderDevice"
            }
        }
    }

    /**
     * Сохраняет публичные ключи устройства контакта. Первичное устройство живёт в
     * колонках `contacts` (обратная совместимость), дополнительные — в
     * `peer_devices`. `true`, если ключи приняты (а не отброшены сверкой identity).
     */
    private fun storeKeys(contact: StoredContact, deviceId: Int, payload: KeyxPayload): Boolean {
        val isPrimary = deviceId == contact.remoteDevice
        val pinned = if (isPrimary) {
            contact.remoteIdentityB64
        } else {
            contact.devices.firstOrNull { it.deviceId == deviceId }?.identityB64
        }
        if (!PrekeyBundle.identityMatches(payload.bundleJson, pinned.orEmpty())) return false
        // Присланный identity сверяем с бандлом: расхождение внутри одного
        // конверта означает битую/поддельную нагрузку.
        if (payload.identityB64.isNotBlank() &&
            !PrekeyBundle.identityMatches(payload.bundleJson, payload.identityB64)
        ) {
            return false
        }

        if (isPrimary) {
            contactStore.setPeerKeys(
                id = contact.contact.id,
                identityB64 = payload.identityB64.takeIf { it.isNotBlank() },
                bundleJson = payload.bundleJson,
            )
        } else {
            val existing = contact.devices.firstOrNull { it.deviceId == deviceId }
            peerDeviceStore.upsert(
                PeerDevice(
                    contactId = contact.contact.id,
                    deviceId = deviceId,
                    identityB64 = payload.identityB64.takeIf { it.isNotBlank() }
                        ?: existing?.identityB64,
                    bundleJson = payload.bundleJson,
                    sessionReady = existing?.sessionReady ?: false,
                    verified = existing?.verified ?: false,
                    revoked = existing?.revoked ?: false,
                    addedAtMs = existing?.addedAtMs ?: System.currentTimeMillis(),
                ),
            )
        }
        return true
    }

    /** Мои публичные ключи слоя B, опубликованные один раз за сессию. */
    private fun myKeys(): MyKeys? {
        myKeys?.let { return it }
        return synchronized(this) {
            myKeys?.let { return@synchronized it }
            val identity = runCatching { sessionManager.identityKeyB64() }.getOrNull().orEmpty()
            val bundle = runCatching { sessionManager.publishBundleJson() }.getOrNull().orEmpty()
            if (identity.isBlank() || bundle.isBlank()) {
                null
            } else {
                MyKeys(identity, bundle).also { myKeys = it }
            }
        }
    }

    /** Нужен ли контакту добор ключей слоя B (нет бандла ни у одного устройства). */
    private fun StoredContact.needsPeerKeys(): Boolean =
        !contact.blocked && activeDevices().none { !it.bundleJson.isNullOrBlank() }

    /** Конверт keyx-канала: только слой A, никаких секретов наружу (§7.6). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class KeyxWireEnvelope(
        /** Base64 контейнера слоя A (внутри — [KeyxPayload]). */
        val ciphertextB64: String,
        /** device id устройства-отправителя (дубль [KeyxPayload.deviceId] снаружи). */
        val senderDevice: Int = 1,
        /** Маркер канала для демультиплексирования на приёме (opaque для сервера). */
        @EncodeDefault
        val channel: String = CHANNEL,
        val v: Int = 1,
    )

    /** Нагрузка keyx-канала — только ПУБЛИЧНЫЕ ключи слоя B (§0). */
    @Serializable
    private data class KeyxPayload(
        /** [TYPE_REQUEST] (вот мои, пришли свои) | [TYPE_ANNOUNCE] (вот мои). */
        val type: String,
        /** device id устройства, чьи ключи внутри (у каждого свой ratchet, MD-1). */
        val deviceId: Int,
        /** Публичный Signal identity-ключ устройства (base64). */
        val identityB64: String,
        /** Публичный prekey-бандл устройства для X3DH/PQXDH (JSON, §0). */
        val bundleJson: String,
        val atMs: Long = 0L,
        val v: Int = 1,
    )

    companion object {
        /** Тег записей обмена ключами слоя B в журнале диагностики. */
        private const val DIAG_TAG = "keyx"

        /** Маркер канала в конверте (см. [RelayReceiver] — демультиплексирование). */
        const val CHANNEL = WireChannel.KEYX

        /** «Вот мои публичные ключи, пришли свои» — начало раунда. */
        const val TYPE_REQUEST = "keyx-request"

        /** «Вот мои публичные ключи» — ответ на запрос (без повторной просьбы). */
        const val TYPE_ANNOUNCE = "keyx-announce"

        /** Как часто цикл добора переоценивает, кому нужны ключи. */
        private const val SWEEP_INTERVAL_MS = 20_000L

        /** Минимальная пауза между анонсами одному контакту. */
        private const val MIN_BACKOFF_MS = 20_000L

        /**
         * Потолок паузы: собеседник может быть офлайн сутками, а конверты копятся
         * в его ящике — расти интервалу дальше незачем.
         */
        private const val MAX_BACKOFF_MS = 15 * 60_000L
    }
}
