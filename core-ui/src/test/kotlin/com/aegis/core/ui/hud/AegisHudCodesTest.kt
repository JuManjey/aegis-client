package com.aegis.core.ui.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты декоративной HUD-маркировки (`docs/ui_hud.md` §6).
 *
 * Проверяется не «красота», а три свойства, на которые опирается интерфейс:
 * форматирование вообще не падает, код детерминирован (иначе шапка мигала бы
 * при каждой рекомпозиции) и разные экраны различимы.
 */
class AegisHudCodesTest {

    /**
     * Регрессия: раньше остаток хеша уходил в `"%06d".format(...)` как `UInt`.
     * `UInt` — inline-класс Kotlin, а не `java.lang.Number`, поэтому
     * `java.util.Formatter` бросал `IllegalFormatConversionException` в рантайме
     * при первой же отрисовке любого экрана с HUD-шапкой.
     */
    @Test
    fun `trace formats without throwing`() {
        listOf("chats", "chat.thread", "contacts", "settings", "profile", "", "ключ").forEach { slug ->
            val code = AegisHudCodes.trace(slug)
            assertTrue("неожиданный формат: $code", code.matches(Regex("TRN_TCLAS_\\d{6}")))
        }
    }

    @Test
    fun `version formats without throwing`() {
        listOf("chats", "chat.thread", "contacts", "", "ключ").forEach { slug ->
            val code = AegisHudCodes.version(slug)
            assertTrue("неожиданный формат: $code", code.matches(Regex("VER_[0-9A-Z]{6}")))
        }
    }

    /** Один экран — один код: иначе шапка «мигала» бы на каждой рекомпозиции. */
    @Test
    fun `codes are deterministic`() {
        assertEquals(AegisHudCodes.trace("chats"), AegisHudCodes.trace("chats"))
        assertEquals(AegisHudCodes.version("chats"), AegisHudCodes.version("chats"))
    }

    /** Разные разделы должны выглядеть по-разному, иначе маркировка бессмысленна. */
    @Test
    fun `different slugs give different codes`() {
        assertNotEquals(AegisHudCodes.trace("chats"), AegisHudCodes.trace("chat.thread"))
        assertNotEquals(AegisHudCodes.version("chats"), AegisHudCodes.version("chat.thread"))
    }

    /**
     * Соль у «трассировки» и «версии» разная, поэтому один и тот же slug не
     * даёт визуально совпадающие числа в обоих углах панели.
     */
    @Test
    fun `trace and version use independent salts`() {
        val slug = "chat.thread"
        val trace = AegisHudCodes.trace(slug).removePrefix("TRN_TCLAS_")
        val version = AegisHudCodes.version(slug).removePrefix("VER_")
        assertNotEquals(trace, version)
    }
}
