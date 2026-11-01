package com.aegis.messenger.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.aegis.core.common.diag.DiagnosticLog
import java.io.InputStream

/**
 * Почему предыдущий процесс умер — по данным САМОЙ СИСТЕМЫ, а не по нашим догадкам.
 *
 * ЗАЧЕМ. Наш собственный sidecar ([DiagnosticNative]) ловит только сигналы,
 * которые доходят до обработчика в процессе. Он БЕССИЛЕН, когда процесс убит
 * снаружи: `SIGKILL` от low-memory killer, «убийство» от ActivityManager за
 * нарушение правил foreground-сервиса, freezer, ANR-kill. В этих случаях журнал
 * просто обрывается — ровно то, что видно в отчёте о падении в начале звонка.
 *
 * [ActivityManager.getHistoricalProcessExitReasons] (Android 11+) даёт
 * однозначный ответ: код причины, статус (номер сигнала или код выхода),
 * важность процесса на момент смерти, потребление памяти и текстовое описание
 * от системы. Для `REASON_ANR` (Android 11+) и `REASON_CRASH_NATIVE` (Android
 * 12+) дополнительно отдаётся трассировка/tombstone.
 *
 * Приватность: наружу не уходит ничего — всё пишется только в локальный журнал,
 * который пользователь выгружает вручную. Из tombstone берём лишь печатаемые
 * строки (имена .so, символы, причина сигнала), payload не парсим.
 */
object ProcessDeathReport {

    private const val DIAG_TAG = "exit"

    /** Сколько последних записей о смерти процесса разбирать. */
    private const val MAX_RECORDS = 6

    /** Потолок чтения трассировки, чтобы не выесть память на большом tombstone. */
    private const val TRACE_READ_LIMIT = 1 shl 20

    /** Потолок вывода трассировки в журнал. */
    private const val TRACE_EMIT_LIMIT = 12_000

    /** Минимальная длина печатаемой строки, которую вытаскиваем из protobuf. */
    private const val MIN_STRING_RUN = 4

    /**
     * Разбирает историю смертей процесса и пишет её в журнал. Идемпотентно в
     * пределах процесса: повторные вызовы ничего не делают, чтобы выгрузка
     * журнала не дублировала один и тот же tombstone.
     *
     * Пока журнал выключен, попытка НЕ засчитывается: приложение зовёт этот
     * метод и из `Application.onCreate` (журнал может быть ещё не включён), и
     * сразу после `DiagnosticLog.enable` — сработает первый же вызов, при
     * котором есть куда писать.
     */
    @Volatile
    private var dumped = false

    fun dumpOnce(context: Context) {
        if (dumped || !DiagnosticLog.isEnabled) return
        dumped = true
        dump(context)
    }

    fun dump(context: Context) {
        if (!DiagnosticLog.isEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DiagnosticLog.event(
                DIAG_TAG, "история смертей недоступна",
                "android" to Build.VERSION.SDK_INT,
                "нужно" to Build.VERSION_CODES.R,
            )
            return
        }
        val records = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        }.getOrElse {
            DiagnosticLog.e(DIAG_TAG, it) { "не удалось получить историю смертей процесса" }
            return
        }
        DiagnosticLog.event(DIAG_TAG, "история смертей", "записей" to records.size)
        records.forEachIndexed { index, info -> dumpRecord(index, info) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun dumpRecord(index: Int, info: ApplicationExitInfo) {
        val reason = info.reason
        DiagnosticLog.sync(
            DIAG_TAG, "смерть процесса",
            "№" to index,
            "pid" to info.pid,
            "процесс" to info.processName,
            "причина" to reasonName(reason),
            "причинаКод" to reason,
            "статус" to statusText(reason, info.status),
            "важность" to importanceName(info.importance),
            "pssКб" to info.pss,
            "rssКб" to info.rss,
            "когда" to info.timestamp,
            "описание" to (info.description ?: "нет"),
            "вердикт" to verdict(reason, info.status),
        )
        dumpStateSummary(index, info)
        dumpTrace(index, info)
    }

    /**
     * Наша собственная метка, положенная живым процессом через
     * [markState]. Отвечает на вопрос «что приложение делало в момент, когда
     * система его убила» — даже если журнал не успел ничего записать.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun dumpStateSummary(index: Int, info: ApplicationExitInfo) {
        val summary = runCatching { info.processStateSummary }.getOrNull() ?: return
        if (summary.isEmpty()) return
        val text = runCatching { String(summary, Charsets.UTF_8) }.getOrNull() ?: return
        DiagnosticLog.sync(
            DIAG_TAG, "метка состояния перед смертью",
            "№" to index,
            "метка" to text,
        )
    }

    /**
     * Трассировка от системы: для ANR — готовый текстовый дамп всех потоков,
     * для нативного падения — tombstone в protobuf. Protobuf не парсим (это
     * потянуло бы зависимость): вытаскиваем печатаемые строки и оставляем
     * только те, что реально отвечают на вопрос «где упало» — имена библиотек,
     * символы, причина сигнала.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun dumpTrace(index: Int, info: ApplicationExitInfo) {
        val stream: InputStream = runCatching { info.traceInputStream }.getOrNull() ?: return
        val bytes = runCatching { stream.use { it.readAtMost(TRACE_READ_LIMIT) } }.getOrElse {
            DiagnosticLog.e(DIAG_TAG, it) { "трассировку смерти №$index прочитать не удалось" }
            return
        }
        if (bytes.isEmpty()) return
        val text = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            // ANR отдаётся простым текстом — берём голову дампа: там главный поток.
            String(bytes, Charsets.UTF_8).take(TRACE_EMIT_LIMIT)
        } else {
            interestingStrings(bytes).take(TRACE_EMIT_LIMIT)
        }
        if (text.isBlank()) return
        DiagnosticLog.sync(
            DIAG_TAG, "трассировка смерти",
            "№" to index,
            "байт" to bytes.size,
            "вид" to if (info.reason == ApplicationExitInfo.REASON_ANR) "anr-текст" else "tombstone-строки",
        )
        // Многострочный блок пишем отдельно: formatLine схлопывает переводы строк
        // в ⏎, поэтому режем сами и нумеруем — так блок читается глазами.
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_TRACE_LINES)
            .forEachIndexed { line, value ->
                DiagnosticLog.sync(DIAG_TAG, "трасса", "№" to index, "стр" to line, "текст" to value)
            }
    }

    /**
     * Печатаемые ASCII-строки из бинарного tombstone, отфильтрованные по
     * признакам полезности. Дубликаты убираем: в protobuf одно и то же имя
     * библиотеки повторяется на каждом кадре каждого потока.
     */
    private fun interestingStrings(bytes: ByteArray): String {
        val seen = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= MIN_STRING_RUN) {
                val value = run.toString()
                if (isInteresting(value)) seen.add(value)
            }
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
            if (seen.size >= MAX_TRACE_LINES) break
        }
        flush()
        return seen.joinToString("\n")
    }

    /**
     * Имена сигналов перечислены явно, а не ловятся подстрокой `"SIG"`.
     *
     * Tombstone включает дампы памяти вокруг регистров, то есть сырые байты
     * процесса. Трёхбуквенный маркер `"SIG"` мог случайно совпасть внутри
     * печатаемого значения из этого дампа — например, внутри base64-строки
     * (`callSecretB64` — 44 печатаемых символа), и тогда её фрагмент попал бы в
     * журнал, который пользователь отдаёт наружу. Остальные маркеры такому
     * совпадению не подвержены: `::`, `.so`, `Cause:` содержат символы вне
     * base64-алфавита, а `webrtc`/`dereference` слишком длинные.
     */
    private val SIGNAL_TOKENS = listOf(
        "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "SIGFPE", "SIGTRAP", "SIGSYS", "SIGKILL",
    )

    private fun isInteresting(value: String): Boolean {
        if (value.length > 300) return false
        return value.contains(".so") ||
            SIGNAL_TOKENS.any { value.contains(it) } ||
            value.contains("signal", ignoreCase = true) ||
            value.contains("fault", ignoreCase = true) ||
            value.contains("abort", ignoreCase = true) ||
            value.contains("Cause:") ||
            value.contains("dereference", ignoreCase = true) ||
            value.contains("webrtc", ignoreCase = true) ||
            value.contains("aegis", ignoreCase = true) ||
            value.contains("::")
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, limit - out.size()))
        }
        return out.toByteArray()
    }

    /**
     * Кладёт короткую метку текущего состояния в [ActivityManager.setProcessStateSummary].
     * Система сохраняет её и отдаёт СЛЕДУЮЩЕМУ процессу вместе с причиной смерти —
     * это единственный способ узнать «что мы делали», когда нас убили снаружи
     * без единого шанса что-либо записать.
     *
     * ⚠️ МЕТКА ЖИВЁТ ВНЕ ПРИВАТНОГО КАТАЛОГА. Её хранит ActivityManager, она
     * переживает смерть процесса by design и видна через
     * `adb shell dumpsys activity exit-info`. Для §10.10 («метаданные звонка
     * минимизируем: состояние держим в памяти, на диск ничего не пишем») это
     * значимо: сам факт и фаза звонка оказались бы записаны системой даже у
     * пользователя, который диагностику не включал.
     *
     * Поэтому метка пишется ТОЛЬКО при включённом журнале — то есть когда
     * пользователь осознанно включил диагностику, — и стирается [clearState]
     * при её выключении. Секретов в метке нет и так: callId идёт отпечатком
     * ([DiagnosticLog.fp], 4 байта SHA-256), id контакта и SDP не попадают.
     *
     * Лимит системы — 128 байт, режем сами.
     */
    fun markState(context: Context, state: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!DiagnosticLog.isEnabled) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.setProcessStateSummary(state.take(STATE_SUMMARY_LIMIT).toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Стирает метку состояния из системной записи. Зовётся при выключении
     * журнала, чтобы после «Диагностика → выключить» в системе не оставалось
     * следа последней фазы звонка.
     */
    fun clearState(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.setProcessStateSummary(null)
        }
    }

    // --- Расшифровка кодов ---------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF(сам вызвал exit)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(убит сигналом)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(LMK: не хватило памяти)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(необработанное Java-исключение)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(нативное падение)"
        ApplicationExitInfo.REASON_ANR -> "ANR(приложение не отвечало)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE(отозвано разрешение)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE(перерасход ресурсов)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED(смахнули из «недавних»)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER(убит системой, см. описание)"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER(заморожен и убит)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED(обновление APK)"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "код=$reason"
    }

    /**
     * `status` — это либо номер сигнала (для SIGNALED/CRASH_NATIVE), либо код
     * выхода. Раскрываем номер: без этого «status=9» ничего не говорит, а это
     * ровно SIGKILL, то есть внешнее убийство.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun statusText(reason: Int, status: Int): String = when (reason) {
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        -> "${signalName(status)}($status)"
        else -> status.toString()
    }

    private fun signalName(signal: Int): String = when (signal) {
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        15 -> "SIGTERM"
        31 -> "SIGSYS"
        else -> "сигнал"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "код=$importance"
    }

    /**
     * Короткий вывод для человека: куда смотреть дальше. Именно эта строка
     * экономит время — она сразу отделяет «наш баг в нативном коде» от
     * «система убила процесс, код ни при чём».
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun verdict(reason: Int, status: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "нативное падение — смотри sidecar (signal/regs/frames) и tombstone ниже"
        ApplicationExitInfo.REASON_CRASH ->
            "Java-исключение — смотри строку [crash] выше"
        ApplicationExitInfo.REASON_ANR ->
            "главный поток заблокирован — смотри дамп потоков ниже"
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "не наш баг: LMK освобождал память, снизь потребление или удержи FGS"
        ApplicationExitInfo.REASON_SIGNALED -> if (status == 9) {
            "SIGKILL: убит снаружи (LMK/AMS/пользователь) — обработчик сигналов бессилен"
        } else {
            "убит сигналом ${signalName(status)} — смотри sidecar"
        }
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "система сочла расход ресурсов чрезмерным (CPU/память фонового сервиса)"
        ApplicationExitInfo.REASON_OTHER ->
            "решение ActivityManager — ответ в поле «описание»"
        ApplicationExitInfo.REASON_FREEZER ->
            "процесс заморожен в фоне и убит — держи foreground-сервис на время звонка"
        ApplicationExitInfo.REASON_USER_REQUESTED ->
            "пользователь закрыл приложение — не баг"
        else -> "см. описание"
    }

    private const val MAX_TRACE_LINES = 120
    private const val STATE_SUMMARY_LIMIT = 120
}
