package com.aegis.core.common.device

import kotlinx.serialization.Serializable

/**
 * Одно из МОИХ устройств в реестре аккаунта (мультидевайс §7.1.1,
 * `docs/multidevice.md` §5). Ведётся на каждом устройстве, чтобы знать, кому
 * рассылать self-sync-копии (Этап 4) и какие device-announce слать контактам
 * (Этап 5). [identityB64]/[bundleJson] заполняются, когда устройство
 * анонсирует себя по self-sync (§4.2); до этого — известны только id и метка,
 * назначенные при линковке.
 */
@Serializable
data class LinkedDevice(
    /** device id устройства (2..255 для линкованных). */
    val deviceId: Int,
    /** Человекочитаемое имя устройства (задаётся при линковке). */
    val label: String = "",
    /** Signal identity-ключ устройства (base64); null, пока не анонсировано. */
    val identityB64: String? = null,
    /** prekey-бандл устройства для self-sync Signal-сессии; null, пока не анонсировано. */
    val bundleJson: String? = null,
    /** Когда устройство добавлено в реестр (Unix-мс). */
    val addedAtMs: Long = 0L,
    /** Отозвано ли устройство (device-revoke, §10): self-sync/fan-out на него прекращается. */
    val revoked: Boolean = false,
)

/**
 * Идентичность ЭТОГО устройства в рамках аккаунта (мультидевайс §7.1.1,
 * `docs/multidevice.md` §3).
 *
 * Аккаунт (мнемоника → `accountId`) одинаков на всех устройствах, а поверх него
 * каждое устройство получает стабильный [localDeviceId] (1..255). `deviceId=1` —
 * ПЕРВИЧНОЕ устройство (обратная совместимость: до мультидевайса всё было
 * захардкожено на 1). Линкованные устройства (Этап 3) получают id ∈ 2..255,
 * назначаемый первичным из счётчика [nextAssignableDeviceId].
 *
 * Хранится отдельным обёрнутым Keystore-файлом (см. `SecureStore`), как прочие
 * не-секретные настройки. Signal-identity устройства сюда НЕ входит — он живёт в
 * Signal-blob (SQLCipher) и у каждого устройства свой (инвариант MD-1).
 */
@Serializable
data class DeviceSettings(
    /** device id ЭТОГО устройства в аккаунте (1 = первичное). */
    val localDeviceId: Int = PRIMARY_DEVICE_ID,
    /** Человекочитаемое имя устройства («Пиксель», «Планшет»); пусто = не задано. */
    val label: String = "",
    /**
     * Следующий свободный device id для выдачи линкуемому устройству (Этап 3).
     * Значим только на первичном устройстве (реестр своих устройств). Начинается
     * с 2, инкрементируется при каждой успешной линковке.
     */
    val nextAssignableDeviceId: Int = FIRST_LINKED_DEVICE_ID,
    /**
     * Реестр ДРУГИХ моих устройств этого аккаунта (§5). Пополняется при линковке
     * (id+метка) и уточняется по self-sync-анонсам (identity+bundle, Этап 4).
     * Не содержит [localDeviceId]. Пусто до первой линковки ⇒ прежнее
     * одноустройственное поведение.
     */
    val linkedDevices: List<LinkedDevice> = emptyList(),
) {
    /** Это первичное устройство аккаунта? */
    val isPrimary: Boolean get() = localDeviceId == PRIMARY_DEVICE_ID

    /** Мои НЕотозванные устройства ≠ текущего — адресаты self-sync (Этап 4). */
    fun activeLinkedDevices(): List<LinkedDevice> =
        linkedDevices.filterNot { it.revoked || it.deviceId == localDeviceId }

    companion object {
        /** device id первичного устройства (обратная совместимость, §7.1.1). */
        const val PRIMARY_DEVICE_ID: Int = 1

        /** Первый device id, выдаваемый линкуемому устройству. */
        const val FIRST_LINKED_DEVICE_ID: Int = 2

        /** Максимальный device id (ограничение `ProtocolAddress::DeviceId = u8`). */
        const val MAX_DEVICE_ID: Int = 255

        /** Значение по умолчанию — первичное устройство без метки. */
        val DEFAULT: DeviceSettings = DeviceSettings()
    }
}
