package com.aegis.core.common.psk

import kotlinx.serialization.json.Json

/**
 * Компактная кодировка полезной нагрузки обмена для QR (§6.2).
 *
 * JSON с вложенным `bundleJson` (Kyber1024, ~2.6 КБ) не влезает в QR version 40-L
 * (2953 байта): кавычки бандла экранируются, и нагрузка NFC (~2986) чуть больше
 * лимита. Строковый формат v3 кладёт бандл СЫРЫМ хвостом — без вложенного JSON, —
 * и тот же обмен помещается в один QR. Старые JSON-коды (v2, без бандла) по-
 * прежнему разбираются.
 *
 * Формат (UTF-8, `\n`-разделители, бандл — всё после 7-й строки):
 * ```
 * aegis-psk/3
 * <pskB64>
 * <fromAccountId>
 * <fromFingerprint>
 * <label>
 * <identityB64>
 * <bundleId>
 * <bundleJson…>
 * ```
 */
object PskQrPayload {
    const val HEADER = "aegis-psk/3"

    /**
     * Максимум данных QR version 40 при коррекции L (байтовый режим). Чуть меньше
     * спецификации, чтобы ZXing гарантированно выбрал 40-L, а не отказал.
     */
    const val QR_MAX_BYTES = 2900

    fun encode(payload: PskExchangePayload): String = buildString {
        append(HEADER).append('\n')
        append(oneLine(payload.pskB64)).append('\n')
        append(oneLine(payload.fromAccountId)).append('\n')
        append(oneLine(payload.fromFingerprint)).append('\n')
        append(oneLine(payload.label)).append('\n')
        append(oneLine(payload.identityB64)).append('\n')
        append(oneLine(payload.bundleId)).append('\n')
        append(payload.bundleJson)
    }

    /**
     * Код для QR: полный (с бандлом), если влезает; иначе без бандла — слой B
     * догонит по NFC / keyx / bundleId.
     */
    fun encodeForQr(payload: PskExchangePayload): String {
        val full = encode(payload)
        if (utf8Size(full) <= QR_MAX_BYTES) return full
        if (payload.bundleJson.isEmpty()) return full
        return encode(payload.copy(bundleJson = ""))
    }

    fun decode(raw: String, json: Json): PskExchangePayload? {
        val trimmed = raw.trim()
        if (trimmed.startsWith(HEADER)) return decodeV3(trimmed)
        return runCatching {
            json.decodeFromString(PskExchangePayload.serializer(), trimmed)
        }.getOrNull()
    }

    fun containsAegisMarker(raw: String): Boolean =
        raw.contains(PskExchangePayload.TYPE) || raw.startsWith(HEADER)

    private fun decodeV3(raw: String): PskExchangePayload? {
        val body = raw.removePrefix(HEADER).trimStart('\r', '\n')
        val parts = body.split("\n", limit = 7)
        if (parts.size < 6) return null
        return PskExchangePayload(
            t = PskExchangePayload.TYPE,
            v = PskExchangePayload.VERSION,
            pskB64 = parts[0].trim(),
            fromAccountId = parts.getOrElse(1) { "" }.trim(),
            fromFingerprint = parts.getOrElse(2) { "" }.trim(),
            label = parts.getOrElse(3) { "" }.trim(),
            identityB64 = parts.getOrElse(4) { "" }.trim(),
            bundleId = parts.getOrElse(5) { "" }.trim(),
            bundleJson = parts.getOrElse(6) { "" },
        )
    }

    private fun oneLine(value: String): String =
        value.replace("\r", "").replace("\n", " ")

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
