package com.aegis.core.common.security

/**
 * Pluggable transports (§14.7): встроенные мосты и маппинг «протокол → бинарь».
 *
 * Мы НЕ пишем свои PT (§1) — приложение поставляет официальные аудированные
 * бинари Tor Project, кросс-скомпилированные под ABI (`pt/build_pt.sh`):
 *   - `liblyrebird.so`  — преемник obfs4proxy: obfs4 + meek_lite (+ obfs2/obfs3/
 *                         scramblesuit). Один бинарь на несколько протоколов.
 *   - `libsnowflake.so` — snowflake-client (domain-fronted WebRTC rendezvous).
 *
 * Arti (feature `pt-client`) запускает их как УПРАВЛЯЕМЫЕ (managed) PT: клиенту
 * не нужен системный Tor/Orbot. Путь к бинарю резолвится в рантайме из
 * `nativeLibraryDir` (см. `PtBinaries` в модуле `:network-tor`).
 */

/** Выбор встроенного набора мостов на экране «Сеть и Tor». */
enum class BuiltInBridge {
    /** Свои bridge lines (поле ввода) или без мостов — прямой Tor. */
    NONE,

    /** Встроенные obfs4-мосты (маскировка под случайный трафик). */
    OBFS4,

    /** Встроенный Snowflake (маскировка под WebRTC-видеозвонок). */
    SNOWFLAKE,
}

/**
 * Встроенные строки мостов — зеркало актуальных built-in мостов Tor Browser
 * (`tpo/applications/tor-browser-build`, `projects/common/bridges_list.*`).
 *
 * ВНИМАНИЕ: адреса/сертификаты мостов ПЕРИОДИЧЕСКИ РОТИРУЮТСЯ. Это дефолт «из
 * коробки» для удобного старта в цензурируемой сети; если он перестал работать —
 * пользователь берёт свежие строки на bridges.torproject.org и вставляет их как
 * «свои мосты». По сети мы эти строки не публикуем (они и так публичны).
 */
object BuiltInBridges {

    /** Актуальные встроенные obfs4-мосты Tor Browser (обновлять при ротации). */
    val OBFS4_LINES: List<String> = listOf(
        "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 " +
            "cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg iat-mode=1",
        "obfs4 212.83.43.74:443 39562501228A4D5E27FCA4C0C81A01EE23AE3EE4 " +
            "cert=PBwr+S8JTVZo6MPdHnkTwXJPILWADLqfMGoVvhZClMq/Urndyd42BwX9YFJHZnBB3H0XCw iat-mode=1",
        "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
            "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0",
    )

    /** Актуальная встроенная строка Snowflake Tor Browser (полная, с uTLS). */
    val SNOWFLAKE_LINES: List<String> = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org front=www.phpmyadmin.net,cdn.zk.mk " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
    )

    /** Строки мостов для выбранного встроенного набора. */
    fun linesFor(bridge: BuiltInBridge): List<String> = when (bridge) {
        BuiltInBridge.OBFS4 -> OBFS4_LINES
        BuiltInBridge.SNOWFLAKE -> SNOWFLAKE_LINES
        BuiltInBridge.NONE -> emptyList()
    }
}

/**
 * Маппинг имени PT-протокола → базовое имя вложенного бинаря (без префикса `lib`
 * и суффикса `.so`). Реальный путь резолвится в рантайме (`PtBinaries`).
 *
 * `null` — протокол нам неизвестен (строка мостов, вероятно, «ванильная» или PT,
 * для которого мы не поставляем бинарь).
 */
object PtBinaryNames {
    /** Протоколы, которые реализует lyrebird (один бинарь). */
    val LYREBIRD_PROTOCOLS: Set<String> =
        setOf("obfs4", "meek_lite", "obfs2", "obfs3", "scramblesuit")

    /** Протоколы, которые реализует snowflake-client. */
    val SNOWFLAKE_PROTOCOLS: Set<String> = setOf("snowflake")

    fun binaryFor(protocol: String): String? = when (protocol.lowercase()) {
        in LYREBIRD_PROTOCOLS -> "lyrebird"
        in SNOWFLAKE_PROTOCOLS -> "snowflake"
        else -> null
    }

    /** Извлекает имя транспорта из bridge line (первый токен). */
    fun protocolOf(bridgeLine: String): String? =
        bridgeLine.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }?.lowercase()
}
