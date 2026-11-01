package com.aegis.core.common.account

import kotlinx.serialization.Serializable

/**
 * Публичная криптографическая идентичность аккаунта (§7.1) — без привязки к
 * телефону. Аккаунт = пара ключей; здесь только ПУБЛИЧНЫЕ части, безопасные для
 * отображения в UI, экспорта и передачи. Приватные ключи никогда не покидают
 * нативную память в открытом виде.
 *
 * Значения детерминированно выводятся из мнемоники в `aegis-core` (Rust).
 */
@Serializable
data class AccountIdentity(
    /** Стабильный публичный идентификатор аккаунта: `aegis:<hex>`. */
    val accountId: String,
    /** Публичный ключ Ed25519 (подписи), hex. */
    val ed25519PublicHex: String,
    /** Публичный ключ X25519 (ECDH / X3DH), hex. */
    val x25519PublicHex: String,
    /** Отпечаток для ручной верификации (safety number), hex. */
    val fingerprint: String,
) {
    /**
     * Отпечаток, разбитый на группы по 4 символа для удобной сверки вслух/глазами
     * (аналог safety number). Пример: `AB12 CD34 …`.
     */
    fun fingerprintGrouped(groupSize: Int = 4): String =
        fingerprint.uppercase().chunked(groupSize).joinToString(" ")

    /** Короткий вид ID для компактных мест UI (первые 12 значимых символов). */
    fun shortId(): String {
        val body = accountId.substringAfter(':', accountId)
        return "aegis:" + body.take(12)
    }
}
