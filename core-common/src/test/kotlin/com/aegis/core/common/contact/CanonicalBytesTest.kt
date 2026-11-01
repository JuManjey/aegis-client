package com.aegis.core.common.contact

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия на реальную ошибку: подпись записи каталога не сходилась
 * (`BadSignature` на подлинных данных), потому что каноничные байты считались
 * ПАРАМЕТРИЗУЕМЫМ `Json` — у публикатора с `encodeDefaults = true`, у
 * проверяющего с `false`. Поля, равные значениям по умолчанию, у одной стороны
 * попадали в байты, у другой пропускались.
 *
 * Эти тесты фиксируют инварианты, на которых держится подпись:
 *  1. байты НЕ зависят от значения поля подписи;
 *  2. поля со значениями по умолчанию присутствуют в байтах;
 *  3. байты детерминированы (одинаковы при повторном вычислении).
 */
class CanonicalBytesTest {

    private fun record(sig: String) = DirectoryRecord(
        accountId = "aegis:34750f98bd59fcfc946da45aaabe933b",
        ed25519Hex = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
        x25519Hex = "aa".repeat(32),
        // Поля НАМЕРЕННО оставлены со значениями по умолчанию: именно на них
        // расходились публикатор и проверяющий.
        issuedAtMs = 1_700_000_000_000,
        expiresAtMs = 1_700_086_400_000,
        sigB64 = sig,
    )

    @Test
    fun `байты записи не зависят от поля подписи`() {
        assertArrayEquals(
            "подпись не должна входить в подписываемые байты",
            record("").canonicalBytes(),
            record("c2lnbmF0dXJl").canonicalBytes(),
        )
    }

    @Test
    fun `байты записи включают поля со значениями по умолчанию`() {
        val text = record("").canonicalBytes().decodeToString()
        // Без encodeDefaults эти поля пропадали — и подпись расходилась.
        assertTrue("нет версии: $text", text.contains("\"v\":1"))
        assertTrue("нет пустого sigB64: $text", text.contains("\"sigB64\":\"\""))
        assertTrue("нет пустого nickname: $text", text.contains("\"nickname\":\"\""))
        assertTrue("нет пустого fingerprint: $text", text.contains("\"fingerprint\":\"\""))
        assertTrue("нет пустого devices: $text", text.contains("\"devices\":[]"))
    }

    @Test
    fun `байты записи детерминированы`() {
        assertArrayEquals(record("").canonicalBytes(), record("").canonicalBytes())
    }

    @Test
    fun `байты приглашения не зависят от поля подписи`() {
        val invite = ContactInvite(
            fromAccountId = "aegis:34750f98bd59fcfc946da45aaabe933b",
            fromEd25519Hex = "bb".repeat(32),
            fromX25519Hex = "cc".repeat(32),
            replyTagB64 = "dGFn",
            epkPublicB64 = "ZXBr",
            kemPublicB64 = "a2Vt",
            contribB64 = "Y29u",
            nonceB64 = "bm9u",
        )
        assertArrayEquals(
            invite.canonicalBytes(),
            invite.copy(sigB64 = "c2ln").canonicalBytes(),
        )
        val text = invite.canonicalBytes().decodeToString()
        assertTrue("нет типа: $text", text.contains("\"t\":\"contact-invite\""))
        assertTrue("нет версии: $text", text.contains("\"v\":1"))
    }

    @Test
    fun `байты ответа и отказа не зависят от поля подписи`() {
        val accept = ContactAccept(
            fromAccountId = "aegis:aaaa",
            fromEd25519Hex = "dd".repeat(32),
            fromX25519Hex = "ee".repeat(32),
            epkPublicB64 = "ZXBr",
            kemCiphertextB64 = "a2VtY3Q",
            contribB64 = "Y29u",
            nonceB64 = "bm9u",
        )
        assertArrayEquals(accept.canonicalBytes(), accept.copy(sigB64 = "x").canonicalBytes())

        val decline = ContactDecline(
            fromAccountId = "aegis:bbbb",
            fromEd25519Hex = "ff".repeat(32),
        )
        assertArrayEquals(decline.canonicalBytes(), decline.copy(sigB64 = "y").canonicalBytes())
    }

    /**
     * Заявка на алиас подписывается БЕЗ поля подписи внутри payload, а
     * проверяется над принятыми байтами. Здесь важна только детерминированность
     * и наличие дефолтных полей.
     */
    @Test
    fun `байты заявки на алиас детерминированы и содержат дефолты`() {
        val claim = AliasClaim(
            aliasIndex = "aWR4",
            accountId = "aegis:cccc",
            ed25519Hex = "11".repeat(32),
            discoveryIndex = "ZGlzY28",
            tagB64 = "dGFn",
            issuedAtMs = 1,
            expiresAtMs = 2,
        )
        assertArrayEquals(claim.canonicalBytes(), claim.canonicalBytes())
        val text = claim.canonicalBytes().decodeToString()
        assertTrue("нет версии: $text", text.contains("\"v\":1"))
        assertTrue("нет пустого nickname: $text", text.contains("\"nickname\":\"\""))
    }

    /**
     * Формат адреса контакта: разбор обязан быть обратным к сборке, а опечатка —
     * отсекаться контрольной суммой ДО обращения к сети.
     */
    @Test
    fun `адрес контакта собирается и разбирается обратно`() {
        val accountId = "aegis:34750f98bd59fcfc946da45aaabe933b"
        val tag = "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH0123456789a"
        val address = ContactAddress.format(accountId, tag)
        val parsed = ContactAddress.parse(address)
        assertEquals(accountId, parsed?.accountId)
        assertEquals(tag, parsed?.tagB64Url)

        // Опечатка в теге ломает контрольную сумму.
        val broken = address.replaceFirst("AAAAB", "AAAAC")
        assertEquals(null, ContactAddress.parse(broken))

        // Пробелы и обёртка `aegis://`, которые добавляют мессенджеры, терпимы.
        assertEquals(parsed, ContactAddress.parse("  aegis://$address\n"))
    }
}
