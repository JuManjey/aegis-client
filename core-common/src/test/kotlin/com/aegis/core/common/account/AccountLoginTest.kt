package com.aegis.core.common.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginTest {

    @Test
    fun acceptsLettersDigitsUnderscoreAndUnicode() {
        assertNull(AccountLogin.validate("alice"))
        assertNull(AccountLogin.validate("Alice_01"))
        assertNull(AccountLogin.validate("Алиса"))
        assertNull(AccountLogin.validate("user_1"))
    }

    @Test
    fun normalizeTrimsWhitespace() {
        assertEquals("alice", AccountLogin.normalize("  alice  "))
    }

    @Test
    fun rejectsEmptyAndTooShort() {
        assertEquals(AccountLogin.Error.Empty, AccountLogin.validate(""))
        assertEquals(AccountLogin.Error.TooShort, AccountLogin.validate("a"))
    }

    @Test
    fun rejectsTooLong() {
        val long = "a".repeat(AccountLogin.MAX_CHARS + 1)
        assertEquals(AccountLogin.Error.TooLong, AccountLogin.validate(long))
        assertNull(AccountLogin.validate("a".repeat(AccountLogin.MAX_CHARS)))
    }

    @Test
    fun rejectsEmailAndPhone() {
        assertEquals(AccountLogin.Error.LooksLikeEmail, AccountLogin.validate("user@mail.com"))
        assertEquals(AccountLogin.Error.LooksLikePhone, AccountLogin.validate("79991234567"))
        assertEquals(AccountLogin.Error.LooksLikePhone, AccountLogin.validate("+79991234567"))
    }

    @Test
    fun rejectsSpacesHyphenAndControls() {
        assertEquals(AccountLogin.Error.InvalidChars, AccountLogin.validate("a b"))
        assertEquals(AccountLogin.Error.InvalidChars, AccountLogin.validate("user-name"))
        assertEquals(AccountLogin.Error.InvalidChars, AccountLogin.validate("a\u0000b"))
    }

    @Test
    fun persistAllowsEmptyOnlyWhenAsked() {
        assertNull(AccountLogin.validateForPersist("", allowEmpty = true))
        assertEquals(AccountLogin.Error.Empty, AccountLogin.validateForPersist("", allowEmpty = false))
        assertTrue(AccountLogin.validateForPersist("ok", allowEmpty = true) == null)
    }

    @Test
    fun digitMixWithLettersIsNotPhone() {
        assertNull(AccountLogin.validate("alice1234"))
    }
}
