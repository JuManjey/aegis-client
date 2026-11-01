package com.aegis.core.common.call

/**
 * Этапы установки звонка для показа пользователю (§7.5).
 *
 * Порядок объявления — порядок обычного прохождения. Ярлыки НЕ хранятся здесь:
 * их резолвит UI через `stringResource`, иначе строки не переключались бы по
 * языку (§14.6) и попали бы в доменный модуль.
 *
 * Это ПОЛЬЗОВАТЕЛЬСКИЕ этапы, а не журнал диагностики: сюда попадает только то,
 * что помогает понять «почему так долго» и «на чём встало». Никаких адресов,
 * onion, ufrag, ключей и отпечатков (§10.10) — детали ограничены короткими
 * человеческими пометками.
 */
enum class CallStage {
    /** Исходящий: начали набор. */
    OUTGOING_START,

    /** Входящий: получили приглашение собеседника. */
    INCOMING_OFFER,

    /** Фоновая служба звонка (микрофон/камера, Android 14+). */
    MEDIA_SERVICE,

    /** Ретранслятор TURN через Tor (анонимный режим или резерв). */
    TURN,

    /** Медиадвижок WebRTC поднят. */
    ENGINE,

    /** Локальное описание готово (offer/answer с DTLS-фингерпринтом). */
    LOCAL_SDP,

    /** Приглашение ушло собеседнику через релей. */
    SIGNAL_OFFER,

    /** Ответ собеседника получен/отправлен. */
    SIGNAL_ANSWER,

    /** Ключи звонка выведены (Signal + PSK), код сверки готов. */
    KEYS,

    /** Поиск сетевого пути между абонентами (ICE). */
    ICE,

    /** Медиапоток под DTLS-SRTP идёт. */
    MEDIA,

    /** Звонок завершён. */
    ENDED,
    ;
}

/** Состояние этапа. */
enum class CallStageState {
    /** Идёт прямо сейчас. */
    ACTIVE,

    /** Пройден успешно. */
    DONE,

    /** Пройден, но с оговоркой (например, TURN недоступен — поедем напрямую). */
    WARN,

    /** Не удался — звонок дальше не пойдёт. */
    FAILED,
}

/**
 * Одна запись «ленты этапов» звонка.
 *
 * @param atMs монотонные миллисекунды с начала звонка (для подписи «+1.2 с»);
 *   абсолютное время НЕ храним — это метаданные звонка (§10.10).
 * @param detail короткая уже локализованная пометка или пустая строка.
 */
data class CallStageEntry(
    val stage: CallStage,
    val state: CallStageState,
    val atMs: Long,
    val detail: String = "",
)

/**
 * Накопитель ленты этапов одного звонка.
 *
 * Семантика [mark]: если этап уже есть в ленте в состоянии [CallStageState.ACTIVE],
 * запись ОБНОВЛЯЕТСЯ на месте (то есть «Поиск пути…» превращается в «Путь
 * найден», а не плодит вторую строку); во всех остальных случаях запись
 * добавляется в конец. Так лента остаётся хронологической и короткой.
 *
 * Класс не потокобезопасен сам по себе — вызывающий ([com.aegis.messenger.call.CallManager])
 * сериализует доступ, потому что этапы приходят и с корутин сигналинга, и с
 * нативных потоков WebRTC.
 */
class CallStageLog(private val clock: () -> Long) {

    private val entries = ArrayList<CallStageEntry>(LIMIT)

    /** Момент старта звонка в той же шкале, что [clock]; 0 — лента пуста. */
    private var startedAt = 0L

    /** Начинает новую ленту: старая лента предыдущего звонка не перетекает. */
    fun reset() {
        entries.clear()
        startedAt = 0L
    }

    /**
     * Добавляет/обновляет этап и возвращает НЕИЗМЕНЯЕМЫЙ снимок ленты, готовый
     * положить в [CallInfo.stages].
     */
    fun mark(
        stage: CallStage,
        state: CallStageState,
        detail: String = "",
    ): List<CallStageEntry> {
        val now = clock()
        if (startedAt == 0L) startedAt = now
        val at = (now - startedAt).coerceAtLeast(0L)
        val entry = CallStageEntry(stage, state, at, detail)
        val openIdx = entries.indexOfLast { it.stage == stage && it.state == CallStageState.ACTIVE }
        if (openIdx >= 0) {
            entries[openIdx] = entry
        } else {
            entries.add(entry)
            // Лента живёт в памяти и рисуется на экране звонка: неограниченный
            // рост дал бы и утечку, и бесполезную прокрутку.
            while (entries.size > LIMIT) entries.removeAt(0)
        }
        return snapshot()
    }

    /** Неизменяемый снимок ленты. */
    fun snapshot(): List<CallStageEntry> = entries.toList()

    private companion object {
        const val LIMIT = 24
    }
}
