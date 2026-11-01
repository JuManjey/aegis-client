package com.aegis.core.common.diag

import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Диагностический журнал приложения (отладка обмена ключами, доставки, звонков,
 * аккаунта и транспорта).
 *
 * ЗАЧЕМ. Транспорт (Tor → onion-релей), обмен ключами (§6.2) и многие другие
 * шаги отказывают тихо: почти весь код best-effort и глотает исключения через
 * `runCatching`, поэтому снаружи виден только итог — «ошибка E2EE». Журнал
 * делает каждый шаг видимым: поднялся ли Tor, задан ли адрес релея, ушёл ли
 * бандл, дошёл ли конверт, на какой фазе оборвался звонок.
 *
 * ГЛАВНОЕ ПРАВИЛО: В ЖУРНАЛ НЕ ПОПАДАЮТ СЕКРЕТЫ. Никогда не логируются:
 *  - PSK и любые его байты, мнемоника, пароли, приватные ключи;
 *  - текст сообщений и содержимое вложений;
 *  - полные адреса mailbox (это capability — знание id даёт доступ к ящику).
 * Вместо них — длины, короткие отпечатки ([fp]) и усечённые id ([shortId]).
 * Журнал можно отдать посторонним: он раскрывает поведение, но не переписку.
 *
 * ВЫКЛЮЧЕН ПО УМОЛЧАНИЮ. Пока [isEnabled] ложно, все вызовы — это одна проверка
 * volatile-флага, строки даже не собираются (сообщение передаётся лямбдой).
 * Включается пользователем в «Настройки → Диагностика» ([enable]).
 *
 * ДОЛГОВЕЧНОСТЬ ЗАПИСЕЙ. [sync] пишет строку обычным `write(2)` в постоянно
 * открытый дескриптор — БЕЗ `fsync`. Этого достаточно для расследования
 * падений: данные уже у ядра в page cache и переживают смерть процесса, включая
 * SIGKILL. `fsync` (десятки миллисекунд на UFS) нужен только против отключения
 * питания и делается точечно в [checkpoint], [crash] и [disable]. Раньше `fsync`
 * стоял на каждой строке, а строки идут в том числе из нативных потоков WebRTC
 * (`network_thread`), — это добавляло по 1–20 мс к каждой записи и заметно
 * искажало тайминги ICE, которые мы же и пытались отладить.
 *
 * Файл живёт в приватном каталоге приложения, ротация по [MAX_FILE_BYTES]
 * (текущий + один предыдущий), поэтому журнал не растёт бесконечно.
 *
 * Формат строки:
 * `MM-dd HH:mm:ss.SSS L [tag] #000123 +12.345s t=DefaultDispatcher-worker-1 сообщение`
 * `#N` — сквозной номер записи (по нему видно порядок и потери),
 * `+N.NNNs` — МОНОТОННОЕ время с момента включения журнала в этом процессе,
 * `t=` — поток.
 */
object DiagnosticLog {

    /** Уровни записи; [Level.ERROR] выделяется в файле как `E`. */
    enum class Level(val mark: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

    @Volatile
    private var sink: Sink? = null

    /** Последний каталог журнала — чтобы выгрузить файл после выключения записи. */
    @Volatile
    private var lastDir: File? = null

    /** Идентификатор сессии записи (меняется при каждом [enable] в процессе). */
    @Volatile
    private var sessionId: String = "-"

    /** Монотонное время включения журнала (для `+N.NNNs` в каждой строке). */
    @Volatile
    private var enabledAtElapsedMs: Long = 0L

    /** Сквозной номер записи: разрывы в нумерации сразу видно глазами. */
    private val sequence = AtomicLong(0)

    /** Пишется ли журнал прямо сейчас. */
    val isEnabled: Boolean get() = sink != null

    /** Сколько записей отброшено из-за переполнения очереди (видно в журнале). */
    private val dropped = AtomicLong(0)

    /** Файл, куда C++ пишет SIGSEGV/SIGABRT до гибели процесса. */
    @Volatile
    private var nativeCrashFile: File? = null

    /** Текст нативного падения, пойманный до включения журнала. */
    @Volatile
    private var pendingNativeCrash: String? = null

    /** Предыдущий обработчик падений — чтобы не затирать системный. */
    @Volatile
    private var previousUncaught: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var crashHookInstalled: Boolean = false

    /**
     * Мост к нативному кольцу «хлебных крошек» (`libaegis_frame.so`). Ставится
     * приложением, чтобы `core-common` не зависел от JNI-модуля. Крошки попадают
     * в sidecar при нативном сигнале и отвечают на вопрос «на какой операции
     * умерли», когда Java-журнал оборвался на предыдущей строке.
     */
    @Volatile
    private var nativeBreadcrumbSink: ((String) -> Unit)? = null

    fun setNativeBreadcrumbSink(sink: ((String) -> Unit)?) {
        nativeBreadcrumbSink = sink
    }

    /**
     * Включает журнал: создаёт (или продолжает) файл в [dir]. Идемпотентно —
     * повторный вызов с тем же каталогом ничего не меняет.
     */
    @Synchronized
    fun enable(dir: File) {
        if (sink?.dir == dir) return
        sink?.close()
        lastDir = dir
        sessionId = UUID.randomUUID().toString().take(8)
        enabledAtElapsedMs = SystemClock.elapsedRealtime()
        dropped.set(0)
        sequence.set(0)
        sink = runCatching { Sink(dir) }.getOrNull()
        installCrashHook()
        write(
            Level.INFO,
            "diag",
            "журнал включён сессия=$sessionId pid=${currentPid()} " +
                "потоки=${Thread.activeCount()} отброшеноРанее=${dropped.get()} " +
                "аптаймМс=${SystemClock.elapsedRealtime()}",
        )
        ingestSidecars(dir)
    }

    /** Выключает журнал (файл остаётся на диске — его ещё можно выгрузить). */
    @Synchronized
    fun disable() {
        write(
            Level.INFO,
            "diag",
            "журнал выключен сессия=$sessionId отброшено=${dropped.get()} записей=${sequence.get()}",
        )
        checkpoint()
        clearAliveMarker(lastDir)
        sink?.close()
        sink = null
    }

    /**
     * Сбрасывает очередь на диск. Нужен перед шагами, после которых процесс
     * может умереть без Java-стека (native WebRTC): иначе последние события
     * остаются в очереди демона и не попадают в выгрузку.
     */
    fun flush() {
        sink?.flushAndGet()
    }

    /**
     * Принудительный `fsync`. В отличие от [flush] защищает не от смерти
     * процесса (от неё достаточно обычной записи), а от отключения питания.
     * Дорого — вызывать только на границах сценариев, не в цикле.
     */
    fun checkpoint() {
        sink?.checkpoint()
    }

    /**
     * Путь sidecar-файла нативного сигнала. C++ пишет туда из SIGSEGV/SIGABRT
     * (async-signal-safe). Журнал подхватывает файл при [enable] и в выгрузке.
     */
    fun setNativeCrashFile(file: File) {
        nativeCrashFile = file
        if (file.isFile && file.length() > 0L) {
            pendingNativeCrash = runCatching { file.readText() }.getOrNull()
        }
    }

    /**
     * Пишет строку сразу в файл, минуя очередь. Для шагов, после которых
     * native-код может убить процесс быстрее, чем демон успеет сбросить хвост.
     * При выключенном журнале не пишет ничего, даже если каталог известен.
     */
    fun sync(tag: String, event: String, vararg fields: Pair<String, Any?>) {
        val s = sink ?: return
        s.writeNow(formatLine(Level.INFO, tag, compose(event, fields)))
    }

    /**
     * Событие + «хлебная крошка» в нативное кольцо. Ставить перед каждым
     * опасным нативным шагом: если процесс умрёт внутри него, крошка окажется
     * в sidecar рядом с сигналом и регистрами.
     */
    fun step(tag: String, event: String, vararg fields: Pair<String, Any?>) {
        val body = compose(event, fields)
        // Крошку кладём даже при выключенном журнале: она попадёт в sidecar и
        // в tombstone, и тогда причина падения видна без включённой диагностики.
        nativeBreadcrumbSink?.invoke("$tag $body")
        val s = sink ?: return
        s.writeNow(formatLine(Level.INFO, tag, body))
    }

    /**
     * Пишет падение даже если запись уже выключена, но каталог известен
     * (прошлый сеанс или только что вызвали [disable]). Нужен, чтобы crash
     * после выключения всё же попал в файл, который пользователь выгрузит.
     */
    fun crash(error: Throwable, message: String = "необработанное исключение") {
        val line = formatLine(Level.ERROR, "crash", message + formatError(error))
        val s = sink
        if (s != null) {
            s.writeNow(line)
            s.checkpoint()
            return
        }
        val dir = lastDir ?: return
        runCatching {
            dir.mkdirs()
            File(dir, FILE_NAME).appendText(line + "\n")
        }
    }

    /** Ставит хук необработанных исключений один раз на процесс. */
    fun installCrashHook() {
        if (crashHookInstalled) return
        crashHookInstalled = true
        previousUncaught = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            crash(error, "необработанное исключение поток=${thread.name}")
            // Полный дамп потоков: причина часто не в упавшем потоке, а в том,
            // кто держит блокировку. Дорого, но процесс уже всё равно умирает.
            runCatching { write(Level.ERROR, "crash", "потоки при падении " + threadDump()) }
            checkpoint()
            previousUncaught?.uncaughtException(thread, error)
        }
    }

    /** Файл журнала для выгрузки/отправки или `null`, если записей ещё нет. */
    fun fileOrNull(): File? {
        sink?.flushAndGet()?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        val dir = lastDir ?: return null
        val current = File(dir, FILE_NAME)
        val previous = File(dir, "$FILE_NAME.1")
        return when {
            current.isFile && current.length() > 0 -> current
            previous.isFile && previous.length() > 0 -> previous
            else -> null
        }
    }

    /**
     * Собирает текущий файл и ротированный предыдущий в один снимок для отправки.
     * Имя содержит метку времени, чтобы в почте/мессенджере было видно, с какого
     * устройства и когда сняли журнал. `null`, если писать было нечего.
     */
    fun snapshotForExport(): File? {
        checkpoint()
        val current = fileOrNull() ?: return null
        val dir = current.parentFile ?: return current
        val previous = File(dir, "$FILE_NAME.1")
        val stamp = TIME_FORMAT.get()!!.format(Date()).replace(':', '-').replace(' ', '_')
        val out = File(dir, "aegis-diag-$stamp.log")
        runCatching {
            out.outputStream().use { dest ->
                if (previous.isFile && previous != current) {
                    previous.inputStream().use { it.copyTo(dest) }
                }
                if (current.isFile) current.inputStream().use { it.copyTo(dest) }
                val sidecar = nativeCrashFile
                if (sidecar != null && sidecar.isFile && sidecar.length() > 0L) {
                    dest.write("\n----- native-crash-sidecar -----\n".toByteArray())
                    sidecar.inputStream().use { it.copyTo(dest) }
                }
            }
        }.getOrElse { return current.takeIf { it.isFile && it.length() > 0 } }
        return out.takeIf { it.isFile && it.length() > 0 }
    }

    /** Суммарный размер журнала в байтах (текущий файл + предыдущий). */
    fun sizeBytes(): Long {
        sink?.let { return it.sizeBytes() }
        val dir = lastDir ?: return 0L
        return File(dir, FILE_NAME).length() + File(dir, "$FILE_NAME.1").length()
    }

    /** Стирает журнал, не выключая запись. */
    @Synchronized
    fun clear() {
        sink?.clear()
        dropped.set(0)
        sequence.set(0)
        write(Level.INFO, "diag", "журнал очищен")
    }

    /**
     * Выключает запись и безвозвратно стирает ВСЁ содержимое [dir]: текущий и
     * ротированный журналы, снимки для отправки ([snapshotForExport]), маркер
     * сессии. Часть экстренной очистки (§7.4): в журнале есть идентификаторы и
     * ники контактов, а [clear] при выключенной записи не трогает ничего, и
     * снимки не удаляет вообще.
     *
     * Нативный sidecar ([setNativeCrashFile]) обнуляется, а не удаляется: его
     * дескриптор держит обработчик сигналов, и удалённый файл он писал бы в никуда.
     */
    @Synchronized
    fun purge(dir: File) {
        sink?.close()
        sink = null
        lastDir = null
        pendingNativeCrash = null
        dropped.set(0)
        sequence.set(0)
        val sidecarPath = nativeCrashFile?.absolutePath
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath == sidecarPath) {
                runCatching { RandomAccessFile(file, "rw").use { it.setLength(0) } }
            } else {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    // --- Запись --------------------------------------------------------------

    fun d(tag: String, message: () -> String) = lazyWrite(Level.DEBUG, tag, message)
    fun i(tag: String, message: () -> String) = lazyWrite(Level.INFO, tag, message)
    fun w(tag: String, message: () -> String) = lazyWrite(Level.WARN, tag, message)

    /** Ошибка с необязательным исключением (класс, message, cause и усечённый стек). */
    fun e(tag: String, error: Throwable? = null, message: () -> String) {
        if (sink == null) return
        val suffix = error?.let { formatError(it) }.orEmpty()
        write(Level.ERROR, tag, message() + suffix)
    }

    /**
     * Класс + сообщение + цепочка cause (до 4) + до 12 кадров стека.
     * Сообщения исключений могут содержать пути/коды, но не секреты приложения
     * (мы их туда не кладём). Cause нужен: типичный сбой — обёртка вокруг IO.
     */
    private fun formatError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .take(4)
            .joinToString(" → ") { t ->
                "${t.javaClass.simpleName}: ${t.message}"
            }
        val stack = error.stackTrace.take(12).joinToString(" ← ") { f ->
            val cls = f.className.substringAfterLast('.')
            "$cls.${f.methodName}:${f.lineNumber}"
        }
        return " | $chain | $stack"
    }

    /**
     * Событие с полями: `событие k=v k=v`. Удобно для машинного чтения журнала и
     * заставляет писать значения по одному, а не склеивать в прозу.
     */
    fun event(tag: String, event: String, vararg fields: Pair<String, Any?>) {
        if (sink == null) return
        write(Level.INFO, tag, compose(event, fields))
    }

    private fun compose(event: String, fields: Array<out Pair<String, Any?>>): String =
        if (fields.isEmpty()) {
            event
        } else {
            event + fields.joinToString(prefix = " ", separator = " ") { (k, v) -> "$k=${render(v)}" }
        }

    /**
     * Имена и состояния потоков, связанных со звонком/WebRTC, БЕЗ снятия стеков.
     *
     * Раньше здесь звался `Thread.getAllStackTraces()`, а он останавливает всю
     * виртуальную машину (suspend-all). Вызов раз в 100 мс из сторожа
     * `setLocalDescription` — как раз в тот момент, когда нативные потоки WebRTC
     * непрерывно ходят через JNI, — сам по себе тормозил процесс и искажал
     * картину падения. [Thread.enumerate] только копирует ссылки, а `state`
     * читается без остановки мира.
     */
    fun threadBrief(): String {
        val threads = enumerateThreads() ?: return "нет"
        return threads
            .filter { interestingThread(it.name) }
            .sortedBy { it.name }
            .joinToString(";") { "${it.name}:${it.state}" }
            .ifEmpty { "нет" }
    }

    /**
     * Полный дамп: имена, состояния и верхние кадры. Останавливает мир, поэтому
     * зовётся только там, где это оправдано: при падении и по явному запросу
     * сторожа, когда операция уже зависла на секунды.
     */
    fun threadDump(maxFrames: Int = 6): String {
        val traces = runCatching { Thread.getAllStackTraces() }.getOrNull() ?: return "нет"
        return traces.entries
            .sortedBy { it.key.name }
            .joinToString(" || ") { (thread, stack) ->
                val frames = stack.take(maxFrames).joinToString(" ← ") { frame ->
                    "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                }.ifEmpty { "-" }
                "${thread.name}:${thread.state}:$frames"
            }
            .ifEmpty { "нет" }
    }

    /** Все живые потоки процесса без остановки мира. */
    private fun enumerateThreads(): Array<Thread>? = runCatching {
        var group = Thread.currentThread().threadGroup ?: return null
        while (true) group = group.parent ?: break
        // enumerate() усекает молча — берём запас и повторяем при переполнении.
        var capacity = group.activeCount() + 32
        repeat(3) {
            val buffer = arrayOfNulls<Thread>(capacity)
            val n = group.enumerate(buffer, true)
            if (n < capacity) {
                @Suppress("UNCHECKED_CAST")
                return buffer.copyOf(n) as Array<Thread>
            }
            capacity *= 2
        }
        null
    }.getOrNull()

    private fun interestingThread(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("aegis") || n.contains("webrtc") || n.contains("signaling") ||
            n.contains("network") || n.contains("worker_thread") || n.contains("sdp") ||
            n.contains("audio") || n.contains("ice") || n.contains("turn") ||
            n.contains("tor") || n == "main"
    }

    private inline fun lazyWrite(level: Level, tag: String, message: () -> String) {
        if (sink == null) return
        write(level, tag, message())
    }

    private fun write(level: Level, tag: String, message: String) {
        val s = sink ?: return
        val line = formatLine(level, tag, message)
        if (!s.offer(line)) {
            val n = dropped.incrementAndGet()
            if (n == 1L || n % DROPPED_LOG_EVERY == 0L) {
                s.offer(formatLine(Level.WARN, "diag", "очередь переполнена, отброшено=$n"))
            }
        }
    }

    private fun formatLine(level: Level, tag: String, message: String): String {
        val elapsed = if (enabledAtElapsedMs == 0L) 0L else SystemClock.elapsedRealtime() - enabledAtElapsedMs
        val thread = Thread.currentThread().name
        val seq = sequence.incrementAndGet()
        return buildString(message.length + 96) {
            append(TIME_FORMAT.get()!!.format(Date()))
            append(' ').append(level.mark)
            append(' ').append('[').append(tag).append(']')
            append(' ').append('#').append(seq.toString().padStart(6, '0'))
            append(' ').append('+').append(elapsed / 1000).append('.')
            append((elapsed % 1000).toString().padStart(3, '0')).append('s')
            append(' ').append('t').append('=').append(thread)
            append(' ').append(message.replace('\n', '⏎'))
        }
    }

    /** PID процесса (Android). */
    private fun currentPid(): String = android.os.Process.myPid().toString()

    /** Сколько записей отброшено с момента включения (для снимка окружения). */
    fun droppedCount(): Long = dropped.get()

    /** Идентификатор текущей сессии записи или `-`, если журнал не включали. */
    fun sessionId(): String = sessionId

    // --- Редакция (значения, безопасные для журнала) -------------------------

    /**
     * Короткий отпечаток произвольных байтов: 8 hex от SHA-256. Позволяет
     * СРАВНИТЬ значения на двух устройствах, не раскрывая их. Применим и к
     * секретам: восстановить PSK из 4 байт хеша нельзя.
     */
    fun fp(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /** Короткий отпечаток строки (см. [fp]) — для base64/hex значений. */
    fun fp(value: String?): String = if (value == null) "null" else fp(value.toByteArray())

    /**
     * Усечённый идентификатор: начало + длина. Для accountId это достаточно, чтобы
     * различать участников, но недостаточно, чтобы вычислить адрес ящика.
     */
    fun shortId(value: String?): String = when {
        value == null -> "null"
        value.isEmpty() -> "empty"
        value.length <= 10 -> "$value(len=${value.length})"
        else -> value.take(10) + "…(len=${value.length})"
    }

    /**
     * Адрес mailbox — это capability, поэтому целиком он в журнал не идёт: только
     * отпечаток. Двум устройствам этого хватит, чтобы сверить, совпали ли адреса.
     */
    fun mailbox(value: String?): String = if (value == null) "null" else "mbx:" + fp(value)

    /** Признак наличия секрета/значения без его раскрытия: `yes(len=32)` / `no`. */
    fun presence(value: String?): String =
        if (value.isNullOrEmpty()) "no" else "yes(len=${value.length})"

    /** Признак наличия байтов без их раскрытия. */
    fun presence(value: ByteArray?): String =
        if (value == null || value.isEmpty()) "no" else "yes(len=${value.size})"

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is String -> if (value.isEmpty()) "''" else if (value.contains(' ')) "'$value'" else value
        else -> value.toString()
    }

    // --- Файловый сток -------------------------------------------------------

    /**
     * Запись в файл через постоянно открытый дескриптор.
     *
     * Обычные записи ([event], [d]/[i]/[w]/[e]) кладутся в очередь и пишутся
     * фоновым потоком — так журнал не превращает IO-задержку в задержку
     * крипты/сети. Критичные ([sync], [step], [crash]) пишутся в вызывающем
     * потоке, предварительно вытолкнув очередь, чтобы порядок в файле совпадал
     * с порядком событий.
     *
     * `fsync` на каждую строку НЕ делается: данные, отданные ядру через
     * `write(2)`, переживают смерть процесса. Явная синхронизация — [checkpoint].
     */
    private class Sink(val dir: File) {
        private val current = File(dir, FILE_NAME)
        private val previous = File(dir, "$FILE_NAME.1")
        private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
        private val fileLock = Any()

        /** Открытый поток записи; пересоздаётся только при ротации. */
        private var out: FileOutputStream? = null

        /** Размер файла в байтах — считаем сами, чтобы не звать stat() на строку. */
        private var written: Long = 0

        @Volatile
        private var running = true

        /** Сток закрыт окончательно: дозапись больше не должна открывать файл. */
        private var terminated = false

        private val worker = Thread({ drain() }, "aegis-diag-log").apply {
            isDaemon = true
            // Не MIN_PRIORITY: голодающий писатель копит очередь, и тогда
            // очередной sync() из нативного потока вынужден выталкивать её сам.
            priority = Thread.NORM_PRIORITY - 1
        }

        init {
            dir.mkdirs()
            synchronized(fileLock) { openLocked() }
            worker.start()
        }

        fun offer(line: String): Boolean = running && queue.offer(line)

        /**
         * Немедленная запись с сохранением порядка: сначала выталкиваем всё, что
         * уже накопилось в очереди, потом пишем свою строку.
         */
        fun writeNow(line: String) {
            synchronized(fileLock) {
                drainLocked()
                appendLocked(line)
            }
        }

        fun sizeBytes(): Long = current.length() + previous.length()

        fun flushAndGet(): File {
            synchronized(fileLock) { drainLocked() }
            return current
        }

        /** Дорогая, но честная синхронизация с диском (против потери питания). */
        fun checkpoint() {
            synchronized(fileLock) {
                drainLocked()
                runCatching { out?.fd?.sync() }
            }
        }

        fun clear() {
            queue.clear()
            synchronized(fileLock) {
                closeLocked()
                runCatching { current.delete() }
                runCatching { previous.delete() }
                openLocked()
            }
        }

        fun close() {
            running = false
            worker.interrupt()
            synchronized(fileLock) {
                drainLocked()
                terminated = true
                closeLocked()
            }
        }

        private fun drain() {
            while (running) {
                val line = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                synchronized(fileLock) {
                    appendLocked(line)
                    drainLocked()
                }
            }
            synchronized(fileLock) { drainLocked() }
        }

        /** Выталкивает всё накопленное. Вызывать только под [fileLock]. */
        private fun drainLocked() {
            while (true) {
                val next = queue.poll() ?: return
                appendLocked(next)
            }
        }

        private fun appendLocked(line: String) {
            // После close() писатель мог успеть проснуться на прерывании и зайти
            // сюда ещё раз — без этой проверки он бы снова открыл файл, и
            // дескриптор остался бы висеть до конца процесса.
            if (terminated) return
            runCatching {
                if (written > MAX_FILE_BYTES) rotateLocked()
                val stream = out ?: openLocked() ?: return
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                stream.write(bytes)
                written += bytes.size
            }
        }

        private fun openLocked(): FileOutputStream? {
            closeLocked()
            if (terminated) return null
            return runCatching {
                dir.mkdirs()
                FileOutputStream(current, /* append = */ true).also {
                    out = it
                    written = current.length()
                }
            }.getOrNull()
        }

        private fun closeLocked() {
            runCatching { out?.close() }
            out = null
        }

        private fun rotateLocked() {
            closeLocked()
            runCatching { previous.delete() }
            if (!current.renameTo(previous)) runCatching { current.delete() }
            openLocked()
        }
    }

    /** Имя файла журнала внутри переданного каталога. */
    const val FILE_NAME = "aegis-diag.log"

    /** Порог ротации: при превышении текущий файл становится предыдущим. */
    private const val MAX_FILE_BYTES = 16L * 1024 * 1024

    /** Ёмкость очереди записи; при переполнении записи отбрасываются. */
    private const val QUEUE_CAPACITY = 32_768

    const val ALIVE_FILE_NAME = "aegis-diag.alive"

    /** Как часто писать предупреждение об отброшенных строках. */
    private const val DROPPED_LOG_EVERY = 100L

    private val TIME_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * После падения процесса маркер [ALIVE_FILE_NAME] остаётся на диске.
     * Нативный sidecar читается отдельно: Java-хук SIGSEGV не ловит.
     */
    private fun ingestSidecars(dir: File) {
        val alive = File(dir, ALIVE_FILE_NAME)
        val abrupt = alive.isFile
        if (abrupt) {
            val prev = runCatching { alive.readText().trim() }.getOrDefault("?")
            write(
                Level.WARN,
                "diag",
                "прошлый процесс оборвался без штатного выключения было=$prev " +
                    "сейчасPid=${currentPid()}",
            )
        }
        val sidecar = nativeCrashFile
        val pending = pendingNativeCrash
            ?: sidecar?.takeIf { it.isFile && it.length() > 0L }
                ?.let { runCatching { it.readText() }.getOrNull() }
        if (!pending.isNullOrBlank()) {
            // Sidecar многострочный: печатаем построчно, иначе всё схлопнется в
            // одну строку с ⏎ и станет нечитаемым.
            write(Level.ERROR, "crash", "нативный сигнал — отчёт ниже")
            pending.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
                .take(200)
                .forEach { write(Level.ERROR, "crash", "  $it") }
            pendingNativeCrash = null
            runCatching { sidecar?.delete() }
        } else if (abrupt) {
            // Это САМ ПО СЕБЕ диагноз, а не отсутствие информации: хук сигналов
            // стоял, но ни один сигнал до него не дошёл. Значит процесс убит
            // снаружи (SIGKILL от LMK/ActivityManager) либо упал так, что
            // обработчик не успел — ответ даст история ApplicationExitInfo.
            write(
                Level.WARN,
                "diag",
                "нативного сигнала НЕ было: sidecar пуст (есть=${sidecar != null} " +
                    "файл=${sidecar?.isFile == true}). Процесс, скорее всего, убит " +
                    "снаружи — смотри записи [exit] ниже",
            )
        }
        runCatching {
            dir.mkdirs()
            alive.writeText(
                "$sessionId pid=${currentPid()} at=${System.currentTimeMillis()} " +
                    "аптаймМс=${SystemClock.elapsedRealtime()}",
            )
        }
    }

    private fun clearAliveMarker(dir: File?) {
        if (dir == null) return
        runCatching { File(dir, ALIVE_FILE_NAME).delete() }
    }
}
