package com.aegis.core.common.diag

import java.io.File

/**
 * Дешёвые метрики собственного процесса из `/proc/self`.
 *
 * ЗАЧЕМ. Три самых частых способа умереть «без стека» на Android различаются
 * ровно этими числами, и ни одно из них не видно из Java API:
 *  - low-memory killer  → растёт `VmRSS`, `oom_score_adj` уходит вверх;
 *  - исчерпание дескрипторов (сокеты Tor/форвардера, файлы журнала) →
 *    число записей в `/proc/self/fd` подходит к лимиту, и следующий
 *    `socket()`/`open()` в нативном коде падает уже необрабатываемо;
 *  - «взрыв потоков» (каждый TURN-канал поднимает корутины и native-потоки) →
 *    растёт `Threads`.
 *
 * Всё читается обычным чтением файла и переживает отсутствие прав: на любой
 * ошибке возвращается `-1`, а не исключение. Замер стоит десятки микросекунд,
 * поэтому его не страшно ставить в пульс звонка.
 */
object ProcSelf {

    /** Открытых файловых дескрипторов или -1, если каталог недоступен. */
    fun openFdCount(): Int = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)

    /** Мягкий лимит дескрипторов процесса (`Max open files`) или -1. */
    fun fdLimit(): Long = limitValue("Max open files")

    /**
     * Значения из `/proc/self/status` в килобайтах (`VmRSS`, `VmSize`, …) или
     * штуках (`Threads`). -1, если ключ не найден.
     */
    fun statusValue(key: String): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeWhile { it.isDigit() }
                ?.toLongOrNull()
                ?: -1L
        }
    }.getOrDefault(-1L)

    /**
     * `oom_score_adj`: чем больше, тем раньше LMK убьёт процесс. Foreground —
     * около 0, кэшированный фон — сотни. Скачок вверх прямо перед смертью
     * означает, что приложение потеряло foreground-статус (например, FGS не
     * поднялся) и его убили штатно, а не оно упало.
     */
    fun oomScoreAdj(): Int = runCatching {
        File("/proc/self/oom_score_adj").readText().trim().toInt()
    }.getOrDefault(Int.MIN_VALUE)

    private fun limitValue(name: String): Long = runCatching {
        File("/proc/self/limits").useLines { lines ->
            lines.firstOrNull { it.startsWith(name) }
                ?.removePrefix(name)
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toLongOrNull()
                ?: -1L
        }
    }.getOrDefault(-1L)

    /**
     * Готовый набор полей для журнала. Именно этот набор нужно смотреть первым,
     * когда процесс умер без сигнала: `fd` у лимита или `oom` в сотнях
     * объясняют смерть без всякого нативного стека.
     */
    fun fields(): Array<Pair<String, Any?>> = arrayOf(
        "fd" to openFdCount(),
        "fdЛимит" to fdLimit(),
        "потоковПроц" to statusValue("Threads"),
        "rssМб" to (statusValue("VmRSS") / 1024),
        "vmМб" to (statusValue("VmSize") / 1024),
        "oom" to oomScoreAdj(),
    )
}
