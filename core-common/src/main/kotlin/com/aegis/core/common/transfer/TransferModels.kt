package com.aegis.core.common.transfer

import com.aegis.core.common.account.AccountKdfParams
import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.MessageCryptoInfo
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorNetworkSettings
import kotlinx.serialization.Serializable

/**
 * Перенос аккаунта с устройства на устройство (§7.1.2, §14.1).
 *
 * Механизм полностью ВНЕ СЕТИ: пакет переноса шифруется на пароле, который
 * пользователь придумывает и вводит вручную на обоих устройствах, и передаётся
 * файлом или QR-кодом (не через релей). Секреты внутри пакета (мнемоника, PSK,
 * ключ Signal-состояния) в открытом виде наружу не попадают — весь [TransferPayload]
 * запечатан каскадом слоя A (§5) на ключе, выведенном Argon2id из пароля переноса.
 */

/**
 * Что именно перенести (§7.1.2 — выборочный перенос). Пользователь снимает
 * ненужные пункты на устройстве-источнике. `account` включает мнемонику
 * (идентичность) и обязателен для полного восстановления на чистом устройстве;
 * без него в пакет попадут только выбранные данные (контакты/переписки/…).
 */
@Serializable
data class TransferSelection(
    val account: Boolean = true,
    val contacts: Boolean = true,
    val conversations: Boolean = true,
    val psks: Boolean = true,
    val settings: Boolean = true,
)

/** Контакт для переноса (зеркало [com.aegis.data.storage.StoredContact]). */
@Serializable
data class TransferContact(
    val id: String,
    val nickname: String,
    /** Локальный алиас чата; пусто — на приёмнике будет показан [nickname]. */
    val localName: String = "",
    val about: String = "",
    val fingerprint: String = "",
    val verified: Boolean = false,
    val blocked: Boolean = false,
    val mutedUntilMs: Long = 0L,
    val notificationSoundUri: String = "",
    val callRingtoneUri: String = "",
    val remoteIdentityB64: String? = null,
    val remoteDevice: Int = 1,
    val bundleJson: String? = null,
    val sessionReady: Boolean = false,
)

/**
 * Сообщение для переноса (зеркало [com.aegis.core.common.model.Message]).
 * [direction]/[status] хранятся как имена enum. Байты вложения — опционально
 * в [attachmentBytesB64] (были локально доступны на источнике).
 */
@Serializable
data class TransferMessage(
    val id: String,
    val contactId: String,
    val direction: String,
    val body: String,
    val timestampMs: Long,
    val status: String,
    val attachment: AttachmentRef? = null,
    val attachmentLocal: Boolean = false,
    val attachmentBytesB64: String? = null,
    val crypto: MessageCryptoInfo? = null,
)

/**
 * Полезная нагрузка переноса — то, что запечатывается каскадом слоя A. Все поля
 * секций опциональны: присутствуют только выбранные (§7.1.2).
 */
@Serializable
data class TransferPayload(
    val v: Int = VERSION,
    val createdAtMs: Long = 0L,
    /** accountId источника (для сверки/подтверждения после расшифровки). */
    val sourceAccountId: String = "",
    val sourceFingerprint: String = "",
    val login: String = "",
    val multiDeviceAllowed: Boolean = false,
    /** Мнемоника аккаунта — присутствует, если перенесена идентичность (§7.1). */
    val mnemonic: String? = null,
    /** Blob состояния Signal (храповики Double Ratchet), если перенесены переписки. */
    val signalStateB64: String? = null,
    val contacts: List<TransferContact> = emptyList(),
    val messages: List<TransferMessage> = emptyList(),
    val psks: List<PskRecord> = emptyList(),
    val securitySettings: SecuritySettings? = null,
    val torNetworkSettings: TorNetworkSettings? = null,
    val privacySettings: PrivacySettings? = null,
    val notificationSettings: NotificationSettings? = null,
) {
    val hasAccount: Boolean get() = !mnemonic.isNullOrBlank()

    companion object {
        const val VERSION: Int = 1
    }
}

/**
 * Внешняя оболочка пакета переноса (то, что реально уходит в файл/QR).
 *
 * Содержит ТОЛЬКО параметры KDF (соль + параметры Argon2id для повторного вывода
 * ключа из пароля на устройстве-приёмнике) и запечатанную нагрузку [payloadB64]
 * (= base64( каскад-слой-A( JSON([TransferPayload]) ) )). Никаких открытых
 * секретов и никакой открытой идентичности здесь нет: без пароля оболочка
 * бесполезна. [t] — маркер формата для распознавания при сканировании QR.
 */
@Serializable
data class TransferEnvelope(
    val t: String = MAGIC,
    val v: Int = VERSION,
    val kdf: AccountKdfParams,
    val payloadB64: String,
    val createdAtMs: Long = 0L,
) {
    companion object {
        const val MAGIC: String = "aegis-transfer"
        const val VERSION: Int = 1
    }
}
