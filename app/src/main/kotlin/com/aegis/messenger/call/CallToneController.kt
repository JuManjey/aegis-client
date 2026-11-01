package com.aegis.messenger.call

import com.aegis.core.common.call.CallDirection
import com.aegis.core.common.call.CallEndCode
import com.aegis.core.common.call.CallInfo
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.call.tone.CallTone
import com.aegis.core.common.call.tone.CallToneRoute
import com.aegis.core.common.diag.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Озвучивание хода звонка: [CallPhase] → [CallTone] (§7.5/§14.5).
 *
 * Отдельный класс, а не ветка в `AppContainer`, по двум причинам: озвучка — это
 * ПРОИЗВОДНАЯ от состояния звонка (никакого своего состояния, кроме памяти о
 * предыдущем кадре, у неё нет), и её правила — единственное место, где написано
 * «что должно звучать в каждой фазе». Логику звонка это не трогает: контроллер
 * только подписчик [CallManager.state].
 *
 * ## Разделение с рингтоном входящего
 *
 * Входящий звонок (фаза [CallPhase.RINGING]) озвучивает НЕ этот класс, а
 * [CallRingtonePlayer] через `AppContainer`: там свои правила — режим звонка
 * устройства, персональная мелодия контакта, вибрация, глобальная пауза
 * уведомлений. Контроллер намеренно не трогает [CallPhase.RINGING], иначе
 * входящий звонок озвучивали бы двое.
 *
 * ## Правила
 *
 * Зацикленный тон (ровно один в каждый момент) — по ТЕКУЩЕМУ состоянию:
 *
 * | Состояние                          | Тон                             |
 * |------------------------------------|---------------------------------|
 * | `DIALING`, исходящий               | [CallTone.OUTGOING_RINGBACK]    |
 * | `CONNECTING`                       | [CallTone.NEGOTIATING]          |
 * | `ACTIVE` + `reconnecting`          | [CallTone.LINK_LOST]            |
 * | остальное                          | тишина                          |
 *
 * Одиночные тоны — на ПЕРЕХОДАХ: вход в `ACTIVE` ([CallTone.CONNECTED]),
 * снятие `reconnecting` ([CallTone.RELINK]), вход в `ENDED` (тон зависит от
 * [CallEndCode]). Исключение — переход `RINGING → ENDED`: пропущенный или
 * отклонённый входящий звонок остаётся беззвучным.
 */
class CallToneController(
    private val player: CallTonePlayer,
    private val scope: CoroutineScope,
    /** Включены ли тоны прогресса в настройках (§14.5). Читается на каждый кадр. */
    private val tonesEnabled: () -> Boolean,
) {

    /** Снимок предыдущего кадра — по нему определяются переходы. */
    private var lastPhase: CallPhase = CallPhase.IDLE
    private var lastReconnecting: Boolean = false
    private var lastCallId: String = ""

    private var job: Job? = null

    /**
     * Тишина до конца ТЕКУЩЕГО звонка. Ставится [silence] и снимается, когда
     * звонок догорел (см. [onCall]).
     */
    @Volatile
    private var suppressed = false

    /** Подписывается на состояние звонка. Повторный вызов пересоздаёт подписку. */
    fun bind(state: StateFlow<CallInfo>) {
        job?.cancel()
        job = scope.launch {
            state.collect { call -> onCall(call) }
        }
    }

    /**
     * Немедленная тишина и запрет озвучивать завершение текущего звонка.
     *
     * Нужно на пути блокировки аккаунта (§14.3) и дюресс-стирания: там звонок
     * сбрасывается принудительно, и тон завершения прозвучал бы В УХО уже после
     * того, как пользователь убрал телефон, — то есть выдал бы факт звонка ровно
     * в тот момент, когда его гасили. Подписка при этом остаётся: контейнер
     * живёт весь процесс, аккаунт можно разблокировать снова.
     */
    fun silence() {
        suppressed = true
        player.release()
    }

    private fun onCall(call: CallInfo) {
        // Выключенные тоны: гасим СВОЁ и обновляем снимок кадра. Снимок нужен,
        // иначе включение переключателя посреди разговора «догнало» бы нас
        // одиночным тоном перехода, который случился минуту назад. Рингтон
        // входящего не трогаем — у него свои настройки и свой владелец.
        if (!tonesEnabled()) {
            player.stopLoop(CallToneRoute.CALL)
            player.stopOneShots()
            forget(call)
            return
        }

        val newCall = call.callId != lastCallId && call.callId.isNotEmpty()

        if (suppressed) {
            // Снимаем запрет, когда прошлый звонок закончился (IDLE) или начался
            // новый: сам факт нового звонка означает, что пользователь вернулся.
            if (call.phase == CallPhase.IDLE || newCall) {
                suppressed = false
            } else {
                player.stopLoop(CallToneRoute.CALL)
                player.stopOneShots()
                forget(call)
                return
            }
        }
        if (newCall) {
            // Новый звонок: прошлые переходы к нему отношения не имеют.
            lastPhase = CallPhase.IDLE
            lastReconnecting = false
            // Синтез палитры — заранее, пока поднимаются движок и Tor: первый
            // «гудок» не должен ждать рендера. Набор зависит от направления:
            // исходящему нужны гудки, входящему — рингтон (его попросит
            // CallRingtonePlayer, и кэш к тому моменту уже тёплый).
            val entry = if (call.direction == CallDirection.OUTGOING) {
                CallTone.OUTGOING_RINGBACK
            } else {
                CallTone.INCOMING_RING
            }
            player.prewarm(entry, CallTone.NEGOTIATING, CallTone.CONNECTED, CallTone.HANGUP)
        }

        // Петля — ПЕРВОЙ: на переходе в ACTIVE/ENDED она должна смолкнуть до
        // того, как зазвучит одиночный тон события, иначе они накладываются.
        applyLoop(call)
        applyTransitions(call)

        if (call.phase == CallPhase.IDLE) {
            // Звонков больше нет: отдаём память кэша PCM (~1 МБ). Именно
            // clearCache, а НЕ release: окно звонка закрывается через 220 мс
            // после своего сброса, а тон завершения длится до 1.5 с — полное
            // глушение обрубало бы его на полуслове.
            player.clearCache()
        }
        forget(call)
    }

    private fun applyLoop(call: CallInfo) {
        val tone = when {
            call.phase == CallPhase.DIALING && call.direction == CallDirection.OUTGOING ->
                CallTone.OUTGOING_RINGBACK
            call.phase == CallPhase.CONNECTING -> CallTone.NEGOTIATING
            call.phase == CallPhase.ACTIVE && call.reconnecting -> CallTone.LINK_LOST
            else -> null
        }
        // Гасим ТОЛЬКО свой маршрут: рингтон входящего — не наша петля, им
        // управляет CallRingtonePlayer (см. KDoc класса).
        //
        // Результат startLoop здесь не проверяется намеренно: у тонов прогресса
        // нет и не нужен резервный источник — молчащий «гудок» это неудобство,
        // а не пропущенный звонок, и сам сбой уже попал в журнал.
        if (tone != null) {
            player.startLoop(tone)
        } else {
            player.stopLoop(CallToneRoute.CALL)
        }
    }

    private fun applyTransitions(call: CallInfo) {
        val phase = call.phase
        if (phase == CallPhase.ACTIVE && lastPhase != CallPhase.ACTIVE) {
            player.playOnce(CallTone.CONNECTED)
        }
        // Восстановление пути: звучит только возврат, потерю озвучивает петля.
        if (phase == CallPhase.ACTIVE && lastReconnecting && !call.reconnecting) {
            player.playOnce(CallTone.RELINK)
        }
        if (phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED) {
            // Пропущенный или отклонённый входящий НЕ озвучиваем: из RINGING
            // звонок уходит в ENDED, ни разу не став разговором, и тон в ухо
            // здесь неуместен — телефон просто перестаёт звонить, как и в любой
            // другой телефонии. Обратную связь о пропуске даёт уведомление.
            if (lastPhase == CallPhase.RINGING) {
                DiagnosticLog.event(DIAG_TAG, "завершение из RINGING — без тона")
                return
            }
            val tone = endTone(call.endCode)
            DiagnosticLog.event(
                DIAG_TAG, "тон завершения",
                "код" to call.endCode.name,
                "тон" to tone.name,
            )
            player.playOnce(tone)
        }
    }

    private fun forget(call: CallInfo) {
        lastPhase = call.phase
        lastReconnecting = call.reconnecting
        lastCallId = call.callId
    }

    /**
     * Тон завершения по причине. Три интонации вместо девяти звуков: штатный
     * конец, «занято» и общий отказ — больше оттенков на слух всё равно не
     * различить, а причину словами показывает экран звонка.
     */
    private fun endTone(code: CallEndCode): CallTone = when (code) {
        CallEndCode.BUSY -> CallTone.BUSY
        CallEndCode.DECLINED,
        CallEndCode.TIMEOUT,
        CallEndCode.UNREACHABLE,
        CallEndCode.ERROR -> CallTone.DENIED
        // NONE тоже сюда: фаза ENDED без кода — это штатный конец из старого
        // пути, и «отключение питания» для него верно.
        CallEndCode.ENDED, CallEndCode.NONE -> CallTone.HANGUP
    }

    private companion object {
        const val DIAG_TAG = "call-tone"
    }
}
