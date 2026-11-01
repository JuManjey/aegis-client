package com.aegis.core.common.call

/**
 * Буфер ICE-кандидатов, пришедших раньше offer или до создания медиадвижка.
 *
 * Сигналинг звонка идёт через Tor/mailbox: ICE часто обгоняет offer (меньший
 * POST) или приходит, пока входящий ещё в RINGING и PeerConnection нет.
 * Без буфера кандидат теряется, ICE-политика RELAY не сходится, звонок
 * падает с `ICE FAILED` через ~15 с.
 *
 * Ключ — [callId]: кандидаты чужого звонка никогда не подмешиваются в текущий.
 */
class PendingCallIce(
    private val maxPerCall: Int = DEFAULT_MAX_PER_CALL,
    private val maxCalls: Int = DEFAULT_MAX_CALLS,
) {
    private val lock = Any()
    private val byCallId = LinkedHashMap<String, MutableList<String>>()

    /** Кладёт encoded ICE (`sdpMid|idx|candidate`). Пустые [callId] игнорирует. */
    fun add(callId: String, encodedIce: String) {
        if (callId.isEmpty() || encodedIce.isEmpty()) return
        synchronized(lock) {
            val list = byCallId[callId] ?: run {
                while (byCallId.size >= maxCalls) {
                    val oldest = byCallId.keys.first()
                    byCallId.remove(oldest)
                }
                mutableListOf<String>().also { byCallId[callId] = it }
            }
            if (list.size >= maxPerCall) return
            if (encodedIce !in list) list.add(encodedIce)
        }
    }

    /** Забирает и удаляет все кандидаты звонка (порядок сохранения). */
    fun take(callId: String): List<String> = synchronized(lock) {
        byCallId.remove(callId)?.toList().orEmpty()
    }

    /** Отбрасывает буфер звонка (busy/hangup), не применяя. */
    fun drop(callId: String) {
        synchronized(lock) { byCallId.remove(callId) }
    }

    fun size(callId: String): Int = synchronized(lock) { byCallId[callId]?.size ?: 0 }

    companion object {
        const val DEFAULT_MAX_PER_CALL = 24
        const val DEFAULT_MAX_CALLS = 8
    }
}
