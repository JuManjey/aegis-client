package com.aegis.core.common.psk

import kotlinx.serialization.Serializable

/**
 * Способ, которым ключ контейнера (PSK) попал на устройство (P4, §6.2/§10.3).
 *
 * ИНВАРИАНТ: PSK НИКОГДА не передаётся по сети. Способы [GENERATED], [QR],
 * [MNEMONIC], [NFC] переносят его вне сети; [REMOTE_HANDSHAKE] не переносит
 * вовсе — там секрет ВЫВОДИТСЯ из подписанного рукопожатия на обеих сторонах
 * независимо (см. `docs/contact_requests.md`).
 */
enum class PskSource {
    /** PSK сгенерирован на этом устройстве и показан собеседнику (лично). */
    GENERATED,
    /** PSK получен сканированием QR-кода собеседника (лично). */
    QR,
    /** PSK введён вручную как мнемоническая фраза (BIP-39-подобная). */
    MNEMONIC,
    /** PSK получен по NFC при касании устройств (локально, без сети). */
    NFC,

    /**
     * Секрет слоя A ВЫВЕДЕН из рукопожатия RCH v1 при удалённом добавлении
     * контакта: `HKDF(X25519(эфемерные) ‖ X25519(статические) ‖ Kyber1024 ‖
     * вклады сторон, info=transcript)`, нагрузки подписаны Ed25519-ключами
     * аккаунтов. По сети едут только публичные части — сам секрет не передаётся
     * и на обеих сторонах получается независимо.
     *
     * Отличие от личного обмена честное и отражено в UI ([ContactTrust]): здесь
     * идентичность держится на подлинности полученного адреса (accountId — это
     * коммитмент к ключу), а не на физической встрече.
     */
    REMOTE_HANDSHAKE,
}

/**
 * Полезная нагрузка ручного обмена PSK (QR / NFC), P4 §6.2.
 *
 * ВНИМАНИЕ: поле [pskB64] — это САМ секрет слоя A. Он показывается/передаётся
 * ТОЛЬКО лично (QR с FLAG_SECURE и автоскрытием, либо NFC при касании) и
 * НИКОГДА не уходит в сеть и не копируется в буфер обмена. Остальные поля —
 * вспомогательные (привязка к контакту, отпечаток для сверки).
 */
@Serializable
data class PskExchangePayload(
    /** Тип-маркер: отличает наш QR/NFC от посторонних кодов при сканировании. */
    val t: String = TYPE,
    /** Версия формата обмена (для согласованной эволюции). */
    val v: Int = VERSION,
    /** Base64 PSK — секрет слоя A (обменивается только лично). */
    val pskB64: String,
    /** accountId отправителя (для привязки PSK к контакту), опционально. */
    val fromAccountId: String = "",
    /** Отпечаток identity-ключа отправителя (safety number) для сверки, опционально. */
    val fromFingerprint: String = "",
    /** Человекочитаемая метка (никнейм), опционально. */
    val label: String = "",
    /**
     * Публичный Signal identity-ключ отправителя (base64) — для PSK-биндинга
     * HKDF и сессионной AEAD-обёртки (§6.2). Опционально: пусто в устаревших
     * кодах без слоя B.
     */
    val identityB64: String = "",
    /**
     * Публичный prekey-бандл отправителя (JSON, формат §0) — позволяет получателю
     * СРАЗУ установить исходящую Signal-сессию (X3DH/PQXDH) без сети. PSK при
     * этом не входит в KDF PQXDH (API libsignal хука не даёт): слой A (PSK) и
     * слой B (бандл) обмениваются одним личным жестом, а PSK привязывается
     * отдельным HKDF-тегом и AEAD-обёрткой plaintext. Опционально: пусто, если
     * слой B недоступен у отправителя.
     *
     * Передаётся ИНЛАЙН только по NFC (там нет лимита размера). В QR не влезает
     * (Kyber1024 ~2.9 КБ), поэтому по QR вместо инлайна едет [bundleId].
     */
    val bundleJson: String = "",
    /**
     * Опаковый id ПУБЛИЧНОГО prekey-бандла, опубликованного отправителем на релее
     * (`PUT /v1/prekeys/{id}`). Позволяет доставить бандл слоя B, когда он не
     * влезает в QR: получатель тянет бандл по этому id (`GET /v1/prekeys/{id}`) и
     * СВЕРЯЕТ его identity-ключ с [identityB64], закреплённым лично в QR. Секрет
     * (PSK) при этом остаётся ТОЛЬКО в QR — на релей уходит лишь публичный бандл,
     * а контент слоя A защищён PSK даже при скомпрометированном сервере (§10.7).
     */
    val bundleId: String = "",
) {
    companion object {
        const val TYPE: String = "aegis-psk"
        // v2: добавлены identityB64 + bundleJson (личный жест несёт слой B).
        const val VERSION: Int = 2
    }
}

/**
 * Запечатанная запись PSK для конкретного контакта (P4). Хранится ТОЛЬКО в
 * защищённом хранилище: весь файл PSK-стора дополнительно оборачивается
 * аппаратным ключом Keystore/StrongBox ([com.aegis.data.storage.KeyStoreManager]).
 *
 * [pskB64] лежит внутри уже обёрнутого файла (defense-in-depth) и в открытом
 * виде на диск НЕ попадает. В буфер обмена PSK не копируется никогда (§6.2).
 */
@Serializable
data class PskRecord(
    /** Идентификатор контакта, которому принадлежит PSK. */
    val contactId: String,
    /** Base64 PSK (внутри Keystore-обёрнутого файла). */
    val pskB64: String,
    /** Как PSK попал на устройство. */
    val source: PskSource,
    /** Отпечаток PSK (hex) для повторной сверки без раскрытия секрета (§6.2). */
    val pskFingerprintHex: String,
    /** accountId собеседника (если известен). */
    val peerAccountId: String = "",
    /** Отпечаток identity собеседника (safety number), если известен. */
    val peerFingerprint: String = "",
    /** Человекочитаемая метка (никнейм). */
    val label: String = "",
    val createdAtEpochMs: Long = 0L,
    val formatVersion: Int = 1,
) {
    /** Небесекретная проекция для UI (без байтов PSK). */
    fun toMeta(): PskMeta = PskMeta(
        contactId = contactId,
        source = source,
        pskFingerprintHex = pskFingerprintHex,
        peerAccountId = peerAccountId,
        peerFingerprint = peerFingerprint,
        label = label,
        createdAtEpochMs = createdAtEpochMs,
    )
}

/**
 * Небесекретное описание установленного PSK — безопасно для показа в UI.
 * НЕ содержит байтов PSK; только отпечаток и метаданные.
 */
data class PskMeta(
    val contactId: String,
    val source: PskSource,
    val pskFingerprintHex: String,
    val peerAccountId: String = "",
    val peerFingerprint: String = "",
    val label: String = "",
    val createdAtEpochMs: Long = 0L,
)
