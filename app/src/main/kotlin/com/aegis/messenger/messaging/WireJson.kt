package com.aegis.messenger.messaging

import kotlinx.serialization.json.Json

/**
 * JSON внешних конвертов mailbox (control / call / keyx / self-sync / message).
 *
 * [encodeDefaults] = true обязателен: поле `channel` имеет Kotlin-дефолт, и при
 * `encodeDefaults = false` (дефолт kotlinx.serialization) маркер канала не
 * попадает в JSON. Приёмник тогда считает конверт обычным сообщением, и
 * «печатает…»/presence/SDP оказываются в ленте чата.
 */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
