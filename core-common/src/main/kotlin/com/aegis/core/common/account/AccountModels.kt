package com.aegis.core.common.account

import kotlinx.serialization.Serializable

/**
 * Параметры Argon2id для вывода ключа-обёртки из пароля входа (§6.1, §7.1).
 *
 * Значения по умолчанию — разумный компромисс между стойкостью и UX разблокировки
 * на мобильном устройстве (ориентир OWASP для Argon2id). Соль уникальна на аккаунт.
 *
 * Замечание: это KDF ПАРОЛЯ ВХОДА (защита хранения мнемоники). Параметры ключа
 * каскадного контейнера (слой A) задаются отдельно в `CascadeSpec` (P3).
 */
@Serializable
data class AccountKdfParams(
    val memoryKib: Int = 65_536, // 64 МиБ
    val iterations: Int = 3,
    val parallelism: Int = 1,
    val keyLengthBytes: Int = 32,
    /** Соль Argon2id, base64. Генерируется при создании аккаунта. */
    val saltB64: String,
)

/**
 * Способ создания аккаунта (§7.1):
 *  - [MANUAL] — пользователь задал логин и пароль.
 *  - [AUTOMATIC] — приложение сгенерировало аккаунт само (как в Session),
 *    без ввода данных; защита хранения — аппаратный Keystore.
 */
@Serializable
enum class AccountCreationMode { MANUAL, AUTOMATIC }

/**
 * Профиль аккаунта — публичные метаданные, которые можно показывать в UI и
 * персистить (без секретов). Мнемоника хранится отдельно в запечатанном виде.
 */
@Serializable
data class AccountProfile(
    val identity: AccountIdentity,
    /**
     * Публичный никнейм/логин (§14.1). Может быть пустым при авто-создании;
     * задаётся позже из настроек/профиля ([AccountLogin]).
     */
    val login: String = "",
    /** О себе / статус (§14.1). */
    val about: String = "",
    val creationMode: AccountCreationMode = AccountCreationMode.MANUAL,
    /** Есть ли пароль входа (false для авто-аккаунта без пароля). */
    val hasPassword: Boolean = true,
    /**
     * Разрешён ли аккаунт на нескольких устройствах одновременно (§7.1.1).
     * По умолчанию запрещено — безопаснее.
     */
    val multiDeviceAllowed: Boolean = false,
    val createdAtEpochMs: Long = 0L,
) {
    val accountId: String get() = identity.accountId
}

/**
 * Состояние аутентификации для навигации (auth-gate).
 *
 *  - [None] — аккаунта на устройстве нет → онбординг.
 *  - [Locked] — аккаунт есть, но не разблокирован (нужен пароль/биометрия).
 *  - [Unlocked] — аккаунт разблокирован, доступны главные экраны.
 */
sealed interface AccountState {
    data object None : AccountState
    data class Locked(val profile: AccountProfile) : AccountState
    data class Unlocked(val profile: AccountProfile) : AccountState

    /** Имя для журнала диагностики: имя класса в release переименовывает R8. */
    val label: String
        get() = when (this) {
            None -> "None"
            is Locked -> "Locked"
            is Unlocked -> "Unlocked"
        }
}
