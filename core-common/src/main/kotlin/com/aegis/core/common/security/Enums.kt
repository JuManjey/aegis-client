package com.aegis.core.common.security

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Транспорт до релея (§7.2, §8: `net.tor`) — переключатель «Tor» в разделе
 * «Сеть и Tor». Значений ровно два.
 *
 * Шифрование от выбора НЕ зависит: PSK-обёртка, каскад слоя A, Signal (PQXDH +
 * SPQR), закрепление identity, шифрование вложений, DTLS-SRTP с кадровым
 * AES-GCM и SAS в звонках работают одинаково. Режим меняет только то, какие
 * метаданные видны сети, серверу и собеседнику.
 *
 * Сериализованные имена `ALWAYS`/`OFF` прежние. Устаревшее `MESSAGES_ONLY` и
 * любое неизвестное значение читаются как [ALWAYS] ([TorModeSerializer]): иначе
 * упал бы разбор всего объекта настроек, а `FileSecureStore` при ошибке разбора
 * молча возвращает настройки по умолчанию.
 */
@Serializable(with = TorModeSerializer::class)
enum class TorMode {
    /**
     * Tor ВКЛ (по умолчанию). Сообщения, вложения, заявки и каталог, обмен
     * ключами, self-sync, конфиг, сигналинг и медиа звонков идут через встроенный
     * Tor к onion-релею; медиа — только relay-кандидат TCP-TURN через onion. Ни
     * одного сокета и DNS-запроса мимо Tor. Tor не поднят — ничего не уходит.
     */
    ALWAYS,

    /**
     * Tor ВЫКЛ. Arti не запускается; клиент ходит к ТОМУ ЖЕ релею по HTTPS
     * (системная проверка цепочки и hostname, при наличии — пины SPKI), звонки —
     * WebRTC с ICE `ALL`: STUN своего сервера и TURN своего сервера как резерв.
     *
     * Цена: провайдер видит соединение с сервером AEGIS; сервер видит IP и может
     * сопоставлять его с адресами ящиков, то есть косвенно понять, кто с кем
     * переписывается; собеседник в звонке видит IP. Включается только явным
     * действием пользователя с предупреждением
     * ([SecuritySettings.torOffConfirmed]).
     */
    OFF,
}

/**
 * Разбор [TorMode] с миграцией устаревших значений: `OFF` — только явное `OFF`,
 * всё остальное (включая `MESSAGES_ONLY`) — безопасный [TorMode.ALWAYS].
 */
object TorModeSerializer : KSerializer<TorMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.aegis.core.common.security.TorMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TorMode) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): TorMode = fromStored(decoder.decodeString())

    fun fromStored(raw: String): TorMode =
        if (raw.trim() == TorMode.OFF.name) TorMode.OFF else TorMode.ALWAYS
}

/**
 * Уровень агрессивности минимизации метаданных (§8: `meta.minimize`).
 * Метаданные (кто/с кем/когда) — профильное конкурентное поле AEGIS.
 */
@Serializable
enum class MetadataMinimization {
    MAXIMUM,
    MODERATE,
    BASIC,
}

/**
 * Пользовательская настройка смягчения тайминг-корреляции fan-out
 * (`docs/multidevice.md` §12, §1.40). Раньше уровень джиттера жёстко следовал
 * `meta.minimize`; теперь его можно задать явно, независимо от пресета (§14.7, №3b).
 *
 *  - [AUTO] — следовать `meta.minimize` активного профиля (прежнее поведение).
 *  - [OFF] — джиттер выключен (порядок дропов не меняется, задержки нет).
 *  - [MODERATE] — умеренный джиттер (как `meta.minimize=MODERATE`).
 *  - [MAXIMUM] — максимальный джиттер (как `meta.minimize=MAXIMUM`).
 */
@Serializable
enum class FanoutJitterMode {
    AUTO,
    OFF,
    MODERATE,
    MAXIMUM,
}

/**
 * Пользовательская настройка прикрытия факта/объёма отправки cover-traffic'ом
 * (`docs/multidevice.md` §12, §1.41, вариант №3a). Раньше cover-traffic был жёстко
 * привязан к `meta.minimize=MAXIMUM`; теперь его можно включить/выключить явно,
 * независимо от пресета (§14.7, №3b).
 *
 *  - [AUTO] — следовать пресету: включён только при `meta.minimize=MAXIMUM`
 *    (прежнее поведение).
 *  - [ON] — включён всегда (фоновые decoy-дропы + паддинг fan-out).
 *  - [OFF] — выключен всегда.
 */
@Serializable
enum class CoverTrafficMode {
    AUTO,
    ON,
    OFF,
}

/**
 * Удалённое добавление контактов (RCH v1, `docs/contact_requests.md`).
 *
 * ОТДЕЛЬНАЯ ОСЬ, а не часть `keys.overSignal`. Разница принципиальная:
 * `keys.overSignal` разрешает ПЕРЕДАТЬ по сети готовый секрет слоя A, а здесь
 * секрет никуда не передаётся — он ВЫВОДИТСЯ из подписанного рукопожатия на
 * обеих сторонах независимо. Поэтому [ADDRESS] совместим даже с пресетом
 * «Параноик»: доверие сведено к подлинности полученного вне сети адреса — то же
 * допущение, что и для отсканированного QR-кода.
 */
@Serializable
enum class ContactRequestMode {
    /** Удалённое добавление выключено: только личный обмен QR/NFC. */
    OFF,

    /**
     * Добавление по АДРЕСУ (`aegis:1:<accountId>:<tag>`). Приватный каталог
     * неперечислим, ников на сервере нет, граф связей не раскрывается.
     */
    ADDRESS,

    /**
     * Дополнительно — поиск по ПУБЛИЧНОМУ `@нику`.
     *
     * ЧЕСТНАЯ ЦЕНА: публичное пространство имён перечислимо словарём (индекс —
     * это HKDF от низкоэнтропийного ника). Утекает «такой ник существует и
     * указывает на такой accountId»; ключи, prekey-бандлы и то, кто кого искал,
     * не утекают, а подмена ключей невозможна (подпись + привязка accountId).
     * Требует, чтобы оператор релея включил `AEGIS_ALIAS_ENABLED=1`.
     */
    ADDRESS_AND_ALIAS,
    ;

    /** Разрешено ли удалённое добавление хоть в каком-то виде. */
    val enabled: Boolean get() = this != OFF

    /** Разрешён ли поиск/публикация публичного `@ника`. */
    val aliasAllowed: Boolean get() = this == ADDRESS_AND_ALIAS
}

/**
 * Политика поведения на рутованных устройствах и эмуляторах (§8: `ui.blockRooted`).
 */
@Serializable
enum class RootedDevicePolicy {
    /** Блокировать запуск приложения. */
    BLOCK,

    /** Показывать предупреждение, но разрешать работу. */
    WARN,

    /** Не реагировать. */
    OFF,
}
