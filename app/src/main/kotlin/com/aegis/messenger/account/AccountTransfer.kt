package com.aegis.messenger.account

import android.content.Context
import android.util.Base64
import com.aegis.core.common.account.AccountKdfParams
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.core.common.model.Contact
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.transfer.TransferContact
import com.aegis.core.common.transfer.TransferEnvelope
import com.aegis.core.common.transfer.TransferMessage
import com.aegis.core.common.transfer.TransferPayload
import com.aegis.core.common.transfer.TransferSelection
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.cascade.CascadeAuthException
import com.aegis.data.storage.AccountStore
import com.aegis.data.storage.ContactStore
import com.aegis.data.storage.MessageStore
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.SecureStore
import com.aegis.data.storage.SignalStateStore
import com.aegis.data.storage.StoredContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Что показать пользователю о содержимом расшифрованного пакета переноса. */
data class TransferSummary(
    val hasAccount: Boolean,
    val login: String,
    val sourceFingerprint: String,
    val multiDeviceAllowed: Boolean,
    val contactCount: Int,
    val messageCount: Int,
    val pskCount: Int,
    val hasSettings: Boolean,
    val createdAtMs: Long,
)

/** Результат сборки пакета переноса на устройстве-источнике (§7.1.2). */
sealed interface TransferBuildResult {
    /**
     * [envelopeJson] — готовая к передаче оболочка (файл/QR). [sizeBytes] — её
     * размер; UI решает, влезает ли в QR (иначе предлагает файл). [payload]
     * оставлен для отладочного показа сводки перед экспортом.
     */
    data class Success(
        val envelopeJson: String,
        val sizeBytes: Int,
        val summary: TransferSummary,
    ) : TransferBuildResult

    data class Failure(val reason: String) : TransferBuildResult
}

/** Результат распечатывания принятого пакета (перед применением). */
sealed interface TransferOpenResult {
    data class Success(val payload: TransferPayload, val summary: TransferSummary) : TransferOpenResult
    data object WrongPassword : TransferOpenResult
    data class Invalid(val reason: String) : TransferOpenResult
}

/** Результат применения распечатанного пакета на устройстве-приёмнике. */
sealed interface TransferApplyResult {
    data class Success(val summary: TransferSummary) : TransferApplyResult
    data class Failure(val reason: String) : TransferApplyResult
}

/**
 * Отчёт о слиянии пакета в СУЩЕСТВУЮЩИЙ аккаунт (§7.1.2, follow-up). Merge —
 * строго add-only: добавляются только НОВЫЕ сущности (по id), а совпадающие
 * локальные записи сохраняются нетронутыми (без перезаписи). Секретное состояние
 * Signal (храповики Double Ratchet) и идентичность НЕ переносятся при merge —
 * иначе локальный ratchet рассинхронизировался бы (см. §1.23, границы).
 */
data class TransferMergeReport(
    /** Совпадает ли accountId источника с локальным (иначе — данные другого аккаунта). */
    val identityMatchesLocal: Boolean,
    val contactsAdded: Int,
    val contactsExisting: Int,
    val messagesAdded: Int,
    val messagesExisting: Int,
    /** Пропущено сообщений без известного контакта (осиротевшие ссылки). */
    val messagesOrphaned: Int,
    val psksAdded: Int,
    /** PSK, для которых локальный уже есть — оставлен локальный (не перезаписан). */
    val psksConflicting: Int,
    /** Были ли в пакете настройки (можно применить по явному согласию). */
    val hasImportableSettings: Boolean,
    /** Реально применены ли настройки (в предпросмотре — всегда false). */
    val settingsApplied: Boolean,
)

/** Результат слияния пакета в существующий аккаунт. */
sealed interface TransferMergeResult {
    data class Success(val report: TransferMergeReport) : TransferMergeResult
    data class Failure(val reason: String) : TransferMergeResult
}

/**
 * Перенос аккаунта между устройствами (§7.1.2) — полностью вне сети.
 *
 * Источник собирает выбранные данные в [TransferPayload], сериализует в JSON и
 * запечатывает каскадом слоя A (§5) на ключе, выведенном Argon2id из пароля
 * переноса (пароль пользователь придумывает и вводит вручную на обоих
 * устройствах — как PSK, он НИКОГДА не уходит в сеть). Наружу отдаётся только
 * [TransferEnvelope]: соль KDF + запечатанный blob. Приёмник тем же паролем
 * выводит ключ, распечатывает пакет и разворачивает данные (в т.ч. создаёт
 * аккаунт из перенесённой мнемоники, пароль переноса становится локальным).
 *
 * Секреты (мнемоника, PSK, ключ Signal-состояния) существуют в открытом виде
 * только в памяти во время сборки/применения; наружу без пароля они недоступны.
 */
class AccountTransfer(
    private val appContext: Context,
    private val accountManager: AccountManager,
    private val accountStore: AccountStore,
    private val contactStore: ContactStore,
    private val messageStore: MessageStore,
    private val signalStateStore: SignalStateStore,
    private val pskStore: PskStore,
    private val secureStore: SecureStore,
    private val crypto: AccountCrypto = AccountCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val secureRandom = SecureRandom()

    /** Локализованная строка (уважает выбранный в приложении язык вне Activity). */
    private fun str(resId: Int, vararg args: Any): String =
        LocaleManager.wrap(appContext).getString(resId, *args)

    /**
     * Собирает и запечатывает пакет переноса из ДАННЫХ РАЗБЛОКИРОВАННОГО
     * аккаунта по [selection]. [password] защищает пакет (мин. длина [MIN_PASSWORD]).
     * [includeAttachmentBytes] прикладывает байты локальных вложений (крупно —
     * годится только для файла, не для QR).
     */
    suspend fun buildEnvelope(
        selection: TransferSelection,
        password: String,
        includeAttachmentBytes: Boolean = false,
    ): TransferBuildResult = withContext(Dispatchers.IO) {
        if (!crypto.isReady) {
            return@withContext TransferBuildResult.Failure(str(R.string.transfer_err_crypto_unavailable))
        }
        if (password.length < MIN_PASSWORD) {
            return@withContext TransferBuildResult.Failure(
                str(R.string.transfer_err_password_too_short, MIN_PASSWORD),
            )
        }
        val record = accountStore.load()
            ?: return@withContext TransferBuildResult.Failure(str(R.string.transfer_err_account_not_found))
        val identity = accountManager.currentIdentity()
            ?: return@withContext TransferBuildResult.Failure(str(R.string.transfer_err_account_locked))
        val mnemonic = accountManager.unlockedMnemonic
            ?: return@withContext TransferBuildResult.Failure(str(R.string.transfer_err_account_locked))
        val profile = record.profile

        try {
            val contacts = if (selection.contacts) {
                contactStore.all().map { it.toTransfer() }
            } else {
                emptyList()
            }
            val messages = if (selection.conversations) {
                messageStore.allMessages().map { it.toTransfer(includeAttachmentBytes) }
            } else {
                emptyList()
            }
            val payload = TransferPayload(
                createdAtMs = System.currentTimeMillis(),
                sourceAccountId = identity.accountId,
                sourceFingerprint = identity.fingerprint,
                login = profile.login,
                multiDeviceAllowed = profile.multiDeviceAllowed,
                mnemonic = if (selection.account) mnemonic else null,
                signalStateB64 = if (selection.conversations) {
                    signalStateStore.load(identity.accountId)
                } else {
                    null
                },
                contacts = contacts,
                messages = messages,
                psks = if (selection.psks) pskStore.exportAll() else emptyList(),
                securitySettings = if (selection.settings) secureStore.loadSecuritySettings() else null,
                torNetworkSettings = if (selection.settings) secureStore.loadTorNetworkSettings() else null,
                privacySettings = if (selection.settings) secureStore.loadPrivacySettings() else null,
                notificationSettings = if (selection.settings) {
                    secureStore.loadNotificationSettings()
                } else {
                    null
                },
            )

            val plaintext = json.encodeToString(TransferPayload.serializer(), payload).encodeToByteArray()
            val kdf = AccountKdfParams(saltB64 = randomB64(SALT_LENGTH))
            val payloadB64 = sealWithPassword(plaintext, password, kdf)
            plaintext.fill(0)

            val envelope = TransferEnvelope(
                kdf = kdf,
                payloadB64 = payloadB64,
                createdAtMs = payload.createdAtMs,
            )
            val envelopeJson = json.encodeToString(TransferEnvelope.serializer(), envelope)
            DiagnosticLog.event(
                DIAG_TAG, "пакет переноса собран",
                "контактов" to contacts.size,
                "сообщений" to messages.size,
                "байт" to envelopeJson.toByteArray().size,
            )
            TransferBuildResult.Success(
                envelopeJson = envelopeJson,
                sizeBytes = envelopeJson.toByteArray().size,
                summary = payload.summary(),
            )
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "сборка пакета переноса упала" }
            TransferBuildResult.Failure(e.message ?: str(R.string.transfer_err_build_generic))
        }
    }

    /**
     * Распечатывает принятую оболочку [envelopeJson] паролем [password] (без
     * применения — для предпросмотра сводки). Неверный пароль → [TransferOpenResult.WrongPassword].
     */
    suspend fun openEnvelope(envelopeJson: String, password: String): TransferOpenResult =
        withContext(Dispatchers.IO) {
            if (!crypto.isReady) {
                return@withContext TransferOpenResult.Invalid(str(R.string.transfer_err_crypto_unavailable))
            }
            val envelope = runCatching {
                json.decodeFromString(TransferEnvelope.serializer(), envelopeJson.trim())
            }.getOrElse { return@withContext TransferOpenResult.Invalid(str(R.string.transfer_err_not_aegis)) }
            if (envelope.t != TransferEnvelope.MAGIC) {
                return@withContext TransferOpenResult.Invalid(str(R.string.transfer_err_unknown_format))
            }
            val plaintext = try {
                openWithPassword(envelope.payloadB64, password, envelope.kdf)
            } catch (_: CascadeAuthException) {
                return@withContext TransferOpenResult.WrongPassword
            } catch (e: Exception) {
                return@withContext TransferOpenResult.Invalid(e.message ?: str(R.string.transfer_err_open_generic))
            }
            val payload = runCatching {
                json.decodeFromString(TransferPayload.serializer(), plaintext.decodeToString())
            }.getOrElse {
                plaintext.fill(0)
                return@withContext TransferOpenResult.Invalid(str(R.string.transfer_err_corrupt))
            }
            plaintext.fill(0)
            DiagnosticLog.event(
                DIAG_TAG, "пакет переноса открыт",
                "контактов" to payload.contacts.size,
                "сообщений" to payload.messages.size,
            )
            TransferOpenResult.Success(payload, payload.summary())
        }

    /**
     * Применяет распечатанный [payload] на ЧИСТОМ устройстве (§7.1.2): создаёт
     * аккаунт из перенесённой мнемоники ([password] становится локальным
     * паролем), затем разворачивает выбранные данные (Signal-состояние,
     * контакты, переписки, PSK, настройки). Требует, чтобы на устройстве ещё не
     * было аккаунта и чтобы в пакете была идентичность.
     */
    suspend fun apply(payload: TransferPayload, password: String): TransferApplyResult =
        withContext(Dispatchers.IO) {
            if (accountStore.load() != null) {
                return@withContext TransferApplyResult.Failure(str(R.string.transfer_err_already_account))
            }
            val mnemonic = payload.mnemonic
            if (mnemonic.isNullOrBlank()) {
                return@withContext TransferApplyResult.Failure(str(R.string.transfer_err_no_identity))
            }
            if (!crypto.validateMnemonic(mnemonic)) {
                return@withContext TransferApplyResult.Failure(str(R.string.transfer_err_bad_mnemonic))
            }
            // ВАЖНО: сначала разворачиваем данные и лишь ПОТОМ создаём аккаунт.
            // Восстановление аккаунта переводит состояние в Unlocked, что
            // мгновенно уводит UI из онбординга и обрывает корутину импорта; если
            // делать это первым, разворачивание данных не успеет завершиться.
            // accountId выводится из мнемоники детерминированно — до создания.
            val accountId = runCatching { crypto.deriveIdentity(mnemonic).accountId }.getOrElse {
                return@withContext TransferApplyResult.Failure(str(R.string.transfer_err_derive_identity))
            }

            try {
                // Хранилища закрыты ключом из мнемоники — открываем их ею до записи.
                accountManager.openStorageForImport(mnemonic)
                payload.signalStateB64?.takeIf { it.isNotBlank() }?.let { blob ->
                    signalStateStore.save(accountId, blob)
                }
                payload.contacts.forEach { tc ->
                    contactStore.upsert(tc.toStored())
                    // upsert на CONFLICT не трогает local_name (гонка с UI-переименованием).
                    // На полном восстановлении явно применяем алиас из пакета.
                    contactStore.setLocalName(tc.id, Contact.sanitizeLocalName(tc.localName))
                }
                payload.messages.forEach { tm ->
                    messageStore.insert(tm.toMessage())
                    tm.attachmentBytesB64?.let { b64 ->
                        runCatching {
                            messageStore.saveAttachmentBytes(tm.id, Base64.decode(b64, Base64.NO_WRAP))
                        }
                    }
                }
                payload.psks.forEach { pskStore.put(it) }
                payload.securitySettings?.let { secureStore.saveSecuritySettings(it) }
                payload.torNetworkSettings?.let { secureStore.saveTorNetworkSettings(it) }
                payload.privacySettings?.let { secureStore.savePrivacySettings(it) }
                payload.notificationSettings?.let { secureStore.saveNotificationSettings(it) }
            } catch (e: Exception) {
                accountManager.closeStorageAfterFailedImport()
                return@withContext TransferApplyResult.Failure(
                    e.message ?: str(R.string.transfer_err_apply_generic),
                )
            }

            // Последним — создаём/восстанавливаем аккаунт (пароль переноса =
            // локальный пароль этого устройства). Это включает Unlocked-состояние.
            val created = accountManager.recoverAccount(
                mnemonic = mnemonic,
                password = password,
                login = payload.login,
                multiDeviceAllowed = payload.multiDeviceAllowed,
            )
            if (created !is AccountCreationResult.Success) {
                accountManager.closeStorageAfterFailedImport()
                // Не Failure — значит, аккаунт появился уже после проверки в начале.
                val reason = (created as? AccountCreationResult.Failure)?.reason
                    ?: str(R.string.transfer_err_already_account)
                DiagnosticLog.w(DIAG_TAG) { "перенос: аккаунт не создан $reason" }
                return@withContext TransferApplyResult.Failure(reason)
            }
            DiagnosticLog.event(
                DIAG_TAG, "перенос применён",
                "контактов" to payload.contacts.size,
                "сообщений" to payload.messages.size,
            )
            TransferApplyResult.Success(payload.summary())
        }

    /**
     * Предпросмотр слияния [payload] в СУЩЕСТВУЮЩИЙ аккаунт (без записи): считает,
     * сколько контактов/сообщений/PSK будет ДОБАВЛЕНО, а сколько уже есть локально
     * (останется нетронутым). Требует наличия аккаунта на устройстве.
     */
    suspend fun previewMerge(payload: TransferPayload): TransferMergeResult =
        withContext(Dispatchers.IO) {
            if (accountStore.load() == null) {
                return@withContext TransferMergeResult.Failure(str(R.string.transfer_err_no_account_for_merge))
            }
            runCatching { computePlan(payload) }
                .fold(
                    onSuccess = { TransferMergeResult.Success(it.report) },
                    onFailure = { TransferMergeResult.Failure(it.message ?: str(R.string.transfer_err_apply_generic)) },
                )
        }

    /**
     * Сливает [payload] в СУЩЕСТВУЮЩИЙ аккаунт (§7.1.2, follow-up) — строго
     * add-only. Идентичность и Signal-состояние НЕ трогаются (иначе рассинхрон
     * локального Double Ratchet). Настройки применяются только при
     * [overwriteSettings] = true (по умолчанию — нет). Возвращает отчёт о том,
     * что реально добавлено и что оставлено локальным.
     */
    suspend fun merge(payload: TransferPayload, overwriteSettings: Boolean): TransferMergeResult =
        withContext(Dispatchers.IO) {
            if (accountStore.load() == null) {
                return@withContext TransferMergeResult.Failure(str(R.string.transfer_err_no_account_for_merge))
            }
            try {
                val plan = computePlan(payload)
                // Контакты: добавляем ТОЛЬКО новые. Сессию сбрасываем — она должна
                // переустановиться под ЛОКАЛЬНОЙ идентичностью (Signal-состояние
                // источника при merge не переносится).
                plan.contactsToAdd.forEach { contactStore.upsert(it.copy(sessionReady = false)) }
                // Сообщения: только новые и только для известных контактов.
                plan.messagesToAdd.forEach { tm ->
                    messageStore.insert(tm.toMessage())
                    tm.attachmentBytesB64?.let { b64 ->
                        runCatching {
                            messageStore.saveAttachmentBytes(tm.id, Base64.decode(b64, Base64.NO_WRAP))
                        }
                    }
                }
                // PSK: только там, где локального ещё нет (не перезаписываем слой A).
                plan.psksToAdd.forEach { pskStore.put(it) }

                val settingsApplied = if (overwriteSettings && plan.report.hasImportableSettings) {
                    payload.securitySettings?.let { secureStore.saveSecuritySettings(it) }
                    payload.torNetworkSettings?.let { secureStore.saveTorNetworkSettings(it) }
                    payload.privacySettings?.let { secureStore.savePrivacySettings(it) }
                    payload.notificationSettings?.let { secureStore.saveNotificationSettings(it) }
                    true
                } else {
                    false
                }
                DiagnosticLog.event(
                    DIAG_TAG, "слияние переноса",
                    "контактов" to plan.contactsToAdd.size,
                    "сообщений" to plan.messagesToAdd.size,
                    "настройки" to settingsApplied,
                )
                TransferMergeResult.Success(plan.report.copy(settingsApplied = settingsApplied))
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) { "слияние переноса упало" }
                TransferMergeResult.Failure(e.message ?: str(R.string.transfer_err_apply_generic))
            }
        }

    /** Внутренний «план» слияния: что добавить + сводный отчёт (без записи). */
    private class MergePlan(
        val contactsToAdd: List<StoredContact>,
        val messagesToAdd: List<TransferMessage>,
        val psksToAdd: List<PskRecord>,
        val report: TransferMergeReport,
    )

    private suspend fun computePlan(payload: TransferPayload): MergePlan {
        val localContactIds = contactStore.all().map { it.contact.id }.toHashSet()
        val newContacts = payload.contacts.filter { it.id !in localContactIds }
        val contactsToAdd = newContacts.map { it.toStored() }

        // Известные после слияния контакты = локальные ∪ добавляемые.
        val knownContactIds = HashSet(localContactIds).apply { addAll(newContacts.map { it.id }) }
        val existingMessageIds = messageStore.allMessages().map { it.id }.toHashSet()
        var messagesExisting = 0
        var messagesOrphaned = 0
        val messagesToAdd = ArrayList<TransferMessage>()
        for (tm in payload.messages) {
            when {
                tm.contactId !in knownContactIds -> messagesOrphaned++
                tm.id in existingMessageIds -> messagesExisting++
                else -> messagesToAdd += tm
            }
        }

        var psksConflicting = 0
        val psksToAdd = ArrayList<PskRecord>()
        for (record in payload.psks) {
            if (pskStore.hasPsk(record.contactId)) psksConflicting++ else psksToAdd += record
        }

        val localAccountId = accountManager.currentIdentity()?.accountId
        val hasSettings = payload.securitySettings != null ||
            payload.torNetworkSettings != null ||
            payload.privacySettings != null ||
            payload.notificationSettings != null

        val report = TransferMergeReport(
            identityMatchesLocal = localAccountId != null &&
                payload.sourceAccountId.isNotBlank() &&
                localAccountId == payload.sourceAccountId,
            contactsAdded = contactsToAdd.size,
            contactsExisting = payload.contacts.size - contactsToAdd.size,
            messagesAdded = messagesToAdd.size,
            messagesExisting = messagesExisting,
            messagesOrphaned = messagesOrphaned,
            psksAdded = psksToAdd.size,
            psksConflicting = psksConflicting,
            hasImportableSettings = hasSettings,
            settingsApplied = false,
        )
        return MergePlan(contactsToAdd, messagesToAdd, psksToAdd, report)
    }

    // --- Крипто (Argon2id → каскад слоя A) ---

    private fun sealWithPassword(plaintext: ByteArray, password: String, kdf: AccountKdfParams): String {
        val secret = deriveSecret(password, kdf)
        return try {
            val cipher = CryptoModule.createCascadeCipher(secret)
            Base64.encodeToString(cipher.encrypt(plaintext), Base64.NO_WRAP)
        } finally {
            secret.fill(0)
        }
    }

    private fun openWithPassword(payloadB64: String, password: String, kdf: AccountKdfParams): ByteArray {
        val secret = deriveSecret(password, kdf)
        return try {
            val cipher = CryptoModule.createCascadeCipher(secret)
            cipher.decrypt(Base64.decode(payloadB64, Base64.NO_WRAP))
        } finally {
            secret.fill(0)
        }
    }

    private fun deriveSecret(password: String, kdf: AccountKdfParams): ByteArray {
        val keyB64 = crypto.deriveWrappingKeyB64(password, kdf)
        return Base64.decode(keyB64, Base64.NO_WRAP)
    }

    private fun randomB64(lengthBytes: Int): String {
        val bytes = ByteArray(lengthBytes)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // --- Мэппинг доменных моделей ↔ модели переноса ---

    private fun StoredContact.toTransfer(): TransferContact = TransferContact(
        id = contact.id,
        nickname = contact.nickname,
        localName = contact.localName,
        about = contact.about,
        fingerprint = contact.fingerprint,
        verified = contact.verified,
        blocked = contact.blocked,
        mutedUntilMs = contact.mutedUntilMs,
        notificationSoundUri = contact.notificationSoundUri,
        callRingtoneUri = contact.callRingtoneUri,
        remoteIdentityB64 = remoteIdentityB64,
        remoteDevice = remoteDevice,
        bundleJson = bundleJson,
        sessionReady = sessionReady,
    )

    private fun TransferContact.toStored(): StoredContact = StoredContact(
        contact = Contact(
            id = id,
            nickname = nickname,
            localName = localName,
            about = about,
            fingerprint = fingerprint,
            verified = verified,
            blocked = blocked,
            mutedUntilMs = mutedUntilMs,
            notificationSoundUri = notificationSoundUri,
            callRingtoneUri = callRingtoneUri,
        ),
        remoteIdentityB64 = remoteIdentityB64,
        remoteDevice = remoteDevice,
        bundleJson = bundleJson,
        sessionReady = sessionReady,
    )

    private fun Message.toTransfer(includeAttachmentBytes: Boolean): TransferMessage = TransferMessage(
        id = id,
        contactId = contactId,
        direction = direction.name,
        body = body,
        timestampMs = timestampMs,
        status = status.name,
        attachment = attachment,
        attachmentLocal = attachmentLocal,
        attachmentBytesB64 = if (includeAttachmentBytes && attachmentLocal) {
            runCatching { messageStore.attachmentBytes(id) }.getOrNull()
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        } else {
            null
        },
        crypto = crypto,
    )

    private fun TransferMessage.toMessage(): Message = Message(
        id = id,
        contactId = contactId,
        direction = runCatching { MessageDirection.valueOf(direction) }
            .getOrDefault(MessageDirection.INCOMING),
        body = body,
        timestampMs = timestampMs,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.RECEIVED),
        attachment = attachment,
        attachmentLocal = attachmentLocal && attachmentBytesB64 != null,
        crypto = crypto,
    )

    private fun TransferPayload.summary(): TransferSummary = TransferSummary(
        hasAccount = hasAccount,
        login = login,
        sourceFingerprint = sourceFingerprint,
        multiDeviceAllowed = multiDeviceAllowed,
        contactCount = contacts.size,
        messageCount = messages.size,
        pskCount = psks.size,
        hasSettings = securitySettings != null,
        createdAtMs = createdAtMs,
    )

    private companion object {
        const val DIAG_TAG = "transfer"
        const val MIN_PASSWORD = 8
        const val SALT_LENGTH = 16
    }
}
