package com.aegis.messenger.psk

import org.json.JSONObject

/**
 * Разбор ПУБЛИЧНОГО prekey-бандла слоя B (JSON, формат §0 — см.
 * `rust/aegis-core/src/signal.rs`, `wire::PreKeyBundlePublic`).
 *
 * Клиенту от бандла нужно ровно одно поле вне нативного ядра — `identity_key`:
 * по нему бандл сверяется с identity-ключом, закреплённым ЛИЧНО при обмене
 * (QR/NFC, §6.2). Это анти-MITM инвариант: бандл, пришедший по сети (с релея
 * или по keyx-каналу), принимается только если его identity совпадает с
 * закреплённым — иначе сервер мог бы подменить публичные ключи слоя B.
 */
object PrekeyBundle {

    /** identity-ключ (base64) из бандла или пустая строка, если поля/JSON нет. */
    fun identityB64(bundleJson: String): String =
        runCatching { JSONObject(bundleJson).optString("identity_key", "") }.getOrDefault("")

    /**
     * Сверяет identity-ключ [bundleJson] с закреплённым лично [pinnedIdentityB64].
     *
     * Пустой закреплённый ключ ⇒ сверять нечего (обмен без слоя B), принимаем.
     * Пустой ключ в бандле ⇒ старый формат без поля, принимаем (best-effort).
     * Сравнение без учёта base64-паддинга: `STANDARD` и `NO_PADDING` дают одну и
     * ту же строку с точностью до хвостовых `=`.
     */
    fun identityMatches(bundleJson: String, pinnedIdentityB64: String): Boolean {
        val pinned = normalize(pinnedIdentityB64)
        if (pinned.isEmpty()) return true
        val actual = normalize(identityB64(bundleJson))
        return actual.isEmpty() || actual == pinned
    }

    private fun normalize(b64: String): String = b64.trim().trimEnd('=')
}
