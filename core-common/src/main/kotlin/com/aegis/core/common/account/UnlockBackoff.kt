package com.aegis.core.common.account

import kotlinx.serialization.Serializable

/**
 * Состояние ограничителя попыток ввода пароля (§14.3).
 *
 * Время — монотонные часы устройства (`elapsedRealtime`) в пределах одной
 * загрузки ([bootCount]): системное время переводится без прав, а монотонные
 * часы после перезагрузки начинаются с нуля, поэтому окно задержки тогда
 * отсчитывается заново.
 */
@Serializable
data class UnlockAttempts(
    val failures: Int = 0,
    val bootCount: Int = UNKNOWN_BOOT,
    val lockedUntilElapsedMs: Long = 0L,
) {
    companion object {
        const val UNKNOWN_BOOT: Int = -1
    }
}

/** Нарастающая задержка между попытками ввода пароля и порог необязательной очистки. */
object UnlockBackoff {

    /** Сколько неудач подряд допускается без задержки. */
    const val FREE_ATTEMPTS: Int = 4

    /** После стольких неудач подряд данные стираются, если очистка включена. */
    const val WIPE_AFTER_FAILURES: Int = 10

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND

    /** Задержка после [failures]-й неудачи подряд. */
    fun delayAfter(failures: Int): Long = when {
        failures <= FREE_ATTEMPTS -> 0L
        failures == 5 -> 30 * SECOND
        failures == 6 -> MINUTE
        failures == 7 -> 5 * MINUTE
        failures == 8 -> 15 * MINUTE
        failures == 9 -> 30 * MINUTE
        else -> 60 * MINUTE
    }

    fun recordFailure(state: UnlockAttempts, nowElapsedMs: Long, bootCount: Int): UnlockAttempts {
        val failures = state.failures + 1
        return UnlockAttempts(
            failures = failures,
            bootCount = bootCount,
            lockedUntilElapsedMs = nowElapsedMs + delayAfter(failures),
        )
    }

    /**
     * Сколько ещё ждать до следующей попытки и состояние, которое нужно
     * сохранить (меняется, если устройство перезагружалось).
     *
     * Результат не превышает задержку для текущего числа неудач: если номер
     * загрузки определить не удалось, метка прошлой загрузки не растянет ожидание.
     */
    fun remaining(state: UnlockAttempts, nowElapsedMs: Long, bootCount: Int): Pair<Long, UnlockAttempts> {
        if (state.failures == 0) return 0L to state
        val delay = delayAfter(state.failures)
        val current = if (bootCount != UnlockAttempts.UNKNOWN_BOOT && state.bootCount == bootCount) {
            state
        } else {
            state.copy(bootCount = bootCount, lockedUntilElapsedMs = nowElapsedMs + delay)
        }
        val left = (current.lockedUntilElapsedMs - nowElapsedMs).coerceIn(0L, delay)
        return left to current
    }

    /** Сколько неудач осталось до очистки после [failures] неудач подряд. */
    fun attemptsBeforeWipe(failures: Int): Int = (WIPE_AFTER_FAILURES - failures).coerceAtLeast(0)
}
