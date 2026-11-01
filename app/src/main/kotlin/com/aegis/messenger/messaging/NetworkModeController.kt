package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.TorMode
import com.aegis.network.tor.NetworkEpoch
import com.aegis.network.tor.RelayConnectionScope
import com.aegis.network.tor.TorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Единственная точка применения режима сети (Tor ВКЛ / Tor ВЫКЛ). Раньше Tor
 * поднимал `LaunchedEffect` экрана, то есть только при открытом интерфейсе, и
 * смена режима никак не упорядочивалась с сетевыми циклами.
 *
 * Порядок переключения и есть гарантия «без перекрёстных утечек»:
 *  1. шлюз закрывает сессию — новых клиентов нет, соединения прежнего режима
 *     обрываются;
 *  2. сетевые циклы останавливаются, TURN-аренды закрываются ([onBeforeSwitch]);
 *  3. ждём выхода последнего запроса прежнего режима из транспорта — без
 *     верхней границы: запрос, начатый напрямую, не должен закончиться уже после
 *     старта Tor, а конечность ожидания обеспечивают таймауты соединения;
 *  4. транспорт: Arti поднимается (ВКЛ) или останавливается полностью (ВЫКЛ);
 *  5. шлюз открывает сессию нового режима, циклы запускаются заново ([onAfterSwitch]).
 *
 * Активный звонок режим не меняет: переключатель в это время недоступен, а
 * `AppContainer.setTorMode` отказывает. Поэтому живых PeerConnection с сокетами
 * прежнего режима к моменту шага 4 нет.
 */
class NetworkModeController(
    private val torTransport: TorTransport,
    private val gateway: RelaySessions,
    private val modeProvider: () -> TorMode,
    /** Поколения сети: в режиме без Tor смена сети пересобирает HTTPS-сессию. */
    private val networkEpochs: StateFlow<NetworkEpoch>?,
    private val onBeforeSwitch: suspend () -> Unit,
    private val onAfterSwitch: suspend (TorMode) -> Unit,
    private val scope: CoroutineScope,
) {
    /** Что сейчас с транспортом — для UI. */
    sealed interface State {
        data object Idle : State
        data class Switching(val target: TorMode) : State
        data class Active(val mode: TorMode) : State
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var applied: TorMode? = null

    private var watchJob: Job? = null

    /** Применяет сохранённый режим при старте процесса и начинает следить за сетью. */
    suspend fun start() {
        switchTo(modeProvider(), "старт")
        watchNetwork()
    }

    /** Переводит транспорт в [mode]. Тот же уже применённый режим — no-op. */
    suspend fun switchTo(mode: TorMode, reason: String) = mutex.withLock {
        if (applied == mode && _state.value == State.Active(mode)) return@withLock
        val from = applied
        _state.value = State.Switching(mode)
        DiagnosticLog.event(DIAG_TAG, "смена режима сети", "было" to from?.name, "стало" to mode.name, "повод" to reason)
        replaceSession {
            withContext(Dispatchers.IO) { torTransport.applyMode(mode) }
        }
        gateway.openSession(mode)
        applied = mode
        _state.value = State.Active(mode)
        runCatching { onAfterSwitch(mode) }
            .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "перезапуск циклов после смены режима" } }
        DiagnosticLog.event(DIAG_TAG, "режим сети применён", "режим" to mode.name)
    }

    /**
     * Мосты/PT поменялись: Arti перезапускается с новым конфигом (сам решает,
     * нужен ли перезапуск), циклы пересобираются под новый порт SOCKS.
     */
    suspend fun reapplyTorConfig() = mutex.withLock {
        if (applied != TorMode.ALWAYS) return@withLock
        withContext(Dispatchers.IO) { torTransport.applyMode(TorMode.ALWAYS) }
        runCatching { onAfterSwitch(TorMode.ALWAYS) }
            .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "перезапуск циклов после смены мостов" } }
    }

    /**
     * Режим без Tor, сеть сменилась: соединения на старом интерфейсе мертвы, но
     * long-poll узнал бы об этом только по таймауту. Пересобираем сессию сразу.
     */
    suspend fun renewDirectSession(reason: String) = mutex.withLock {
        if (applied != TorMode.OFF || modeProvider() != TorMode.OFF) return@withLock
        DiagnosticLog.event(DIAG_TAG, "пересборка HTTPS-сессии", "повод" to reason)
        replaceSession {}
        gateway.openSession(TorMode.OFF)
        runCatching { onAfterSwitch(TorMode.OFF) }
            .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "перезапуск циклов после пересборки сессии" } }
    }

    /** Шаги 1–4: закрыть сессию, остановить циклы, дождаться запросов, затем [transport]. */
    private suspend fun replaceSession(transport: suspend () -> Unit) {
        val closing = gateway.closeSession()
        runCatching { onBeforeSwitch() }
            .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "остановка циклов перед сменой транспорта" } }
        if (closing != null) drain(closing)
        transport()
    }

    private suspend fun drain(closing: RelayConnectionScope) = withContext(Dispatchers.IO) {
        var waitedMs = 0L
        while (!closing.awaitIdle(DRAIN_STEP_MS)) {
            waitedMs += DRAIN_STEP_MS
            DiagnosticLog.w(DIAG_TAG) {
                "ждём завершения запросов прежнего режима: ${closing.inFlightCount} шт., уже ${waitedMs}мс"
            }
        }
    }

    private fun watchNetwork() {
        val epochs = networkEpochs ?: return
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var seen = epochs.value.id
            epochs.collect { epoch ->
                if (epoch.id == seen) return@collect
                seen = epoch.id
                if (epoch.online && applied == TorMode.OFF) renewDirectSession("сеть")
            }
        }
    }

    private companion object {
        const val DIAG_TAG = "net-mode"

        /** Шаг ожидания запросов прежнего режима (только для журнала). */
        const val DRAIN_STEP_MS = 5_000L
    }
}
