package com.aegis.core.common.privacy

import kotlinx.serialization.Serializable

/**
 * Настройки приватности (§14.2). В отличие от [com.aegis.core.common.security.SecuritySettings]
 * (управляется пресетами §8), это независимые пользовательские переключатели,
 * не входящие в сравнение пресетов, поэтому хранятся отдельно (см. SecureStore).
 *
 * Все три флага управляют тем, что МЫ РАССКАЗЫВАЕМ собеседнику через control-
 * канал (§10.9, минимизация метаданных). Их отключение не влияет на E2EE и не
 * зависит от честности сервера — сервер этих метаданных всё равно не видит, но
 * пользователь вправе не раскрывать их даже собеседнику. Дефолт — включено (как
 * в массовых мессенджерах, взаимность галочек/«печатает»); при желании
 * выключается для приватности.
 */
@Serializable
data class PrivacySettings(
    /** Отправлять отчёты о прочтении (галочки). Взаимность: выкл ⇒ не видим и чужие. */
    val sendReadReceipts: Boolean = true,
    /** Отправлять индикатор «печатает…». */
    val sendTypingIndicators: Boolean = true,
    /** Раскрывать онлайн-статус / «был(а) в сети» собеседнику при открытом чате. */
    val shareOnlineStatus: Boolean = true,
) {
    companion object {
        val DEFAULT: PrivacySettings = PrivacySettings()
    }
}
