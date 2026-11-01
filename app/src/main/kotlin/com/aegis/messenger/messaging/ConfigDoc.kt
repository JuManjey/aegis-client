package com.aegis.messenger.messaging

import com.aegis.core.common.security.HttpsRelayAddress
import com.aegis.core.common.security.OnionRelayAddress
import com.aegis.core.common.security.TorNetworkSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Подписанный конфиг-документ релея (§12.2) после проверки подписи. Разбор и
 * применение вынесены из [ConfigSync] в чистые функции ради тестов.
 *
 * Поля `relaysHttps`/`httpsPins` аддитивные: старый клиент их не читает, старый
 * документ их не содержит — тогда HTTPS-адрес и пины клиента не трогаются.
 */
data class ConfigDoc(
    val version: Long,
    val minAppVersion: Long,
    /** Первый правдоподобный onion из `relays` или `null`. */
    val onion: String?,
    /** Первый правдоподобный https-origin из `relaysHttps` или `null`. */
    val https: String?,
    /** `null` — поля `httpsPins` в документе нет; список — пины, которые он объявил. */
    val httpsPins: List<String>?,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Разбор JSON документа; `null` — не JSON-объект. */
        fun parse(text: String): ConfigDoc? {
            val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            return ConfigDoc(
                version = obj.long("v"),
                minAppVersion = obj.long("minAppVersion"),
                onion = obj.strings("relays")?.firstNotNullOfOrNull { OnionRelayAddress.parse(it)?.stored },
                https = obj.strings("relaysHttps")?.firstNotNullOfOrNull { HttpsRelayAddress.parse(it)?.stored },
                httpsPins = obj.strings("httpsPins")?.let { HttpsRelayAddress.normalizePins(it) },
            )
        }

        private fun JsonObject.long(key: String): Long =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L

        private fun JsonObject.strings(key: String): List<String>? =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
    }

    /**
     * Что получится из [current] после применения документа, или `null`, если
     * применять нечего: версия не новее принятой (защита от отката) либо клиент
     * старше `minAppVersion`. Ручные адреса не трогаются — меняется только версия.
     * HTTPS-адрес и пины берутся из документа только вместе: пины без адреса, к
     * которому они относятся, ничего не значат.
     */
    fun applyTo(current: TorNetworkSettings, appVersionCode: Long): TorNetworkSettings? {
        if (version <= current.configVersion) return null
        if (minAppVersion > appVersionCode) return null
        var next = current.copy(configVersion = version)
        if (!current.relayManual) {
            if (onion != null) next = next.copy(onionRelayAddress = onion)
            if (https != null) next = next.copy(httpsRelayAddress = https, httpsPins = httpsPins.orEmpty())
        }
        return next
    }
}
