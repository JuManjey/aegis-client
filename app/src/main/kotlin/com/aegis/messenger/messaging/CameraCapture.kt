package com.aegis.messenger.messaging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Съёмка фото на встроенную камеру для отправки вложением (§14.4, follow-up).
 *
 * Системное приложение камеры пишет JPEG во ВРЕМЕННЫЙ файл в приватном кэше
 * приложения (`cacheDir/camera`), доступ к которому выдаётся камере через
 * [FileProvider] (grant read/write на один Uri). После того как байты прочитаны в
 * память для конвейера слой A→B→Tor, файл best-effort «затирается» (перезапись
 * случайными байтами + удаление, [shred]) — чтобы плейнтекст-снимок не оставался
 * на диске (§6.3/§7.4 crypto-shredding).
 *
 * Честная граница: пока идёт съёмка, JPEG кратковременно лежит в приватном
 * хранилище приложения нешифрованным. Физическое затирание на флеш-памяти (NAND,
 * wear-leveling) не гарантируется (см. §7.4) — [shred] снижает риск восстановления,
 * но не даёт абсолютной гарантии. Само вложение в БД (SQLCipher) шифруется (§6.3).
 */
object CameraCapture {
    /** Пара «файл для камеры + Uri через FileProvider» для запуска `TakePicture`. */
    data class Target(val file: File, val uri: Uri)

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** Готовит новый временный файл в кэше и Uri для системной камеры. */
    fun newTarget(context: Context): Target {
        val dir = File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        return Target(file, uri)
    }

    /**
     * Читает снятый снимок в память и сразу затирает файл (см. [shred]). Возвращает
     * байты JPEG или `null`, если файл пуст/недоступен. Вызывать из корутины.
     */
    suspend fun readAndShred(file: File): ByteArray? = withContext(Dispatchers.IO) {
        val bytes = runCatching { if (file.length() > 0L) file.readBytes() else null }.getOrNull()
        shred(file)
        bytes
    }

    /** Имя файла для снятого камерой снимка (только для показа/иконки). */
    fun fileName(file: File): String = file.name

    /** MIME снятого камерой снимка (системная камера отдаёт JPEG). */
    const val MIME_JPEG: String = "image/jpeg"

    /** Отменённая/незавершённая съёмка: затираем и удаляем временный файл. */
    fun discard(file: File) = shred(file)

    /**
     * Затирает ВСЕ временные снимки (экстренная очистка, §7.4). Обычно каталог
     * пуст, но если процесс убили между съёмкой и [readAndShred], открытый JPEG
     * остался бы на диске и пережил бы очистку аккаунта.
     */
    suspend fun purgeAll(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CAPTURE_DIR).listFiles()?.forEach { shred(it) }
        Unit
    }

    /**
     * Best-effort затирание: перезаписывает содержимое случайными байтами и
     * удаляет файл. Не бросает исключений (лучшее усилие, §7.4).
     */
    private fun shred(file: File) {
        runCatching {
            if (file.exists()) {
                val len = file.length()
                if (len in 1..MAX_SHRED_BYTES) {
                    val random = ByteArray(len.toInt()).also { SecureRandom().nextBytes(it) }
                    file.outputStream().use { it.write(random); it.flush() }
                }
                file.delete()
            }
        }
    }

    /** Кап на перезапись, чтобы не тратить память на огромные снимки (лимит §14.4 всё равно 8 МБ). */
    private const val MAX_SHRED_BYTES: Long = 16L * 1024 * 1024

    /** Подкаталог кэша для снимков; тот же путь разрешён FileProvider'у (`file_paths.xml`). */
    private const val CAPTURE_DIR = "camera"
}
