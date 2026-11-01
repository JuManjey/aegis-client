package com.aegis.messenger.messaging

import android.util.Base64
import com.aegis.core.common.call.CallControl
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.model.AttachmentKind
import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageCryptoInfo
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import com.aegis.core.common.model.VoiceMeta
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.core.common.wire.WireChannel
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.cascade.CascadeLayer
import com.aegis.data.storage.MessageStore
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.psk.PskManager
import com.aegis.network.tor.RelayTransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.UUID

/**
 * Конвейер отправки/приёма сообщения (P6, §14.4): слой A (каскад) → слой B
 * (Signal) → транспорт, §5/§6.1/§7.2. Транспорт задаёт режим сети: Tor ВКЛ —
 * SOCKS5 к onion-релею, Tor ВЫКЛ — HTTPS к тому же релею. Слои шифрования от
 * режима не зависят.
 *
 * ```
 *  контент ──▶ [фрейм WireContent: текст (+ ссылка на вложение §14.4)]
 *          ──▶ [Слой A: CascadeCipher на PSK контакта] ──▶ контейнер
 *          ──▶ [Слой B: Signal X3DH/PQXDH + Double Ratchet] ──▶ конверт
 *          ──▶ [Tor → onion-релей | HTTPS → релей] ──▶ сеть (best-effort)
 * ```
 *
 * Внутренний plaintext всегда начинается с 1-байтового фрейма [FRAME_CONTENT_V1],
 * за которым идёт JSON [WireContent]. Фрейм находится ВНУТРИ слоёв A+B — серверу
 * он не виден (zero-trust §7.6/§10.9). Для конвертов старого формата (без фрейма)
 * есть откат: их тело трактуется как «сырой» UTF-8 текст.
 *
 * ВЛОЖЕНИЯ (§14.4): сам файл шифруется каскадом слоя A на СЛУЧАЙНОМ 256-битном
 * ключе и грузится на релей отдельным блобом ([RelayGateway.uploadBlob]) под
 * опаковым [AttachmentRef.objectId]; ключ+id+метаданные едут внутри [WireContent]
 * тем же защищённым конвейером, что и текст. Байты файла на проводе — независимый
 * шифртекст, не связанный сервером ни с личностью, ни с перепиской.
 */
class MessagePipeline(
    private val sessionManager: SignalSessionManager,
    private val pskManager: PskManager,
    private val messageStore: MessageStore,
    private val relayGateway: RelayGateway,
    private val localDeviceIdProvider: () -> Int = { 1 },
    private val json: Json = WireJson,
) {
    private val secureRandom = SecureRandom()

    /**
     * Зеркалирование копий на СВОИ устройства (self-sync, §6). Задаётся из DI
     * ([SelfSyncManager]); `null` до линковки ⇒ обычное одноустройственное
     * поведение. Best-effort — сбой не влияет на доставку контакту.
     */
    var selfSyncMirror: (suspend (SelfSyncCopy) -> Unit)? = null

    /**
     * Ни для одного устройства контакта нет ключей слоя B (§6.2): доставлять
     * нечего. Хук запускает догоняющий обмен ключами поверх слоя A
     * ([KeyExchangeChannel]) — он уже установлен личным обменом PSK, поэтому
     * работает и без Signal-сессии. Задаётся из DI; best-effort.
     */
    var onMissingPeerKeys: (suspend (StoredContact) -> Unit)? = null

    /**
     * Служебный control-plaintext, ошибочно пришедший как сообщение (нет
     * `channel` во внешнем конверте). Уже расшифрован — повторно через
     * [ControlChannel.onWire] гонять нельзя (ratchet уже сдвинут).
     */
    var onMisroutedControl: (suspend (StoredContact, ControlMessage) -> Unit)? = null

    /**
     * Служебный сигналинг звонка, ошибочно пришедший как сообщение. Уже
     * расшифрован — повторно через слой сигналинга звонка не отдаём.
     */
    var onMisroutedCall: (suspend (StoredContact, CallControl) -> Unit)? = null

    /**
     * Копия сообщения для синхронизации МОИХ устройств (§6): уже расшифрованный
     * контент + метаданные. Идёт по self-sync mailbox под каскадом на секрете
     * аккаунта (недоступном контактам); Signal-состояние переписки НЕ передаётся
     * (MD-1) — другое устройство просто отражает запись в своём хранилище.
     */
    data class SelfSyncCopy(
        val contactId: String,
        val msgId: String,
        val outgoing: Boolean,
        val text: String,
        val timestampMs: Long,
        val attachment: AttachmentRef? = null,
        val crypto: MessageCryptoInfo? = null,
    )

    /** Итог fan-out: доставлено ли на релей и какие слои реально применились. */
    private data class DeliveryOutcome(
        val delivered: Boolean,
        val crypto: MessageCryptoInfo?,
    )

    /**
     * Шифрует и «отправляет» текстовое сообщение контакту. Возвращает запись
     * сообщения с итоговым статусом E2EE. Персистит в зашифрованное хранилище.
     */
    suspend fun send(contact: StoredContact, text: String): Message = withContext(Dispatchers.IO) {
        val pending = Message(
            id = UUID.randomUUID().toString(),
            contactId = contact.contact.id,
            direction = MessageDirection.OUTGOING,
            body = text,
            timestampMs = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        messageStore.insert(pending)

        val outcome = try {
            deliver(contact, WireContent(text = text), pending.id)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "отправка текста упала контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                    "msgId=${DiagnosticLog.fp(pending.id)}"
            }
            messageStore.updateStatus(pending.id, MessageStatus.FAILED)
            return@withContext pending.copy(status = MessageStatus.FAILED)
        }
        persistCrypto(pending.id, outcome.crypto)
        if (!outcome.delivered) {
            DiagnosticLog.w(DIAG_TAG) {
                "отправка текста не доставлена контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                    "msgId=${DiagnosticLog.fp(pending.id)}"
            }
            messageStore.updateStatus(pending.id, MessageStatus.FAILED)
            return@withContext pending.copy(status = MessageStatus.FAILED, crypto = outcome.crypto)
        }

        DiagnosticLog.event(
            DIAG_TAG, "текст отправлен",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "msgId" to DiagnosticLog.fp(pending.id),
            "слойA" to (outcome.crypto?.layerA ?: false),
            "слойB" to (outcome.crypto?.layerBType ?: ""),
        )
        messageStore.updateStatus(pending.id, MessageStatus.SENT)
        mirrorToMyDevices(
            contact.contact.id, pending.id, outgoing = true, text = text, ref = null,
            ts = pending.timestampMs, crypto = outcome.crypto,
        )
        pending.copy(status = MessageStatus.SENT, crypto = outcome.crypto)
    }

    /**
     * Повторяет доставку уже сохранённого исходящего ТЕКСТОВОГО сообщения — того
     * же самого (id сохраняется, §14.2), без новой записи в хранилище.
     *
     * Нужен, когда сообщение упало в `FAILED` из-за отсутствия ключей слоя B, а
     * потом они пришли по keyx-каналу (§6.2). Вложения повторяются отдельно
     * ([uploadAndSendAttachment]) — им нужны байты блоба из БД.
     */
    suspend fun resend(contact: StoredContact, message: Message): Boolean =
        withContext(Dispatchers.IO) {
            messageStore.updateStatus(message.id, MessageStatus.PENDING)
            val outcome = try {
                deliver(contact, WireContent(text = message.body), message.id)
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) {
                    "повтор отправки упал контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                        "msgId=${DiagnosticLog.fp(message.id)}"
                }
                DeliveryOutcome(false, null)
            }
            persistCrypto(message.id, outcome.crypto)
            DiagnosticLog.event(
                DIAG_TAG, "повтор отправки",
                "контакт" to DiagnosticLog.shortId(contact.contact.id),
                "msgId" to DiagnosticLog.fp(message.id),
                "ок" to outcome.delivered,
            )
            messageStore.updateStatus(
                message.id,
                if (outcome.delivered) MessageStatus.SENT else MessageStatus.FAILED,
            )
            if (outcome.delivered) {
                mirrorToMyDevices(
                    contact.contact.id, message.id, outgoing = true,
                    text = message.body, ref = null, ts = message.timestampMs,
                    crypto = outcome.crypto,
                )
            }
            outcome.delivered
        }

    /**
     * Фаза 1 отправки вложения (§14.4, быстрая, без сети): генерирует случайный
     * ключ+id блоба, сохраняет исходные байты в зашифрованную БД и вставляет
     * запись сообщения (PENDING). UI сразу показывает вложение как локальное;
     * загрузка/отправка — фаза 2 ([uploadAndSendAttachment]).
     */
    @Suppress("LongParameterList")
    suspend fun createOutgoingAttachment(
        contact: StoredContact,
        caption: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        kind: AttachmentKind,
        /**
         * Метаданные голосового сообщения (§14.4) или `null` для обычного
         * вложения. Едут внутри [AttachmentRef], то есть под слоями A+B.
         */
        voice: VoiceMeta? = null,
    ): Message = withContext(Dispatchers.IO) {
        val ref = AttachmentRef(
            objectId = randomBlobId(),
            keyB64 = Base64.encodeToString(randomKey(), Base64.NO_WRAP),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            kind = kind,
            voice = voice,
        )
        val message = Message(
            id = UUID.randomUUID().toString(),
            contactId = contact.contact.id,
            direction = MessageDirection.OUTGOING,
            body = caption,
            timestampMs = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
            attachment = ref,
            attachmentLocal = true,
        )
        messageStore.insert(message)
        messageStore.saveAttachmentBytes(message.id, bytes)
        message
    }

    /**
     * Фаза 2 отправки вложения (§14.4, медленная, через Tor): шифрует блоб
     * каскадом слоя A на ключе из [Message.attachment], грузит его на релей,
     * затем шлёт ссылку тем же конвейером слой A(PSK)→B(Signal). Обновляет статус
     * (SENT/FAILED). Возвращает обновлённую запись.
     */
    suspend fun uploadAndSendAttachment(
        contact: StoredContact,
        message: Message,
        bytes: ByteArray,
    ): Message = withContext(Dispatchers.IO) {
        val ref = message.attachment ?: return@withContext message
        val uploaded = try {
            val keyBytes = Base64.decode(ref.keyB64, Base64.NO_WRAP)
            val ciphertext = try {
                CryptoModule.createCascadeCipher(keyBytes).encrypt(bytes)
            } finally {
                keyBytes.fill(0)
            }
            relayGateway.uploadBlob(ref.objectId, ciphertext)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "загрузка вложения упала контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                    "msgId=${DiagnosticLog.fp(message.id)} байт=${bytes.size}"
            }
            false
        }
        DiagnosticLog.event(
            DIAG_TAG, "вложение: загрузка блоба",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "msgId" to DiagnosticLog.fp(message.id),
            "байт" to bytes.size,
            "ок" to uploaded,
        )
        if (!uploaded) {
            // Блоб не загрузился — ссылку слать бессмысленно (получатель не скачает).
            // Байты остаются локально (пользователь видит своё вложение).
            messageStore.updateStatus(message.id, MessageStatus.FAILED)
            return@withContext message.copy(status = MessageStatus.FAILED)
        }

        val outcome = try {
            deliver(contact, WireContent(text = message.body, attachment = ref), message.id)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "отправка ссылки на вложение упала контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                    "msgId=${DiagnosticLog.fp(message.id)}"
            }
            DeliveryOutcome(false, null)
        }
        persistCrypto(message.id, outcome.crypto)
        DiagnosticLog.event(
            DIAG_TAG, "вложение: ссылка",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "msgId" to DiagnosticLog.fp(message.id),
            "ок" to outcome.delivered,
        )
        val status = if (outcome.delivered) MessageStatus.SENT else MessageStatus.FAILED
        messageStore.updateStatus(message.id, status)
        if (outcome.delivered) {
            mirrorToMyDevices(
                contact.contact.id, message.id, outgoing = true,
                text = message.body, ref = ref, ts = message.timestampMs,
                crypto = outcome.crypto,
            )
        }
        message.copy(status = status, crypto = outcome.crypto ?: message.crypto)
    }

    /**
     * Принимает входящий конверт (из релея/mailbox), расшифровывает слой B → слой A
     * → [WireContent] и сохраняет сообщение. Байты вложения НЕ качаются здесь —
     * это делает [downloadAttachment] (авто или по тапу, §14.4). Возвращает запись
     * или `null`, если это служебный control/звонок без маркера `channel` — его
     * отдаём в [onMisroutedControl]/[onMisroutedCall], в историю не пишем.
     */
    suspend fun receive(
        contact: StoredContact,
        wireJson: String,
        transport: RelayTransportKind? = null,
    ): Message? =
        withContext(Dispatchers.IO) {
            val wire = json.decodeFromString(WireEnvelope.serializer(), wireJson)
            pskManager.requireLayerA(contact.contact.id, wire.layerA)
            val envelope = Base64.decode(wire.ciphertextB64, Base64.NO_WRAP)
            // Устройство-отправитель берём из конверта (мультидевайс §6): у каждого
            // устройства контакта свой ratchet (MD-1). Откат на первичное (1) для
            // конвертов старого формата (senderDevice отсутствует ⇒ 0 → 1).
            val senderDevice = wire.senderDevice.takeIf { it in 1..255 } ?: contact.remoteDevice
            val inner = sessionManager.decryptFrom(
                contact.contact.id, senderDevice, envelope, wire.type, contact.identityFor(senderDevice),
            )
            var plaintext = if (wire.layerA) {
                val cascade = pskManager.cipherFor(contact.contact.id)
                    ?: error("Нет PSK для расшифровки слоя A")
                cascade.decrypt(inner)
            } else {
                inner
            }
            val pskSealUsed = openPskSessionIfPresent(contact, plaintext)?.also { plaintext = it } != null
            if (divertMisroutedSignaling(contact, plaintext)) return@withContext null
            val content = decodeContent(plaintext)
            val crypto = recordedCrypto(
                layerA = wire.layerA,
                layerBType = wire.type,
                hasAttachment = content.attachment != null,
                pskSessionSeal = pskSealUsed,
                transport = transport,
            )
            val message = Message(
                // Тот же id, что у отправителя (для отчётов прочтения / удаления у
                // всех, §14.2/§7.3); откат на локальный UUID для старых конвертов.
                id = wire.msgId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                contactId = contact.contact.id,
                direction = MessageDirection.INCOMING,
                body = content.text,
                timestampMs = System.currentTimeMillis(),
                status = MessageStatus.RECEIVED,
                attachment = content.attachment,
                attachmentLocal = false,
                crypto = crypto,
            )
            DiagnosticLog.event(
                DIAG_TAG, "входящее расшифровано",
                "контакт" to DiagnosticLog.shortId(contact.contact.id),
                "msgId" to DiagnosticLog.fp(message.id),
                "слойA" to wire.layerA,
                "тип" to wire.type,
                "устройство" to senderDevice,
                "вложение" to (content.attachment != null),
                "байт" to plaintext.size,
            )
            messageStore.insert(message)
            mirrorToMyDevices(
                contact.contact.id, message.id, outgoing = false,
                text = content.text, ref = content.attachment, ts = message.timestampMs,
                crypto = crypto,
            )
            message
        }

    /**
     * Отражает копию из self-sync (§6) в локальное хранилище на ДРУГОМ моём
     * устройстве. Крипто здесь уже снято ([SelfSyncManager]); просто идемпотентно
     * вставляем запись (тот же msgId ⇒ без дублей, ON CONFLICT в [MessageStore]).
     */
    suspend fun applySelfSyncCopy(copy: SelfSyncCopy): Message? = withContext(Dispatchers.IO) {
        if (WireChannel.isStoredLeak(copy.text, hasAttachment = copy.attachment != null)) {
            DiagnosticLog.w(DIAG_TAG) {
                "self-sync копия отброшена: служебный JSON контакт=${DiagnosticLog.shortId(copy.contactId)}"
            }
            return@withContext null
        }
        val message = Message(
            id = copy.msgId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            contactId = copy.contactId,
            direction = if (copy.outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            body = copy.text,
            timestampMs = copy.timestampMs,
            status = if (copy.outgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
            attachment = copy.attachment,
            attachmentLocal = false,
            crypto = copy.crypto,
        )
        messageStore.insert(message)
        message
    }

    /**
     * Скачивает и расшифровывает блоб вложения входящего сообщения (§14.4),
     * сохраняет байты в зашифрованную БД и помечает как локально доступное.
     * `true` при успехе; `false`, если блоба нет/повреждён или сеть недоступна.
     * После успешного приёма блоб на релее удаляется (минимизация хранения §7.3).
     */
    suspend fun downloadAttachment(message: Message): Boolean = withContext(Dispatchers.IO) {
        val ref = message.attachment ?: return@withContext false
        if (ref.objectId.isBlank()) return@withContext false
        val ciphertext = relayGateway.downloadBlob(ref.objectId) ?: run {
            DiagnosticLog.w(DIAG_TAG) {
                "вложение не скачалось msgId=${DiagnosticLog.fp(message.id)} " +
                    "id=${DiagnosticLog.fp(ref.objectId)}"
            }
            return@withContext false
        }
        val plain = try {
            val keyBytes = Base64.decode(ref.keyB64, Base64.NO_WRAP)
            try {
                CryptoModule.createCascadeCipher(keyBytes).decrypt(ciphertext)
            } finally {
                keyBytes.fill(0)
            }
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "вложение не расшифровано msgId=${DiagnosticLog.fp(message.id)} " +
                    "шифрБайт=${ciphertext.size}"
            }
            return@withContext false
        }
        messageStore.saveAttachmentBytes(message.id, plain)
        relayGateway.deleteBlob(ref.objectId)
        DiagnosticLog.event(
            DIAG_TAG, "вложение скачано",
            "msgId" to DiagnosticLog.fp(message.id),
            "байт" to plain.size,
        )
        true
    }

    // --- Внутреннее ----------------------------------------------------------

    /** Кодирует [WireContent] во внутренний фреймированный plaintext (§14.4). */
    private fun encodeContent(content: WireContent): ByteArray {
        val body = json.encodeToString(WireContent.serializer(), content).toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 1).also {
            it[0] = FRAME_CONTENT_V1
            System.arraycopy(body, 0, it, 1, body.size)
        }
    }

    /** Разбирает внутренний plaintext в [WireContent] (с откатом на raw-текст). */
    private fun decodeContent(plaintext: ByteArray): WireContent {
        if (plaintext.isNotEmpty() && plaintext[0] == FRAME_CONTENT_V1) {
            val body = String(plaintext, 1, plaintext.size - 1, Charsets.UTF_8)
            runCatching { json.decodeFromString(WireContent.serializer(), body) }
                .getOrNull()?.let { return it }
        }
        // Старый формат / нераспознанный фрейм: считаем содержимое обычным текстом.
        return WireContent(text = String(plaintext, Charsets.UTF_8))
    }

    /**
     * Unframed JSON control/звонка (нет кадра [FRAME_CONTENT_V1]) — не сообщение.
     * `true`, если отвели в служебный обработчик и в чат писать нельзя.
     */
    private suspend fun divertMisroutedSignaling(contact: StoredContact, plaintext: ByteArray): Boolean {
        if (plaintext.isNotEmpty() && plaintext[0] == FRAME_CONTENT_V1) return false
        val text = String(plaintext, Charsets.UTF_8)
        when (WireChannel.classifyPlaintext(text)) {
            WireChannel.Kind.CONTROL -> {
                val msg = runCatching { json.decodeFromString(ControlMessage.serializer(), text) }.getOrNull()
                DiagnosticLog.event(
                    DIAG_TAG, "служебное отведено в control",
                    "контакт" to DiagnosticLog.shortId(contact.contact.id),
                    "тип" to (msg?.type ?: "?"),
                )
                if (msg != null) onMisroutedControl?.invoke(contact, msg)
                return true
            }
            WireChannel.Kind.CALL -> {
                val ctrl = runCatching { json.decodeFromString(CallControl.serializer(), text) }.getOrNull()
                DiagnosticLog.event(
                    DIAG_TAG, "служебное отведено в звонок",
                    "контакт" to DiagnosticLog.shortId(contact.contact.id),
                    "тип" to (ctrl?.type ?: "?"),
                )
                if (ctrl != null) onMisroutedCall?.invoke(contact, ctrl)
                return true
            }
            null -> return false
        }
    }

    /**
     * Fan-out доставки (мультидевайс §6): слой A (каскад на PSK) применяется ОДИН
     * раз (общий на переписку), затем для КАЖДОГО активного устройства контакта —
     * своя Signal-сессия `(myDevice) → (contact, deviceId)` и свой шифртекст
     * (разные ratchet, MD-1), который постится в per-device подканал v2 адресата.
     * `true`, если удалось собрать конверт хотя бы для одного устройства
     * (иначе доставлять нечего). До линковки контакта — ровно одно устройство,
     * т.е. прежнее поведение.
     */
    private suspend fun deliver(contact: StoredContact, content: WireContent, msgId: String): DeliveryOutcome {
        val diagId = DiagnosticLog.shortId(contact.contact.id)
        val framed = encodeContent(content)
        val cascade = pskManager.cipherFor(contact.contact.id)
        val layerAUsed = cascade != null
        val sealed = sealPskSession(contact, framed)
        val pskSealUsed = sealed !== framed
        val innerPayload = cascade?.encrypt(sealed) ?: sealed
        val myDevice = localDeviceIdProvider()
        DiagnosticLog.event(
            DIAG_TAG, "доставка: начало",
            "контакт" to diagId,
            "msgId" to DiagnosticLog.fp(msgId),
            "слойA" to layerAUsed,
            "вложение" to (content.attachment != null),
            "контентБайт" to framed.size,
            "устройств" to contact.activeDevices().size,
        )
        if (!layerAUsed) {
            DiagnosticLog.w(DIAG_TAG) { "нет PSK — слой A не применён, контакт=$diagId" }
        }

        val envelopes = HashMap<Int, ByteArray>()
        var missingKeys = false
        var layerBType = ""
        for (device in contact.activeDevices()) {
            val result = try {
                sessionManager.encryptTo(
                    contact.contact.id, device.deviceId, device.bundleJson, innerPayload, device.identityB64,
                )
            } catch (_: MissingPeerKeysException) {
                DiagnosticLog.w(DIAG_TAG) {
                    "нет ключей слоя B у устройства=${device.deviceId}, контакт=$diagId " +
                        "бандл=${DiagnosticLog.presence(device.bundleJson)}"
                }
                missingKeys = true
                continue
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) {
                    "encryptTo упал устройство=${device.deviceId} контакт=$diagId"
                }
                continue
            }
            if (layerBType.isBlank()) layerBType = result.type
            val wire = WireEnvelope(
                type = result.type,
                layerA = layerAUsed,
                ciphertextB64 = Base64.encodeToString(result.ciphertext, Base64.NO_WRAP),
                msgId = msgId,
                senderDevice = myDevice,
            )
            envelopes[device.deviceId] = json.encodeToString(WireEnvelope.serializer(), wire)
                .toByteArray(Charsets.UTF_8)
        }
        if (missingKeys) {
            DiagnosticLog.w(DIAG_TAG) { "запускаю догон ключей по keyx, контакт=$diagId" }
            runCatching { onMissingPeerKeys?.invoke(contact) }
        }
        val crypto = if (layerBType.isNotBlank()) {
            recordedCrypto(layerAUsed, layerBType, content.attachment != null, pskSealUsed)
        } else {
            null
        }
        if (envelopes.isEmpty()) {
            DiagnosticLog.e(DIAG_TAG) { "доставка СОРВАНА: ни одного конверта, контакт=$diagId" }
            return DeliveryOutcome(false, crypto)
        }
        // Статус сообщения обязан отражать ФАКТ дропа, а не факт попытки. Раньше
        // результат fan-out отбрасывался и доставка всегда объявлялась успешной:
        // при недоступном Tor/релее или отказе сервера сообщение показывалось как
        // отправленное, хотя не попало ни в один ящик и собеседник его не получал.
        // Хотя бы одно устройство ⇒ доставлено (у каждого устройства свой ящик,
        // остальные догонят своим дропом при повторе).
        val fanout = relayGateway.deliverToPeerDevices(contact, envelopes)
        val sentCrypto = crypto?.copy(transport = fanout.transport?.label.orEmpty())
        if (fanout.sent == 0) {
            DiagnosticLog.w(DIAG_TAG) {
                "доставка не состоялась: ни один дроп не принят, конвертов=${envelopes.size} " +
                    "контакт=$diagId msgId=${DiagnosticLog.fp(msgId)}"
            }
            return DeliveryOutcome(false, sentCrypto)
        }
        return DeliveryOutcome(true, sentCrypto)
    }

    /** Best-effort копия сообщения на СВОИ устройства (self-sync §6). */
    private suspend fun mirrorToMyDevices(
        contactId: String,
        msgId: String,
        outgoing: Boolean,
        text: String,
        ref: AttachmentRef?,
        ts: Long,
        crypto: MessageCryptoInfo? = null,
    ) {
        if (WireChannel.isStoredLeak(text, hasAttachment = ref != null)) return
        val mirror = selfSyncMirror ?: return
        runCatching {
            mirror(SelfSyncCopy(contactId, msgId, outgoing, text, ts, ref, crypto))
        }
    }

    private fun persistCrypto(id: String, crypto: MessageCryptoInfo?) {
        if (crypto != null) messageStore.updateCrypto(id, crypto)
    }

    /**
     * Сессионная обёртка PSK: AEAD на ключе из PSK + identity, внутри слоя A.
     * Без PSK/identity не применяется (тогда остаётся только Signal).
     */
    private suspend fun sealPskSession(contact: StoredContact, framed: ByteArray): ByteArray {
        val identity = contact.remoteIdentityB64 ?: return framed
        val psk = pskManager.pskBytesFor(contact.contact.id) ?: return framed
        try {
            return sessionManager.pskSessionSeal(identity, psk, framed)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "PSK-seal сорван контакт=${DiagnosticLog.shortId(contact.contact.id)} — отправка прервана"
            }
            throw e
        } finally {
            psk.fill(0)
        }
    }

    /**
     * Снимает сессионную обёртку, если она есть. `null` — метки не было
     * (legacy). При метке и провале тега бросает — fail-closed.
     */
    private suspend fun openPskSessionIfPresent(contact: StoredContact, plaintext: ByteArray): ByteArray? {
        if (plaintext.size < 4) return null
        if (plaintext[0] != 'A'.code.toByte() ||
            plaintext[1] != '1'.code.toByte() ||
            plaintext[2] != 'S'.code.toByte() ||
            plaintext[3] != '1'.code.toByte()
        ) {
            return null
        }
        val identity = contact.remoteIdentityB64
            ?: error("Нет identity для снятия PSK-слоя сессии")
        val psk = pskManager.pskBytesFor(contact.contact.id)
            ?: error("Нет PSK для снятия PSK-слоя сессии")
        return try {
            sessionManager.pskSessionOpen(identity, psk, plaintext)
        } finally {
            psk.fill(0)
        }
    }

    /**
     * След фактически применённых алгоритмов. Список каскада берётся из
     * [CascadeLayer.ENCRYPT_ORDER] — это единственная реализация слоя A; запись
     * делается только после успешного encrypt/decrypt. [transport] — тот, которым
     * конверт реально ушёл или пришёл (у исходящих проставляется после дропа);
     * `null` — неизвестен, строка остаётся пустой и в UI не показывается.
     */
    private fun recordedCrypto(
        layerA: Boolean,
        layerBType: String,
        hasAttachment: Boolean,
        pskSessionSeal: Boolean = false,
        transport: RelayTransportKind? = null,
    ): MessageCryptoInfo = MessageCryptoInfo(
        layerA = layerA,
        layerBType = layerBType,
        layerBProtocol = LAYER_B_PROTOCOL,
        cascadeAlgorithms = if (layerA) {
            CascadeLayer.ENCRYPT_ORDER.map { it.displayName }
        } else {
            emptyList()
        },
        attachmentEncrypted = hasAttachment,
        transport = transport?.label.orEmpty(),
        pskSessionSeal = pskSessionSeal,
    )

    /** Случайный 256-битный ключ каскада для блоба вложения (ОС-ГСЧ). */
    private fun randomKey(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

    /**
     * Случайный опаковый id блоба (256 бит → 43 симв. url-safe base64 без
     * паддинга) — совпадает с валидатором релея (8..64 симв. `[A-Za-z0-9-_]`).
     */
    private fun randomBlobId(): String {
        val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Внутренний контент сообщения (внутри слоёв A+B): текст и/или вложение (§14.4). */
    @Serializable
    private data class WireContent(
        val text: String = "",
        val attachment: AttachmentRef? = null,
        val v: Int = 1,
    )

    /** Двойной конверт, уходящий по сети: только шифртекст, без секретов (§6.1). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class WireEnvelope(
        /** Тип конверта Signal: "PreKey" (установление сессии) или "Whisper". */
        val type: String,
        /** Присутствует ли внутри слой A (каскад на PSK). */
        val layerA: Boolean,
        /** Base64 конверта Signal (внутри — контейнер слоя A либо plaintext). */
        val ciphertextB64: String,
        /** Общий id сообщения (§14.2/§7.3); "" для конвертов старого формата. */
        val msgId: String = "",
        /**
         * device id УСТРОЙСТВА-отправителя (мультидевайс §6): получатель выбирает
         * по нему нужный ratchet ([SignalSessionManager.decryptFrom]). 0 = старый
         * формат ⇒ откат на первичное устройство контакта.
         */
        val senderDevice: Int = 0,
        @EncodeDefault
        val channel: String = WireChannel.MESSAGE,
        val v: Int = 1,
    )

    private companion object {
        const val DIAG_TAG = "pipeline"
        /** Маркер фрейма внутреннего контента v1 (§14.4). Внутри шифра — серверу не виден. */
        const val FRAME_CONTENT_V1: Byte = 0x01
        const val LAYER_B_PROTOCOL: String = "Signal Protocol (X3DH/PQXDH + Double Ratchet)"
    }
}
