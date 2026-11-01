package com.aegis.core.common.security

import kotlinx.serialization.Serializable

/**
 * Настройки сети и Tor (§14.7): мосты и pluggable transports для обхода
 * блокировок, а также адреса релея (§12.2): onion для режима «Tor ВКЛ» и HTTPS
 * для режима «Tor ВЫКЛ».
 *
 * Сам переключатель Tor живёт в [SecuritySettings.netTor] как часть профиля
 * безопасности; здесь — доп. сетевые параметры, которые не входят в пресеты и
 * настраиваются на экране «Сеть и Tor».
 *
 * Ключи контейнера/аккаунта тут НЕ хранятся: это только транспортная конфигурация.
 * Persists в защищённом [com.aegis.data.storage.SecureStore] рядом с настройками.
 */
@Serializable
data class TorNetworkSettings(
    /** Использовать ли мосты (bridges). По умолчанию выкл — прямое подключение к Tor. */
    val bridgesEnabled: Boolean = false,

    /**
     * Строки мостов (bridge lines) в формате Tor, например:
     * `obfs4 1.2.3.4:443 <FINGERPRINT> cert=... iat-mode=0`.
     * Обмениваются вне приложения (BridgeDB/канал доверия), в сеть не публикуются нами.
     */
    val bridgeLines: List<String> = emptyList(),

    /**
     * Pluggable transports (obfs4 / snowflake / meek, §14.7). Для управляемых PT
     * нужен путь к исполняемому бинарнику PT (на Android — распакованный нативный
     * файл). Пусто = мосты без PT (vanilla bridge).
     *
     * ПРИМЕЧАНИЕ: обычно этот список НЕ заполняется вручную — [ArtiTorTransport]
     * АВТОМАТИЧЕСКИ выводит нужные PT из строк мостов и подставляет путь к
     * вложенному бинарю (`liblyrebird.so`/`libsnowflake.so`) из `nativeLibraryDir`.
     * Поле оставлено для явного переопределения (кастомный путь к PT).
     */
    val transports: List<PtTransportSpec> = emptyList(),

    /**
     * Встроенный набор мостов (§14.7): удобный дефолт «из коробки». Если не
     * [BuiltInBridge.NONE] и мосты включены — используются встроенные строки
     * ([BuiltInBridges]) и авто-подстановка PT-бинаря вместо [bridgeLines].
     */
    val builtInBridge: BuiltInBridge = BuiltInBridge.NONE,

    /**
     * Эффективный адрес onion-релея v3 (§12.2), который использует транспорт для
     * доставки/приёма и диагностики. Может быть зашитым в сборку дефолтом,
     * авто-обнаруженным через `GET /v1/config` или введённым пользователем
     * вручную (см. [relayManual]). По сети как «настройка» не раскрывается.
     */
    val onionRelayAddress: String = "",

    /**
     * HTTPS-адрес ТОГО ЖЕ релея для режима «Tor ВЫКЛ» в каноническом виде
     * [HttpsRelayAddress.Parsed.stored]. Обязан вести к той же БД, что и
     * [onionRelayAddress], иначе собеседники в разных режимах не увидят
     * сообщений друг друга. Источники те же, что у onion: зашитый в сборку
     * дефолт, подписанный конфиг (`relaysHttps`), ручной ввод.
     */
    val httpsRelayAddress: String = "",

    /**
     * Пины SPKI HTTPS-релея (`sha256/<base64>`) из подписанного конфига или
     * сборки. Пусто — только системная проверка цепочки и hostname. Действуют
     * лишь для автоматического адреса ([effectiveHttpsPins]).
     */
    val httpsPins: List<String> = emptyList(),

    /**
     * Задал ли адреса релея пользователь ВРУЧНУЮ (§12.2). Флаг общий для пары
     * onion + HTTPS: адреса обязаны вести к одному релею, поэтому при ручном
     * onion автоматический HTTPS (и наоборот) не подставляется — иначе пара
     * указала бы на чужой сервер. Если true — `ConfigSync` и зашитый дефолт
     * адреса не трогают; если false — адреса управляются автоматически.
     */
    val relayManual: Boolean = false,

    /**
     * Версия последнего ПРИНЯТОГО подписанного конфиг-документа (§12.2). Защита
     * от отката: документ со СТАРОЙ или равной версией игнорируется, чтобы
     * злоумышленник не «откатил» клиента на устаревший адрес. 0 = ещё не получен.
     */
    val configVersion: Long = 0,
) {
    /**
     * Подставляет зашитые в сборку адреса (onion и HTTPS, а к HTTPS — пины),
     * если пользователь не задавал адреса вручную и соответствующее поле пусто.
     * [relayManual] не трогает — адреса остаются под авто-обновлением (сброс
     * «вернуть автоматический» снова подставит дефолт).
     */
    fun withBakedRelayIfNeeded(
        bakedOnionRaw: String,
        bakedHttpsRaw: String = "",
        bakedPins: List<String> = emptyList(),
    ): TorNetworkSettings {
        if (relayManual) return this
        var next = this
        if (onionRelayAddress.isBlank()) {
            val baked = OnionRelayAddress.normalize(bakedOnionRaw)
            if (baked.isNotBlank()) next = next.copy(onionRelayAddress = baked)
        }
        if (httpsRelayAddress.isBlank()) {
            val baked = HttpsRelayAddress.parse(bakedHttpsRaw)
            if (baked != null) {
                next = next.copy(
                    httpsRelayAddress = baked.stored,
                    httpsPins = httpsPins.ifEmpty { HttpsRelayAddress.normalizePins(bakedPins) },
                )
            }
        }
        return next
    }

    /** Пины, которые реально проверяются: для адреса, введённого вручную, — никаких. */
    fun effectiveHttpsPins(): List<String> = if (relayManual) emptyList() else httpsPins

    /** Задан ли адрес релея для [mode] (то, без чего в этом режиме сеть не работает). */
    fun relayConfiguredFor(mode: TorMode): Boolean = when (mode) {
        TorMode.ALWAYS -> OnionRelayAddress.parse(onionRelayAddress) != null
        TorMode.OFF -> HttpsRelayAddress.parse(httpsRelayAddress) != null
    }

    /**
     * Ключ конфига клиента Arti: смена этих полей требует перезапуска Tor.
     * Адрес релея / [relayManual] / [configVersion] сюда не входят — иначе
     * сохранение onion рвёт живой bootstrap и проверка релея падает с
     * `Protocol error while launching a data stream`.
     */
    fun torClientRestartToken(): String = buildString {
        append(bridgesEnabled)
        append('|')
        append(builtInBridge.name)
        append('|')
        append(bridgeLines.joinToString("\n"))
        append('|')
        transports.forEach { spec ->
            append(spec.protocols.joinToString(","))
            append('@')
            append(spec.path)
            append('#')
            append(spec.arguments.joinToString(" "))
            append(';')
        }
    }

    companion object {
        /** Значения по умолчанию: без мостов (прямой Tor), без PT. */
        val DEFAULT: TorNetworkSettings = TorNetworkSettings()
    }
}

/**
 * Описание одного pluggable transport (§14.7). `protocols` — имена протоколов,
 * которые умеет бинарник (например, `["obfs4"]` или `["snowflake"]`).
 */
@Serializable
data class PtTransportSpec(
    val protocols: List<String> = emptyList(),
    /** Путь к исполняемому файлу PT (управляемый транспорт). */
    val path: String = "",
    /** Доп. аргументы командной строки PT. */
    val arguments: List<String> = emptyList(),
)
