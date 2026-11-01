package com.aegis.messenger.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import com.aegis.core.common.account.AccountState
import com.aegis.core.common.diag.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Автоблокировка приложения по таймауту бездействия (§14.3).
 *
 * Триггеров два, оба сбрасываются при разблокировке/блокировке аккаунта:
 *  1. **Бездействие в foreground** — таймер перезапускается на каждое
 *     взаимодействие пользователя ([onUserInteraction], вызывается из
 *     `Activity.onUserInteraction`). Если за [timeoutMillis] минут не было ни
 *     одного касания — аккаунт блокируется.
 *  2. **Уход в фон** — при уходе приложения в фон (последняя Activity остановлена)
 *     фиксируется монотонное время; при возврате в foreground, если прошло не
 *     меньше таймаута, блокируем сразу. Это закрывает сценарий «телефон забрали
 *     из рук / переключились в другое приложение».
 *
 * Оба триггера УСТУПАЮТ живому звонку ([isBusyProvider]): блокировка в середине
 * набора/ICE/разговора рвёт приём и Tor, а для пользователя выглядит как «звонок
 * оборвался сам» или «приложение закрылось после принятия». Блокировка не
 * отменяется, а откладывается — она применяется сразу по завершении звонка
 * ([onBusyFinished]), поэтому уровень защиты §14.3 сохраняется.
 *
 * Таймер активен ТОЛЬКО когда аккаунт разблокирован ([AccountState.Unlocked]),
 * таймаут > 0 (§8: пресет «Быстро» отключает автоблокировку) и приложение на
 * экране. В фоне касаний не бывает по определению, и таймер, запущенный там,
 * закрывал аккаунт через [timeoutMillis] после любого звонка — а вместе с
 * аккаунтом останавливался приём, и следующий звонок до телефона уже не доходил.
 * Время в фоне учитывает второй триггер — при возврате. Блокировка —
 * «мягкая» ([onLock] дергает `AccountManager.lock()`): секрет затирается из
 * памяти, auth-gate уводит на экран входа, данные не уничтожаются.
 *
 * Используются [SystemClock.elapsedRealtime] (монотонное время, не зависит от
 * перевода часов) и [Application.ActivityLifecycleCallbacks] — без внешних
 * зависимостей на lifecycle-process.
 */
class IdleLockController(
    private val scope: CoroutineScope,
    private val accountState: StateFlow<AccountState>,
    private val timeoutMillisProvider: () -> Long,
    private val onLock: () -> Unit,
    /**
     * Идёт звонок (набор/гудки/медиа). В это время не блокируем: иначе Tor/приём
     * рвутся на середине ICE, и звонок падает с ICE FAILED / SOCKS rep=5.
     */
    private val isBusyProvider: () -> Boolean = { false },
) : Application.ActivityLifecycleCallbacks {

    private var idleJob: Job? = null

    /** Счётчик «видимых» Activity: 0 ⇒ приложение в фоне. Пишется с UI-потока, читается с любого. */
    @Volatile
    private var startedActivities = 0

    /** Монотонное время ухода в фон; 0 — пока в foreground. */
    @Volatile
    private var backgroundedAtElapsed = 0L

    /**
     * Блокировка, отложенная из-за живого звонка: применяется сразу, как звонок
     * завершится ([onBusyFinished]). Защита §14.3 не ослабляется — она только
     * сдвигается на конец разговора.
     */
    @Volatile
    private var lockPending = false

    init {
        // Таймер живёт синхронно с состоянием аккаунта: разблокировали — запустили,
        // заблокировали/удалили — погасили. Это же ловит момент входа, когда
        // приложение уже на переднем плане (Activity не перезапускается).
        scope.launch {
            accountState.collect { state ->
                // Смена состояния аккаунта снимает отложенную блокировку: она
                // относилась к предыдущей разблокированной сессии.
                lockPending = false
                if (state is AccountState.Unlocked && isInForeground()) restartTimer() else cancelTimer()
            }
        }
    }

    /**
     * Находится ли приложение на переднем плане (есть видимые Activity). Нужно
     * уведомлениям (§14.5): «внутренние» уведомления и подавление для открытого
     * чата зависят от того, видит ли пользователь экран прямо сейчас.
     */
    fun isInForeground(): Boolean = startedActivities > 0

    /** Вызывается на каждое взаимодействие пользователя (касание/клавиша). */
    fun onUserInteraction() {
        if (!enabled()) return
        restartTimer()
    }

    /**
     * Звонок завершился — применяем блокировку, отложенную на время разговора.
     *
     * Вызывается из [com.aegis.messenger.AppContainer] по уходу фазы звонка из
     * «живой». Если блокировка не откладывалась, перезапускает таймер
     * бездействия — но только на экране: пока шёл звонок, касаний могло не быть
     * вовсе, а в фоне время и так учтётся при возврате.
     */
    fun onBusyFinished() {
        if (!lockPending) {
            if (enabled() && isInForeground()) restartTimer()
            return
        }
        lockPending = false
        if (accountState.value !is AccountState.Unlocked) return
        DiagnosticLog.event(DIAG_TAG, "применяем отложенную автоблокировку")
        onLock()
    }

    private fun enabled(): Boolean =
        timeoutMillisProvider() > 0L && accountState.value is AccountState.Unlocked

    private fun restartTimer() {
        idleJob?.cancel()
        val timeout = timeoutMillisProvider()
        if (timeout <= 0L || accountState.value !is AccountState.Unlocked) {
            idleJob = null
            return
        }
        idleJob = scope.launch {
            delay(timeout)
            // Перепроверяем условия на момент срабатывания: настройки/состояние
            // могли измениться за время ожидания.
            if (timeoutMillisProvider() > 0L && accountState.value is AccountState.Unlocked) {
                if (isBusyProvider()) {
                    DiagnosticLog.d(DIAG_TAG) { "автоблокировка отложена: идёт звонок" }
                    restartTimer()
                    return@launch
                }
                DiagnosticLog.event(DIAG_TAG, "автоблокировка по бездействию", "таймаутМс" to timeout)
                onLock()
            }
        }
    }

    private fun cancelTimer() {
        idleJob?.cancel()
        idleJob = null
    }

    // --- Application.ActivityLifecycleCallbacks ---

    override fun onActivityStarted(activity: Activity) {
        val wasBackground = startedActivities == 0
        startedActivities++
        if (!wasBackground) return

        // Возврат в foreground: если в фоне провели дольше таймаута — блокируем.
        val timeout = timeoutMillisProvider()
        val bgAt = backgroundedAtElapsed
        backgroundedAtElapsed = 0L
        if (timeout > 0L && accountState.value is AccountState.Unlocked && bgAt > 0L) {
            val awayMillis = SystemClock.elapsedRealtime() - bgAt
            if (awayMillis >= timeout) {
                // Телефон лежал заблокированным дольше таймаута, а сейчас на нём
                // звонит вызов и пользователь вернулся, чтобы его принять.
                // Заблокировать аккаунт прямо здесь — значит оборвать звонок
                // (AppContainer по Locked делает hangUp) и увести UI на экран
                // входа РОВНО в момент нажатия «Принять»: снаружи это выглядит
                // как «приложение закрылось после принятия звонка».
                if (isBusyProvider()) {
                    lockPending = true
                    DiagnosticLog.event(
                        DIAG_TAG, "автоблокировка после фона отложена: идёт звонок",
                        "вФонеМс" to awayMillis,
                        "таймаутМс" to timeout,
                    )
                } else {
                    DiagnosticLog.event(
                        DIAG_TAG, "автоблокировка после фона",
                        "вФонеМс" to awayMillis,
                        "таймаутМс" to timeout,
                    )
                    onLock()
                    return
                }
            }
        }
        if (enabled()) restartTimer()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            // Ушли в фон: гасим foreground-таймер и запоминаем момент.
            cancelTimer()
            backgroundedAtElapsed = SystemClock.elapsedRealtime()
            DiagnosticLog.d(DIAG_TAG) { "приложение в фоне" }
        }
    }

    private companion object {
        const val DIAG_TAG = "idle-lock"
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
