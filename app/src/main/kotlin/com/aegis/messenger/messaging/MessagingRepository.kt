package com.aegis.messenger.messaging

import com.aegis.core.common.AegisConstants
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.model.AttachmentKind
import com.aegis.core.common.model.ChatSummary
import com.aegis.core.common.model.ContactCard
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import com.aegis.core.common.model.VoiceMeta
import com.aegis.core.common.psk.PskExchangePayload
import com.aegis.core.common.wire.WireChannel
import com.aegis.data.storage.AegisDatabase
import com.aegis.data.storage.StoredContact
import com.aegis.core.common.model.Contact
import com.aegis.network.tor.RelayTransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Репозиторий переписки (P6): связывает зашифрованные хранилища (SQLCipher) с UI.
 *
 * Держит наблюдаемые срезы: список чатов ([conversations]) и сообщения активного
 * чата ([activeMessages]). Все обращения к БД идут на IO-диспетчере. Тела
 * сообщений и контакты берутся уже расшифрованными из SQLCipher (§6.3) — в
 * открытом виде на диск не попадают.
 */
class MessagingRepository(
    private val database: AegisDatabase,
    private val pipeline: MessagePipeline,
    /**
     * Эффективный срок хранения в днях (§7.3). Перед каждым чтением из БД
     * применяется граница «сейчас − N дней», поэтому просроченные сообщения не
     * попадают в UI даже до срабатывания фонового [RetentionEnforcer].
     */
    private val retentionDaysProvider: () -> Int = { AegisConstants.AUTO_DELETE_MAX_DAYS },
    /**
     * Разрешена ли автозагрузка входящих медиа (§14.4/§14.2). Если да — небольшие
     * (≤ [AegisConstants.ATTACHMENT_AUTO_DOWNLOAD_MAX_BYTES]) картинки/видео
     * качаются сразу при приёме; иначе — только по тапу пользователя.
     */
    private val autoDownloadProvider: () -> Boolean = { false },
) {
    private val contactStore get() = database.contactStore
    private val messageStore get() = database.messageStore

    private val _conversations = MutableStateFlow<List<ChatSummary>>(emptyList())
    val conversations: StateFlow<List<ChatSummary>> = _conversations.asStateFlow()

    /**
     * Карточки контактов для раздела «Контакты» (§14.1): поиск, подробности,
     * удаление. Обновляется тем же проходом, что и [conversations], поэтому оба
     * среза всегда согласованы между собой и стоят один запрос к БД.
     */
    private val _contacts = MutableStateFlow<List<ContactCard>>(emptyList())
    val contacts: StateFlow<List<ContactCard>> = _contacts.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<Message>>(emptyList())
    val activeMessages: StateFlow<List<Message>> = _activeMessages.asStateFlow()

    @Volatile
    private var activeContactId: String? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * Перечитать срезы для UI из хранилища контактов + последних сообщений:
     * список чатов ([conversations]) и карточки контактов ([contacts]).
     *
     * Оба собираются ОДНИМ проходом по контактам и одним чтением сообщений на
     * контакт. Раздельные проходы означали бы повторный `SELECT` и повторную
     * фильтрацию служебных конвертов на каждый контакт — при десятках чатов
     * это заметная задержка на каждом входящем сообщении, а срезы ещё и могли
     * бы разъехаться между собой.
     */
    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        purgeExpired()
        val stored = contactStore.all()
        val nowMs = System.currentTimeMillis()
        val summaries = ArrayList<ChatSummary>(stored.size)
        val cards = ArrayList<ContactCard>(stored.size)
        for (entry in stored) {
            val contact = entry.contact
            val messages = visibleMessages(contact.id)
            val last = messages.lastOrNull()
            val e2eeActive = entry.sessionReady || entry.bundleJson != null
            summaries += ChatSummary(
                id = contact.id,
                displayName = contact.displayLabel(),
                nickname = contact.nickname,
                lastMessagePreview = last?.body ?: "Нет сообщений",
                timestampLabel = last?.let { timeFormat.format(Date(it.timestampMs)) } ?: "",
                unreadCount = messages.count {
                    it.direction == MessageDirection.INCOMING && it.status == MessageStatus.RECEIVED
                },
                verified = contact.verified,
                e2eeActive = e2eeActive,
                blocked = contact.blocked,
                muted = contact.isMutedNow(nowMs),
            )
            cards += ContactCard(
                id = contact.id,
                displayName = contact.displayLabel(),
                nickname = contact.nickname,
                localName = contact.localName,
                fingerprint = contact.fingerprint,
                verified = contact.verified,
                blocked = contact.blocked,
                muted = contact.isMutedNow(nowMs),
                trust = contact.trust,
                e2eeActive = e2eeActive,
                deviceCount = entry.activeDevices().size,
            )
        }
        _conversations.value = summaries
        _contacts.value = cards
    }

    /**
     * Забывает всё, что было прочитано из БД: списки чатов и контактов, открытый
     * чат и его сообщения. Зовётся при экстренной очистке (§7.4) ПОСЛЕ стирания
     * БД — без этого экран нового аккаунта показал бы переписку прежнего прямо
     * из памяти, до первого перечитывания.
     */
    fun clearCache() {
        activeContactId = null
        _activeMessages.value = emptyList()
        _conversations.value = emptyList()
        _contacts.value = emptyList()
    }

    /** Открыть чат с контактом: сделать активным и загрузить его сообщения. */
    suspend fun openConversation(contactId: String): StoredContact? = withContext(Dispatchers.IO) {
        activeContactId = contactId
        purgeExpired()
        _activeMessages.value = visibleMessages(contactId)
        contactStore.get(contactId)
    }

    /** Отправить сообщение в активный чат (слой A→B→Tor). */
    suspend fun sendMessage(contactId: String, text: String) {
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: run {
            DiagnosticLog.w(DIAG_TAG) { "отправка: контакта нет ${DiagnosticLog.shortId(contactId)}" }
            return
        }
        // Чёрный список (§14.2): заблокированному контакту не пишем.
        if (contact.contact.blocked) {
            DiagnosticLog.w(DIAG_TAG) { "отправка: контакт заблокирован ${DiagnosticLog.shortId(contactId)}" }
            return
        }
        DiagnosticLog.event(
            DIAG_TAG, "отправка текста",
            "контакт" to DiagnosticLog.shortId(contactId),
            "символов" to text.length,
            "бандлЕсть" to !contact.bundleJson.isNullOrBlank(),
            "сессия" to contact.sessionReady,
        )
        pipeline.send(contact, text)
        reloadActive(contactId)
        refreshConversations()
    }

    /**
     * Принять входящий конверт для контакта (из релея/mailbox или теста).
     * Возвращает разобранное сообщение (для уведомления §14.5) или `null`, если
     * контакт заблокирован (§14.2) или расшифровка не удалась.
     */
    suspend fun receive(
        contactId: String,
        wireJson: String,
        transport: RelayTransportKind? = null,
    ): Message? {
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: return null
        // Чёрный список (§14.2): входящие от заблокированного контакта отбрасываем
        // (незаметно для него — §10.9). Расшифровку даже не запускаем.
        if (contact.contact.blocked) {
            DiagnosticLog.w(DIAG_TAG) {
                "входящее отброшено: контакт заблокирован ${DiagnosticLog.shortId(contactId)}"
            }
            return null
        }
        val message = runCatching { pipeline.receive(contact, wireJson, transport) }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) {
                "входящее НЕ расшифровано контакт=${DiagnosticLog.shortId(contactId)} байт=${wireJson.length}"
            }
            return null
        } ?: return null
        maybeAutoDownload(message)
        reloadActive(contactId)
        refreshConversations()
        return message
    }

    /**
     * Отправить вложение в чат (§14.4): фаза 1 — локально сохранить и сразу
     * показать (PENDING), фаза 2 — зашифровать блоб, загрузить на релей и
     * отправить ссылку слой A→B→Tor (обновляет статус SENT/FAILED).
     */
    @Suppress("LongParameterList")
    suspend fun sendAttachment(
        contactId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        kind: AttachmentKind,
        caption: String = "",
        /** Метаданные голосового (§14.4) — см. [sendVoice]. */
        voice: VoiceMeta? = null,
    ) {
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: run {
            DiagnosticLog.w(DIAG_TAG) { "вложение: контакта нет ${DiagnosticLog.shortId(contactId)}" }
            return
        }
        // Чёрный список (§14.2): заблокированному контакту не отправляем.
        if (contact.contact.blocked) {
            DiagnosticLog.w(DIAG_TAG) { "вложение: контакт заблокирован ${DiagnosticLog.shortId(contactId)}" }
            return
        }
        DiagnosticLog.event(
            DIAG_TAG, "отправка вложения",
            "контакт" to DiagnosticLog.shortId(contactId),
            "байт" to bytes.size,
            "тип" to kind.name,
            "mime" to mimeType,
            "голос" to (voice != null),
        )
        val message = pipeline.createOutgoingAttachment(
            contact, caption, bytes, fileName, mimeType, kind, voice,
        )
        reloadActive(contactId)
        refreshConversations()
        pipeline.uploadAndSendAttachment(contact, message, bytes)
        reloadActive(contactId)
        refreshConversations()
    }

    /**
     * Отправить ГОЛОСОВОЕ сообщение (§14.4). Тонкая обёртка над
     * [sendAttachment]: имя файла и MIME задаёт запись, а не пользователь,
     * поэтому собирать их на каждом вызывающем месте незачем.
     *
     * На провод уходит `kind = FILE` + [VoiceMeta] — обоснование обратной
     * совместимости см. в [VoiceMeta].
     */
    suspend fun sendVoice(
        contactId: String,
        bytes: ByteArray,
        durationMs: Long,
        waveformB64: String,
    ) {
        try {
            sendAttachment(
                contactId = contactId,
                bytes = bytes,
                // Имя видит только получатель со СТАРЫМ клиентом (как карточку
                // файла), поэтому оно человекочитаемое, но без даты/времени: имя
                // файла попадает в UI и не должно нести лишних метаданных.
                fileName = VOICE_FILE_NAME,
                mimeType = AegisConstants.VOICE_MIME_TYPE,
                kind = AttachmentKind.FILE,
                voice = VoiceMeta(durationMs = durationMs, waveformB64 = waveformB64),
            )
        } finally {
            // Затираем открытый звук в куче, как только он больше не нужен: копия
            // уже лежит в SQLCipher, а на релей ушёл шифртекст. Так процесс не
            // держит plaintext записи до следующей сборки мусора (та же
            // дисциплина, что `passphrase.fill(0)` в AegisDatabase).
            //
            // Best-effort и честно: GC мог переместить массив, и его прежняя копия
            // в куче этим вызовом не затрагивается (ср. оговорку в
            // [com.aegis.messenger.messaging.CameraCapture]). Это снижение окна
            // экспозиции, а не гарантия. Единственный владелец массива —
            // VoiceRecorder, поэтому затирать его здесь безопасно.
            bytes.fill(0)
        }
    }

    /**
     * Скачать и расшифровать вложение входящего сообщения (§14.4, по тапу).
     * `true` при успехе. Перечитывает активный чат, чтобы показать содержимое.
     * Идемпотентно: повторный вызов после обрыва — это «докачка» (best-effort
     * повтор целого блоба, побайтовый resume не нужен — блоб мал, cap 8 МБ §14.4).
     */
    suspend fun downloadAttachment(contactId: String, message: Message): Boolean {
        val ok = pipeline.downloadAttachment(message)
        if (ok) {
            reloadActive(contactId)
            refreshConversations()
        }
        return ok
    }

    /**
     * Повторить отправку ЛЮБОГО неотправленного исходящего сообщения (§14.2/§14.4).
     *
     * Единая точка для UI: пузырю не нужно знать, текст перед ним или вложение, —
     * иначе кнопка «Повторить» существовала бы только у вложений (как было до
     * появления честного статуса доставки), а неотправленный текст оставался бы в
     * тупике: помечен как неудачный, а повторить нечем.
     *
     * Id сообщения сохраняется (§14.2) — отчёты о прочтении и «удалить у всех»
     * ссылаются на ту же запись.
     */
    suspend fun retrySendMessage(contactId: String, message: Message): Boolean {
        if (message.direction != MessageDirection.OUTGOING) return false
        if (message.attachment != null) return retrySendAttachment(contactId, message)
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: return false
        if (contact.contact.blocked) return false
        val ok = pipeline.resend(contact, message)
        reloadActive(contactId)
        refreshConversations()
        return ok
    }

    /**
     * Повторить отправку вложения, которое ранее не ушло (§14.4, follow-up
     * «повтор при обрыве»). Исходные байты уже лежат в зашифрованной БД (фаза 1
     * [MessagePipeline.createOutgoingAttachment]), поэтому повтор — это заново
     * фаза 2: снова зашифровать блоб, загрузить на релей и отправить ссылку слой
     * A→B→Tor. `true` при успехе (статус → SENT); иначе снова FAILED.
     */
    suspend fun retrySendAttachment(contactId: String, message: Message): Boolean {
        if (message.direction != MessageDirection.OUTGOING || message.attachment == null) return false
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: return false
        if (contact.contact.blocked) return false
        val bytes = withContext(Dispatchers.IO) { messageStore.attachmentBytes(message.id) } ?: return false
        // Вернуть в «отправляется», чтобы UI показал прогресс, а не «ошибку».
        withContext(Dispatchers.IO) { messageStore.updateStatus(message.id, MessageStatus.PENDING) }
        reloadActive(contactId)
        val updated = pipeline.uploadAndSendAttachment(contact, message, bytes)
        reloadActive(contactId)
        refreshConversations()
        return updated.status == MessageStatus.SENT
    }

    /**
     * Повторяет отправку исходящих текстовых сообщений контакта, которые не ушли
     * (`FAILED`). Вызывается, когда публичные ключи слоя B наконец получены по
     * keyx-каналу (§6.2): до этого шифровать было нечем, теперь есть. Возвращает
     * число фактически доставленных.
     *
     * Вложения пропускаем — у них свой повтор ([retrySendAttachment]) с байтами
     * блоба из БД, и запускает его пользователь.
     */
    suspend fun retryFailedMessages(contactId: String): Int {
        val contact = withContext(Dispatchers.IO) { contactStore.get(contactId) } ?: return 0
        if (contact.contact.blocked) return 0
        val failed = withContext(Dispatchers.IO) {
            messageStore.messagesFor(contactId).filter {
                it.direction == MessageDirection.OUTGOING &&
                    it.status == MessageStatus.FAILED &&
                    it.attachment == null
            }
        }
        if (failed.isEmpty()) return 0
        DiagnosticLog.event(
            DIAG_TAG, "повтор FAILED",
            "контакт" to DiagnosticLog.shortId(contactId),
            "штук" to failed.size,
        )
        var sent = 0
        for (message in failed) {
            if (pipeline.resend(contact, message)) sent++
        }
        reloadActive(contactId)
        refreshConversations()
        return sent
    }

    /** Расшифрованные байты локально доступного вложения (для показа в UI) или `null`. */
    suspend fun attachmentBytes(messageId: String): ByteArray? =
        withContext(Dispatchers.IO) { messageStore.attachmentBytes(messageId) }

    /**
     * Автозагрузка небольшого входящего медиа (§14.4), если это разрешено
     * настройками и размер ≤ порога авто-скачивания. Best-effort и тихая.
     */
    private suspend fun maybeAutoDownload(message: Message) {
        val ref = message.attachment ?: return
        if (message.attachmentLocal) return
        if (!autoDownloadProvider()) return
        // Произвольный файл не качаем автоматически (он может быть чем угодно и
        // весить до 8 МБ), а ГОЛОСОВОЕ — качаем: оно приходит как kind = FILE,
        // но это медиа, которое пользователь ждёт готовым к нажатию «play».
        // Минута записи ≈ 180 КБ, то есть заведомо внутри порога автозагрузки.
        if (ref.kind == AttachmentKind.FILE && !ref.isVoice) return
        if (ref.sizeBytes > AegisConstants.ATTACHMENT_AUTO_DOWNLOAD_MAX_BYTES) return
        runCatching { pipeline.downloadAttachment(message) }
    }

    /** Текущая запись контакта (для экрана чата). */
    suspend fun contact(contactId: String): StoredContact? =
        withContext(Dispatchers.IO) { contactStore.get(contactId) }

    /** Все контакты (для экрана чёрного списка §14.2). */
    suspend fun allContacts(): List<StoredContact> =
        withContext(Dispatchers.IO) { contactStore.all() }

    /** Заблокирован ли контакт (§14.2). */
    suspend fun isBlocked(contactId: String): Boolean =
        withContext(Dispatchers.IO) { contactStore.get(contactId)?.contact?.blocked == true }

    /**
     * Блокировка/разблокировка контакта (§14.2, чёрный список). После изменения
     * перечитывает список чатов. Незаметно для собеседника (§10.9).
     */
    suspend fun setBlocked(contactId: String, blocked: Boolean) {
        withContext(Dispatchers.IO) { contactStore.setBlocked(contactId, blocked) }
        refreshConversations()
    }

    /**
     * Удаляет сообщения локально по общим id (§7.3: «удалить у меня» / приём
     * «удалить у всех»). Перечитывает активный чат и список чатов.
     */
    suspend fun deleteMessages(contactId: String, ids: List<String>) {
        withContext(Dispatchers.IO) { messageStore.deleteByIds(contactId, ids) }
        reloadActive(contactId)
        refreshConversations()
    }

    /**
     * «Очистить чат» (§7.3): локально удаляет ВСЕ сообщения контакта, но
     * сохраняет сам контакт, PSK и Signal-сессию — переписка обнуляется, а чат
     * остаётся в списке. Действие только локальное (собеседника не касается).
     */
    suspend fun clearConversation(contactId: String) {
        DiagnosticLog.event(DIAG_TAG, "чат очищен", "контакт" to DiagnosticLog.shortId(contactId))
        withContext(Dispatchers.IO) { messageStore.deleteFor(contactId) }
        reloadActive(contactId)
        refreshConversations()
    }

    /**
     * «Удалить чат»: локально сносит сам чат целиком — все сообщения, вложения
     * и запись контакта, а также привязанные устройства собеседника. PSK и
     * Signal-сессия здесь НЕ трогаются (за это отвечает вызывающий, см.
     * [com.aegis.messenger.psk.PskManager.remove]) — так после удаления можно
     * заново провести чистый обмен ключами. Действие только локальное.
     */
    suspend fun deleteConversation(contactId: String) {
        DiagnosticLog.event(DIAG_TAG, "чат удалён", "контакт" to DiagnosticLog.shortId(contactId))
        withContext(Dispatchers.IO) {
            messageStore.deleteFor(contactId)
            database.peerDeviceStore.deleteAllFor(contactId)
            contactStore.delete(contactId)
        }
        if (activeContactId == contactId) {
            activeContactId = null
            _activeMessages.value = emptyList()
        }
        refreshConversations()
    }

    /**
     * Открытие чата → отметить входящие как прочитанные (§14.2). Возвращает
     * общие id, которые только что перешли в READ — их надо подтвердить
     * собеседнику отчётом о прочтении (если это разрешено настройками).
     */
    suspend fun markIncomingRead(contactId: String): List<String> {
        val ids = withContext(Dispatchers.IO) { messageStore.markIncomingRead(contactId) }
        if (ids.isNotEmpty()) {
            reloadActive(contactId)
            refreshConversations()
        }
        return ids
    }

    /**
     * Применяет отчёт о прочтении от собеседника (§14.2): наши исходящие
     * сообщения с указанными id → READ (двойная галочка). Перечитывает UI.
     */
    suspend fun applyReadReceipt(contactId: String, ids: List<String>) {
        val changed = withContext(Dispatchers.IO) { messageStore.markOutgoingRead(contactId, ids) }
        if (changed > 0) {
            reloadActive(contactId)
            refreshConversations()
        }
    }

    /** Создать/обновить контакт (например, после ручного обмена PSK/бандлом). */
    suspend fun upsertContact(contact: StoredContact) = withContext(Dispatchers.IO) {
        contactStore.upsert(contact)
    }

    /**
     * Гарантировать контакт для установленного PSK. Если пришёл payload обмена
     * (§6.2) — сохраняет identity-ключ и prekey-бандл контакта, что сразу
     * позволяет установить Signal-сессию. PSK в KDF PQXDH не входит.
     *
     * Локальный алиас чата ([Contact.localName]) берётся из уже сохранённой
     * записи и на CONFLICT не перезаписывается — иначе синхронизация PSK
     * затирала бы пользовательское имя.
     */
    suspend fun ensureContact(
        contactId: String,
        nickname: String,
        fingerprint: String = "",
        exchange: PskExchangePayload? = null,
    ) = withContext(Dispatchers.IO) {
        val existing = contactStore.get(contactId)
        val merged = StoredContact(
            contact = Contact(
                id = contactId,
                nickname = nickname.ifBlank { existing?.contact?.nickname ?: contactId },
                localName = existing?.contact?.localName ?: "",
                about = existing?.contact?.about ?: "",
                fingerprint = fingerprint.ifBlank {
                    exchange?.fromFingerprint ?: existing?.contact?.fingerprint ?: ""
                },
                verified = existing?.contact?.verified ?: false,
                blocked = existing?.contact?.blocked ?: false,
                mutedUntilMs = existing?.contact?.mutedUntilMs ?: 0L,
                notificationSoundUri = existing?.contact?.notificationSoundUri ?: "",
                callRingtoneUri = existing?.contact?.callRingtoneUri ?: "",
            ),
            remoteIdentityB64 = exchange?.identityB64?.takeIf { it.isNotBlank() }
                ?: existing?.remoteIdentityB64,
            remoteDevice = existing?.remoteDevice ?: 1,
            bundleJson = exchange?.bundleJson?.takeIf { it.isNotBlank() }
                ?: existing?.bundleJson,
            sessionReady = existing?.sessionReady ?: false,
        )
        contactStore.upsert(merged)
    }

    /**
     * Отметить/снять ручную верификацию отпечатка контакта (§14.3). После
     * обновления перечитывает список чатов, чтобы бейдж «проверен» обновился.
     */
    suspend fun setContactVerified(contactId: String, verified: Boolean) {
        withContext(Dispatchers.IO) { contactStore.setVerified(contactId, verified) }
        refreshConversations()
    }

    /**
     * Персональное отключение уведомлений контакта (§14.5). `untilMs`: 0 — снять,
     * [com.aegis.core.common.model.Contact.MUTED_FOREVER] — навсегда, иначе — до
     * указанного времени. Перечитывает список чатов (индикатор mute).
     */
    suspend fun setContactMuted(contactId: String, untilMs: Long) {
        withContext(Dispatchers.IO) { contactStore.setMuted(contactId, untilMs) }
        reloadActive(contactId)
        refreshConversations()
    }

    /** Персональные звуки уведомлений контакта (§14.5): сообщение и звонок. */
    suspend fun setContactNotificationSounds(
        contactId: String,
        messageSoundUri: String,
        callRingtoneUri: String,
    ) {
        withContext(Dispatchers.IO) {
            contactStore.setNotificationSounds(contactId, messageSoundUri, callRingtoneUri)
        }
        refreshConversations()
    }

    /**
     * Локальное переименование чата. Пустая строка сбрасывает имя к публичному
     * никнейму контакта. Идентичность (id / никнейм обмена / PSK) не меняется.
     * Перечитывает список чатов, чтобы карточка в UI обновилась сразу.
     */
    suspend fun setChatLocalName(contactId: String, rawName: String) {
        val sanitized = Contact.sanitizeLocalName(rawName)
        withContext(Dispatchers.IO) { contactStore.setLocalName(contactId, sanitized) }
        refreshConversations()
    }

    private suspend fun reloadActive(contactId: String) = withContext(Dispatchers.IO) {
        if (activeContactId == contactId) {
            _activeMessages.value = visibleMessages(contactId)
        }
    }

    /** Применяет жёсткий срок хранения (§7.3) перед чтением из БД. */
    private fun purgeExpired() {
        val days = retentionDaysProvider().coerceIn(
            AegisConstants.AUTO_DELETE_MIN_DAYS,
            AegisConstants.AUTO_DELETE_MAX_DAYS,
        )
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        runCatching { messageStore.purgeOlderThan(cutoff) }
        purgeLeakedSignaling()
    }

    /**
     * Вычищает из БД служебные JSON (typing/presence/offer/…), которые раньше
     * попали в историю из-за отсутствующего `channel` во внешнем конверте.
     */
    private fun purgeLeakedSignaling() {
        val leaked = messageStore.allMessages().filter { message ->
            message.direction == MessageDirection.INCOMING &&
                message.attachment == null &&
                WireChannel.isStoredLeak(message.body)
        }
        if (leaked.isEmpty()) return
        leaked.groupBy { it.contactId }.forEach { (contactId, messages) ->
            messageStore.deleteByIds(contactId, messages.map { it.id })
        }
        DiagnosticLog.event(DIAG_TAG, "вычищены служебные JSON из чата", "штук" to leaked.size)
    }

    private fun visibleMessages(contactId: String): List<Message> =
        messageStore.messagesFor(contactId).filterNot {
            it.attachment == null && WireChannel.isStoredLeak(it.body)
        }

    private companion object {
        const val DIAG_TAG = "msg-repo"
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Имя блоба голосового: видно только клиентам старых версий (§14.4). */
        const val VOICE_FILE_NAME = "voice-message.aac"
    }
}
