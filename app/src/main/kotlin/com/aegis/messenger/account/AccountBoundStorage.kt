package com.aegis.messenger.account

/**
 * Локальные хранилища, закрытые ключом из секрета аккаунта: база SQLCipher и
 * файл PSK (см. [com.aegis.messenger.security.AccountStorageKey]).
 * [AccountManager] открывает их, как только мнемоника оказывается в памяти, и
 * закрывает при блокировке.
 */
interface AccountBoundStorage {

    /** Выводит ключ из [mnemonic]; повторный вызов с той же фразой безвреден. */
    fun open(mnemonic: String)

    /** Затирает ключ и закрывает базу; всё прочитанное из неё уходит из памяти. */
    fun close()
}
