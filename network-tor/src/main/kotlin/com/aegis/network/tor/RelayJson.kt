package com.aegis.network.tor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Разбор ответов релея на kotlinx.serialization. Раньше здесь был `org.json`,
 * который в JVM-тестах модуля — заглушка; поведение «отсутствует или не того
 * типа → значение по умолчанию» сохранено.
 */
internal object RelayJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** Объект JSON или `null`, если тело не JSON-объект. */
    fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

internal fun JsonObject.optString(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: default

internal fun JsonObject.optLong(key: String, default: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: default

internal fun JsonObject.optInt(key: String, default: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: default

internal fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

/** Строковые элементы массива; нестроковые пропускаются. */
internal fun JsonArray.strings(): List<String> =
    mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
