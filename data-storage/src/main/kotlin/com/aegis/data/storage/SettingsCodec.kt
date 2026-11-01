package com.aegis.data.storage

import com.aegis.core.common.security.SecuritySettings
import kotlinx.serialization.json.Json

/**
 * JSON настроек в запечатанных файлах [FileSecureStore].
 *
 * Вынесен отдельно ради тестов миграции: при ЛЮБОЙ ошибке разбора хранилище
 * откатывается к значениям по умолчанию, поэтому несовместимое изменение
 * модели молча сбросило бы пользователю все настройки безопасности.
 */
object SettingsCodec {

    /** Неизвестные ключи (например, удалённый `callsMediaOverTor`) пропускаются. */
    val json: Json = Json { ignoreUnknownKeys = true }

    /** Разбор с приведением к согласованному виду ([SecuritySettings.normalized]). */
    fun decodeSecurity(text: String, json: Json = this.json): SecuritySettings =
        json.decodeFromString(SecuritySettings.serializer(), text).normalized()

    fun encodeSecurity(settings: SecuritySettings, json: Json = this.json): String =
        json.encodeToString(SecuritySettings.serializer(), settings)
}
