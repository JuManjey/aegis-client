package com.aegis.core.common.account

/**
 * Правила публичного логина/никнейма (§7.1, §14.1).
 *
 * Логин — это отображаемое имя, НЕ криптографическая идентичность и НЕ
 * учётная запись на сервере (zero-trust: сервер логины не регистрирует).
 * Телефон и e-mail запрещены по продуктовому требованию.
 *
 * Пустая строка допустима только у авто-аккаунта до того, как пользователь
 * задаст логин позже (настройки / профиль).
 */
object AccountLogin {

    const val MIN_CHARS: Int = 2
    const val MAX_CHARS: Int = 32
    const val PASSWORD_MIN_CHARS: Int = 8

    /** Подчёркивание — единственный небуквенно-цифровой символ в никнейме. */
    private const val EXTRA_ALLOWED: String = "_"

    sealed interface Error {
        data object Empty : Error
        data object TooShort : Error
        data object TooLong : Error
        data object InvalidChars : Error
        data object LooksLikeEmail : Error
        data object LooksLikePhone : Error
    }

    fun normalize(raw: String): String = raw.trim()

    /**
     * Проверяет уже [normalize]-ванный логин. `null` — пригоден к записи.
     * Пустая строка даёт [Error.Empty] (вызывающий сам решает, допустима ли она).
     */
    fun validate(normalized: String): Error? {
        if (normalized.isEmpty()) return Error.Empty
        if (normalized.length < MIN_CHARS) return Error.TooShort
        if (normalized.length > MAX_CHARS) return Error.TooLong
        if ('@' in normalized) return Error.LooksLikeEmail
        if (looksLikePhone(normalized)) return Error.LooksLikePhone
        if (normalized.any { !isAllowedChar(it) }) return Error.InvalidChars
        return null
    }

    /**
     * Для авто-аккаунта пустой логин допустим; непустой обязан пройти [validate].
     * Для ручного создания пустой логин — ошибка.
     */
    fun validateForPersist(normalized: String, allowEmpty: Boolean): Error? {
        if (normalized.isEmpty()) return if (allowEmpty) null else Error.Empty
        return validate(normalized)
    }

    private fun isAllowedChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch in EXTRA_ALLOWED

    /**
     * «Похоже на телефон»: сплошные цифры (опционально с +) длиной ≥ 8.
     * `alice123` проходит — там есть буквы.
     */
    private fun looksLikePhone(value: String): Boolean {
        val body = if (value.startsWith('+')) value.substring(1) else value
        if (body.length < 8) return false
        return body.all { it.isDigit() }
    }
}
