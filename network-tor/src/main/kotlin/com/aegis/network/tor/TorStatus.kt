package com.aegis.network.tor

/**
 * Состояние встроенного Tor-транспорта (Arti, §7.2).
 *
 * Используется для индикатора статуса в шапке главного экрана (§13:
 * «индикаторы безопасности (статус Tor)»).
 */
sealed interface TorStatus {

    /** Tor выключен пользователем (режим `net.tor = OFF`). */
    data object Disabled : TorStatus

    /** Транспорт запускается. */
    data object Starting : TorStatus

    /** Идёт построение цепочки/bootstrap. [progress] в диапазоне 0..100. */
    data class Bootstrapping(val progress: Int) : TorStatus

    /** Tor активен, трафик маршрутизируется. */
    data object Connected : TorStatus

    /**
     * Нативного Arti нет в этой сборке. Это НЕ «подключено»: SOCKS нет,
     * трафик в Tor не идёт. Показывается отдельно от [Disabled], чтобы
     * демонстрации и отладочные APK не путали заглушку с живым Tor.
     */
    data object Unavailable : TorStatus

    /** Ошибка (например, заблокирован — нужны мосты obfs4/snowflake). */
    data class Error(val reason: String) : TorStatus
}
