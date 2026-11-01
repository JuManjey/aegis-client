package com.aegis.data.storage

/**
 * Второй слой обёртки локальных хранилищ — ключом, выведенным из секрета
 * аккаунта (мнемоники). Им запечатываются ключ SQLCipher ([AegisDatabase]) и
 * файл PSK ([FilePskStore]) ПОВЕРХ обёртки Keystore.
 *
 * Зачем два слоя. Мастер-ключ Keystore не требует аутентификации пользователя,
 * поэтому сам по себе он защищает только от копирования файлов с устройства:
 * любой код, запущенный на телефоне от имени приложения (debug-сборка, root,
 * криминалистический комплекс при известном PIN), снял бы его. Ключ этого слоя
 * существует только в памяти и только пока аккаунт открыт, а для аккаунта с
 * паролем получить его можно лишь после ввода пароля (мнемоника запечатана
 * Argon2id от пароля).
 */
interface StorageSealer {

    /** Есть ли ключ в памяти (аккаунт открыт). */
    val isUnlocked: Boolean

    /** @throws StorageLockedException если ключа нет. */
    fun seal(plaintext: ByteArray, purpose: StoragePurpose): ByteArray

    /**
     * @throws StorageLockedException если ключа нет.
     * Бросает и при неверном ключе или подмене данных.
     */
    fun open(sealed: ByteArray, purpose: StoragePurpose): ByteArray
}

/**
 * Назначение запечатанных данных. Метка уходит в AAD, поэтому содержимое одного
 * файла нельзя выдать за другой.
 */
enum class StoragePurpose(val label: String) {
    DATABASE_KEY("aegis/local-storage/v1/database-key"),
    PSK_STORE("aegis/local-storage/v1/psk-store"),
}

/** Хранилище закрыто: аккаунт заблокирован, ключа из его секрета в памяти нет. */
class StorageLockedException :
    IllegalStateException("Локальное хранилище закрыто: аккаунт заблокирован")
