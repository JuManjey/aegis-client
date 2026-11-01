package com.aegis.data.storage

/**
 * Персистентное состояние Signal-аккаунта/сессий (§6.1, P6).
 *
 * Само состояние — непрозрачный blob (base64 JSON), который формирует нативное
 * ядро (`signal::stateful`): identity-ключ, prekeys, установленные сессии и
 * храповики Double Ratchet. Kotlin не разбирает его содержимое — только хранит
 * зашифрованным в SQLCipher и передаёт обратно при каждой операции.
 */
interface SignalStateStore {
    /** Текущий blob состояния для аккаунта или `null`, если ещё не создан. */
    fun load(accountId: String): String?

    /** Сохранить (перезаписать) blob состояния после операции шифрования/дешифрования. */
    fun save(accountId: String, stateBlob: String)

    /** Стереть всё состояние Signal (например, при выходе/очистке). */
    fun clear()
}

internal class SqlSignalStateStore(
    private val database: AegisDatabase,
) : SignalStateStore {

    override fun load(accountId: String): String? {
        database.database.rawQuery(
            "SELECT state_blob FROM signal_state WHERE account_id = ? LIMIT 1",
            arrayOf(accountId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun save(accountId: String, stateBlob: String) {
        database.database.execSQL(
            """
            INSERT INTO signal_state(account_id, state_blob, updated_at) VALUES(?, ?, ?)
            ON CONFLICT(account_id) DO UPDATE SET state_blob = excluded.state_blob,
                updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any?>(accountId, stateBlob, System.currentTimeMillis()),
        )
    }

    override fun clear() {
        database.database.execSQL("DELETE FROM signal_state")
    }
}
