package com.aegis.messenger.account

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.aegis.core.common.account.UnlockAttempts
import com.aegis.core.common.account.UnlockBackoff
import com.aegis.data.storage.KeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Счётчик неверных паролей с нарастающей задержкой ([UnlockBackoff]). Переживает
 * перезапуск процесса и перезагрузку: файл `unlock_guard.aegis` обёрнут ключом
 * Keystore.
 *
 * Защищает путь через интерфейс. Перебор в обход него требует кода от имени
 * приложения, а против этого стоит Argon2id и ключ аккаунта на хранилищах.
 */
class UnlockThrottle(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val context = appContext.applicationContext
    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    /** Сколько ждать до следующей попытки (0 — можно сейчас). */
    suspend fun remainingMs(): Long = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val stored = load()
            val (left, updated) = UnlockBackoff.remaining(stored, SystemClock.elapsedRealtime(), bootCount())
            if (updated != stored) save(updated)
            left
        }
    }

    /** Учитывает неудачу и возвращает число неудач подряд. */
    suspend fun recordFailure(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val next = UnlockBackoff.recordFailure(load(), SystemClock.elapsedRealtime(), bootCount())
            save(next)
            next.failures
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching { file.delete() }
            runCatching { File(file.parentFile, "$FILE_NAME.tmp").delete() }
        }
        Unit
    }

    // Нечитаемый файл (например, после уничтожения мастер-ключа очисткой) — чистое состояние.
    private fun load(): UnlockAttempts {
        if (!file.isFile) return UnlockAttempts()
        return runCatching {
            json.decodeFromString(UnlockAttempts.serializer(), keyStoreManager.unwrap(file.readBytes()).decodeToString())
        }.getOrDefault(UnlockAttempts())
    }

    private fun save(state: UnlockAttempts) {
        val wrapped = keyStoreManager.wrap(json.encodeToString(UnlockAttempts.serializer(), state).encodeToByteArray())
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(file)) {
            file.writeBytes(wrapped)
            tmp.delete()
        }
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UnlockAttempts.UNKNOWN_BOOT)

    private companion object {
        const val FILE_NAME = "unlock_guard.aegis"
    }
}
