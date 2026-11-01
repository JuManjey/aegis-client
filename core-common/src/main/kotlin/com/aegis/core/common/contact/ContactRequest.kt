package com.aegis.core.common.contact

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Нагрузки рукопожатия RCH v1 — удалённое добавление контакта
 * (`docs/contact_requests.md`, PROTOCOL.md §4.7).
 *
 * ```
 *  A ──[INVITE  в ящик приглашений B, внутри Signal PreKeyMessage]──▶ B
 *  A ◀──[ACCEPT в ящик ответа A, внутри Signal Whisper]───────────── B
 *      (либо DECLINE — чтобы A не повторял запрос бесконечно)
 * ```
 *
 * ПОЧЕМУ ВНУТРИ SIGNAL. Инициатор берёт prekey-бандл собеседника из записи
 * каталога, поэтому уже первое сообщение — полноценный PQXDH PreKeyMessage:
 * слой B аутентифицирован сразу, новых транспортных примитивов не появляется, а
 * сервер видит ровно тот же непрозрачный конверт, что и у обычной переписки.
 *
 * ПОЧЕМУ ВСЁ РАВНО ПОДПИСАНО Ed25519. Секрет слоя A выводится из этих же полей
 * ([epkPublicB64], [contribB64], [nonceB64], ключей KEM) и должен остаться
 * независимым от libsignal: подпись ключом аккаунта означает, что подмена поля
 * требует взлома ещё и Ed25519, даже если слой B и релей скомпрометированы
 * одновременно. Подпись считается над каноничными байтами нагрузки с пустым
 * полем подписи (см. `canonicalBytes`).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContactInvite(
    @EncodeDefault
    val t: String = TYPE,
    @EncodeDefault
    val v: Int = VERSION,
    /** `aegis:<hex>` инициатора — коммитмент к [fromEd25519Hex]. */
    val fromAccountId: String,
    val fromEd25519Hex: String,
    val fromX25519Hex: String,
    val fromFingerprint: String = "",
    /** Ник инициатора для показа в уведомлении. Ничего не аутентифицирует. */
    val fromNickname: String = "",
    /** device id инициатора (у каждого устройства свой ratchet, MD-1). */
    val fromDeviceId: Int = 1,
    /**
     * ЭФЕМЕРНЫЙ тег ответа, сгенерированный на ЭТОТ раунд (base64).
     *
     * Из него получатель выводит адрес ящика, куда положить `ACCEPT`. Отдельный
     * от долгоживущего тега инициатора специально: приняв заявку, собеседник не
     * получает права рассылать инициатору новые приглашения, а сам тег ответа
     * умирает вместе с раундом.
     */
    val replyTagB64: String,
    /** Публичный эфемерный X25519 инициатора — вклад в forward secrecy слоя A. */
    val epkPublicB64: String,
    /** Публичный ключ Kyber1024 инициатора — ответчик в него инкапсулирует. */
    val kemPublicB64: String,
    /** Случайный вклад инициатора в секрет слоя A. */
    val contribB64: String,
    /** Nonce раунда — вместе с [ContactAccept.nonceB64] защищает от повторов. */
    val nonceB64: String,
    /**
     * Публичный Signal identity-ключ устройства инициатора (base64).
     *
     * Получатель узнаёт ключи слоя B инициатора СРАЗУ: без них он имел бы
     * сессию (её завёл входящий `PreKeyMessage`), но не имел бы бандла, и
     * `keyx`-каналу пришлось бы добирать их лишним раундом.
     */
    val fromSignalIdentityB64: String = "",
    /** Публичный prekey-бандл устройства инициатора (JSON, §0). */
    val fromBundleJson: String = "",
    /** Короткое сообщение получателю («это Алиса с работы»), ≤ [MAX_GREETING]. */
    val greeting: String = "",
    val atMs: Long = 0L,
    /** Ed25519-подпись над каноничными байтами (base64). Вне подписываемых байт. */
    val sigB64: String = "",
) {
    /** Каноничные байты для подписи/проверки: та же нагрузка с пустой [sigB64]. */
    fun canonicalBytes(): ByteArray =
        CanonicalJson.encodeToString(serializer(), copy(sigB64 = "")).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE: String = "contact-invite"
        const val VERSION: Int = 1

        /** Потолок длины приветствия: это поле показывается ДО принятия заявки. */
        const val MAX_GREETING: Int = 200
    }
}

/** Ответ «принимаю» — завершает рукопожатие и активирует контакт у обеих сторон. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContactAccept(
    @EncodeDefault
    val t: String = TYPE,
    @EncodeDefault
    val v: Int = VERSION,
    val fromAccountId: String,
    val fromEd25519Hex: String,
    val fromX25519Hex: String,
    val fromFingerprint: String = "",
    val fromNickname: String = "",
    val fromDeviceId: Int = 1,
    val epkPublicB64: String,
    /** Шифртекст инкапсуляции Kyber1024 в ключ инициатора. */
    val kemCiphertextB64: String,
    val contribB64: String,
    val nonceB64: String,
    val atMs: Long = 0L,
    val sigB64: String = "",
) {
    /** Каноничные байты для подписи/проверки: та же нагрузка с пустой [sigB64]. */
    fun canonicalBytes(): ByteArray =
        CanonicalJson.encodeToString(serializer(), copy(sigB64 = "")).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE: String = "contact-accept"
        const val VERSION: Int = 1
    }
}

/**
 * Ответ «отклоняю».
 *
 * Отправляется, чтобы инициатор не повторял заявку бесконечно и видел честный
 * итог. Причину НЕ раскрывает (нет поля «почему»): это метаданные, которые
 * получателю ни к чему раскрывать. Отправку можно выключить в настройках
 * приватности — тогда заявка у инициатора просто истечёт по сроку.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ContactDecline(
    @EncodeDefault
    val t: String = TYPE,
    @EncodeDefault
    val v: Int = VERSION,
    val fromAccountId: String,
    val fromEd25519Hex: String,
    val atMs: Long = 0L,
    val sigB64: String = "",
) {
    /** Каноничные байты для подписи/проверки: та же нагрузка с пустой [sigB64]. */
    fun canonicalBytes(): ByteArray =
        CanonicalJson.encodeToString(serializer(), copy(sigB64 = "")).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE: String = "contact-decline"
        const val VERSION: Int = 1
    }
}

/** Состояние заявки в локальном хранилище (`contact_requests`). */
@Serializable
enum class ContactRequestState {
    /** Исходящая: `INVITE` отправлен, ждём ответа. */
    OUTGOING_PENDING,

    /** Исходящая: собеседник отклонил. */
    OUTGOING_DECLINED,

    /** Входящая: показана пользователю, ждёт решения. */
    INCOMING_PENDING,

    /** Входящая: пользователь отклонил (заявку больше не показываем). */
    INCOMING_DECLINED,

    /** Рукопожатие завершено, контакт активирован — запись можно удалять. */
    COMPLETED,
    ;

    val isPending: Boolean get() = this == OUTGOING_PENDING || this == INCOMING_PENDING

    companion object {
        fun fromStorage(raw: String?): ContactRequestState =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: OUTGOING_PENDING
    }
}

/** Направление заявки — чтобы UI не выводил его из состояния. */
@Serializable
enum class ContactRequestDirection { OUTGOING, INCOMING }

/**
 * Заявка на добавление в контакты, как её видит UI.
 *
 * Секретов здесь НЕТ: эфемерные секреты раунда (свой X25519, ключ Kyber, тег
 * ответа) живут отдельно в SQLCipher и в модель UI не попадают.
 */
data class ContactRequestView(
    val id: String,
    val direction: ContactRequestDirection,
    val state: ContactRequestState,
    /** `aegis:<hex>` собеседника — главное, что нужно сверить пользователю. */
    val peerAccountId: String,
    val peerNickname: String,
    /** Отпечаток аккаунта собеседника (safety number) для сверки. */
    val peerFingerprint: String,
    /** Приветствие из входящей заявки (у исходящих — своё отправленное). */
    val greeting: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)
