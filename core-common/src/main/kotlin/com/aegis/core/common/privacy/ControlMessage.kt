package com.aegis.core.common.privacy

import kotlinx.serialization.Serializable

/**
 * Управляющее (control) сообщение переписки (§14.2/§7.3). Идёт ВНУТРИ того же
 * защищённого конвейера, что и текст и сигналинг звонков: слой A (каскад на PSK)
 * → слой B (Signal/Double Ratchet) → Tor → onion-релей. По сети — только двойной
 * шифртекст, поэтому сервер/сеть не видят ни типа события, ни идентификаторов
 * сообщений (zero-trust, §7.6/§10.9).
 *
 * Плоский JSON с полем [type] (как [com.aegis.core.common.call.CallControl]) —
 * проще и надёжнее для транспорта, чем sealed-полиморфизм.
 *
 * Все события control-канала — эфемерные метаданные (§10.10): онлайн-статус и
 * «печатает…» на приёме держатся ТОЛЬКО в памяти и не пишутся на диск; отметки
 * прочтения и «удалить у всех» меняют статус/удаляют записи в зашифрованной БД.
 */
@Serializable
data class ControlMessage(
    /** Тип события: [TYPE_READ] | [TYPE_TYPING] | [TYPE_PRESENCE] | [TYPE_DELETE]. */
    val type: String,
    /**
     * Идентификаторы сообщений (общие для обеих сторон, см. `WireEnvelope.msgId`):
     *  - для [TYPE_READ] — какие мои исходящие сообщения собеседник прочитал;
     *  - для [TYPE_DELETE] — какие сообщения удалить у получателя («удалить у всех»).
     */
    val messageIds: List<String> = emptyList(),
    /** Печатает ли собеседник сейчас (для [TYPE_TYPING]). */
    val typing: Boolean = false,
    /** В сети ли собеседник (для [TYPE_PRESENCE]). */
    val online: Boolean = false,
    /** Момент события у отправителя (мс, epoch); служит «последним визитом». */
    val atMs: Long = 0L,
    /**
     * device id МОЕГО устройства для [TYPE_DEVICE_ANNOUNCE]/[TYPE_DEVICE_REVOKE]
     * (мультидевайс §7.1.1, `docs/multidevice.md` §5/§10): сообщаю контакту о
     * своём новом/отзываемом устройстве, чтобы он вёл fan-out и на него.
     */
    val deviceId: Int = 0,
    /** Публичный Signal identity-ключ анонсируемого устройства (base64). */
    val deviceIdentityB64: String = "",
    /** prekey-бандл анонсируемого устройства для X3DH/PQXDH (JSON). */
    val deviceBundleJson: String = "",
    /** Человекочитаемое имя анонсируемого устройства (опционально). */
    val deviceLabel: String = "",
    val v: Int = 1,
) {
    companion object {
        /** Отчёт о прочтении (галочки, §14.2). */
        const val TYPE_READ = "read"

        /** Индикатор «печатает…» (§14.2). */
        const val TYPE_TYPING = "typing"

        /** Онлайн-статус / «был(а) в сети» (§14.2). */
        const val TYPE_PRESENCE = "presence"

        /** «Удалить у всех» — запрос удаления сообщений у собеседника (§7.3). */
        const val TYPE_DELETE = "delete"

        /** Анонс моего нового устройства контакту (мультидевайс §5, шаг 3). */
        const val TYPE_DEVICE_ANNOUNCE = "device-announce"

        /** Отзыв моего устройства у контакта (мультидевайс §10). */
        const val TYPE_DEVICE_REVOKE = "device-revoke"

        /** Маркер канала для демультиплексирования на релее (opaque для сервера). */
        const val CHANNEL = "control"
    }
}
