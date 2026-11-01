package com.aegis.messenger.notifications

import android.content.Context
import android.os.PowerManager
import com.aegis.core.common.diag.DiagnosticLog

/**
 * Включает экран на входящем звонке.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ МЕХАНИЗМ, если есть full-screen intent. Начиная с Android 14
 * разрешение `USE_FULL_SCREEN_INTENT` больше не выдаётся при установке всем
 * подряд — только приложениям, зарегистрированным как звонилка или будильник.
 * Всем остальным его включает пользователь вручную («Специальный доступ → Full
 * screen intents»). Пока разрешения нет, full-screen intent молча деградирует до
 * обычного heads-up: телефон звонит и вибрирует, но НИЧЕГО не показывает — и на
 * погашенном экране пользователь даже не понимает, какое приложение звонит.
 *
 * Замок `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` будит экран
 * принудительно. Дальше пользователь видит хотя бы уведомление о звонке
 * (CallStyle, [AegisNotifier.notifyIncomingCall]) на экране блокировки и может
 * ответить прямо с него.
 *
 * ПОЧЕМУ ЭТО НЕ ОСЛАБЛЯЕТ §14.3. Мы включаем ПОДСВЕТКУ, а не снимаем блокировку:
 * keyguard остаётся, `FLAG_SECURE` остаётся, а карточка звонка поверх блокировки
 * обезличена (см. `CallScreen`). Замок берётся строго на время дозвона и с
 * жёстким таймаутом [TIMEOUT_MS], поэтому «забыть» его невозможно.
 */
class IncomingCallWaker(context: Context) {

    private val appContext = context.applicationContext

    private var wakeLock: PowerManager.WakeLock? = null

    /** Будит экран. Идемпотентно: повторный вызов только продлевает срок. */
    @Synchronized
    fun wake() {
        val power = runCatching {
            appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return

        val existing = wakeLock
        if (existing != null) {
            runCatching { existing.acquire(TIMEOUT_MS) }
            return
        }
        // SCREEN_BRIGHT_WAKE_LOCK помечен deprecated в пользу FLAG_TURN_SCREEN_ON /
        // setTurnScreenOn(), но те работают ТОЛЬКО когда Activity действительно
        // запускается. Именно этого и не происходит без разрешения на full-screen
        // intent, поэтому здесь замок — не дублирование, а единственный путь.
        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
        val lock = runCatching {
            power.newWakeLock(flags, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(TIMEOUT_MS)
            }
        }.getOrNull()
        if (lock == null) {
            DiagnosticLog.w(DIAG_TAG) { "не удалось включить экран на входящем звонке" }
            return
        }
        wakeLock = lock
        DiagnosticLog.event(DIAG_TAG, "экран включён для входящего звонка", "таймаутМс" to TIMEOUT_MS)
    }

    /** Отпускает замок (звонок принят/отклонён/завершён). Идемпотентно. */
    @Synchronized
    fun release() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        DiagnosticLog.d(DIAG_TAG) { "замок экрана отпущен" }
    }

    private companion object {
        const val DIAG_TAG = "call-wake"
        const val WAKE_LOCK_TAG = "aegis:incoming-call"

        /**
         * Дольше любого разумного дозвона: рингтон гасится по смене фазы, а этот
         * таймаут — страховка от «зависшего» замка, если фаза не придёт вовсе.
         */
        const val TIMEOUT_MS = 60_000L
    }
}
