package com.aegis.network.tor

import android.os.SystemClock
import com.aegis.core.common.diag.DiagnosticLog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Сторож соединения с Tor: делает связь самовосстанавливающейся.
 *
 * ЗАЧЕМ. [ArtiTorTransport] умеет только «поднять» и «погасить» клиента, но не
 * следит за собой. На практике встроенный Tor теряет связь тремя разными
 * способами, и ни один из них не виден по статусу:
 *
 *  1. **Смена сети.** Wi-Fi ⇄ мобильный, VPN вверх/вниз — старые TCP-соединения
 *     цепей мертвы, но клиент остаётся в состоянии `connected` и узнаёт правду
 *     только на таймауте очередного запроса (десятки секунд ⇒ минуты).
 *  2. **Зависший bootstrap.** `Bootstrapping 40 %` может стоять сколько угодно:
 *     сам Arti повтор не инициирует, а статус выглядит «в процессе».
 *  3. **Живой SOCKS без живого релея.** Порт SOCKS открыт, статус `Connected`,
 *     а все запросы к onion-релею падают: цепь до сервиса-получателя сломана.
 *     По статусу транспорта это НЕОТЛИЧИМО от нормальной работы, поэтому
 *     единственный честный сигнал — счётчик подряд неудачных запросов, который
 *     нам передаёт прикладной слой ([noteRelayFailure] / [noteRelaySuccess]).
 *
 * Сторож закрывает все три случая одним действием — принудительным
 * [TorTransport.restart] — и разводит их только по причине в журнале.
 *
 * ЗАЩИТА ОТ ШТОРМА ПЕРЕЗАПУСКОВ. Перезапуск Tor стоит нового bootstrap (единицы
 * секунд, на мостах — десятки), поэтому:
 *  * между перезапусками держится экспоненциальная пауза
 *    [BACKOFF_START_MS] → [BACKOFF_MAX_MS] (сбрасывается при успехе);
 *  * пока сеть офлайн, перезапусков НЕТ вовсе: поднимать Tor без сети
 *    бессмысленно, а bootstrap в пустоту только жжёт батарею;
 *  * поводы «сеть сменилась» и «статус висит» не складываются — за один цикл
 *    выполняется не больше одного перезапуска.
 *
 * ГРАНИЦЫ. Сторож НЕ включает Tor, если пользователь его выключил: [restart]
 * в режиме `OFF` — no-op, а сюда мы приходим только когда `enabledProvider()`
 * истинно. Он также не заменяет wake lock: если процессор спит, сторож просто
 * не выполняется — за это отвечает foreground-сервис приёма.
 */
class TorSupervisor(
    private val transport: TorTransport,
    private val networkWatcher: NetworkWatcher,
    /** Должен ли Tor быть поднят прямо сейчас (режим ≠ OFF и аккаунт открыт). */
    private val enabledProvider: () -> Boolean,
    /**
     * Вызывается ПОСЛЕ успешного перезапуска: прикладной слой обычно должен
     * пересобрать зависимые от порта SOCKS сущности (long-poll циклы,
     * форвардеры) и разбудить их немедленно, не дожидаясь своего reconcile.
     */
    private val onTorRestarted: () -> Unit = {},
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Сколько запросов к релею подряд провалилось (0 — последний был успешным). */
    private val relayFailures = AtomicInteger(0)

    /** Монотонное время последнего перезапуска; 0 — ещё не перезапускали. */
    @Volatile
    private var lastRestartAt = 0L

    /** Текущая пауза между перезапусками. */
    @Volatile
    private var backoffMs = BACKOFF_START_MS

    /** Поколение сети, на котором Tor был поднят последний раз. */
    @Volatile
    private var anchoredEpoch = 0L

    /**
     * Монотонное время последней проверки, признавшей соединение здоровым. По
     * разрыву между ним и текущим временем видно, усыплялся ли процесс
     * ([sleptTooLong]).
     */
    @Volatile
    private var lastSeenAwakeAt = 0L

    /** Подписка на смену сети — реагирует мгновенно, не дожидаясь тика. */
    private var networkJob: Job? = null

    /** Запускает сторожевой цикл (идемпотентно). */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        networkWatcher.start()
        anchoredEpoch = networkWatcher.epoch.value.id
        DiagnosticLog.event(DIAG_TAG, "сторож Tor запущен", "интервалМс" to TICK_MS)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(TICK_MS)
                // Колбэки ConnectivityManager после долгого Doze приходят не на
                // всех прошивках — на каждом тике перепроверяем состояние сами.
                runCatching { networkWatcher.refresh() }
                runCatching { tick("тик") }.onFailure {
                    DiagnosticLog.e(DIAG_TAG, it) { "сбой в цикле сторожа Tor" }
                }
            }
        }
        // Смена сети — самый частый повод лечить соединение, и ждать до
        // TICK_MS здесь незачем: реагируем на событие сразу.
        networkJob = scope.launch(Dispatchers.IO) {
            var seen = networkWatcher.epoch.value.id
            networkWatcher.epoch.collect { epoch ->
                if (epoch.id == seen) return@collect
                seen = epoch.id
                DiagnosticLog.event(
                    DIAG_TAG, "событие сети — проверяем Tor",
                    "поколение" to epoch.id,
                    "онлайн" to epoch.online,
                    "транспорт" to epoch.transport,
                )
                runCatching { tick("сеть-событие") }.onFailure {
                    DiagnosticLog.e(DIAG_TAG, it) { "сбой обработки события сети" }
                }
            }
        }
    }

    /** Останавливает сторожевой цикл и наблюдение за сетью. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        networkJob?.cancel()
        networkJob = null
        networkWatcher.stop()
        DiagnosticLog.event(DIAG_TAG, "сторож Tor остановлен")
    }

    /**
     * Отметить УСПЕШНЫЙ обмен с onion-релеем. Обнуляет счётчик отказов и
     * экспоненциальную паузу: связь работает, накопленное недоверие снимается.
     */
    fun noteRelaySuccess() {
        if (relayFailures.getAndSet(0) != 0) {
            backoffMs = BACKOFF_START_MS
        }
    }

    /**
     * Отметить НЕУДАЧНЫЙ обмен с onion-релеем (таймаут/SOCKS-отказ/разрыв).
     *
     * Считаем только подряд идущие отказы: один потерянный long-poll — норма
     * для Tor, а вот [RELAY_FAILURE_LIMIT] подряд означает, что цепь до сервиса
     * не восстановится сама.
     */
    fun noteRelayFailure() {
        val n = relayFailures.incrementAndGet()
        if (n == RELAY_FAILURE_LIMIT) {
            DiagnosticLog.w(DIAG_TAG) { "релей недоступен $n раз подряд — Tor будет перезапущен" }
        }
    }

    private suspend fun tick(trigger: String) {
        if (!enabledProvider()) {
            // Tor выключен пользователем или аккаунт закрыт: никакого «лечения».
            relayFailures.set(0)
            return
        }
        val epoch = networkWatcher.epoch.value
        if (!epoch.online) {
            // Сети нет совсем — перезапуск только сожжёт батарею. Но поколение
            // ЗАПОМИНАЕМ как «неякорное»: когда сеть вернётся, поколение снова
            // изменится, и следующая проверка увидит расхождение и пересоберёт
            // соединения. Раньше здесь терялся именно этот переход.
            DiagnosticLog.d(DIAG_TAG) { "сети нет — лечение отложено (повод=$trigger)" }
            return
        }
        val status = transport.status.value
        val socks = transport.socksPort

        val reason = when {
            // Сеть под нами стала другой — цепи держатся на мёртвых сокетах.
            epoch.id != anchoredEpoch && epoch.id != 0L -> "сеть"
            // Клиент явно в ошибке: сам он из неё не выйдет.
            status is TorStatus.Error -> "статус-ошибка"
            status is TorStatus.Unavailable -> null
            // Транспорт «поднят», но порта SOCKS нет — работать нечем.
            status is TorStatus.Connected && socks == null -> "нет-socks"
            // Bootstrap стоит на месте дольше отведённого: повторов Arti не делает.
            stalled(status) -> "bootstrap-висит"
            // Статус зелёный, а релей недостижим: сломана цепь до сервиса.
            relayFailures.get() >= RELAY_FAILURE_LIMIT -> "релей"
            // Процесс спал дольше, чем живут цепи Tor. Явного признака поломки
            // нет, но и доверия к соединениям тоже: после долгой паузы сокеты
            // почти наверняка мертвы, а узнаем мы об этом только на таймауте
            // первого запроса — то есть ровно тогда, когда пользователь уже
            // открыл приложение и ждёт. Проверяем заранее.
            sleptTooLong() -> "долгий-сон"
            else -> null
        }
        if (reason == null) {
            lastSeenAwakeAt = SystemClock.elapsedRealtime()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val sinceRestart = now - lastRestartAt
        if (lastRestartAt != 0L && sinceRestart < backoffMs) {
            DiagnosticLog.d(DIAG_TAG) {
                "перезапуск отложен причина=$reason ждём=${backoffMs - sinceRestart}мс"
            }
            return
        }

        DiagnosticLog.event(
            DIAG_TAG, "лечим соединение Tor",
            "причина" to reason,
            "повод" to trigger,
            "статус" to status.toString(),
            "socks" to socks,
            "поколениеСети" to epoch.id,
            "якорь" to anchoredEpoch,
            "транспорт" to epoch.transport,
            "отказовРелея" to relayFailures.get(),
            "паузаМс" to backoffMs,
        )
        lastRestartAt = now
        lastSeenAwakeAt = now
        anchoredEpoch = epoch.id
        relayFailures.set(0)
        transport.restart(reason)
        // Пауза растёт от ПОПЫТКИ, а не от результата: если следующий тик снова
        // найдёт повод, между перезапусками будет уже вдвое больше времени.
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        runCatching { onTorRestarted() }
    }

    /**
     * Спал ли процесс дольше, чем имеет смысл доверять открытым соединениям.
     *
     * Признак сна — разрыв между тиками: цикл просит [TICK_MS], и если реально
     * прошло на порядок больше, значит нас усыпляли. Именно так выглядел
     * сценарий «телефон долго лежал заблокированным, открываю приложение — Tor
     * подключается заново»: соединения были мертвы задолго до открытия, просто
     * никто не проверял.
     */
    private fun sleptTooLong(): Boolean {
        val previous = lastSeenAwakeAt
        if (previous == 0L) return false
        return SystemClock.elapsedRealtime() - previous > SLEEP_SUSPECT_MS
    }

    /**
     * Завис ли bootstrap. `Starting`/`Bootstrapping` без единой смены статуса
     * дольше [BOOTSTRAP_STALL_MS] — это отказ, а не медленный старт: при живой
     * сети Arti двигает прогресс хотя бы на проценты.
     */
    private fun stalled(status: TorStatus): Boolean {
        if (status !is TorStatus.Starting && status !is TorStatus.Bootstrapping) return false
        val changedAt = transport.statusChangedAtElapsed
        if (changedAt == 0L) return false
        return SystemClock.elapsedRealtime() - changedAt > BOOTSTRAP_STALL_MS
    }

    private companion object {
        const val DIAG_TAG = "tor-watch"

        /**
         * Шаг сторожа. 15 с — компромисс: реакция на смену сети ощущается
         * мгновенной по сравнению с прежними минутами, а сам тик стоит одного
         * чтения StateFlow (никаких системных вызовов, если чинить нечего).
         */
        const val TICK_MS = 15_000L

        /** Начальная пауза между перезапусками. */
        const val BACKOFF_START_MS = 20_000L

        /** Потолок паузы: даже в глухой блокировке пробуем раз в 5 минут. */
        const val BACKOFF_MAX_MS = 5 * 60_000L

        /**
         * Сколько держать «Bootstrapping N %» без движения, прежде чем считать
         * старт сорванным. 90 с с запасом покрывают медленный первый запуск с
         * мостами (obfs4/snowflake) на слабой сети.
         */
        const val BOOTSTRAP_STALL_MS = 90_000L

        /**
         * Отказов релея подряд до перезапуска. Порог намеренно не 1: обрыв
         * одного long-poll (30 с ожидания + путь через Tor) — рядовое событие,
         * и лечить его перезапуском означало бы рвать связь ради шума.
         */
        const val RELAY_FAILURE_LIMIT = 3

        /**
         * С какого разрыва между проверками считать, что процесс спал и открытым
         * соединениям больше доверять нельзя.
         *
         * Порог заведомо выше обычного джиттера планировщика (TICK_MS = 15 с) и
         * ниже времени, за которое цепь Tor успевает отмереть. 3 минуты: если
         * тик опоздал настолько, нас точно усыпляли, а не просто задержали.
         */
        const val SLEEP_SUSPECT_MS = 3 * 60_000L
    }
}
