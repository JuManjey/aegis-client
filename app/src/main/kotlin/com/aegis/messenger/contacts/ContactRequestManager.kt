package com.aegis.messenger.contacts

import android.util.Base64
import com.aegis.core.common.contact.ContactAccept
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.contact.ContactDecline
import com.aegis.core.common.contact.ContactInvite
import com.aegis.core.common.contact.ContactRequestDirection
import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.contact.ContactTrust
import com.aegis.core.common.contact.DirectoryRecord
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.model.Contact
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.DiscoveryCrypto
import com.aegis.crypto.PskCrypto
import com.aegis.data.storage.ContactRequestStore
import com.aegis.data.storage.ContactStore
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.StoredContact
import com.aegis.data.storage.StoredContactRequest
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.messenger.psk.PrekeyBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Оркестратор удалённого добавления контакта (RCH v1, `docs/contact_requests.md`).
 *
 * ```
 *  A ──[INVITE  в ящик приглашений B]──▶ B      (уведомление, принять/отклонить)
 *  A ◀──[ACCEPT в ящик ответа A]──────── B      (обе стороны выводят секрет слоя A)
 *  A ◀──[DECLINE]─────────────────────── B      (если отклонил; опционально)
 * ```
 *
 * ЧЕМ ЭТО ОТЛИЧАЕТСЯ ОТ ОБМЕНА ПО QR. Секрет слоя A (PSK) не переносится, а
 * ВЫВОДИТСЯ на обеих сторонах из подписанного рукопожатия
 * ([DiscoveryCrypto.derive]). Идентичность держится на привязке
 * `accountId ↔ ed25519` (accountId — коммитмент к ключу) и на подлинности
 * адреса, полученного вне сети. Поэтому контакт получает
 * [ContactTrust.REMOTE_ADDRESS], а не [ContactTrust.IN_PERSON] — UI показывает
 * разницу, и сверка отпечатка по-прежнему повышает уверенность.
 *
 * ЧТО ЗДЕСЬ НАМЕРЕННО НЕ ДЕЛАЕТСЯ. Автоматического принятия заявок нет ни в
 * каком виде: контакт активируется только после явного решения пользователя.
 */
class ContactRequestManager(
    private val requestStore: ContactRequestStore,
    private val contactStore: ContactStore,
    private val pskStore: PskStore,
    private val sessionManager: SignalSessionManager,
    private val transport: ContactRequestTransport,
    private val directoryLookup: DirectoryLookup,
    /** Мнемоника разблокированного аккаунта. `null` — заблокировано. */
    private val mnemonicProvider: () -> String?,
    private val identityProvider: suspend () -> DirectoryPublisher.AccountPublicIdentity?,
    private val nicknameProvider: suspend () -> String,
    private val localDeviceIdProvider: () -> Int,
    private val modeProvider: () -> ContactRequestMode,
    private val declineNotifyProvider: () -> Boolean,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /**
     * Заявка обработана и контакт активирован. Задаётся из DI: перечитать чаты,
     * запустить приём, снять уведомление.
     */
    var onContactActivated: (suspend (String) -> Unit)? = null

    /** Пришла новая входящая заявка — показать уведомление. Задаётся из DI. */
    var onIncomingRequest: (suspend (StoredContactRequest) -> Unit)? = null

    /** Состав заявок изменился — обновить бейдж/экран. Задаётся из DI. */
    var onRequestsChanged: (suspend () -> Unit)? = null

    /** Итог отправки заявки. */
    sealed interface SendResult {
        data class Sent(val peerAccountId: String, val nickname: String) : SendResult

        /** Уже есть контакт с этим аккаунтом. */
        data class AlreadyContact(val peerAccountId: String) : SendResult

        /** Заявка этому аккаунту уже отправлена и ждёт ответа. */
        data object AlreadyPending : SendResult

        /** Это мой собственный адрес. */
        data object SelfAddress : SendResult

        /** Адрес не разобран (опечатка) — контрольная сумма не сошлась. */
        data object BadAddress : SendResult

        /** Запись каталога не найдена: адрес устарел либо владелец не публиковался. */
        data object NotFound : SendResult

        /** Запись найдена, но НЕ прошла проверку — возможна подмена. */
        data class Untrusted(val reason: DirectoryRecordVerifier.Failure) : SendResult

        /** Функция выключена настройками либо недоступна на релее. */
        data object Unavailable : SendResult

        data class Failed(val reason: String) : SendResult
    }

    /** Итог решения по входящей заявке. */
    sealed interface DecisionResult {
        data class Accepted(val contactId: String, val handshakeCode: String) : DecisionResult
        data object Declined : DecisionResult
        data object NotFound : DecisionResult
        data class Failed(val reason: String) : DecisionResult
    }

    // --- Отправка заявки -----------------------------------------------------

    /**
     * Находит владельца [rawAddress] в каталоге, проверяет его запись и
     * отправляет `INVITE` в его ящик приглашений.
     *
     * Порядок операций важен: сначала ПОЛНАЯ проверка записи каталога
     * ([DirectoryRecordVerifier]), только потом установление Signal-сессии по её
     * бандлу. Иначе непроверенная запись успела бы завести сессию с чужими
     * ключами.
     */
    suspend fun sendByAddress(rawAddress: String, greeting: String): SendResult =
        withContext(Dispatchers.IO) {
            if (!modeProvider().enabled) return@withContext SendResult.Unavailable
            val parsed = ContactAddress.parse(rawAddress) ?: return@withContext SendResult.BadAddress
            val lookup = directoryLookup.byAddress(parsed)
            sendToLookup(lookup, greeting)
        }

    /**
     * То же по ПУБЛИЧНОМУ `@нику`. Ник — лишь подсказка для поиска: доверенным
     * идентификатором остаётся `accountId` из найденной заявки, и он показывается
     * пользователю до отправки.
     */
    suspend fun sendByAlias(alias: String, greeting: String): SendResult =
        withContext(Dispatchers.IO) {
            if (!modeProvider().aliasAllowed) return@withContext SendResult.Unavailable
            val lookup = directoryLookup.byAlias(alias)
            sendToLookup(lookup, greeting)
        }

    private suspend fun sendToLookup(
        lookup: DirectoryLookup.Result,
        greeting: String,
    ): SendResult {
        val found = when (lookup) {
            is DirectoryLookup.Result.Found -> lookup
            DirectoryLookup.Result.NotFound -> return SendResult.NotFound
            DirectoryLookup.Result.Unavailable -> return SendResult.Unavailable
            DirectoryLookup.Result.BadInput -> return SendResult.BadAddress
            is DirectoryLookup.Result.Untrusted -> return SendResult.Untrusted(lookup.reason)
        }
        val record = found.record
        val identity = identityProvider() ?: return SendResult.Failed("нет идентичности аккаунта")
        if (record.accountId.equals(identity.accountId, ignoreCase = true)) {
            return SendResult.SelfAddress
        }
        if (contactStore.get(record.accountId) != null) {
            return SendResult.AlreadyContact(record.accountId)
        }
        val existing = requestStore.find(ContactRequestDirection.OUTGOING, record.accountId)
        if (existing?.state == ContactRequestState.OUTGOING_PENDING) {
            return SendResult.AlreadyPending
        }

        val mnemonic = mnemonicProvider() ?: return SendResult.Failed("аккаунт заблокирован")
        val round = runCatching { DiscoveryCrypto.generateRound() }.getOrNull()
            ?: return SendResult.Failed("не удалось подготовить рукопожатие")
        val replyTag = runCatching { DiscoveryCrypto.generateTag() }.getOrNull()
            ?: return SendResult.Failed("не удалось создать тег ответа")
        val replyTagB64 = PskCrypto.toBase64(replyTag).also { replyTag.fill(0) }

        val device = record.devices.firstOrNull()
            ?: return SendResult.Untrusted(DirectoryRecordVerifier.Failure.NoUsableDevice)
        // Свои публичные ключи слоя B кладём в заявку: тогда получатель после
        // принятия сразу имеет и сессию, и бандл, и `keyx` не нужен лишним раундом.
        val myIdentity = runCatching { sessionManager.identityKeyB64() }.getOrNull().orEmpty()
        val myBundle = runCatching { sessionManager.publishBundleJson() }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val invite = signInvite(
            mnemonic = mnemonic,
            identity = identity,
            round = round,
            replyTagB64 = replyTagB64,
            signalIdentityB64 = myIdentity,
            bundleJson = myBundle,
            greeting = greeting.take(ContactInvite.MAX_GREETING),
            atMs = now,
        ) ?: return SendResult.Failed("не удалось подписать заявку")

        // Заявку сохраняем ДО отправки: собеседник может ответить в любой момент,
        // и без сохранённых секретов раунда завершить рукопожатие будет нечем.
        val stored = StoredContactRequest(
            id = existing?.id ?: UUID.randomUUID().toString(),
            direction = ContactRequestDirection.OUTGOING,
            state = ContactRequestState.OUTGOING_PENDING,
            peerAccountId = record.accountId,
            peerEd25519Hex = record.ed25519Hex,
            peerX25519Hex = record.x25519Hex,
            peerNickname = record.nickname,
            peerFingerprint = record.fingerprint,
            peerDeviceId = device.deviceId,
            peerTagB64 = found.tagB64,
            peerSignalIdentityB64 = device.signalIdentityB64,
            peerBundleJson = device.bundleJson,
            greeting = greeting.take(ContactInvite.MAX_GREETING),
            epkSecretB64 = round.epkSecretB64,
            epkPublicB64 = round.epkPublicB64,
            contribB64 = round.contribB64,
            nonceB64 = round.nonceB64,
            kemPublicB64 = round.kemPublicB64,
            kemSecretB64 = round.kemSecretB64,
            replyTagB64 = replyTagB64,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            expiresAtMs = now + REQUEST_TTL_MS,
        )
        requestStore.upsert(stored)
        onRequestsChanged?.invoke()

        val sent = transport.sendInvite(
            peerAccountId = record.accountId,
            peerDeviceId = device.deviceId,
            peerBundleJson = device.bundleJson,
            peerIdentityB64 = device.signalIdentityB64,
            peerTagB64 = found.tagB64,
            payloadJson = json.encodeToString(ContactInvite.serializer(), invite),
        )
        DiagnosticLog.event(
            DIAG_TAG, "заявка отправлена",
            "accountId" to DiagnosticLog.shortId(record.accountId),
            "устройство" to device.deviceId,
            "ок" to sent,
        )
        if (!sent) {
            return SendResult.Failed("не удалось доставить заявку (нет Tor/релея)")
        }
        return SendResult.Sent(record.accountId, record.nickname)
    }

    @Suppress("LongParameterList")
    private suspend fun signInvite(
        mnemonic: String,
        identity: DirectoryPublisher.AccountPublicIdentity,
        round: DiscoveryCrypto.RoundMaterial,
        replyTagB64: String,
        signalIdentityB64: String,
        bundleJson: String,
        greeting: String,
        atMs: Long,
    ): ContactInvite? {
        val unsigned = ContactInvite(
            fromAccountId = identity.accountId,
            fromEd25519Hex = identity.ed25519Hex,
            fromX25519Hex = identity.x25519Hex,
            fromFingerprint = identity.fingerprint,
            fromNickname = nicknameProvider(),
            fromDeviceId = localDeviceIdProvider(),
            replyTagB64 = replyTagB64,
            epkPublicB64 = round.epkPublicB64,
            kemPublicB64 = round.kemPublicB64,
            contribB64 = round.contribB64,
            nonceB64 = round.nonceB64,
            fromSignalIdentityB64 = signalIdentityB64,
            fromBundleJson = bundleJson,
            greeting = greeting,
            atMs = atMs,
        )
        val bytes = unsigned.canonicalBytes()
        val sig = runCatching { AccountCrypto.sign(mnemonic, bytes) }.getOrNull() ?: return null
        return unsigned.copy(sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP))
    }

    // --- Приём заявки --------------------------------------------------------

    /**
     * Обрабатывает пришедший `INVITE`.
     *
     * Проверки (fail-closed): версия, длины полей, подпись Ed25519 и привязка
     * `accountId ↔ ed25519`. Только после этого заявка попадает в список и
     * показывается пользователю — на экран не должно попадать ничего, что не
     * прошло криптографическую проверку.
     */
    suspend fun onInvite(payloadJson: String, senderDeviceId: Int) = withContext(Dispatchers.IO) {
        if (!modeProvider().enabled) {
            DiagnosticLog.w(DIAG_TAG) { "входящая заявка отброшена: функция выключена настройками" }
            return@withContext
        }
        val invite = runCatching { json.decodeFromString(ContactInvite.serializer(), payloadJson) }
            .getOrNull() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "входящая заявка не разобрана" }
            return@withContext
        }
        if (invite.t != ContactInvite.TYPE || invite.v != ContactInvite.VERSION) return@withContext
        if (!verifySigned(invite.fromAccountId, invite.fromEd25519Hex, invite.sigB64) {
                invite.canonicalBytes()
            }
        ) {
            return@withContext
        }
        if (invite.replyTagB64.isBlank() || invite.epkPublicB64.isBlank() ||
            invite.kemPublicB64.isBlank() || invite.contribB64.isBlank() ||
            invite.nonceB64.isBlank()
        ) {
            DiagnosticLog.w(DIAG_TAG) { "входящая заявка без обязательных полей рукопожатия" }
            return@withContext
        }
        val identity = identityProvider() ?: return@withContext
        if (invite.fromAccountId.equals(identity.accountId, ignoreCase = true)) return@withContext
        // Заблокированный контакт не должен всплывать заявкой (§14.2).
        val known = contactStore.get(invite.fromAccountId)
        if (known?.contact?.blocked == true) {
            DiagnosticLog.event(DIAG_TAG, "заявка от заблокированного контакта отброшена")
            return@withContext
        }

        val now = System.currentTimeMillis()
        val existing = requestStore.find(ContactRequestDirection.INCOMING, invite.fromAccountId)
        if (existing?.peerNonceB64 == invite.nonceB64 &&
            existing.state != ContactRequestState.OUTGOING_PENDING
        ) {
            DiagnosticLog.d(DIAG_TAG) { "повтор той же заявки — уведомление не дублируем" }
            return@withContext
        }
        if (existing?.state == ContactRequestState.INCOMING_DECLINED) {
            DiagnosticLog.event(DIAG_TAG, "повторная заявка после отказа — игнорируем")
            return@withContext
        }

        val stored = StoredContactRequest(
            id = existing?.id ?: UUID.randomUUID().toString(),
            direction = ContactRequestDirection.INCOMING,
            state = ContactRequestState.INCOMING_PENDING,
            peerAccountId = invite.fromAccountId,
            peerEd25519Hex = invite.fromEd25519Hex,
            peerX25519Hex = invite.fromX25519Hex,
            peerNickname = invite.fromNickname,
            peerFingerprint = invite.fromFingerprint,
            peerDeviceId = invite.fromDeviceId.takeIf { it in 1..255 } ?: senderDeviceId,
            peerEpkPublicB64 = invite.epkPublicB64,
            peerContribB64 = invite.contribB64,
            peerNonceB64 = invite.nonceB64,
            peerReplyTagB64 = invite.replyTagB64,
            // Ключи слоя B инициатора приняты вместе с подписанной заявкой, но
            // только если бандл внутренне согласован (identity_key совпадает с
            // объявленным) — иначе сессия завелась бы не на том ключе.
            peerSignalIdentityB64 = invite.fromSignalIdentityB64,
            peerBundleJson = invite.fromBundleJson.takeIf {
                it.isNotBlank() &&
                    PrekeyBundle.identityMatches(it, invite.fromSignalIdentityB64)
            }.orEmpty(),
            kemPublicB64 = invite.kemPublicB64,
            greeting = invite.greeting.take(ContactInvite.MAX_GREETING),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            expiresAtMs = now + REQUEST_TTL_MS,
        )
        requestStore.upsert(stored)
        DiagnosticLog.event(
            DIAG_TAG, "ВХОДЯЩАЯ ЗАЯВКА",
            "accountId" to DiagnosticLog.shortId(invite.fromAccountId),
            "ник" to DiagnosticLog.presence(invite.fromNickname),
            "устройство" to stored.peerDeviceId,
        )
        onIncomingRequest?.invoke(stored)
        onRequestsChanged?.invoke()

        // Одновременные заявки друг другу: побеждает та, чей accountId
        // лексикографически меньше. Иначе стороны вывели бы ДВА разных секрета
        // слоя A и не сошлись бы по адресам ящиков (связи не было бы вообще).
        val ourOutgoing = requestStore.find(ContactRequestDirection.OUTGOING, invite.fromAccountId)
        if (ourOutgoing?.state == ContactRequestState.OUTGOING_PENDING) {
            val theirsWins = invite.fromAccountId.lowercase() < identity.accountId.lowercase()
            DiagnosticLog.event(
                DIAG_TAG, "встречная заявка",
                "принимаем-их" to theirsWins,
            )
            if (theirsWins) {
                requestStore.delete(ourOutgoing.id)
                runCatching { accept(stored.id) }
            }
        }
    }

    /** Обрабатывает `ACCEPT`: выводит секрет слоя A и активирует контакт. */
    suspend fun onAccept(payloadJson: String) = withContext(Dispatchers.IO) {
        val accept = runCatching { json.decodeFromString(ContactAccept.serializer(), payloadJson) }
            .getOrNull() ?: return@withContext
        if (accept.t != ContactAccept.TYPE || accept.v != ContactAccept.VERSION) return@withContext
        if (!verifySigned(accept.fromAccountId, accept.fromEd25519Hex, accept.sigB64) {
                accept.canonicalBytes()
            }
        ) {
            return@withContext
        }
        val pending = requestStore.find(ContactRequestDirection.OUTGOING, accept.fromAccountId)
            ?: run {
                DiagnosticLog.w(DIAG_TAG) {
                    "ACCEPT без нашей исходящей заявки — игнорируем " +
                        "(accountId=${DiagnosticLog.shortId(accept.fromAccountId)})"
                }
                return@withContext
            }
        if (pending.state != ContactRequestState.OUTGOING_PENDING) return@withContext
        // Ключ собеседника закреплён при отправке заявки (взят из проверенной
        // записи каталога): подмена его в ответе не принимается.
        if (pending.peerEd25519Hex.isNotBlank() &&
            !pending.peerEd25519Hex.equals(accept.fromEd25519Hex, ignoreCase = true)
        ) {
            DiagnosticLog.w(DIAG_TAG) { "ACCEPT с ДРУГИМ ключом собеседника — отброшен" }
            return@withContext
        }

        val mnemonic = mnemonicProvider() ?: return@withContext
        val identity = identityProvider() ?: return@withContext
        val derived = runCatching {
            DiscoveryCrypto.derive(
                initiator = true,
                mnemonic = mnemonic,
                local = DiscoveryCrypto.Party(
                    accountId = identity.accountId,
                    ed25519Hex = identity.ed25519Hex,
                    x25519Hex = identity.x25519Hex,
                    epkPublicB64 = pending.epkPublicB64,
                    contribB64 = pending.contribB64,
                    nonceB64 = pending.nonceB64,
                ),
                remote = DiscoveryCrypto.Party(
                    accountId = accept.fromAccountId,
                    ed25519Hex = accept.fromEd25519Hex,
                    x25519Hex = accept.fromX25519Hex,
                    epkPublicB64 = accept.epkPublicB64,
                    contribB64 = accept.contribB64,
                    nonceB64 = accept.nonceB64,
                ),
                localEpkSecretB64 = pending.epkSecretB64,
                kemPublicB64 = pending.kemPublicB64,
                kemSecretB64 = pending.kemSecretB64,
                kemCiphertextB64 = accept.kemCiphertextB64,
            )
        }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) { "не удалось вывести секрет слоя A из ACCEPT" }
            return@withContext
        }

        activate(
            request = pending,
            peerAccountId = accept.fromAccountId,
            peerNickname = accept.fromNickname,
            peerFingerprint = accept.fromFingerprint,
            peerDeviceId = accept.fromDeviceId.takeIf { it in 1..255 } ?: pending.peerDeviceId,
            pskB64 = derived.pskB64,
            handshakeCode = derived.handshakeCode,
        )
    }

    /** Обрабатывает `DECLINE`: помечает исходящую заявку отклонённой. */
    suspend fun onDecline(payloadJson: String) = withContext(Dispatchers.IO) {
        val decline = runCatching { json.decodeFromString(ContactDecline.serializer(), payloadJson) }
            .getOrNull() ?: return@withContext
        if (decline.t != ContactDecline.TYPE) return@withContext
        if (!verifySigned(decline.fromAccountId, decline.fromEd25519Hex, decline.sigB64) {
                decline.canonicalBytes()
            }
        ) {
            return@withContext
        }
        val pending = requestStore.find(ContactRequestDirection.OUTGOING, decline.fromAccountId)
            ?: return@withContext
        if (pending.state != ContactRequestState.OUTGOING_PENDING) return@withContext
        requestStore.setState(pending.id, ContactRequestState.OUTGOING_DECLINED)
        DiagnosticLog.event(
            DIAG_TAG, "заявка отклонена собеседником",
            "accountId" to DiagnosticLog.shortId(decline.fromAccountId),
        )
        onRequestsChanged?.invoke()
    }

    // --- Решение пользователя ------------------------------------------------

    /**
     * Принимает входящую заявку: выводит секрет слоя A, активирует контакт и
     * отправляет `ACCEPT`.
     */
    suspend fun accept(requestId: String): DecisionResult = withContext(Dispatchers.IO) {
        val request = requestStore.get(requestId) ?: return@withContext DecisionResult.NotFound
        if (request.direction != ContactRequestDirection.INCOMING) {
            return@withContext DecisionResult.NotFound
        }
        val mnemonic = mnemonicProvider()
            ?: return@withContext DecisionResult.Failed("аккаунт заблокирован")
        val identity = identityProvider()
            ?: return@withContext DecisionResult.Failed("нет идентичности аккаунта")

        val round = runCatching { DiscoveryCrypto.generateRound() }.getOrNull()
            ?: return@withContext DecisionResult.Failed("не удалось подготовить рукопожатие")

        val derived = runCatching {
            DiscoveryCrypto.derive(
                initiator = false,
                mnemonic = mnemonic,
                local = DiscoveryCrypto.Party(
                    accountId = identity.accountId,
                    ed25519Hex = identity.ed25519Hex,
                    x25519Hex = identity.x25519Hex,
                    epkPublicB64 = round.epkPublicB64,
                    contribB64 = round.contribB64,
                    nonceB64 = round.nonceB64,
                ),
                remote = DiscoveryCrypto.Party(
                    accountId = request.peerAccountId,
                    ed25519Hex = request.peerEd25519Hex,
                    x25519Hex = request.peerX25519Hex,
                    epkPublicB64 = request.peerEpkPublicB64,
                    contribB64 = request.peerContribB64,
                    nonceB64 = request.peerNonceB64,
                ),
                localEpkSecretB64 = round.epkSecretB64,
                kemPublicB64 = request.kemPublicB64,
            )
        }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) { "не удалось вывести секрет слоя A при принятии заявки" }
            return@withContext DecisionResult.Failed("рукопожатие не сошлось")
        }

        val accept = ContactAccept(
            fromAccountId = identity.accountId,
            fromEd25519Hex = identity.ed25519Hex,
            fromX25519Hex = identity.x25519Hex,
            fromFingerprint = identity.fingerprint,
            fromNickname = nicknameProvider(),
            fromDeviceId = localDeviceIdProvider(),
            epkPublicB64 = round.epkPublicB64,
            kemCiphertextB64 = derived.kemCiphertextB64,
            contribB64 = round.contribB64,
            nonceB64 = round.nonceB64,
            atMs = System.currentTimeMillis(),
        )
        val bytes = accept.canonicalBytes()
        val sig = runCatching { AccountCrypto.sign(mnemonic, bytes) }.getOrNull()
            ?: return@withContext DecisionResult.Failed("не удалось подписать ответ")
        val signed = accept.copy(sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP))

        // Контакт активируем ДО отправки ответа: секрет слоя A уже выведен, и
        // если ответ не доедет сейчас, инициатор повторит заявку — а у нас всё
        // готово. Обратный порядок оставил бы нас без контакта при сбое сети.
        activate(
            request = request,
            peerAccountId = request.peerAccountId,
            peerNickname = request.peerNickname,
            peerFingerprint = request.peerFingerprint,
            peerDeviceId = request.peerDeviceId,
            pskB64 = derived.pskB64,
            handshakeCode = derived.handshakeCode,
        )

        val sent = transport.sendToReplyBox(
            peerAccountId = request.peerAccountId,
            peerDeviceId = request.peerDeviceId,
            peerIdentityB64 = request.peerSignalIdentityB64,
            replyTagB64 = request.peerReplyTagB64,
            payloadJson = json.encodeToString(ContactAccept.serializer(), signed),
        )
        DiagnosticLog.event(
            DIAG_TAG, "заявка ПРИНЯТА",
            "accountId" to DiagnosticLog.shortId(request.peerAccountId),
            "ответДоставлен" to sent,
        )
        DecisionResult.Accepted(request.peerAccountId, derived.handshakeCode)
    }

    /**
     * Отклоняет входящую заявку. Если [SecuritySettings.contactDeclineNotify]
     * включён, инициатору уходит подписанный `DECLINE` — иначе он не отличил бы
     * отказ от «не дошло» и повторял бы запрос.
     */
    suspend fun decline(requestId: String): DecisionResult = withContext(Dispatchers.IO) {
        val request = requestStore.get(requestId) ?: return@withContext DecisionResult.NotFound
        requestStore.setState(requestId, ContactRequestState.INCOMING_DECLINED)
        onRequestsChanged?.invoke()

        if (declineNotifyProvider()) {
            val mnemonic = mnemonicProvider()
            val identity = identityProvider()
            if (mnemonic != null && identity != null) {
                val decline = ContactDecline(
                    fromAccountId = identity.accountId,
                    fromEd25519Hex = identity.ed25519Hex,
                    atMs = System.currentTimeMillis(),
                )
                val bytes = decline.canonicalBytes()
                val sig = runCatching { AccountCrypto.sign(mnemonic, bytes) }.getOrNull()
                if (sig != null) {
                    runCatching {
                        transport.sendToReplyBox(
                            peerAccountId = request.peerAccountId,
                            peerDeviceId = request.peerDeviceId,
                            peerIdentityB64 = request.peerSignalIdentityB64,
                            replyTagB64 = request.peerReplyTagB64,
                            payloadJson = json.encodeToString(
                                ContactDecline.serializer(),
                                decline.copy(sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP)),
                            ),
                        )
                    }
                }
            }
        }
        DiagnosticLog.event(
            DIAG_TAG, "заявка отклонена",
            "accountId" to DiagnosticLog.shortId(request.peerAccountId),
            "уведомили" to declineNotifyProvider(),
        )
        DecisionResult.Declined
    }

    /** Отменяет свою исходящую заявку (удаляет вместе с секретами раунда). */
    suspend fun cancelOutgoing(requestId: String) = withContext(Dispatchers.IO) {
        requestStore.delete(requestId)
        onRequestsChanged?.invoke()
    }

    /** Удаляет просроченные заявки вместе с их секретами. */
    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) {
        val removed = requestStore.purgeExpired(System.currentTimeMillis())
        if (removed > 0) {
            DiagnosticLog.event(DIAG_TAG, "просроченные заявки удалены", "штук" to removed)
            onRequestsChanged?.invoke()
        }
        removed
    }

    // --- Общее ---------------------------------------------------------------

    /**
     * Сохраняет выведенный секрет слоя A, создаёт контакт с ключами слоя B и
     * помечает заявку завершённой (затирая секреты раунда).
     */
    @Suppress("LongParameterList")
    private suspend fun activate(
        request: StoredContactRequest,
        peerAccountId: String,
        peerNickname: String,
        peerFingerprint: String,
        peerDeviceId: Int,
        pskB64: String,
        handshakeCode: String,
    ) {
        val psk = PskCrypto.fromBase64(pskB64)
        try {
            pskStore.put(
                PskRecord(
                    contactId = peerAccountId,
                    pskB64 = pskB64,
                    source = PskSource.REMOTE_HANDSHAKE,
                    pskFingerprintHex = PskCrypto.fingerprintHex(psk),
                    peerAccountId = peerAccountId,
                    peerFingerprint = peerFingerprint,
                    label = peerNickname,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } finally {
            psk.fill(0)
        }

        val existing = contactStore.get(peerAccountId)
        contactStore.upsert(
            StoredContact(
                contact = Contact(
                    id = peerAccountId,
                    nickname = peerNickname.ifBlank { existing?.contact?.nickname ?: peerAccountId },
                    localName = existing?.contact?.localName.orEmpty(),
                    fingerprint = peerFingerprint.ifBlank { existing?.contact?.fingerprint.orEmpty() },
                    verified = existing?.contact?.verified ?: false,
                    blocked = existing?.contact?.blocked ?: false,
                    mutedUntilMs = existing?.contact?.mutedUntilMs ?: 0L,
                    trust = ContactTrust.REMOTE_ADDRESS,
                ),
                // Ключи слоя B получены вместе с рукопожатием (у инициатора — из
                // проверенной записи каталога, у ответчика — из подписанной
                // заявки), поэтому переписка возможна сразу, без добора по keyx.
                remoteIdentityB64 = request.peerSignalIdentityB64.takeIf { it.isNotBlank() }
                    ?: existing?.remoteIdentityB64,
                remoteDevice = peerDeviceId,
                bundleJson = request.peerBundleJson.takeIf { it.isNotBlank() }
                    ?: existing?.bundleJson,
                sessionReady = existing?.sessionReady ?: false,
            ),
        )
        requestStore.complete(request.id)
        DiagnosticLog.event(
            DIAG_TAG, "КОНТАКТ АКТИВИРОВАН удалённо",
            "accountId" to DiagnosticLog.shortId(peerAccountId),
            "устройство" to peerDeviceId,
            "кодРукопожатия" to handshakeCode.take(5),
        )
        onContactActivated?.invoke(peerAccountId)
        onRequestsChanged?.invoke()
    }

    /**
     * Проверяет подпись нагрузки и привязку `accountId ↔ ed25519`.
     *
     * Обе проверки обязательны и идут вместе: подпись без привязки означала бы
     * «кто-то подписал», а не «подписал владелец этого accountId» — а именно на
     * привязке держится вся модель доверия удалённого добавления.
     */
    private inline fun verifySigned(
        accountId: String,
        ed25519Hex: String,
        sigB64: String,
        canonical: () -> ByteArray,
    ): Boolean {
        if (accountId.isBlank() || ed25519Hex.isBlank() || sigB64.isBlank()) {
            DiagnosticLog.w(DIAG_TAG) { "нагрузка без подписи/ключа — отброшена" }
            return false
        }
        val derived = AccountCrypto.accountIdFromEd25519(ed25519Hex)
        if (derived == null || !derived.equals(accountId, ignoreCase = true)) {
            DiagnosticLog.w(DIAG_TAG) {
                "accountId НЕ коммитится к присланному ключу — нагрузка отброшена"
            }
            return false
        }
        val sig = runCatching { Base64.decode(sigB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        val ok = AccountCrypto.verify(ed25519Hex, canonical(), sig)
        if (!ok) DiagnosticLog.w(DIAG_TAG) { "подпись нагрузки не прошла проверку — отброшена" }
        return ok
    }

    private companion object {
        const val DIAG_TAG = "contact-req"

        /**
         * Срок жизни заявки. Собеседник может быть офлайн долго, но держать
         * секреты раунда бесконечно — держать лишний секрет на диске.
         */
        const val REQUEST_TTL_MS = 14L * 24 * 3600 * 1000
    }
}
