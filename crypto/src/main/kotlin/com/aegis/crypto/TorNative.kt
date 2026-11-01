package com.aegis.crypto

/**
 * Тонкий фасад к встроенному Tor-транспорту нативного ядра (Arti, P5,
 * §7.2/§10.5/§10.11). Всё, что связано с Tor, живёт в том же `libaegis_core.so`
 * (единый Rust-модуль §9/§10.11); здесь — только безопасная Kotlin-обёртка над
 * JNI, которая не падает, если фича `tor` не собрана в `.so`.
 *
 * Высокоуровневая логика транспорта (маппинг статуса, применение режимов сети,
 * SOCKS-порт для сетевого клиента) — в модуле `:network-tor` (`ArtiTorTransport`),
 * который использует этот фасад.
 */
object TorNative {

    /** Собран ли встроенный Tor (фича `tor`) в нативной библиотеке. */
    val isSupported: Boolean
        get() = NativeAegisCore.hasTorSupport()

    /**
     * Запускает/перезапускает Tor по JSON-конфигу
     * (`{stateDir,cacheDir,socksPort,bridges,transports}`).
     * @return `+<port>` при успехе (назначенный SOCKS-порт) или `-<текст>` при ошибке;
     *   `null`, если нативная поддержка недоступна.
     */
    fun start(configJson: String): String? =
        if (isSupported) runCatching { NativeAegisCore.nativeTorStart(configJson) }.getOrNull() else null

    /** Останавливает Tor (безопасно при отсутствии поддержки). */
    fun stop() {
        if (isSupported) runCatching { NativeAegisCore.nativeTorStop() }
    }

    /** JSON-статус транспорта `{state,progress,socksPort,error}` или `null`. */
    fun statusJson(): String? =
        if (isSupported) runCatching { NativeAegisCore.nativeTorStatusJson() }.getOrNull() else null

    /**
     * Диагностика достижимости адреса (напр. onion-релея §12.2) через Tor.
     * @return `+ok` при успехе, `-<текст>` при ошибке, `null` если поддержки нет.
     */
    fun testConnect(host: String, port: Int, timeoutMs: Int): String? =
        if (isSupported) {
            runCatching { NativeAegisCore.nativeTorTestConnect(host, port, timeoutMs) }.getOrNull()
        } else {
            null
        }
}
