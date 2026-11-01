package com.aegis.messenger.messaging

import com.aegis.core.common.security.MetadataMinimization
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Смягчение тайминг-корреляции fan-out (`docs/multidevice.md` §12, `meta.minimize`
 * §8 ТЗ).
 *
 * При sender-side fan-out (§6) одно логическое сообщение уходит N дропами (по
 * одному на устройство адресата и на каждое своё устройство в self-sync) в
 * НЕСВЯЗАННЫЕ mailbox'ы. Сервер не видит ни адресатов, ни связи ящиков, ни числа
 * устройств (MD-2/MD-3): все mailbox-id — независимые HKDF-выводы. Однако
 * ГЛОБАЛЬНЫЙ наблюдатель за релеем мог бы по ОДНОВРЕМЕННОСТИ и ДЕТЕРМИНИРОВАННОМУ
 * ПОРЯДКУ (дропы шли по возрастанию `deviceId`) N дропов предположить групповую
 * доставку. Этот хелпер убирает обе улики:
 *  - [order] рандомизирует ПОРЯДОК per-device дропов;
 *  - [awaitJitter] вносит случайную ЗАДЕРЖКУ перед каждым дропом, чтобы дропы не
 *    уходили детерминированной «пачкой».
 *
 * Величина управляется `meta.minimize`:
 *  - [MetadataMinimization.MAXIMUM] (пресет «Параноик», дефолт) — заметный джиттер;
 *  - [MetadataMinimization.MODERATE] — умеренный;
 *  - [MetadataMinimization.BASIC] — ВЫКЛ: исходный порядок и нулевая задержка
 *    (полностью прежнее поведение).
 *
 * Честные границы (§12):
 *  - Это СМЯГЧЕНИЕ, а не устранение: общий предел store-and-forward. Основную
 *    защиту метаданных транспорта даёт Tor.
 *  - E2EE (слои A/B) от джиттера НЕ зависит; best-effort доставка сохраняется
 *    (джиттер лишь сдвигает моменты POST во времени, ничего не теряя).
 *  - Джиттер применяется единообразно ко всем каналам fan-out (переписка,
 *    control §14.2, сигналинг звонков §7.5, self-sync §6) — чтобы наблюдатель не
 *    отличал типы трафика и «одно устройство vs несколько» по тайминг-паттерну.
 *    Цена — небольшая задержка установки звонка при `MAXIMUM` (осознанно, в духе
 *    «медиа через Tor» пресета «Параноик»).
 */
class FanoutJitter(
    private val levelProvider: () -> MetadataMinimization,
    private val random: Random = Random.Default,
    /** Приостановка на [ms] мс (инъекция для тестов; по умолчанию корутинный delay). */
    private val sleep: suspend (ms: Long) -> Unit = { ms -> if (ms > 0L) delay(ms) },
) {
    /**
     * Возвращает КОПИЮ [items] в случайном порядке (перестановка — ничего не
     * теряется/не дублируется). При [MetadataMinimization.BASIC] — исходный
     * порядок без копирования семантики (прежнее поведение).
     */
    fun <T> order(items: List<T>): List<T> = when (levelProvider()) {
        MetadataMinimization.BASIC -> items
        MetadataMinimization.MODERATE, MetadataMinimization.MAXIMUM -> items.shuffled(random)
    }

    /** Верхняя граница задержки перед ОДНИМ дропом (мс) для текущего уровня. */
    fun maxDelayMs(): Long = when (levelProvider()) {
        MetadataMinimization.MAXIMUM -> MAXIMUM_JITTER_MAX_MS
        MetadataMinimization.MODERATE -> MODERATE_JITTER_MAX_MS
        MetadataMinimization.BASIC -> 0L
    }

    /**
     * Приостанавливает корутину на случайную величину в `[0..maxDelayMs]` перед
     * дропом, decorrelating дропы во времени. При [MetadataMinimization.BASIC] —
     * no-op (нулевая задержка ⇒ прежнее поведение).
     */
    suspend fun awaitJitter() {
        val max = maxDelayMs()
        if (max <= 0L) return
        sleep(random.nextLong(max + 1L))
    }

    companion object {
        /** Максимальная задержка на дроп при `meta.minimize=MAXIMUM` (Параноик). */
        const val MAXIMUM_JITTER_MAX_MS = 2_500L

        /** Максимальная задержка на дроп при `meta.minimize=MODERATE`. */
        const val MODERATE_JITTER_MAX_MS = 800L
    }
}
