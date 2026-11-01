package com.aegis.core.common.model

/** Направление сообщения относительно владельца устройства. */
enum class MessageDirection { INCOMING, OUTGOING }

/**
 * Статус доставки/прочтения сообщения (§13: индикаторы у бабблов; §14.2 галочки).
 *
 * Для исходящих: PENDING (в очереди на отправку через Tor) → SENT (передано
 * релею) → READ (собеседник прочитал, по отчёту control-канала §14.2) либо FAILED.
 * Для входящих: RECEIVED (получено, ещё не открыто — считается непрочитанным) →
 * READ (чат открыт, отметка «прочитано» отправлена собеседнику, если разрешено).
 */
enum class MessageStatus { PENDING, SENT, FAILED, RECEIVED, READ }

/**
 * Одно сообщение переписки (§6.3). Хранится в SQLCipher (AES-256): [body] —
 * УЖЕ расшифрованный текст для показа в UI; в открытом виде на диск он не
 * попадает, т.к. вся БД зашифрована ключом из StrongBox. По сети сообщение
 * уходит только как каскадный контейнер (слой A) внутри конверта Signal (слой B).
 */
data class Message(
    val id: String,
    val contactId: String,
    val direction: MessageDirection,
    val body: String,
    val timestampMs: Long,
    val status: MessageStatus,
    /**
     * Вложение (§14.4), если сообщение его несёт. Метаданные + ссылка на блоб;
     * `null` для обычного текста. Сами байты файла хранятся отдельным BLOB
     * (см. [com.aegis.data.storage.MessageStore]).
     */
    val attachment: AttachmentRef? = null,
    /**
     * Доступны ли байты вложения локально (уже скачаны/расшифрованы и лежат в
     * зашифрованной БД). Исходящие — сразу `true`; входящие — после скачивания.
     */
    val attachmentLocal: Boolean = false,
    /**
     * Фактически применённые алгоритмы (слой A/B, транспорт). `null` у сообщений,
     * сохранённых до появления этой записи, или если шифрование ещё не выполнялось
     * (PENDING до конвейера).
     */
    val crypto: MessageCryptoInfo? = null,
)
