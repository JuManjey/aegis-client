package com.aegis.core.common.contact

/**
 * Адрес контакта AEGIS — самоаутентифицирующаяся строка для удалённого
 * добавления (`docs/contact_requests.md`).
 *
 * ```
 * aegis:1:<accountId-hex-32>:<tag-b64url-43>:<crc>
 * ```
 *
 * ПОЧЕМУ ЭТО БЕЗОПАСНО. `accountId = "aegis:" + hex(SHA-256(ed25519_pub)[..16])`
 * — криптографический коммитмент к ключу подписи владельца. Получив адрес по
 * каналу, которому пользователь доверяет (лично, письмом, в другом мессенджере),
 * клиент проверяет запись каталога САМ: подпись Ed25519 + равенство
 * `account_id_from(ed25519) == accountId`. Подменить ключи не может даже
 * полностью скомпрометированный релей — ему нужен второй прообраз SHA-256
 * (128 бит стойкости). Доверие сведено к одной вещи — подлинности этой строки,
 * то есть к тому же допущению, что и для отсканированного QR.
 *
 * `tag` — 256-битный тег обнаружения владельца. Из него выводятся адрес записи
 * в каталоге и адрес ящика приглашений, поэтому **знание адреса = право
 * прислать заявку**, и ничего больше: содержимое записи каталога публично по
 * замыслу (идентити-ключи и prekey-бандлы), а переписку защищают слои A и B.
 * Ротация тега мгновенно обесценивает все выданные ранее адреса.
 *
 * `crc` — 4 символа Crockford-Base32 от SHA-256-подобной контрольной суммы;
 * ловит опечатки при переносе адреса руками ДО обращения к сети.
 */
object ContactAddress {

    /** Схема и версия формата. */
    const val PREFIX: String = "aegis:1:"

    /** Длина accountId без префикса `aegis:` (32 hex-символа = 128 бит). */
    const val ACCOUNT_HEX_LENGTH: Int = 32

    /** Длина тега в URL-safe base64 без паддинга (32 байта → 43 символа). */
    const val TAG_B64_LENGTH: Int = 43

    /** Длина контрольной суммы в символах. */
    const val CHECKSUM_LENGTH: Int = 4

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Разобранный адрес контакта. */
    data class Parsed(
        /** Полный `aegis:<32 hex>` — как он используется в контактах и mailbox. */
        val accountId: String,
        /** Тег обнаружения, URL-safe base64 без паддинга. */
        val tagB64Url: String,
    )

    /**
     * Собирает адрес из accountId и тега. [accountId] принимается как с
     * префиксом `aegis:`, так и без него.
     */
    fun format(accountId: String, tagB64Url: String): String {
        val hex = accountHex(accountId)
            ?: throw IllegalArgumentException("некорректный accountId: $accountId")
        val tag = tagB64Url.trim()
        require(tag.length == TAG_B64_LENGTH) {
            "тег должен быть $TAG_B64_LENGTH символов url-safe base64, получено ${tag.length}"
        }
        val body = "$PREFIX$hex:$tag"
        return "$body:${checksum(body)}"
    }

    /**
     * Разбирает адрес. `null` — если это не адрес AEGIS, версия не та, поля
     * неверной длины/алфавита либо не сошлась контрольная сумма (опечатка).
     *
     * Терпимо к тому, как пользователи переносят строки: убирает пробелы,
     * переводы строк и обёртку `aegis://`, которую могут добавить мессенджеры.
     */
    fun parse(raw: String): Parsed? {
        val cleaned = raw
            .trim()
            .removePrefix("aegis://")
            .filterNot { it.isWhitespace() }
        if (!cleaned.startsWith(PREFIX)) return null

        val parts = cleaned.split(':')
        // aegis : 1 : <hex> : <tag> : <crc>
        if (parts.size != 5) return null
        val (_, version, hex, tag, crc) = parts
        if (version != "1") return null
        if (hex.length != ACCOUNT_HEX_LENGTH || !hex.all { it.isHexDigit() }) return null
        if (tag.length != TAG_B64_LENGTH || !tag.all { it.isB64Url() }) return null
        if (crc.length != CHECKSUM_LENGTH) return null

        val body = "$PREFIX${hex.lowercase()}:$tag"
        if (!checksum(body).equals(crc, ignoreCase = true)) return null
        return Parsed(accountId = "aegis:${hex.lowercase()}", tagB64Url = tag)
    }

    /** Есть ли в тексте похожий на адрес фрагмент (для подсказки «вставить»). */
    fun looksLikeAddress(raw: String): Boolean =
        raw.trim().removePrefix("aegis://").startsWith(PREFIX)

    /**
     * `accountId` без префикса в нижнем регистре или `null`, если это не
     * корректный идентификатор аккаунта AEGIS.
     */
    fun accountHex(accountId: String): String? {
        val hex = accountId.trim().removePrefix("aegis:").lowercase()
        if (hex.length != ACCOUNT_HEX_LENGTH || !hex.all { it.isHexDigit() }) return null
        return hex
    }

    /**
     * Компактная форма для показа в UI: первые и последние символы accountId.
     * Полный адрес длинный, а пользователю нужно сверить именно accountId —
     * именно он несёт привязку к ключу.
     */
    fun shortAccount(accountId: String): String {
        val hex = accountHex(accountId) ?: return accountId
        return "${hex.take(8)}…${hex.takeLast(8)}".uppercase()
    }

    /**
     * Контрольная сумма тела адреса: 4 символа Crockford-Base32 от FNV-1a-64.
     *
     * Криптографической стойкости здесь НЕ требуется и она не подразумевается:
     * подлинность обеспечивают подпись записи каталога и привязка accountId к
     * ключу. Задача суммы — поймать опечатку локально, не тратя обращение к сети.
     */
    private fun checksum(body: String): String {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (byte in body.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        val sb = StringBuilder(CHECKSUM_LENGTH)
        var acc = hash
        repeat(CHECKSUM_LENGTH) {
            sb.append(CROCKFORD[(acc and 0x1f).toInt()])
            acc = acc ushr 5
        }
        return sb.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isB64Url(): Boolean =
        this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this == '-' || this == '_'
}
