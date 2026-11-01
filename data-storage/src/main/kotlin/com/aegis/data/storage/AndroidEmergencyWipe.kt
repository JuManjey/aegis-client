package com.aegis.data.storage

import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Реализация экстренной очистки (§7.4).
 *
 * Ступень 1 — CRYPTO-SHREDDING (главная и мгновенная гарантия): уничтожение
 * аппаратных ключей ([KeyStoreManager.destroyAllKeys]), удаление запечатанной
 * записи аккаунта, PSK-стора, файлов SQLCipher и файлов [SecureStore]
 * (настройки, реестр устройств, теги обнаружения). После этого запечатанная
 * мнемоника и вся зашифрованная БД становятся вычислительно нечитаемыми.
 *
 * Ступень 2 — BEST-EFFORT перезапись свободного места ([includeBestEffort]):
 * заполняет свободное место в песочнице приложения случайными данными ДО 90%
 * заполнения тома (жёсткий потолок, §7.4), после чего удаляет «мусорные» файлы.
 *
 * Ступени доступны и по отдельности ([cryptoShred], [overwriteFreeSpace]):
 * приложению нужно сбросить данные аккаунта в памяти СРАЗУ после ступени 1, а
 * не через минуты, которые может занять ступень 2.
 *
 * ЧЕСТНОЕ ОГРАНИЧЕНИЕ (обязательно и в UI): на флеш-памяти (NAND, wear-leveling)
 * перезапись логических файлов НЕ гарантирует физического затирания ячеек —
 * контроллер флеша сам решает, куда писать. Поэтому ступень 2 — вспомогательная;
 * настоящую гарантию даёт ступень 1 (crypto-shredding). Потолок 90% выбран, чтобы
 * не доводить устройство до зависания/OOM/износа флеша и не мешать другим
 * приложениям (и не нарушать правила Google Play).
 */
class AndroidEmergencyWipe(
    private val keyStoreManager: KeyStoreManager,
    private val accountStore: AccountStore,
    private val pskStore: PskStore? = null,
    private val database: AegisDatabase? = null,
    private val secureStore: SecureStore? = null,
    /**
     * Каталог песочницы приложения для best-effort перезаписи (обычно `cacheDir`).
     * Если `null`, ступень 2 пропускается (перезаписывать нечего/некуда).
     */
    private val scratchDir: File? = null,
) : EmergencyWipe {

    override suspend fun wipe(includeBestEffort: Boolean): WipeReport {
        val shredded = cryptoShred()
        return if (includeBestEffort) overwriteFreeSpace() else shredded
    }

    /**
     * Ступень 1 — crypto-shredding (главная гарантия). Уничтожение мастер-ключа
     * делает нечитаемыми запись аккаунта, PSK, настройки и всю SQLCipher-БД
     * (§7.4); дополнительно удаляем сами файлы (best-effort на уровне ФС).
     */
    suspend fun cryptoShred(): WipeReport = withContext(Dispatchers.IO) {
        keyStoreManager.destroyAllKeys()
        deleteStorageFiles()
        WipeReport(
            cryptoShreddingDone = true,
            bestEffortDone = false,
            freedBytes = 0L,
            note = "Crypto-shredding выполнен: аппаратные ключи, запись аккаунта, " +
                "PSK, настройки и файлы БД уничтожены. Данные вычислительно нечитаемы.",
        )
    }

    /**
     * Удаляет файлы всех хранилищ, НЕ трогая ключи Keystore. Ступень 1 делает это
     * сама; отдельный вызов нужен для контрольного прохода после неё: корутина,
     * отменённая посреди записи, дописывает начатое уже в пересозданный файл
     * (ключ БД и мастер-ключ создаются заново лениво, при первом обращении).
     */
    suspend fun deleteStorageFiles() = withContext(Dispatchers.IO) {
        accountStore.clear()
        pskStore?.clear()
        database?.deleteDatabaseFiles()
        secureStore?.clear()
        Unit
    }

    /**
     * Ступень 2 — best-effort перезапись свободного места (вспомогательная).
     * Имеет смысл только после [cryptoShred]: отчёт исходит из того, что
     * ступень 1 уже выполнена.
     */
    suspend fun overwriteFreeSpace(): WipeReport = withContext(Dispatchers.IO) {
        val dir = scratchDir
        val (done, overwritten, note) = if (dir == null) {
            Triple(false, 0L, "Ступень 2 пропущена: каталог для перезаписи недоступен.")
        } else {
            runCatching { fillFreeSpace(dir) }.getOrElse { e ->
                Triple(true, 0L, "Ступень 2 завершилась с ограничением: ${e.message ?: "ошибка ввода-вывода"}.")
            }
        }

        WipeReport(
            cryptoShreddingDone = true,
            bestEffortDone = done,
            freedBytes = overwritten,
            note = note,
        )
    }

    /**
     * Заполняет свободное место случайными данными до достижения 90% заполнения
     * тома (или пока не упрёмся в диск), затем удаляет их. Возвращает
     * (bestEffortDone, перезаписано байт, заметка для UI).
     *
     * Пишем чанками с периодической проверкой [StatFs], чтобы НЕ превысить потолок
     * и не довести устройство до критического заполнения. Каталог `scratch`
     * удаляется в `finally` при любом исходе.
     */
    private suspend fun fillFreeSpace(baseDir: File): Triple<Boolean, Long, String> {
        val scratch = File(baseDir, SCRATCH_DIR).apply { mkdirs() }
        val random = SecureRandom()
        val buffer = ByteArray(CHUNK_BYTES)
        var written = 0L
        try {
            var fileIndex = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val stat = StatFs(scratch.absolutePath)
                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                val usedBytes = totalBytes - availableBytes
                val maxUsedBytes = (totalBytes * MAX_FILL_PERCENT) / 100L
                // Остановка: достигли потолка заполнения, кончилось место с запасом,
                // или упёрлись в общий лимит объёма перезаписи.
                if (usedBytes >= maxUsedBytes) break
                if (availableBytes <= SAFETY_MARGIN_BYTES) break
                if (written >= MAX_TOTAL_BYTES) break

                val room = minOf(
                    maxUsedBytes - usedBytes,
                    availableBytes - SAFETY_MARGIN_BYTES,
                    MAX_TOTAL_BYTES - written,
                    FILE_SIZE_BYTES,
                )
                if (room <= 0L) break

                val target = File(scratch, "scratch_$fileIndex.bin")
                fileIndex++
                val writtenToFile = writeRandom(target, room, buffer, random)
                written += writtenToFile
                if (writtenToFile < room) break // диск заполнился раньше расчётного — стоп
            }
        } finally {
            // Освобождаем память: удаляем все «мусорные» файлы и каталог (§7.4).
            scratch.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { scratch.delete() }
        }
        val mb = written / (1024L * 1024L)
        return Triple(
            true,
            written,
            "Ступень 2: перезаписано ~$mb МБ свободного места (потолок $MAX_FILL_PERCENT%), " +
                "затем удалено. ВНИМАНИЕ: на флеш-памяти (wear-leveling) это НЕ гарантирует " +
                "физического затирания ячеек — главная гарантия дана ступенью 1.",
        )
    }

    /**
     * Пишет [bytes] случайных данных в [target] чанками. Возвращает реально
     * записанное число байт (может быть меньше при заполнении диска).
     */
    private suspend fun writeRandom(
        target: File,
        bytes: Long,
        buffer: ByteArray,
        random: SecureRandom,
    ): Long {
        var remaining = bytes
        var written = 0L
        try {
            target.outputStream().use { out ->
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                    random.nextBytes(buffer)
                    out.write(buffer, 0, chunk)
                    written += chunk
                    remaining -= chunk
                }
                out.flush()
            }
        } catch (_: IOException) {
            // Диск заполнился — это ожидаемый предел best-effort перезаписи.
        }
        return written
    }

    private companion object {
        const val SCRATCH_DIR = "aegis_wipe_scratch"
        const val CHUNK_BYTES = 8 * 1024 * 1024 // 8 МиБ — компромисс скорость/память
        const val FILE_SIZE_BYTES = 256L * 1024 * 1024 // не плодим гигантские файлы
        // Верхний лимит суммарной перезаписи: заметный, но не «до отказа» (§7.4).
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
        // Оставляем свободный запас, чтобы не подвесить систему/другие приложения.
        const val SAFETY_MARGIN_BYTES = 256L * 1024 * 1024
        const val MAX_FILL_PERCENT = EmergencyWipe.MAX_DISK_FILL_PERCENT
    }
}
