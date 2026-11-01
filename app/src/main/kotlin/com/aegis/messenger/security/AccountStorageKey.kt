package com.aegis.messenger.security

import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StorageLockedException
import com.aegis.data.storage.StoragePurpose
import com.aegis.data.storage.StorageSealer

/**
 * Ключ локальных хранилищ, выведенный из мнемоники открытого аккаунта
 * ([AccountCrypto.deriveStorageKey]). Запечатывает каскадом слоя A ключ SQLCipher
 * и файл PSK (см. [StorageSealer]). Живёт только в памяти: появляется при
 * разблокировке и затирается при блокировке/очистке.
 */
class AccountStorageKey(
    private val crypto: AccountCrypto = AccountCrypto,
) : StorageSealer {

    private var key: ByteArray? = null

    override val isUnlocked: Boolean
        get() = synchronized(this) { key != null }

    /** Выводит ключ из [mnemonic]. Повторный вызов с той же фразой ничего не меняет. */
    fun unlockWith(mnemonic: String) {
        val derived = crypto.deriveStorageKey(mnemonic)
        synchronized(this) {
            key?.fill(0)
            key = derived
        }
    }

    fun lock() {
        synchronized(this) {
            key?.fill(0)
            key = null
        }
    }

    override fun seal(plaintext: ByteArray, purpose: StoragePurpose): ByteArray =
        withKey { CryptoModule.createCascadeCipher(it).encrypt(plaintext, purpose.label.encodeToByteArray()) }

    override fun open(sealed: ByteArray, purpose: StoragePurpose): ByteArray =
        withKey { CryptoModule.createCascadeCipher(it).decrypt(sealed, purpose.label.encodeToByteArray()) }

    private inline fun <T> withKey(block: (ByteArray) -> T): T {
        val copy = synchronized(this) { key?.copyOf() } ?: throw StorageLockedException()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }
}
