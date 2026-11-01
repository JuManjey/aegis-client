package com.aegis.core.common.account

import kotlinx.serialization.Serializable

/**
 * Персистентная запись аккаунта на устройстве. НЕ содержит открытых секретов:
 * мнемоника хранится в [sealedMnemonicB64] — она запечатана XChaCha20-Poly1305
 * ключом, выведенным Argon2id из пароля ([kdfParams]), и ДОПОЛНИТЕЛЬНО обёрнута
 * аппаратным ключом Android Keystore/StrongBox на уровне хранилища.
 *
 * Для авто-аккаунта без пароля ([AccountProfile.hasPassword] = false) внешний
 * слой шифрования обеспечивает только Keystore-ключ.
 *
 * Уничтожение [sealedMnemonicB64] и Keystore-ключа = crypto-shredding аккаунта.
 */
@Serializable
data class SealedAccountRecord(
    val profile: AccountProfile,
    val kdfParams: AccountKdfParams,
    /** Запечатанная мнемоника (base64: nonce||ciphertext||tag). */
    val sealedMnemonicB64: String,
    /**
     * Ключ распечатывания для БЕСПАРОЛЬНОГО (авто) аккаунта, base64. Присутствует
     * только когда [AccountProfile.hasPassword] = false: в этом режиме защита
     * обеспечивается аппаратным Keystore-ключом, которым обёрнута вся запись.
     * Для аккаунтов с паролем — `null` (ключ выводится из пароля через Argon2id).
     */
    val autoUnlockKeyB64: String? = null,
    /**
     * Параметры Argon2id для проверки duress-пароля (§7.4/§8). `null`, если
     * duress не настроен.
     */
    val duressKdfParams: AccountKdfParams? = null,
    /**
     * Верификатор duress-пароля, base64 (= Argon2id(duressPassword, [duressKdfParams])).
     * НЕ используется для распечатывания мнемоники — служит только для распознавания
     * пароля-обманки на входе, чтобы вместо разблокировки запустить тихую очистку.
     */
    val duressVerifierB64: String? = null,
    /**
     * Ключ-обёртка мнемоники, зашифрованный аппаратным биометрическим ключом
     * Keystore (§14.3). `null`, если биометрический вход не включён. Позволяет
     * разблокировать аккаунт по биометрии без ввода пароля.
     */
    val biometricWrappedKeyB64: String? = null,
    /** IV (base64) для [biometricWrappedKeyB64] (AES-GCM). */
    val biometricIvB64: String? = null,
    /**
     * Стирать данные после [UnlockBackoff.WIPE_AFTER_FAILURES] неверных паролей
     * подряд (§14.3). Только для аккаунта с паролем; по умолчанию выключено.
     */
    val wipeAfterFailedUnlocks: Boolean = false,
    /** Версия формата записи — для будущих миграций хранилища. */
    val formatVersion: Int = 1,
)
