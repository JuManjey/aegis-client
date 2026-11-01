package com.aegis.messenger.messaging

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aegis.core.common.AegisConstants
import com.aegis.core.common.model.AttachmentKind
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Читает выбранное пользователем вложение (Storage Access Framework / Photo
 * Picker) в память для отправки (§14.4). Работает только с [Uri], полученным
 * через системный пикер (без разрешений на хранилище). Ничего не пишет во
 * временные файлы — байты сразу уходят в конвейер и в зашифрованную БД (§6.3).
 */
object AttachmentReader {
    /** Прочитанное вложение, готовое к отправке. */
    data class Draft(
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String,
        val kind: AttachmentKind,
    )

    /** Причина, по которой вложение нельзя отправить (для показа пользователю). */
    sealed interface Error {
        /** Файл крупнее [AegisConstants.ATTACHMENT_MAX_BYTES]. */
        data class TooLarge(val sizeBytes: Long) : Error

        /** Не удалось открыть/прочитать [Uri]. */
        data object Unreadable : Error
    }

    /** Результат чтения: успех с [Draft] либо [Error]. */
    sealed interface Result {
        data class Ok(val draft: Draft) : Result
        data class Fail(val error: Error) : Result
    }

    suspend fun read(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, declaredSize) = queryMeta(context, uri)
        // Ранний отказ по заявленному размеру, чтобы не тянуть огромный файл в RAM.
        if (declaredSize > AegisConstants.ATTACHMENT_MAX_BYTES) {
            return@withContext Result.Fail(Error.TooLarge(declaredSize))
        }
        val mime = resolver.getType(uri) ?: guessMime(displayName)
        val capped: ByteArray? = try {
            val stream = resolver.openInputStream(uri) ?: return@withContext Result.Fail(Error.Unreadable)
            stream.use { readCapped(it, AegisConstants.ATTACHMENT_MAX_BYTES) }
        } catch (e: Exception) {
            return@withContext Result.Fail(Error.Unreadable)
        }
        // readCapped вернул null → поток превысил лимит на лету (SIZE занижен/отсутствовал).
        val bytes = capped
            ?: return@withContext Result.Fail(Error.TooLarge(declaredSize.coerceAtLeast(0L)))

        Result.Ok(
            Draft(
                bytes = bytes,
                fileName = displayName,
                mimeType = mime,
                kind = kindOf(mime),
            ),
        )
    }

    /**
     * Класс вложения по MIME-типу (для превью/иконки).
     *
     * Аудио СОЗНАТЕЛЬНО остаётся `FILE`: [AttachmentKind] едет по проводу как
     * enum, и новое значение уронило бы разбор конверта у клиентов старых версий
     * (подробно — в [com.aegis.core.common.model.VoiceMeta]). Плеер в пузыре
     * включается по MIME, а не по этому полю — см.
     * [com.aegis.core.common.model.AttachmentRef.isPlayableAudio].
     */
    fun kindOf(mimeType: String): AttachmentKind = when {
        mimeType.startsWith("image/") -> AttachmentKind.IMAGE
        mimeType.startsWith("video/") -> AttachmentKind.VIDEO
        else -> AttachmentKind.FILE
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = LocaleManager.wrap(context).getString(R.string.attachment_default_name)
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /**
     * Читает поток, обрывая на превышении [limit] (возвращает `null`), чтобы
     * пикер с заниженным/отсутствующим SIZE не привёл к чтению гигантского файла.
     */
    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
