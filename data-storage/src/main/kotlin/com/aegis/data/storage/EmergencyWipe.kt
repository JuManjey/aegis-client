package com.aegis.data.storage

/**
 * Ручная «Экстренная очистка» (§7.4, §10.4). Запускается ТОЛЬКО вручную.
 *
 * Двухступенчатый механизм:
 *  - [WipeStage.CRYPTO_SHREDDING] — главная гарантия: уничтожение всех ключей
 *    (Keystore/StrongBox, ключ SQLCipher, PSK). Мгновенно делает БД мусором.
 *  - [WipeStage.BEST_EFFORT] — вспомогательная мера: удаление файлов БД, TRIM,
 *    ограниченная перезапись свободного места ТОЛЬКО в песочнице приложения.
 *
 * ЧЕСТНОЕ ПРЕДУПРЕЖДЕНИЕ (обязательно в UI): на флеш-памяти (NAND, wear-leveling)
 * перезапись логических файлов НЕ гарантирует физического затирания ячеек —
 * ступень 2 вспомогательная. Заполнение диска — ДО 90%, не выше (§7.4).
 */
interface EmergencyWipe {

    /**
     * Выполнить очистку. [includeBestEffort] управляет запуском ступени 2
     * (требует явного подтверждения пользователя).
     *
     * @return отчёт о выполненных ступенях.
     */
    suspend fun wipe(includeBestEffort: Boolean): WipeReport

    enum class WipeStage { CRYPTO_SHREDDING, BEST_EFFORT }

    /** Предел заполнения диска при best-effort перезаписи — жёстко 90% (§7.4). */
    companion object {
        const val MAX_DISK_FILL_PERCENT: Int = 90
    }
}

/** Результат экстренной очистки. */
data class WipeReport(
    val cryptoShreddingDone: Boolean,
    val bestEffortDone: Boolean,
    val freedBytes: Long = 0L,
    val note: String = "",
)
