package com.aegis.core.common.model

import com.aegis.core.common.contact.ContactTrust

/**
 * Контакт (§14.1): публичный идентификатор без привязки к телефону/почте.
 * Аккаунт по своей сути — криптографическая пара ключей (§7.1), поэтому
 * контакт идентифицируется никнеймом и отпечатком публичного ключа.
 */
data class Contact(
    val id: String,
    val nickname: String,
    /**
     * Локальное имя чата на ЭТОМ устройстве. Пусто — показывать [nickname].
     * Не уходит собеседнику, не меняет криптографическую идентичность и не
     * перезаписывается при повторном импорте PSK (`ensureContact`).
     */
    val localName: String = "",
    val about: String = "",
    /** Отпечаток identity-ключа (safety number) для ручной верификации, §6.2. */
    val fingerprint: String = "",
    val verified: Boolean = false,
    /**
     * КАК контакт попал на устройство: личный обмен QR/NFC
     * ([ContactTrust.IN_PERSON]) или удалённое добавление по адресу
     * ([ContactTrust.REMOTE_ADDRESS]). Ортогонально [verified] — это два разных
     * факта: способ добавления и ручная сверка отпечатка.
     *
     * Шифрование от способа не зависит (слои A и B одинаковы для всех), но UI
     * обязан показывать разницу: у удалённого контакта идентичность держится на
     * подлинности полученного адреса, а не на личной встрече.
     */
    val trust: ContactTrust = ContactTrust.UNKNOWN,
    /**
     * В чёрном списке (§14.2): входящие от контакта отбрасываются, исходящие не
     * отправляются. Блокировка локальна и незаметна для собеседника (§10.9).
     */
    val blocked: Boolean = false,
    /**
     * Персональное отключение уведомлений (§14.5): `0` — не заглушён,
     * [MUTED_FOREVER] — навсегда, иначе Unix-время (мс), до которого уведомления
     * от этого контакта заглушены. Дополняет глобальную приостановку.
     */
    val mutedUntilMs: Long = 0L,
    /**
     * Персональный звук уведомления о сообщении (§14.5): URI рингтона; пусто —
     * использовать глобальный звук/канал по умолчанию.
     */
    val notificationSoundUri: String = "",
    /**
     * Персональная мелодия входящего звонка (§14.5): URI рингтона; пусто —
     * использовать глобальную мелодию звонка по умолчанию.
     */
    val callRingtoneUri: String = "",
) {
    /** Заглушены ли уведомления от этого контакта прямо сейчас (§14.5). */
    fun isMutedNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        mutedUntilMs == MUTED_FOREVER || mutedUntilMs > nowMs

    /**
     * Имя для UI (список чатов, шапка, уведомления, звонок): локальный алиас,
     * иначе публичный никнейм обмена, иначе id.
     */
    fun displayLabel(): String = localName.ifBlank { nickname }.ifBlank { id }

    companion object {
        /** Метка «заглушить навсегда» для [mutedUntilMs]. */
        const val MUTED_FOREVER: Long = Long.MAX_VALUE

        /** Верхняя граница локального имени чата (символы Unicode). */
        const val LOCAL_NAME_MAX_CHARS: Int = 64

        /**
         * Нормализует ввод пользователя перед записью в SQLCipher:
         * управляющие/zero-width символы выкидываются, пробелы схлопываются,
         * длина режется. Пустая строка = сброс к никнейму.
         */
        fun sanitizeLocalName(raw: String): String {
            if (raw.isEmpty()) return ""
            val builder = StringBuilder(raw.length.coerceAtMost(LOCAL_NAME_MAX_CHARS))
            var prevSpace = false
            for (ch in raw) {
                val isBreak = ch == '\n' || ch == '\r' || ch == '\t' || ch == '\u00A0' || ch == '\u202F'
                when {
                    isBreak || ch == ' ' -> {
                        if (prevSpace || builder.isEmpty()) continue
                        prevSpace = true
                        builder.append(' ')
                    }
                    ch.isISOControl() || ch.category == CharCategory.FORMAT -> continue
                    else -> {
                        prevSpace = false
                        builder.append(ch)
                    }
                }
                if (builder.length >= LOCAL_NAME_MAX_CHARS) break
            }
            return builder.toString().trimEnd()
        }
    }
}
