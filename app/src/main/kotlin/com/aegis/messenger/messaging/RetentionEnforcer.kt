package com.aegis.messenger.messaging

import com.aegis.core.common.AegisConstants
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.data.storage.MessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Автоудаление сообщений по сроку хранения (§7.3, §14.4).
 *
 * ЖЁСТКОЕ ПРАВИЛО ПРОДУКТА: переписка старше 7 дней не хранится вообще
 * ([AegisConstants.AUTO_DELETE_MAX_DAYS]). Пользователь может выбрать МЕНЬШИЙ
 * срок (1–6 дней), но не больший. [daysProvider] обязан возвращать значение из
 * допустимого диапазона (см. [com.aegis.core.common.security.SecuritySettings]);
 * на всякий случай здесь дополнительно применяется потолок.
 *
 * Работает автономно (backend не требуется): periodic-цикл на IO чистит БД и по
 * факту удаления дёргает [onPurged] (перечитать UI). Серверная очистка очереди
 * согласована и обеспечивается backend'ом (`AEGIS_TTL_SECS`, кап 7 суток).
 *
 * Кроме фонового цикла границу применяет и [MessagingRepository] перед КАЖДЫМ
 * чтением, чтобы UI никогда не показал просроченные сообщения, даже если цикл
 * ещё не сработал.
 */
class RetentionEnforcer(
    private val messageStore: MessageStore,
    private val daysProvider: () -> Int,
    private val scope: CoroutineScope,
    private val onPurged: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var job: Job? = null

    /** Запускает фоновый цикл автоудаления (идемпотентно). */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { enforceNow() }
                delay(SWEEP_INTERVAL_MS)
            }
        }
    }

    /** Останавливает фоновый цикл (например, при экстренной очистке/выходе). */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Немедленно применяет срок хранения: удаляет всё старше границы. Возвращает
     * число удалённых записей; при ненулевом результате вызывает [onPurged].
     */
    suspend fun enforceNow(): Int = withContext(Dispatchers.IO) {
        val purged = runCatching { messageStore.purgeOlderThan(cutoffMillis()) }.getOrDefault(0)
        if (purged > 0) {
            DiagnosticLog.event(DIAG_TAG, "автоудаление", "удалено" to purged, "дней" to daysProvider())
            runCatching { onPurged() }
        }
        purged
    }

    /** Граница отсечения: «сейчас» минус эффективный срок хранения. */
    private fun cutoffMillis(): Long {
        val days = daysProvider().coerceIn(
            AegisConstants.AUTO_DELETE_MIN_DAYS,
            AegisConstants.AUTO_DELETE_MAX_DAYS,
        )
        return clock() - days * DAY_MS
    }

    private companion object {
        const val DIAG_TAG = "retention"
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Как часто фоново подчищаем БД. Достаточно редко (данные ≤7 дней). */
        const val SWEEP_INTERVAL_MS = 60L * 60L * 1000L // 1 час
    }
}
