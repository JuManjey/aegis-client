package com.aegis.core.common.device

import com.aegis.core.common.account.AccountKdfParams
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.core.common.transfer.TransferContact
import kotlinx.serialization.Serializable

/**
 * Device-linking — привязка нового устройства к аккаунту (мультидевайс §7.1.1,
 * `docs/multidevice.md` §5). Полностью ВНЕ СЕТИ: пакет линковки шифруется
 * паролем (Argon2id → каскад слоя A) и передаётся файлом/QR — как перенос
 * аккаунта (§7.1.2/§1.31), НО с принципиальным отличием (инвариант MD-1):
 * Signal-состояние (храповики Double Ratchet) НЕ клонируется. Новое устройство
 * создаёт СВОЙ Signal identity и устанавливает СВОИ per-device сессии; общего
 * ratchet между устройствами нет никогда.
 */

/**
 * Устройство контакта, известное первичному устройству (реестр §7), которое
 * передаётся линкуемому устройству, чтобы оно сразу знало все устройства
 * собеседников для fan-out (Этап 4). Signal-состояние сюда НЕ входит — только
 * публичные identity/bundle (новое устройство установит свои сессии само).
 */
@Serializable
data class LinkPeerDevice(
    /** accountId контакта-владельца устройства (`aegis:<hex>`). */
    val contactId: String,
    /** device id устройства контакта (1..255). */
    val deviceId: Int,
    val identityB64: String? = null,
    val bundleJson: String? = null,
    val verified: Boolean = false,
    val revoked: Boolean = false,
)

/**
 * Полезная нагрузка линковки — то, что запечатывается каскадом слоя A. Несёт
 * мастер-секрет аккаунта (мнемонику), назначенный новому устройству
 * [assignedDeviceId], публичные данные контактов/устройств и ключи слоя A (PSK).
 * НЕ несёт Signal-состояние (MD-1) и НЕ несёт переписки (история сойдётся по
 * self-sync, Этап 4).
 */
@Serializable
data class LinkPayload(
    val v: Int = VERSION,
    val createdAtMs: Long = 0L,
    /** accountId аккаунта (общий для всех устройств) — для сверки после расшифровки. */
    val accountId: String = "",
    /** Логин аккаунта (для отображения). */
    val login: String = "",
    /** Мастер-секрет аккаунта — тот же на всех устройствах (§7.1). */
    val mnemonic: String,
    /** device id, выданный ЭТОМУ (новому) устройству первичным (2..255). */
    val assignedDeviceId: Int,
    /** Метка, назначенная новому устройству на первичном («Планшет» и т.п.). */
    val assignedLabel: String = "",
    /** device id первичного устройства (обычно 1) и его метка. */
    val primaryDeviceId: Int = DeviceSettings.PRIMARY_DEVICE_ID,
    val primaryLabel: String = "",
    /** Контакты без Signal-состояния (id + remoteIdentity + bundle; sessionReady=false). */
    val contacts: List<TransferContact> = emptyList(),
    /** Ключи слоя A по контактам (нужны новому устройству для расшифровки слоя A). */
    val psks: List<PskRecord> = emptyList(),
    /** Реестр устройств контактов (§7). */
    val knownPeerDevices: List<LinkPeerDevice> = emptyList(),
    /** Опциональные настройки — новое устройство наследует профиль безопасности. */
    val securitySettings: SecuritySettings? = null,
    val torNetworkSettings: TorNetworkSettings? = null,
    val privacySettings: PrivacySettings? = null,
    val notificationSettings: NotificationSettings? = null,
) {
    companion object {
        const val VERSION: Int = 1
    }
}

/**
 * Внешняя оболочка пакета линковки (то, что реально уходит в файл/QR). Содержит
 * только параметры KDF (соль + Argon2id) и запечатанную нагрузку [payloadB64]
 * (= base64( каскад-слой-A( JSON([LinkPayload]) ) )). Без пароля бесполезна:
 * ни мнемоники, ни идентичности в открытом виде здесь нет. [t] — маркер формата.
 */
@Serializable
data class LinkEnvelope(
    val t: String = MAGIC,
    val v: Int = VERSION,
    val kdf: AccountKdfParams,
    val payloadB64: String,
    val createdAtMs: Long = 0L,
) {
    companion object {
        const val MAGIC: String = "aegis-link"
        const val VERSION: Int = 1
    }
}
