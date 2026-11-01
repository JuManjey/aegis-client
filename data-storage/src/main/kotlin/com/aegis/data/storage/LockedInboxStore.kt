package com.aegis.data.storage

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Приём при ЗАКРЫТОМ аккаунте: ключа хранилищ ([StorageSealer]) в памяти нет,
 * поэтому входящие не расшифровываются, а складываются в очередь как пришли и
 * разбираются после входа.
 *
 * Здесь лежат только:
 *  * адреса МОИХ почтовых ящиков на релее — непрозрачные 256-битные id, без
 *    имён и accountId контактов (сопоставление «ящик → контакт» восстанавливается
 *    после входа из PSK);
 *  * конверты в том виде, в каком их отдал релей: шифртекст end-to-end плюс
 *    открытые серверу поля конверта.
 *
 * Поэтому достаточно обёртки Keystore: тот, кто снимет её кодом от имени
 * приложения, узнает только то, что и так видит релей.
 */
class LockedInboxStore(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val filesDir = appContext.filesDir
    private val inboxFile = File(filesDir, INBOX_FILE)
    private val queueDir = File(filesDir, QUEUE_DIR)
    private val lock = Any()
    private var nextIndex = -1L

    /**
     * Конверт, принятый при закрытом аккаунте. [id] задаёт порядок разбора;
     * [transport] — имя транспорта, которым конверт пришёл (`TOR`/`HTTPS`, пусто
     * у записей прежних версий): после входа он попадает в след сообщения.
     */
    data class Entry(
        val id: String,
        val inbox: String,
        val payload: String,
        val receivedAtMs: Long,
        val transport: String = "",
    )

    @Serializable
    private data class StoredEntry(
        val inbox: String,
        val payload: String,
        val receivedAtMs: Long,
        val transport: String = "",
    )

    /** Ящики, которые слушать при закрытом аккаунте. Нечитаемый файл — пустой набор. */
    fun inboxes(): Set<String> = synchronized(lock) {
        if (!inboxFile.isFile) return emptySet()
        runCatching {
            val plain = keyStoreManager.unwrap(inboxFile.readBytes()).decodeToString()
            json.decodeFromString(ListSerializer(String.serializer()), plain).toSet()
        }.getOrDefault(emptySet())
    }

    fun saveInboxes(inboxes: Set<String>) = synchronized(lock) {
        val plain = json.encodeToString(ListSerializer(String.serializer()), inboxes.sorted())
        writeAtomically(inboxFile, keyStoreManager.wrap(plain.encodeToByteArray()))
    }

    /**
     * Ставит конверт в очередь. `false`, если очередь заполнена: тогда конверт
     * нельзя подтверждать на релее — пусть дождётся входа там.
     */
    fun enqueue(
        inbox: String,
        payload: String,
        receivedAtMs: Long = System.currentTimeMillis(),
        transport: String = "",
    ): Boolean =
        synchronized(lock) {
            val names = entryNames()
            if (names.size >= MAX_ENTRIES) return false
            if (nextIndex < 0) nextIndex = names.maxOfOrNull { it.toLongOrNull() ?: 0L }?.plus(1) ?: 0L
            val name = "%012d".format(nextIndex++)
            val plain = json.encodeToString(
                StoredEntry.serializer(),
                StoredEntry(inbox, payload, receivedAtMs, transport),
            )
            queueDir.mkdirs()
            writeAtomically(File(queueDir, name + SUFFIX), keyStoreManager.wrap(plain.encodeToByteArray()))
            true
        }

    /** Очередь в порядке поступления. Нечитаемые записи удаляются. */
    fun pending(): List<Entry> = synchronized(lock) {
        entryNames().mapNotNull { name ->
            val file = File(queueDir, name + SUFFIX)
            runCatching {
                val stored = json.decodeFromString(
                    StoredEntry.serializer(),
                    keyStoreManager.unwrap(file.readBytes()).decodeToString(),
                )
                Entry(name, stored.inbox, stored.payload, stored.receivedAtMs, stored.transport)
            }.getOrElse {
                file.delete()
                null
            }
        }
    }

    fun remove(id: String) = synchronized(lock) {
        File(queueDir, id + SUFFIX).delete()
        Unit
    }

    /** Часть экстренной очистки: и очередь, и список ящиков. */
    fun clear() = synchronized(lock) {
        queueDir.deleteRecursively()
        inboxFile.delete()
        File(filesDir, "$INBOX_FILE.tmp").delete()
        nextIndex = -1L
    }

    private fun entryNames(): List<String> =
        queueDir.listFiles { file -> file.name.endsWith(SUFFIX) }
            ?.map { it.name.removeSuffix(SUFFIX) }
            ?.sorted()
            .orEmpty()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    private companion object {
        const val INBOX_FILE = "locked_inboxes.aegis"
        const val QUEUE_DIR = "locked_queue"
        const val SUFFIX = ".aegis"

        /** Потолок очереди: дальше конверты ждут входа на релее (там они живут до 7 суток). */
        const val MAX_ENTRIES = 1000
    }
}
