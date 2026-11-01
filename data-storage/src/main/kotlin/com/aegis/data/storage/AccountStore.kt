package com.aegis.data.storage

import android.content.Context
import com.aegis.core.common.account.SealedAccountRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Персистентное хранилище записи аккаунта (§7.1). Хранит [SealedAccountRecord]:
 * мнемоника внутри уже запечатана паролем (XChaCha20-Poly1305), а весь файл
 * ДОПОЛНИТЕЛЬНО обёрнут аппаратным ключом Keystore ([KeyStoreManager.wrap]).
 */
interface AccountStore {
    suspend fun hasAccount(): Boolean
    suspend fun load(): SealedAccountRecord?
    suspend fun save(record: SealedAccountRecord)
    /** Полностью удаляет запись (часть crypto-shredding, §7.4). */
    suspend fun clear()
}

/**
 * Реализация [AccountStore] поверх приватного файла приложения. Открытых
 * секретов на диске нет: файл = `Keystore.wrap( JSON(SealedAccountRecord) )`.
 */
class FileAccountStore(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AccountStore {

    private val file: File = File(appContext.filesDir, FILE_NAME)

    override suspend fun hasAccount(): Boolean = withContext(Dispatchers.IO) { file.isFile }

    override suspend fun load(): SealedAccountRecord? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            val wrapped = file.readBytes()
            val plaintext = keyStoreManager.unwrap(wrapped)
            json.decodeFromString(SealedAccountRecord.serializer(), plaintext.decodeToString())
        }.getOrNull()
    }

    override suspend fun save(record: SealedAccountRecord) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(SealedAccountRecord.serializer(), record).encodeToByteArray()
        val wrapped = keyStoreManager.wrap(plaintext)
        // Атомарная запись: сначала во временный файл, потом rename.
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(file)) {
            file.writeBytes(wrapped)
            tmp.delete()
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) file.delete() }
        // Хвост прерванной атомарной записи ([save]).
        runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
        Unit
    }

    private companion object {
        const val FILE_NAME = "account.aegis"
    }
}
