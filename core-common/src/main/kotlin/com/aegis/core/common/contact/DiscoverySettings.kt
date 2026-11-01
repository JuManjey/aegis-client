package com.aegis.core.common.contact

import kotlinx.serialization.Serializable

/**
 * Локальное состояние удалённого добавления контактов (RCH v1,
 * `docs/contact_requests.md`).
 *
 * ЗДЕСЬ ЛЕЖАТ СЕКРЕТЫ. [tagB64] и [publicTagB64] — 256-битные теги обнаружения:
 * знание тега даёт право прислать владельцу заявку и открыть его запись в
 * каталоге. Поэтому файл хранится обёрнутым аппаратным ключом Keystore (как и
 * остальные настройки) и уничтожается при crypto-shredding (§7.4).
 *
 * ДВА ТЕГА, А НЕ ОДИН — намеренно:
 *  * [tagB64] уходит в АДРЕС, который владелец выдаёт конкретным людям;
 *  * [publicTagB64] раскрывается в заявке на публичный `@ник`, то есть виден
 *    всем, кто угадал ник.
 *
 * Разделение позволяет отключить «пишут по нику» ротацией [publicTagB64], не
 * обесценив адреса, выданные доверенным людям, — и наоборот.
 */
@Serializable
data class DiscoverySettings(
    /** Приватный тег обнаружения (base64, 32 байта). Пусто — ещё не создан. */
    val tagB64: String = "",
    /** Публичный тег для `@ника` (base64, 32 байта). Пусто — алиас не занимался. */
    val publicTagB64: String = "",
    /** Занятый публичный `@ник` (нормализованный) или пусто. */
    val alias: String = "",
    /** Когда заявка на алиас была продлена (мс), 0 — никогда. */
    val aliasRenewedAtMs: Long = 0L,
    /**
     * Последний принятый `issuedAtMs` записи каталога по accountId собеседника.
     * Защита от откатов: сервер не должен подсунуть старую запись с уже
     * отозванными ключами.
     */
    val knownRecordIssuedAtMs: Map<String, Long> = emptyMap(),
) {
    /** Создан ли приватный тег — без него удалённое добавление невозможно. */
    val hasTag: Boolean get() = tagB64.isNotBlank()

    companion object {
        val DEFAULT: DiscoverySettings = DiscoverySettings()

        /**
         * Потолок числа запомненных `issuedAtMs`. Карта нужна только для защиты
         * от откатов у контактов, которых мы искали, — расти без предела ей
         * незачем.
         */
        const val MAX_KNOWN_RECORDS: Int = 500
    }

    /** Запоминает `issuedAtMs` записи, вытесняя самые старые сверх потолка. */
    fun rememberRecord(accountId: String, issuedAtMs: Long): DiscoverySettings {
        if (accountId.isBlank() || issuedAtMs <= 0) return this
        val current = knownRecordIssuedAtMs[accountId] ?: 0L
        if (issuedAtMs <= current) return this
        val merged = knownRecordIssuedAtMs + (accountId to issuedAtMs)
        val trimmed = if (merged.size > MAX_KNOWN_RECORDS) {
            merged.entries.sortedByDescending { it.value }
                .take(MAX_KNOWN_RECORDS)
                .associate { it.key to it.value }
        } else {
            merged
        }
        return copy(knownRecordIssuedAtMs = trimmed)
    }
}
