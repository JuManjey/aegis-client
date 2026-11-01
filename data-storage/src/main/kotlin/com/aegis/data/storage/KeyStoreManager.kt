package com.aegis.data.storage

/**
 * Управление ключами в аппаратном хранилище (Android Keystore / StrongBox / TEE),
 * §6.3 и §8 (`store.hwKeystore`).
 *
 * Мастер-ключ обёртки — внешний, привязанный к устройству слой для записи
 * аккаунта, настроек, ключа SQLCipher и файла PSK. Последние два вдобавок
 * запечатаны ключом аккаунта ([StorageSealer]): мастер-ключ доступен без
 * аутентификации пользователя и в одиночку переписку не защищает.
 *
 * Реализация ([AndroidKeyStoreManager]) использует `KeyGenParameterSpec` с
 * `setIsStrongBoxBacked(true)` и graceful fallback на TEE для устройств без
 * StrongBox.
 */
interface KeyStoreManager {

    /** Поддерживает ли устройство аппаратный StrongBox (Secure Element). */
    fun isStrongBoxAvailable(): Boolean

    /**
     * Получить (или создать при первом запуске) ключ шифрования БД.
     * Возвращает не сам ключ, а дескриптор/обёртку для передачи в SQLCipher.
     */
    fun getOrCreateDatabaseKeyHandle(): DatabaseKeyHandle

    /**
     * Привязать разблокировку к биометрии (§14.3, `store.biometricLock`): создаёт
     * в Keystore ключ, доступный только после сильной биометрической
     * аутентификации и аннулируемый при добавлении нового отпечатка. Идемпотентно.
     *
     * @return `true`, если ключ создан/уже существует и устройство поддерживает
     *   аппаратную привязку к аутентификации; `false` при недоступности.
     */
    fun ensureBiometricBoundKey(): Boolean

    /** Активна ли биометрическая привязка (ключ создан и не аннулирован). */
    fun isBiometricBindingActive(): Boolean

    /** Снять биометрическую привязку (удалить ключ). */
    fun removeBiometricBinding()

    /**
     * Удалить биометрический ключ прежнего формата (принимал и PIN устройства).
     * @return `true`, если такой ключ был.
     */
    fun removeLegacyBiometricKey(): Boolean

    /**
     * Cipher в режиме ШИФРОВАНИЯ на биометрическом ключе (§14.3). Возвращается
     * НЕаутентифицированным: перед [javax.crypto.Cipher.doFinal] его нужно
     * подтвердить через `BiometricPrompt.CryptoObject`. Свежий IV доступен через
     * [javax.crypto.Cipher.getIV] после инициализации. `null`, если ключ
     * недоступен/биометрия не привязана.
     */
    fun biometricEncryptCipher(): javax.crypto.Cipher?

    /**
     * Cipher в режиме РАСШИФРОВАНИЯ на биометрическом ключе (§14.3) с заданным
     * [ivB64] (base64). Так же требует подтверждения через `BiometricPrompt`.
     * `null`, если ключ недоступен/IV некорректен. Аннулированный ключ (на
     * устройстве изменился набор отпечатков) удаляется — после этого
     * [isBiometricBindingActive] вернёт `false`.
     */
    fun biometricDecryptCipher(ivB64: String): javax.crypto.Cipher?

    /**
     * Обернуть произвольные данные аппаратным ключом (AES-GCM). Используется как
     * ВНЕШНИЙ слой над запечатанной мнемоникой/настройками: без этого ключа,
     * привязанного к устройству, данные бесполезны даже при копировании файла.
     * Формат результата: `iv(12) || ciphertext||tag`.
     */
    fun wrap(plaintext: ByteArray): ByteArray

    /** Обратная операция к [wrap]. Бросает исключение при повреждении/подмене. */
    fun unwrap(wrapped: ByteArray): ByteArray

    /**
     * Уничтожить ВСЕ ключи (ступень 1 экстренной очистки — crypto-shredding,
     * §7.4). После вызова вся зашифрованная БД и аккаунт становятся нечитаемым
     * мусором.
     */
    fun destroyAllKeys()
}

/**
 * Непрозрачный дескриптор ключа БД. Реальный материал ключа не выносится в
 * управляемую память в открытом виде дольше, чем необходимо (§6.3).
 */
interface DatabaseKeyHandle {
    /** Алиас ключа в Keystore. */
    val alias: String
    /** Привязан ли ключ к StrongBox (иначе — TEE-fallback). */
    val strongBoxBacked: Boolean
}
