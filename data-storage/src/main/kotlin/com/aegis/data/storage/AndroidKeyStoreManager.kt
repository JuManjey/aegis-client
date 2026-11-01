package com.aegis.data.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Реализация [KeyStoreManager] поверх Android Keystore (§6.3, §8 `store.hwKeystore`).
 *
 * Мастер-ключ обёртки (`aegis_master_wrap`) генерируется НЕЭКСПОРТИРУЕМЫМ и по
 * возможности привязывается к аппаратному StrongBox/TEE — приватный материал
 * никогда не покидает защищённый чип. Этим ключом оборачиваются запись аккаунта,
 * настройки и (поверх ключа аккаунта, см. [StorageSealer]) ключ БД и файл PSK.
 *
 * Мастер-ключ доступен без аутентификации пользователя: метаданные профиля
 * нужны экрану входа до разблокировки. Поэтому всё, что должно быть закрыто
 * вместе с аккаунтом, дополнительно запечатано ключом аккаунта.
 */
class AndroidKeyStoreManager(
    private val appContext: Context,
) : KeyStoreManager {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun isStrongBoxAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    override fun getOrCreateDatabaseKeyHandle(): DatabaseKeyHandle {
        val strongBox = getOrCreateWrapKey(DB_KEY_ALIAS)
        return DatabaseKeyHandleImpl(DB_KEY_ALIAS, strongBox)
    }

    override fun ensureBiometricBoundKey(): Boolean {
        if (keyStore.containsAlias(BIOMETRIC_ALIAS)) return true
        return try {
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                BIOMETRIC_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            // Только сильная биометрия. Если разрешить ещё и AUTH_DEVICE_CREDENTIAL,
            // Android привязывает ключ к блокировке экрана, а не к набору отпечатков:
            // знающий PIN телефона добавил бы свой отпечаток и открыл им аккаунт.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    /* timeout = */ 0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (isStrongBoxAvailable()) {
                runCatching { builder.setIsStrongBoxBacked(true) }
            }
            generator.init(builder.build())
            generator.generateKey()
            true
        } catch (_: Exception) {
            // Устройство без биометрии/защищённого замка — привязка недоступна.
            runCatching { if (keyStore.containsAlias(BIOMETRIC_ALIAS)) keyStore.deleteEntry(BIOMETRIC_ALIAS) }
            false
        }
    }

    // Пробная инициализация, а не проверка алиаса: аннулированный ключ остаётся в
    // Keystore, пока им не попробуют воспользоваться.
    override fun isBiometricBindingActive(): Boolean = biometricEncryptCipher() != null

    override fun removeBiometricBinding() {
        runCatching { if (keyStore.containsAlias(BIOMETRIC_ALIAS)) keyStore.deleteEntry(BIOMETRIC_ALIAS) }
    }

    override fun removeLegacyBiometricKey(): Boolean = runCatching {
        if (!keyStore.containsAlias(LEGACY_BIOMETRIC_ALIAS)) return@runCatching false
        keyStore.deleteEntry(LEGACY_BIOMETRIC_ALIAS)
        true
    }.getOrDefault(false)

    override fun biometricEncryptCipher(): Cipher? = biometricCipher { key ->
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun biometricDecryptCipher(ivB64: String): Cipher? = biometricCipher { key ->
        val iv = android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    private inline fun biometricCipher(init: (SecretKey) -> Cipher): Cipher? = try {
        val key = (keyStore.getEntry(BIOMETRIC_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        key?.let(init)
    } catch (_: KeyPermanentlyInvalidatedException) {
        removeBiometricBinding()
        null
    } catch (_: Exception) {
        null
    }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val key = resolveKey(MASTER_WRAP_ALIAS)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_LENGTH) { "Некорректный размер обёрнутых данных" }
        val key = resolveKey(MASTER_WRAP_ALIAS)
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val ciphertext = wrapped.copyOfRange(IV_LENGTH, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    override fun destroyAllKeys() {
        listOf(MASTER_WRAP_ALIAS, DB_KEY_ALIAS, BIOMETRIC_ALIAS, LEGACY_BIOMETRIC_ALIAS).forEach { alias ->
            runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
        }
    }

    /** Возвращает ключ по алиасу, создавая при отсутствии. */
    private fun resolveKey(alias: String): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        getOrCreateWrapKey(alias)
        return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Создаёт AES-256/GCM-ключ в Keystore. Возвращает `true`, если он привязан
     * к StrongBox. При недоступности StrongBox — прозрачный fallback на TEE.
     */
    private fun getOrCreateWrapKey(alias: String): Boolean {
        if (keyStore.containsAlias(alias)) {
            // Уже создан ранее; точную аппаратную привязку не перепроверяем здесь.
            return isStrongBoxAvailable()
        }
        val useStrongBox = isStrongBoxAvailable()
        return try {
            generateKey(alias, strongBox = useStrongBox)
            useStrongBox
        } catch (_: StrongBoxUnavailableException) {
            generateKey(alias, strongBox = false)
            false
        }
    }

    private fun generateKey(alias: String, strongBox: Boolean) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    private class DatabaseKeyHandleImpl(
        override val alias: String,
        override val strongBoxBacked: Boolean,
    ) : DatabaseKeyHandle

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_WRAP_ALIAS = "aegis_master_wrap"
        const val DB_KEY_ALIAS = "aegis_db_key"
        const val BIOMETRIC_ALIAS = "aegis_biometric_lock_v2"
        const val LEGACY_BIOMETRIC_ALIAS = "aegis_biometric_lock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
