package com.aegis.data.storage

import android.content.Context
import com.aegis.core.common.psk.PskMeta
import com.aegis.core.common.psk.PskRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Защищённое хранилище ключей контейнера (PSK), P4 §6.2/§10.3.
 *
 * PSK обменивается строго вручную и вне сети; на устройстве он живёт ТОЛЬКО в
 * защищённом хранилище. Файл стора запечатан ключом аккаунта ([StorageSealer]) и
 * поверх него аппаратным ключом Keystore/StrongBox: при закрытом аккаунте
 * операции бросают [StorageLockedException]. Уничтожение мастер-ключа
 * ([KeyStoreManager.destroyAllKeys]) делает все PSK нечитаемыми (crypto-shredding, §7.4).
 */
interface PskStore {

    /** Небесекретный список установленных PSK (без байтов ключа) для UI. */
    suspend fun list(): List<PskMeta>

    /** Есть ли PSK для контакта. */
    suspend fun hasPsk(contactId: String): Boolean

    /** Метаданные PSK контакта (без секрета) или `null`. */
    suspend fun meta(contactId: String): PskMeta?

    /**
     * Base64 PSK для контакта — раскрывает СЕКРЕТ. Использовать только на месте
     * создания [com.aegis.crypto.cascade.CascadeCipher] и сразу затирать. Никогда
     * не логировать и не копировать в буфер обмена (§6.2).
     */
    suspend fun getPskB64(contactId: String): String?

    /**
     * ПОЛНЫЕ записи всех PSK (ВКЛЮЧАЯ секретные байты) — только для переноса
     * аккаунта (§7.1.2) и экстренной очистки. Результат содержит секреты: не
     * логировать, не копировать в буфер, использовать строго на месте и затирать.
     */
    suspend fun exportAll(): List<PskRecord>

    /** Сохраняет/заменяет PSK контакта. */
    suspend fun put(record: PskRecord)

    /** Удаляет PSK одного контакта. */
    suspend fun remove(contactId: String)

    /** Полностью очищает стор (часть экстренной очистки, §7.4). */
    suspend fun clear()
}

/** Сериализуемый контейнер файла PSK-стора (внутри Keystore-обёртки). */
@Serializable
private data class PskStoreFile(
    val version: Int = 1,
    val entries: Map<String, PskRecord> = emptyMap(),
)

/**
 * Реализация [PskStore] поверх приватного файла приложения. Открытых секретов на
 * диске нет: `psk_store.v2.aegis = Keystore.wrap( seal_аккаунта( JSON(PskStoreFile) ) )`.
 * Файл прежнего формата (`psk_store.aegis`, только Keystore) переносится в новый
 * при первом чтении в открытом аккаунте и удаляется.
 *
 * Нечитаемый файл — ошибка, а не пустой стор: иначе первая же запись затёрла бы
 * все PSK одним новым.
 */
class FilePskStore(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val sealer: StorageSealer,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : PskStore {

    private val file: File = File(appContext.filesDir, FILE_NAME)
    private val legacyFile: File = File(appContext.filesDir, LEGACY_FILE_NAME)
    private val lock = Any()

    override suspend fun list(): List<PskMeta> = withContext(Dispatchers.IO) {
        read().entries.values.map { it.toMeta() }.sortedByDescending { it.createdAtEpochMs }
    }

    override suspend fun hasPsk(contactId: String): Boolean = withContext(Dispatchers.IO) {
        read().entries.containsKey(contactId)
    }

    override suspend fun meta(contactId: String): PskMeta? = withContext(Dispatchers.IO) {
        read().entries[contactId]?.toMeta()
    }

    override suspend fun getPskB64(contactId: String): String? = withContext(Dispatchers.IO) {
        read().entries[contactId]?.pskB64
    }

    override suspend fun exportAll(): List<PskRecord> = withContext(Dispatchers.IO) {
        read().entries.values.toList()
    }

    override suspend fun put(record: PskRecord) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readFile()
            writeFile(current.copy(entries = current.entries + (record.contactId to record)))
        }
    }

    override suspend fun remove(contactId: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = readFile()
            if (current.entries.containsKey(contactId)) {
                writeFile(current.copy(entries = current.entries - contactId))
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { if (file.exists()) file.delete() }
            runCatching { if (legacyFile.exists()) legacyFile.delete() }
            // Хвост прерванной атомарной записи ([writeFile]).
            runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
        }
        Unit
    }

    private fun read(): PskStoreFile = synchronized(lock) { readFile() }

    private fun readFile(): PskStoreFile {
        if (!file.isFile && !legacyFile.isFile) return PskStoreFile()
        if (!sealer.isUnlocked) throw StorageLockedException()
        if (file.isFile) {
            // Хвост переноса, прерванного между записью нового файла и удалением старого.
            if (legacyFile.exists()) legacyFile.delete()
            val plaintext = sealer.open(keyStoreManager.unwrap(file.readBytes()), StoragePurpose.PSK_STORE)
            return decode(plaintext)
        }
        val migrated = decode(keyStoreManager.unwrap(legacyFile.readBytes()))
        writeFile(migrated)
        return migrated
    }

    private fun decode(plaintext: ByteArray): PskStoreFile = try {
        json.decodeFromString(PskStoreFile.serializer(), plaintext.decodeToString())
    } finally {
        plaintext.fill(0)
    }

    private fun writeFile(data: PskStoreFile) {
        val plaintext = json.encodeToString(PskStoreFile.serializer(), data).encodeToByteArray()
        val wrapped = try {
            keyStoreManager.wrap(sealer.seal(plaintext, StoragePurpose.PSK_STORE))
        } finally {
            plaintext.fill(0)
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(file)) {
            file.writeBytes(wrapped)
            tmp.delete()
        }
        if (legacyFile.exists()) legacyFile.delete()
    }

    private companion object {
        const val FILE_NAME = "psk_store.v2.aegis"
        const val LEGACY_FILE_NAME = "psk_store.aegis"
    }
}
