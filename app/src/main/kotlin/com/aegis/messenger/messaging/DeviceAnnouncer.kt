package com.aegis.messenger.messaging

import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.data.storage.ContactStore
import com.aegis.data.storage.PeerDevice
import com.aegis.data.storage.PeerDeviceStore
import com.aegis.data.storage.StoredContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Анонс/отзыв МОИХ устройств контактам (мультидевайс §7.1.1,
 * `docs/multidevice.md` §5 шаг 3–4 / §10) и ведение per-contact реестра устройств
 * СОБЕСЕДНИКОВ (`peer_devices`).
 *
 * Идёт по control-каналу переписки ([ControlChannel]) тем же защищённым
 * конвейером (слой A→B→Tor→onion), что и остальные метаданные — сервер видит
 * только шифртекст (zero-trust, MD-2). Так контакты узнают о моих новых
 * устройствах и начинают fan-out и на них (Этап 4), а я — о новых устройствах
 * контактов.
 *
 * Первичное устройство (`deviceId=1`) контакты уже знают из первичного обмена
 * ключами (`PskExchangePayload` → колонки `contacts`), поэтому анонсируются и
 * регистрируются только ДОПОЛНИТЕЛЬНЫЕ устройства (`deviceId ∈ 2..255`).
 */
class DeviceAnnouncer(
    private val contactStore: ContactStore,
    private val peerDeviceStore: PeerDeviceStore,
    private val controlChannel: ControlChannel,
    private val sessionManager: SignalSessionManager,
    private val deviceSettingsProvider: () -> DeviceSettings,
) {
    /**
     * Анонсирует ЭТО устройство всем контактам (§5, шаг 3): шлёт по control-каналу
     * мой device id + публичный identity + prekey-бандл. Вызывается на линкованном
     * устройстве при разблокировке (первичное устройство контактам уже известно).
     * Best-effort — недоступность Tor/релея не критична (повторится при следующей
     * разблокировке).
     */
    suspend fun announceSelfToContacts() = withContext(Dispatchers.IO) {
        val device = deviceSettingsProvider()
        if (device.isPrimary) return@withContext // первичное уже известно контактам
        if (!sessionManager.isAvailable) {
            DiagnosticLog.w(DIAG_TAG) { "анонс себя контактам отменён: слой B недоступен" }
            return@withContext
        }
        DiagnosticLog.event(DIAG_TAG, "анонс себя контактам", "устройство" to device.localDeviceId)
        val identityB64 = runCatching { sessionManager.identityKeyB64() }.getOrNull() ?: return@withContext
        val bundleJson = runCatching { sessionManager.publishBundleJson() }.getOrNull() ?: return@withContext
        val msg = ControlMessage(
            type = ControlMessage.TYPE_DEVICE_ANNOUNCE,
            deviceId = device.localDeviceId,
            deviceIdentityB64 = identityB64,
            deviceBundleJson = bundleJson,
            deviceLabel = device.label,
            atMs = System.currentTimeMillis(),
        )
        for (contact in contactStore.all()) {
            if (contact.contact.blocked) continue
            runCatching { controlChannel.send(contact, msg) }
        }
    }

    /**
     * Отзывает МОЁ устройство [deviceId] у всех контактов (§10): они перестают
     * fan-out на него. Локальный реестр своих устройств правит вызывающий.
     */
    suspend fun revokeMyDeviceToContacts(deviceId: Int) = withContext(Dispatchers.IO) {
        DiagnosticLog.event(DIAG_TAG, "отзыв устройства у контактов", "устройство" to deviceId)
        val msg = ControlMessage(
            type = ControlMessage.TYPE_DEVICE_REVOKE,
            deviceId = deviceId,
            atMs = System.currentTimeMillis(),
        )
        for (contact in contactStore.all()) {
            runCatching { controlChannel.send(contact, msg) }
        }
    }

    /**
     * Регистрирует/уточняет ДОПОЛНИТЕЛЬНОЕ устройство контакта из device-announce
     * (§5, шаг 4). Помечается непроверенным (verified=false) — UX предлагает
     * сверить отпечаток (§8). Первичное (`deviceId=1`) игнорируется (уже в
     * колонках контакта).
     */
    suspend fun onAnnounce(contact: StoredContact, msg: ControlMessage) = withContext(Dispatchers.IO) {
        val deviceId = msg.deviceId
        if (deviceId !in DeviceSettings.FIRST_LINKED_DEVICE_ID..DeviceSettings.MAX_DEVICE_ID) return@withContext
        DiagnosticLog.event(
            DIAG_TAG, "устройство контакта",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "устройство" to deviceId,
            "бандл" to DiagnosticLog.presence(msg.deviceBundleJson),
        )
        peerDeviceStore.upsert(
            PeerDevice(
                contactId = contact.contact.id,
                deviceId = deviceId,
                identityB64 = msg.deviceIdentityB64.ifBlank { null },
                bundleJson = msg.deviceBundleJson.ifBlank { null },
                sessionReady = false,
                verified = false,
                revoked = false,
                addedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Помечает устройство контакта отозванным из device-revoke (§10). */
    suspend fun onRevoke(contact: StoredContact, msg: ControlMessage) = withContext(Dispatchers.IO) {
        if (msg.deviceId < DeviceSettings.FIRST_LINKED_DEVICE_ID) return@withContext
        DiagnosticLog.event(
            DIAG_TAG, "устройство контакта отозвано",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "устройство" to msg.deviceId,
        )
        peerDeviceStore.setRevoked(contact.contact.id, msg.deviceId, true)
    }

    private companion object {
        const val DIAG_TAG = "device-announce"
    }
}
