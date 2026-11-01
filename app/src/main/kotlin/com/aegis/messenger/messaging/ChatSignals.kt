package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.data.storage.StoredContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Координатор эфемерных сигналов переписки (§14.2/§7.3) поверх [ControlChannel].
 *
 * Отвечает и за ИСХОДЯЩИЕ (отправить отчёт о прочтении, «печатает…», онлайн-
 * статус, «удалить у всех»), и за ВХОДЯЩИЕ ([onControl] из [RelayReceiver])
 * события. «Печатает…» и онлайн-статус собеседников держатся ТОЛЬКО в памяти
 * ([signals]) — на диск не пишутся (минимизация метаданных §10.10). Отчёты
 * прочтения и удаление меняют зашифрованную БД через [MessagingRepository].
 *
 * Все исходящие события уважают [PrivacySettings]: выключение галочек/«печатает»/
 * онлайн-статуса просто не отправляет соответствующие control-сообщения (§10.9).
 */
class ChatSignals(
    private val controlChannel: ControlChannel,
    private val repository: MessagingRepository,
    private val privacyProvider: () -> PrivacySettings,
    private val scope: CoroutineScope,
) {
    /** Эфемерный сигнал по контакту (только в памяти). */
    data class PeerSignal(
        val typing: Boolean = false,
        val online: Boolean = false,
        /** Последний известный «визит» собеседника (мс, epoch); 0 — неизвестно. */
        val lastSeenMs: Long = 0L,
        /**
         * У контакта появилось НОВОЕ устройство (мультидевайс §8): показываем
         * баннер «сверьте отпечаток». Сбрасывается [acknowledgeNewDevice].
         */
        val newDevicePending: Boolean = false,
    )

    /**
     * Обработчики событий устройств контакта (мультидевайс §5/§10): регистрация
     * нового устройства и его отзыв в реестре `peer_devices`. Задаются из DI
     * ([AppContainer]); по умолчанию no-op.
     */
    var onDeviceAnnounce: suspend (StoredContact, ControlMessage) -> Unit = { _, _ -> }
    var onDeviceRevoke: suspend (StoredContact, ControlMessage) -> Unit = { _, _ -> }

    private val _signals = MutableStateFlow<Map<String, PeerSignal>>(emptyMap())
    val signals: StateFlow<Map<String, PeerSignal>> = _signals.asStateFlow()

    // Троттлинг исходящего «печатает…» и авто-стоп по контакту.
    private val lastTypingSentMs = HashMap<String, Long>()
    private val typingStopJobs = HashMap<String, Job>()
    // Авто-истечение входящего «печатает…» по контакту.
    private val typingExpiryJobs = HashMap<String, Job>()

    // --- Входящие ------------------------------------------------------------

    /** Обрабатывает входящий конверт control-канала (из [RelayReceiver]). */
    suspend fun onControl(contact: StoredContact, wireJson: String) {
        // От заблокированного контакта сигналы игнорируем (§14.2/§10.9).
        if (contact.contact.blocked) {
            DiagnosticLog.w(DIAG_TAG) {
                "control отброшен: контакт заблокирован ${DiagnosticLog.shortId(contact.contact.id)}"
            }
            return
        }
        val id = contact.contact.id
        val msg = runCatching { controlChannel.onWire(contact, wireJson) }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) {
                "control не расшифрован контакт=${DiagnosticLog.shortId(id)}"
            }
            return
        }
        apply(contact, msg)
    }

    /**
     * Применяет уже расшифрованное control-событие. Нужно, когда конверт пришёл
     * без `channel` и был расшифрован как сообщение: ratchet уже сдвинут, повторно
     * [ControlChannel.onWire] вызывать нельзя.
     */
    suspend fun apply(contact: StoredContact, msg: ControlMessage) {
        if (contact.contact.blocked) return
        val id = contact.contact.id
        when (msg.type) {
            ControlMessage.TYPE_READ -> repository.applyReadReceipt(id, msg.messageIds)
            ControlMessage.TYPE_DELETE -> repository.deleteMessages(id, msg.messageIds)
            ControlMessage.TYPE_TYPING -> onPeerTyping(id, msg.typing)
            ControlMessage.TYPE_PRESENCE -> onPeerPresence(id, msg.online, msg.atMs)
            ControlMessage.TYPE_DEVICE_ANNOUNCE -> {
                onDeviceAnnounce(contact, msg)
                update(id) { it.copy(newDevicePending = true) }
            }
            ControlMessage.TYPE_DEVICE_REVOKE -> onDeviceRevoke(contact, msg)
        }
    }

    /**
     * Забывает эфемерные сигналы ВСЕХ собеседников и гасит их таймеры
     * (экстренная очистка, §7.4): «печатает…», онлайн-статус и баннеры нового
     * устройства относятся к контактам прежнего аккаунта.
     */
    fun reset() {
        typingStopJobs.values.forEach { it.cancel() }
        typingStopJobs.clear()
        typingExpiryJobs.values.forEach { it.cancel() }
        typingExpiryJobs.clear()
        lastTypingSentMs.clear()
        _signals.value = emptyMap()
    }

    /** Пользователь увидел/сверил новое устройство — снять баннер (§8). */
    fun acknowledgeNewDevice(contactId: String) {
        update(contactId) { it.copy(newDevicePending = false) }
    }

    private fun onPeerTyping(contactId: String, typing: Boolean) {
        typingExpiryJobs.remove(contactId)?.cancel()
        update(contactId) { it.copy(typing = typing) }
        if (typing) {
            // Авто-сброс, если «печатает stop» потеряется в сети.
            typingExpiryJobs[contactId] = scope.launch {
                delay(TYPING_RECV_TTL_MS)
                update(contactId) { it.copy(typing = false) }
            }
        }
    }

    private fun onPeerPresence(contactId: String, online: Boolean, atMs: Long) {
        val seen = if (atMs > 0) atMs else System.currentTimeMillis()
        update(contactId) { it.copy(online = online, lastSeenMs = seen) }
    }

    // --- Исходящие -----------------------------------------------------------

    /**
     * Чат открыт: отметить входящие прочитанными и (если разрешено) сообщить об
     * этом собеседнику, а также анонсировать свой онлайн-статус.
     */
    fun onConversationOpened(contact: StoredContact) {
        if (contact.contact.blocked) return
        scope.launch(Dispatchers.IO) {
            val id = contact.contact.id
            val readIds = repository.markIncomingRead(id)
            val privacy = privacyProvider()
            if (readIds.isNotEmpty() && privacy.sendReadReceipts) {
                runCatching {
                    controlChannel.send(
                        contact,
                        ControlMessage(type = ControlMessage.TYPE_READ, messageIds = readIds, atMs = now()),
                    )
                }
            }
            if (privacy.shareOnlineStatus) {
                runCatching {
                    controlChannel.send(
                        contact,
                        ControlMessage(type = ControlMessage.TYPE_PRESENCE, online = true, atMs = now()),
                    )
                }
            }
        }
    }

    /** Чат закрыт: снять «печатает…» и (если разрешено) сообщить об уходе. */
    fun onConversationClosed(contact: StoredContact) {
        if (contact.contact.blocked) return
        val id = contact.contact.id
        typingStopJobs.remove(id)?.cancel()
        lastTypingSentMs.remove(id)
        scope.launch(Dispatchers.IO) {
            val privacy = privacyProvider()
            if (privacy.sendTypingIndicators) {
                runCatching { controlChannel.send(contact, typingMsg(false)) }
            }
            if (privacy.shareOnlineStatus) {
                runCatching {
                    controlChannel.send(
                        contact,
                        ControlMessage(type = ControlMessage.TYPE_PRESENCE, online = false, atMs = now()),
                    )
                }
            }
        }
    }

    /**
     * Пользователь печатает: троттлингом шлёт «печатает…=true» и планирует авто-
     * стоп, если ввод прекратится. No-op, если индикатор выключен настройками.
     */
    fun onLocalTyping(contact: StoredContact) {
        if (contact.contact.blocked) return
        if (!privacyProvider().sendTypingIndicators) return
        val id = contact.contact.id
        val now = now()
        val last = lastTypingSentMs[id] ?: 0L
        if (now - last >= TYPING_SEND_THROTTLE_MS) {
            lastTypingSentMs[id] = now
            scope.launch(Dispatchers.IO) { runCatching { controlChannel.send(contact, typingMsg(true)) } }
        }
        // Перепланируем авто-стоп после паузы во вводе.
        typingStopJobs.remove(id)?.cancel()
        typingStopJobs[id] = scope.launch(Dispatchers.IO) {
            delay(TYPING_AUTOSTOP_MS)
            lastTypingSentMs.remove(id)
            runCatching { controlChannel.send(contact, typingMsg(false)) }
        }
    }

    /** «Удалить у меня» (§7.3): локальное удаление без сетевого запроса. */
    fun deleteForMe(contact: StoredContact, ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) { repository.deleteMessages(contact.contact.id, ids) }
    }

    /**
     * «Удалить у всех» (§7.3): удаляет локально и просит собеседника удалить те
     * же записи (по общим id). Best-effort — доставка через тот же конвейер.
     */
    fun deleteForEveryone(contact: StoredContact, ids: List<String>) {
        if (ids.isEmpty()) return
        DiagnosticLog.event(
            DIAG_TAG, "удалить у всех",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "штук" to ids.size,
        )
        scope.launch(Dispatchers.IO) {
            repository.deleteMessages(contact.contact.id, ids)
            runCatching {
                controlChannel.send(
                    contact,
                    ControlMessage(type = ControlMessage.TYPE_DELETE, messageIds = ids, atMs = now()),
                )
            }
        }
    }

    private inline fun update(contactId: String, transform: (PeerSignal) -> PeerSignal) {
        val current = _signals.value
        val updated = transform(current[contactId] ?: PeerSignal())
        _signals.value = current + (contactId to updated)
    }

    private fun typingMsg(typing: Boolean) =
        ControlMessage(type = ControlMessage.TYPE_TYPING, typing = typing, atMs = now())

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TYPING_SEND_THROTTLE_MS = 3_500L
        const val TYPING_AUTOSTOP_MS = 5_000L
        const val TYPING_RECV_TTL_MS = 6_000L
        const val DIAG_TAG = "chat-signals"
    }
}
