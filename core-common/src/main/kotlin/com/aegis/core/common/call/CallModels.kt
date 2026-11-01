package com.aegis.core.common.call

import kotlinx.serialization.Serializable

/** Тип медиа звонка (§7.5/§14.9). */
@Serializable
enum class CallMedia {
    AUDIO,
    VIDEO,
}

/** Направление звонка относительно этого устройства. */
enum class CallDirection {
    OUTGOING,
    INCOMING,
}

/**
 * Фаза жизненного цикла звонка (машина состояний [com.aegis], см. CallManager).
 *
 * ```
 *  IDLE ─dial→ DIALING ─(answer)→ CONNECTING ─(media)→ ACTIVE ─(hangup)→ ENDED
 *  IDLE ─offer→ RINGING ─(accept)→ CONNECTING ─(media)→ ACTIVE ─(hangup)→ ENDED
 * ```
 */
enum class CallPhase {
    /** Нет активного звонка. */
    IDLE,

    /** Исходящий: ожидаем ответа собеседника. */
    DIALING,

    /** Входящий: собеседник звонит, ждём принятия/отклонения. */
    RINGING,

    /** Согласование WebRTC (offer/answer/ICE, DTLS-SRTP). */
    CONNECTING,

    /** Медиапоток установлен. */
    ACTIVE,

    /** Звонок завершён (сброшен, отклонён, ошибка). */
    ENDED,
    ;

    /** Идёт набор, гудки, согласование или разговор — нельзя рвать приём/Tor. */
    val isLive: Boolean
        get() = this == DIALING || this == RINGING || this == CONNECTING || this == ACTIVE
}

/**
 * Машинно-читаемая причина завершения звонка.
 *
 * Существует ОТДЕЛЬНО от [CallInfo.endReason]: та строка локализована и
 * предназначена человеку, поэтому сравнивать её в коде нельзя — перевод
 * поменяется, и логика молча сломается. Код же стабилен и используется теми,
 * кто принимает решения: звуковая палитра ([tone.CallTone]) выбирает по нему
 * тон завершения, а диагностика — формулировку в журнале.
 */
enum class CallEndCode {
    /** Звонок не завершён (фаза отличается от [CallPhase.ENDED]). */
    NONE,

    /** Штатное завершение любой из сторон. */
    ENDED,

    /** Собеседник отклонил вызов. */
    DECLINED,

    /** Собеседник занят другим звонком. */
    BUSY,

    /** Никто не ответил за отведённое время. */
    TIMEOUT,

    /** Согласование прошло, но сеть не пропустила медиапоток (ICE не собрался). */
    UNREACHABLE,

    /** Сбой установки или медиадвижка. */
    ERROR,
}

/**
 * Наблюдаемое состояние активного звонка для UI. Метаданные минимальны (§10.10):
 * храним только то, что нужно экрану звонка; на диск ничего не пишем.
 */
data class CallInfo(
    val callId: String,
    val contactId: String,
    val displayName: String,
    val direction: CallDirection,
    val media: CallMedia,
    val phase: CallPhase,
    /** Код сверки (SAS) для устного подтверждения (§10.10); пусто до согласования. */
    val sas: String = "",
    /** Идёт ли медиапоток звонка через Tor (пресет «Параноик», §7.5). */
    val mediaOverTor: Boolean = true,
    /** Усилен ли звонок ручным PSK (§6.2): подмешан ли PSK в ключи звонка. */
    val pskProtected: Boolean = false,
    /**
     * Навешен ли кадровый AEAD (AES-GCM на ключах Signal+PSK) поверх
     * DTLS-SRTP. `false` у заглушки и если криптор не удалось поставить.
     */
    val frameLayer: Boolean = false,
    /** Включена ли передача локальной камеры (только для видеозвонка, §7.5). */
    val cameraEnabled: Boolean = true,
    /** Причина завершения (для фазы ENDED), человекочитаемая. */
    val endReason: String = "",
    /** Та же причина кодом — для логики (звук завершения, журнал), см. [CallEndCode]. */
    val endCode: CallEndCode = CallEndCode.NONE,
    val startedAtMs: Long = 0L,
    /**
     * Лента этапов установки звонка для экрана звонка (§7.5). Только в памяти,
     * как и остальное состояние; хронологическая, самая свежая запись — последняя.
     */
    val stages: List<CallStageEntry> = emptyList(),
    /**
     * Согласован ли медиапуть через ретранслятор TURN (а не напрямую). Влияет
     * только на подсказку в UI: качество через TURN/Tor заметно хуже.
     */
    val relayedPath: Boolean = false,
    /**
     * Медиа установилось, но путь потерян и идёт восстановление (ICE
     * DISCONNECTED). Фаза при этом остаётся ACTIVE — звонок не сброшен.
     */
    val reconnecting: Boolean = false,
) {
    companion object {
        val NONE = CallInfo(
            callId = "",
            contactId = "",
            displayName = "",
            direction = CallDirection.OUTGOING,
            media = CallMedia.AUDIO,
            phase = CallPhase.IDLE,
        )
    }
}

/** Тайминги звонка, общие для обеих сторон и для приёма при закрытом аккаунте. */
object CallTimeouts {
    /**
     * Ожидание ответа человека: набор и гудки. Щедро, потому что сюда входит
     * и доставка offer через Tor (единицы секунд), и время на то, чтобы достать
     * телефон. Дольше звонящий не ждёт — значит, и звонковый конверт старше этого
     * отзвонил.
     */
    const val RING_MS: Long = 75_000L
}

/**
 * Управляющее сообщение сигналинга звонка (§7.5). Передаётся ВНУТРИ конвейера
 * слой A (каскад на PSK) → слой B (Signal) → Tor — тем же путём, что и текст,
 * поэтому по сети идёт только двойной шифртекст, а `callSecretB64` (корень
 * ключей звонка) никогда не виден серверу/сети.
 *
 * Плоский JSON (а не sealed-полиморфизм) — так проще и надёжнее для транспорта.
 */
@Serializable
data class CallControl(
    /** Тип: "offer" | "answer" | "ice" | "hangup" | "busy". */
    val type: String,
    val callId: String,
    val media: CallMedia = CallMedia.AUDIO,
    /**
     * Корень ключей звонка (base64), присутствует ТОЛЬКО в "offer". Доставляется
     * зашифрованным конвейером — в открытую сеть не попадает. Из него + PSK обе
     * стороны выводят SRTP-ключи и SAS (см. CallCrypto).
     */
    val callSecretB64: String? = null,
    /** SDP WebRTC (offer/answer) — согласование DTLS-SRTP. */
    val sdp: String? = null,
    /** ICE-кандидат (для type == "ice"): "<sdpMid>|<sdpMLineIndex>|<candidate>". */
    val ice: String? = null,
    /** Причина завершения (для "hangup"): напр. "declined", "busy", "ended". */
    val reason: String? = null,
    val v: Int = 1,
) {
    companion object {
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_HANGUP = "hangup"
        const val TYPE_BUSY = "busy"

        /** Маркер канала для демультиплексирования на релее (opaque для сервера). */
        const val CHANNEL = "call"
    }
}
