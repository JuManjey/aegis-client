package com.aegis.messenger.account

import android.content.Context
import android.util.Base64
import com.aegis.core.common.account.AccountKdfParams
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.device.LinkEnvelope
import com.aegis.core.common.device.LinkPayload
import com.aegis.core.common.device.LinkPeerDevice
import com.aegis.core.common.device.LinkedDevice
import com.aegis.core.common.model.Contact
import com.aegis.core.common.transfer.TransferContact
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.cascade.CascadeAuthException
import com.aegis.data.storage.AccountStore
import com.aegis.data.storage.ContactStore
import com.aegis.data.storage.PeerDevice
import com.aegis.data.storage.PeerDeviceStore
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.SecureStore
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Сводка о пакете линковки для показа пользователю. */
data class LinkSummary(
    val accountId: String,
    val login: String,
    val assignedDeviceId: Int,
    val assignedLabel: String,
    val primaryDeviceId: Int,
    val contactCount: Int,
    val pskCount: Int,
    val hasSettings: Boolean,
    val createdAtMs: Long,
)

/** Результат сборки пакета линковки на первичном устройстве (§5). */
sealed interface LinkBuildResult {
    data class Success(
        val envelopeJson: String,
        val sizeBytes: Int,
        val summary: LinkSummary,
    ) : LinkBuildResult

    data class Failure(val reason: String) : LinkBuildResult
}

/** Результат распечатывания принятого пакета линковки (перед применением). */
sealed interface LinkOpenResult {
    data class Success(val payload: LinkPayload, val summary: LinkSummary) : LinkOpenResult
    data object WrongPassword : LinkOpenResult
    data class Invalid(val reason: String) : LinkOpenResult
}

/** Результат применения пакета линковки на новом (чистом) устройстве. */
sealed interface LinkApplyResult {
    data class Success(val summary: LinkSummary) : LinkApplyResult
    data class Failure(val reason: String) : LinkApplyResult
}

/**
 * Device-linking (мультидевайс §7.1.1, `docs/multidevice.md` §5) — привязка
 * нового устройства к аккаунту ВНЕ СЕТИ.
 *
 * Первичное устройство собирает [LinkPayload] (мнемоника + выданный device id +
 * публичные данные контактов/PSK), запечатывает каскадом слоя A на ключе
 * Argon2id из пароля линковки и отдаёт [LinkEnvelope] (QR/файл). Новое
 * устройство тем же паролем распечатывает пакет, создаёт СВОЙ Signal identity
 * (`localDeviceId = assignedDeviceId`) и разворачивает данные. Signal-состояние
 * НЕ переносится (инвариант MD-1) — новое устройство держит собственные
 * per-device сессии; история сойдётся по self-sync (Этап 4).
 */
class DeviceLinking(
    private val appContext: Context,
    private val accountManager: AccountManager,
    private val accountStore: AccountStore,
    private val contactStore: ContactStore,
    private val peerDeviceStore: PeerDeviceStore,
    private val pskStore: PskStore,
    private val secureStore: SecureStore,
    private val crypto: AccountCrypto = AccountCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val secureRandom = SecureRandom()

    private fun str(resId: Int, vararg args: Any): String =
        LocaleManager.wrap(appContext).getString(resId, *args)

    /**
     * Собирает пакет линковки на первичном устройстве. Выделяет новому устройству
     * следующий свободный device id (из [DeviceSettings.nextAssignableDeviceId]),
     * инкрементирует счётчик и добавляет устройство в свой реестр
     * ([DeviceSettings.linkedDevices]). Требует разблокированного аккаунта с
     * разрешённым мультидевайсом (§7.1.1).
     */
    suspend fun buildEnvelope(
        password: String,
        newDeviceLabel: String,
        includeSettings: Boolean = true,
    ): LinkBuildResult = withContext(Dispatchers.IO) {
        if (!crypto.isReady) {
            return@withContext LinkBuildResult.Failure(str(R.string.transfer_err_crypto_unavailable))
        }
        if (password.length < MIN_PASSWORD) {
            return@withContext LinkBuildResult.Failure(
                str(R.string.transfer_err_password_too_short, MIN_PASSWORD),
            )
        }
        val record = accountStore.load()
            ?: return@withContext LinkBuildResult.Failure(str(R.string.transfer_err_account_not_found))
        if (!record.profile.multiDeviceAllowed) {
            return@withContext LinkBuildResult.Failure(str(R.string.link_err_multidevice_disabled))
        }
        val identity = accountManager.currentIdentity()
            ?: return@withContext LinkBuildResult.Failure(str(R.string.transfer_err_account_locked))
        val mnemonic = accountManager.unlockedMnemonic
            ?: return@withContext LinkBuildResult.Failure(str(R.string.transfer_err_account_locked))

        val device = secureStore.loadDeviceSettings()
        val assignedId = device.nextAssignableDeviceId
        if (assignedId > DeviceSettings.MAX_DEVICE_ID) {
            return@withContext LinkBuildResult.Failure(str(R.string.link_err_no_device_ids))
        }

        try {
            val contacts = contactStore.all()
            val knownPeerDevices = contacts.flatMap { stored ->
                stored.devices.map { it.toLink(stored.contact.id) }
            }
            val payload = LinkPayload(
                createdAtMs = System.currentTimeMillis(),
                accountId = identity.accountId,
                login = record.profile.login,
                mnemonic = mnemonic,
                assignedDeviceId = assignedId,
                assignedLabel = newDeviceLabel.trim(),
                primaryDeviceId = device.localDeviceId,
                primaryLabel = device.label,
                contacts = contacts.map { it.toTransfer() },
                psks = pskStore.exportAll(),
                knownPeerDevices = knownPeerDevices,
                securitySettings = if (includeSettings) secureStore.loadSecuritySettings() else null,
                torNetworkSettings = if (includeSettings) secureStore.loadTorNetworkSettings() else null,
                privacySettings = if (includeSettings) secureStore.loadPrivacySettings() else null,
                notificationSettings = if (includeSettings) secureStore.loadNotificationSettings() else null,
            )

            val plaintext = json.encodeToString(LinkPayload.serializer(), payload).encodeToByteArray()
            val kdf = AccountKdfParams(saltB64 = randomB64(SALT_LENGTH))
            val payloadB64 = sealWithPassword(plaintext, password, kdf)
            plaintext.fill(0)

            val envelope = LinkEnvelope(
                kdf = kdf,
                payloadB64 = payloadB64,
                createdAtMs = payload.createdAtMs,
            )
            val envelopeJson = json.encodeToString(LinkEnvelope.serializer(), envelope)

            // Регистрируем новое устройство и двигаем счётчик выдачи id. Identity/
            // bundle появятся позже — из self-sync-анонса нового устройства (Этап 4).
            val updatedDevice = device.copy(
                nextAssignableDeviceId = assignedId + 1,
                linkedDevices = device.linkedDevices.filterNot { it.deviceId == assignedId } +
                    LinkedDevice(
                        deviceId = assignedId,
                        label = newDeviceLabel.trim(),
                        addedAtMs = System.currentTimeMillis(),
                    ),
            )
            secureStore.saveDeviceSettings(updatedDevice)

            DiagnosticLog.event(
                DIAG_TAG, "пакет линковки собран",
                "новоеУстройство" to assignedId,
                "контактов" to contacts.size,
                "байт" to envelopeJson.toByteArray().size,
            )
            LinkBuildResult.Success(
                envelopeJson = envelopeJson,
                sizeBytes = envelopeJson.toByteArray().size,
                summary = payload.summary(),
            )
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "сборка пакета линковки упала" }
            LinkBuildResult.Failure(e.message ?: str(R.string.transfer_err_build_generic))
        }
    }

    /** Распечатывает принятую оболочку паролем (без применения — для предпросмотра). */
    suspend fun openEnvelope(envelopeJson: String, password: String): LinkOpenResult =
        withContext(Dispatchers.IO) {
            if (!crypto.isReady) {
                return@withContext LinkOpenResult.Invalid(str(R.string.transfer_err_crypto_unavailable))
            }
            val envelope = runCatching {
                json.decodeFromString(LinkEnvelope.serializer(), envelopeJson.trim())
            }.getOrElse { return@withContext LinkOpenResult.Invalid(str(R.string.transfer_err_not_aegis)) }
            if (envelope.t != LinkEnvelope.MAGIC) {
                return@withContext LinkOpenResult.Invalid(str(R.string.transfer_err_unknown_format))
            }
            val plaintext = try {
                openWithPassword(envelope.payloadB64, password, envelope.kdf)
            } catch (_: CascadeAuthException) {
                return@withContext LinkOpenResult.WrongPassword
            } catch (e: Exception) {
                return@withContext LinkOpenResult.Invalid(e.message ?: str(R.string.transfer_err_open_generic))
            }
            val payload = runCatching {
                json.decodeFromString(LinkPayload.serializer(), plaintext.decodeToString())
            }.getOrElse {
                plaintext.fill(0)
                return@withContext LinkOpenResult.Invalid(str(R.string.transfer_err_corrupt))
            }
            plaintext.fill(0)
            DiagnosticLog.event(
                DIAG_TAG, "пакет линковки открыт",
                "устройство" to payload.assignedDeviceId,
                "контактов" to payload.contacts.size,
            )
            LinkOpenResult.Success(payload, payload.summary())
        }

    /**
     * Применяет пакет линковки на ЧИСТОМ устройстве: сохраняет идентичность этого
     * устройства ([DeviceSettings] c `localDeviceId = assignedDeviceId`),
     * разворачивает контакты/PSK/устройства/настройки и последним создаёт аккаунт
     * из общей мнемоники (пароль линковки становится локальным). Требует, чтобы
     * на устройстве ещё не было аккаунта.
     */
    suspend fun apply(payload: LinkPayload, password: String): LinkApplyResult =
        withContext(Dispatchers.IO) {
            if (accountStore.load() != null) {
                return@withContext LinkApplyResult.Failure(str(R.string.transfer_err_already_account))
            }
            val mnemonic = payload.mnemonic
            if (mnemonic.isBlank() || !crypto.validateMnemonic(mnemonic)) {
                return@withContext LinkApplyResult.Failure(str(R.string.transfer_err_bad_mnemonic))
            }
            if (payload.assignedDeviceId !in DeviceSettings.FIRST_LINKED_DEVICE_ID..DeviceSettings.MAX_DEVICE_ID) {
                return@withContext LinkApplyResult.Failure(str(R.string.link_err_bad_device_id))
            }

            try {
                // Хранилища закрыты ключом из мнемоники — открываем их ею до записи.
                accountManager.openStorageForImport(mnemonic)
                // Идентичность ЭТОГО устройства: выданный device id + реестр (пока
                // только первичное — его identity/bundle придут по self-sync).
                val deviceSettings = DeviceSettings(
                    localDeviceId = payload.assignedDeviceId,
                    label = payload.assignedLabel,
                    nextAssignableDeviceId = DeviceSettings.FIRST_LINKED_DEVICE_ID,
                    linkedDevices = listOf(
                        LinkedDevice(
                            deviceId = payload.primaryDeviceId,
                            label = payload.primaryLabel,
                            addedAtMs = System.currentTimeMillis(),
                        ),
                    ),
                )
                secureStore.saveDeviceSettings(deviceSettings)

                // Контакты (без Signal-состояния): свои сессии установятся заново.
                payload.contacts.forEach { tc ->
                    contactStore.upsert(tc.toStored())
                    contactStore.setLocalName(tc.id, Contact.sanitizeLocalName(tc.localName))
                }
                // Доп. устройства контактов (не первичные) — в реестр peer_devices.
                payload.knownPeerDevices
                    .filter { it.deviceId != DeviceSettings.PRIMARY_DEVICE_ID }
                    .forEach { peerDeviceStore.upsert(it.toStored()) }
                payload.psks.forEach { pskStore.put(it) }
                payload.securitySettings?.let { secureStore.saveSecuritySettings(it) }
                payload.torNetworkSettings?.let { secureStore.saveTorNetworkSettings(it) }
                payload.privacySettings?.let { secureStore.savePrivacySettings(it) }
                payload.notificationSettings?.let { secureStore.saveNotificationSettings(it) }
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) { "применение линковки упало" }
                accountManager.closeStorageAfterFailedImport()
                return@withContext LinkApplyResult.Failure(e.message ?: str(R.string.transfer_err_apply_generic))
            }

            // Последним — создаём аккаунт из общей мнемоники (мультидевайс включён:
            // это устройство уже часть мультидевайс-аккаунта). Флаг флипает состояние
            // в Unlocked, поэтому делаем это ПОСЛЕ разворачивания данных.
            val created = accountManager.recoverAccount(
                mnemonic = mnemonic,
                password = password,
                login = payload.login,
                multiDeviceAllowed = true,
            )
            val failure = when (created) {
                is AccountCreationResult.Success -> null
                is AccountCreationResult.Failure -> created.reason
                // Аккаунт появился уже после проверки в начале: чужой не заменяем.
                AccountCreationResult.ReplaceConfirmationRequired -> str(R.string.transfer_err_already_account)
            }
            if (failure != null) {
                accountManager.closeStorageAfterFailedImport()
                DiagnosticLog.w(DIAG_TAG) { "линковка: аккаунт не создан $failure" }
                return@withContext LinkApplyResult.Failure(failure)
            }
            DiagnosticLog.event(
                DIAG_TAG, "линковка применена",
                "устройство" to payload.assignedDeviceId,
                "контактов" to payload.contacts.size,
            )
            LinkApplyResult.Success(payload.summary())
        }

    // --- Крипто (Argon2id → каскад слоя A), как в переносе аккаунта ---

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

    private fun deriveSecret(password: String, kdf: AccountKdfParams): ByteArray =
        Base64.decode(crypto.deriveWrappingKeyB64(password, kdf), Base64.NO_WRAP)

    private fun randomB64(lengthBytes: Int): String {
        val bytes = ByteArray(lengthBytes)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // --- Мэппинг доменных моделей ↔ модели линковки ---

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
        // Новое устройство устанавливает СВОИ сессии — состояние источника не годится.
        sessionReady = false,
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
        sessionReady = false,
    )

    private fun PeerDevice.toLink(contactId: String): LinkPeerDevice = LinkPeerDevice(
        contactId = contactId,
        deviceId = deviceId,
        identityB64 = identityB64,
        bundleJson = bundleJson,
        verified = verified,
        revoked = revoked,
    )

    private fun LinkPeerDevice.toStored(): PeerDevice = PeerDevice(
        contactId = contactId,
        deviceId = deviceId,
        identityB64 = identityB64,
        bundleJson = bundleJson,
        sessionReady = false,
        verified = verified,
        revoked = revoked,
        addedAtMs = System.currentTimeMillis(),
    )

    private fun LinkPayload.summary(): LinkSummary = LinkSummary(
        accountId = accountId,
        login = login,
        assignedDeviceId = assignedDeviceId,
        assignedLabel = assignedLabel,
        primaryDeviceId = primaryDeviceId,
        contactCount = contacts.size,
        pskCount = psks.size,
        hasSettings = securitySettings != null,
        createdAtMs = createdAtMs,
    )

    private companion object {
        const val DIAG_TAG = "device-link"
        const val MIN_PASSWORD = 8
        const val SALT_LENGTH = 16
    }
}
