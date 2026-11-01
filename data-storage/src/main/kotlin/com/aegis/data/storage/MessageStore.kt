package com.aegis.data.storage

import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageCryptoInfo
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import kotlinx.serialization.json.Json

/**
 * Зашифрованное хранилище сообщений (§6.3). Тело сообщения кладётся уже
 * расшифрованным (для показа в UI), но вся БД зашифрована ключом StrongBox —
 * в открытом виде на диск ничего не попадает.
 *
 * Вложения (§14.4): метаданные хранятся вместе с сообщением ([Message.attachment]),
 * а сами байты файла — отдельным BLOB (таблица `attachments`), тоже зашифрованные
 * SQLCipher. Никакого plaintext-файла в файловой системе (§6.3).
 */
interface MessageStore {
    fun insert(message: Message)
    fun updateStatus(id: String, status: MessageStatus)

    /** Записывает фактически применённые слои шифрования сообщения. */
    fun updateCrypto(id: String, crypto: MessageCryptoInfo?)
    fun messagesFor(contactId: String): List<Message>

    /** ВСЕ сообщения (во всех чатах) — для переноса аккаунта (§7.1.2). */
    fun allMessages(): List<Message>

    fun lastMessage(contactId: String): Message?
    fun unreadCount(contactId: String): Int
    fun deleteFor(contactId: String)

    /**
     * Обновляет ссылку на вложение и флаг локальной доступности (§14.4): например
     * после загрузки блоба на релей (проставить финальный [AttachmentRef.objectId])
     * или после успешного скачивания/расшифровки входящего (`local = true`).
     */
    fun setAttachment(id: String, attachment: AttachmentRef?, local: Boolean)

    /**
     * Сохраняет расшифрованные байты вложения в зашифрованную БД и помечает
     * сообщение как локально доступное (`attachment_local = 1`).
     */
    fun saveAttachmentBytes(messageId: String, data: ByteArray)

    /** Возвращает расшифрованные байты вложения (или `null`, если их нет). */
    fun attachmentBytes(messageId: String): ByteArray?

    /**
     * «Удалить у всех» / «удалить у меня» (§7.3): удаляет сообщения с указанными
     * id в рамках контакта. Возвращает число удалённых записей.
     */
    fun deleteByIds(contactId: String, ids: List<String>): Int

    /**
     * Открытие чата (§14.2): помечает входящие RECEIVED-сообщения контакта как
     * прочитанные (READ) и возвращает их общие id — чтобы отправить отчёт о
     * прочтении собеседнику. Идемпотентно: повторный вызов вернёт пустой список.
     */
    fun markIncomingRead(contactId: String): List<String>

    /**
     * Отчёт о прочтении от собеседника (§14.2): помечает НАШИ исходящие сообщения
     * с указанными id как прочитанные (READ). Возвращает число обновлённых.
     */
    fun markOutgoingRead(contactId: String, ids: List<String>): Int

    /**
     * Автоудаление по сроку хранения (§7.3): удаляет ВСЕ сообщения (во всех
     * чатах) старше [cutoffMs] (по [Message.timestampMs]). Возвращает число
     * удалённых записей. Жёсткий потолок 7 дней обеспечивается вызывающим
     * ([com.aegis.core.common.AegisConstants.AUTO_DELETE_MAX_DAYS]); хранилище
     * лишь применяет переданную границу.
     */
    fun purgeOlderThan(cutoffMs: Long): Int

    fun clear()
}

internal class SqlMessageStore(
    private val database: AegisDatabase,
) : MessageStore {

    override fun insert(message: Message) {
        database.database.execSQL(
            """
            INSERT INTO messages(id, contact_id, direction, body, timestamp_ms, status,
                                 attachment_json, attachment_local, crypto_json)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                crypto_json = COALESCE(excluded.crypto_json, messages.crypto_json)
            """.trimIndent(),
            arrayOf<Any?>(
                message.id,
                message.contactId,
                message.direction.name,
                message.body,
                message.timestampMs,
                message.status.name,
                message.attachment?.let { json.encodeToString(AttachmentRef.serializer(), it) },
                if (message.attachmentLocal) 1 else 0,
                message.crypto?.let { json.encodeToString(MessageCryptoInfo.serializer(), it) },
            ),
        )
    }

    override fun setAttachment(id: String, attachment: AttachmentRef?, local: Boolean) {
        database.database.execSQL(
            "UPDATE messages SET attachment_json = ?, attachment_local = ? WHERE id = ?",
            arrayOf<Any?>(
                attachment?.let { json.encodeToString(AttachmentRef.serializer(), it) },
                if (local) 1 else 0,
                id,
            ),
        )
    }

    override fun saveAttachmentBytes(messageId: String, data: ByteArray) {
        val db = database.database
        db.execSQL(
            "INSERT INTO attachments(message_id, data) VALUES(?, ?) " +
                "ON CONFLICT(message_id) DO UPDATE SET data = excluded.data",
            arrayOf<Any?>(messageId, data),
        )
        db.execSQL("UPDATE messages SET attachment_local = 1 WHERE id = ?", arrayOf<Any?>(messageId))
    }

    override fun attachmentBytes(messageId: String): ByteArray? {
        database.database.rawQuery(
            "SELECT data FROM attachments WHERE message_id = ?",
            arrayOf(messageId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
    }

    override fun updateStatus(id: String, status: MessageStatus) {
        database.database.execSQL(
            "UPDATE messages SET status = ? WHERE id = ?",
            arrayOf(status.name, id),
        )
    }

    override fun updateCrypto(id: String, crypto: MessageCryptoInfo?) {
        database.database.execSQL(
            "UPDATE messages SET crypto_json = ? WHERE id = ?",
            arrayOf(
                crypto?.let { json.encodeToString(MessageCryptoInfo.serializer(), it) },
                id,
            ),
        )
    }

    override fun messagesFor(contactId: String): List<Message> {
        database.database.rawQuery(
            "$SELECT_ALL WHERE contact_id = ? ORDER BY timestamp_ms ASC",
            arrayOf(contactId),
        ).use { cursor ->
            val result = ArrayList<Message>(cursor.count)
            while (cursor.moveToNext()) result += cursor.readMessage()
            return result
        }
    }

    override fun allMessages(): List<Message> {
        database.database.rawQuery(
            "$SELECT_ALL ORDER BY timestamp_ms ASC",
            null,
        ).use { cursor ->
            val result = ArrayList<Message>(cursor.count)
            while (cursor.moveToNext()) result += cursor.readMessage()
            return result
        }
    }

    override fun lastMessage(contactId: String): Message? {
        database.database.rawQuery(
            "$SELECT_ALL WHERE contact_id = ? ORDER BY timestamp_ms DESC LIMIT 1",
            arrayOf(contactId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.readMessage() else null
        }
    }

    override fun unreadCount(contactId: String): Int {
        database.database.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE contact_id = ? AND direction = ? AND status = ?",
            arrayOf(contactId, MessageDirection.INCOMING.name, MessageStatus.RECEIVED.name),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun deleteFor(contactId: String) {
        val db = database.database
        db.execSQL(
            "DELETE FROM attachments WHERE message_id IN " +
                "(SELECT id FROM messages WHERE contact_id = ?)",
            arrayOf(contactId),
        )
        db.execSQL("DELETE FROM messages WHERE contact_id = ?", arrayOf(contactId))
    }

    override fun deleteByIds(contactId: String, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = database.database
        val placeholders = ids.joinToString(",") { "?" }
        // Байты вложений удаляемых сообщений тоже вычищаем (§7.3/§14.4).
        db.execSQL(
            "DELETE FROM attachments WHERE message_id IN ($placeholders)",
            ids.toTypedArray<Any?>(),
        )
        val args: Array<Any?> = (listOf<Any?>(contactId) + ids).toTypedArray()
        db.execSQL("DELETE FROM messages WHERE contact_id = ? AND id IN ($placeholders)", args)
        return db.rawQuery("SELECT changes()", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun markIncomingRead(contactId: String): List<String> {
        val db = database.database
        val ids = ArrayList<String>()
        db.rawQuery(
            "SELECT id FROM messages WHERE contact_id = ? AND direction = ? AND status = ?",
            arrayOf(contactId, MessageDirection.INCOMING.name, MessageStatus.RECEIVED.name),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        if (ids.isNotEmpty()) {
            db.execSQL(
                "UPDATE messages SET status = ? WHERE contact_id = ? AND direction = ? AND status = ?",
                arrayOf(
                    MessageStatus.READ.name,
                    contactId,
                    MessageDirection.INCOMING.name,
                    MessageStatus.RECEIVED.name,
                ),
            )
        }
        return ids
    }

    override fun markOutgoingRead(contactId: String, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val db = database.database
        val placeholders = ids.joinToString(",") { "?" }
        val args: Array<Any?> =
            (listOf<Any?>(MessageStatus.READ.name, contactId, MessageDirection.OUTGOING.name) + ids)
                .toTypedArray()
        db.execSQL(
            "UPDATE messages SET status = ? WHERE contact_id = ? AND direction = ? AND id IN ($placeholders)",
            args,
        )
        return db.rawQuery("SELECT changes()", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun purgeOlderThan(cutoffMs: Long): Int {
        val db = database.database
        // Сначала байты вложений просроченных сообщений, затем сами сообщения.
        db.execSQL(
            "DELETE FROM attachments WHERE message_id IN " +
                "(SELECT id FROM messages WHERE timestamp_ms < ?)",
            arrayOf<Any?>(cutoffMs),
        )
        db.execSQL("DELETE FROM messages WHERE timestamp_ms < ?", arrayOf<Any?>(cutoffMs))
        // changes() возвращает число строк, затронутых последним DELETE в этом
        // соединении — используем для честной телеметрии/логов без чувствительных данных.
        return db.rawQuery("SELECT changes()", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    override fun clear() {
        val db = database.database
        db.execSQL("DELETE FROM attachments")
        db.execSQL("DELETE FROM messages")
    }

    private fun android.database.Cursor.readMessage(): Message = Message(
        id = getString(0),
        contactId = getString(1),
        direction = MessageDirection.valueOf(getString(2)),
        body = getString(3),
        timestampMs = getLong(4),
        status = MessageStatus.valueOf(getString(5)),
        attachment = getString(6)?.let {
            runCatching { json.decodeFromString(AttachmentRef.serializer(), it) }.getOrNull()
        },
        attachmentLocal = getInt(7) != 0,
        crypto = getString(8)?.let {
            runCatching { json.decodeFromString(MessageCryptoInfo.serializer(), it) }.getOrNull()
        },
    )

    private companion object {
        const val SELECT_ALL =
            "SELECT id, contact_id, direction, body, timestamp_ms, status, " +
                "attachment_json, attachment_local, crypto_json FROM messages"
        val json = Json { ignoreUnknownKeys = true }
    }
}
