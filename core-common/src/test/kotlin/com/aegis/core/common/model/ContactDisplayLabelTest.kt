package com.aegis.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDisplayLabelTest {

    @Test
    fun displayLabel_usesLocalNameWhenSet() {
        val contact = Contact(id = "aegis:aa", nickname = "peer_nick", localName = "Саша")
        assertEquals("Саша", contact.displayLabel())
    }

    @Test
    fun displayLabel_fallsBackToNickname() {
        val contact = Contact(id = "aegis:aa", nickname = "peer_nick", localName = "  ")
        assertEquals("peer_nick", contact.displayLabel())
    }

    @Test
    fun displayLabel_fallsBackToIdWhenBothBlank() {
        val contact = Contact(id = "aegis:aa", nickname = "", localName = "")
        assertEquals("aegis:aa", contact.displayLabel())
    }

    @Test
    fun sanitize_trimsAndCollapsesWhitespace() {
        assertEquals("Анна Петрова", Contact.sanitizeLocalName("  Анна   Петрова  "))
        assertEquals("A B", Contact.sanitizeLocalName("A\n\tB"))
    }

    @Test
    fun sanitize_stripsControlAndZeroWidth() {
        val raw = "A\u0000B\u200BC" // NUL + zero-width space (FORMAT)
        assertEquals("ABC", Contact.sanitizeLocalName(raw))
    }

    @Test
    fun sanitize_emptyMeansReset() {
        assertEquals("", Contact.sanitizeLocalName("   \n\t  "))
        assertEquals("", Contact.sanitizeLocalName(""))
    }

    @Test
    fun sanitize_capsLength() {
        val long = "я".repeat(Contact.LOCAL_NAME_MAX_CHARS + 20)
        val sanitized = Contact.sanitizeLocalName(long)
        assertEquals(Contact.LOCAL_NAME_MAX_CHARS, sanitized.length)
    }
}
