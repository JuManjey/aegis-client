package com.aegis.core.common.diag

import kotlinx.serialization.Serializable

/**
 * Настройки диагностики (отладка обмена ключами и доставки).
 *
 * Выключено по умолчанию и сохраняется между запусками: пользователь включает
 * запись ПЕРЕД проблемным действием (обмен ключами, отправка, звонок, вход),
 * выполняет его, затем выгружает файл. Журнал секретов не содержит — см.
 * [DiagnosticLog]; в нём шаги аккаунта, Tor, релея, сообщений, звонков и
 * синхронизации устройств.
 */
@Serializable
data class DiagnosticSettings(
    /** Писать ли журнал ([DiagnosticLog]). */
    val logEnabled: Boolean = false,
) {
    companion object {
        val DEFAULT: DiagnosticSettings = DiagnosticSettings()
    }
}
