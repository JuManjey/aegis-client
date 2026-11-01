package com.aegis.core.common.model

import com.aegis.core.common.contact.ContactTrust
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCardSearchTest {

    private val card = ContactCard(
        id = "aegis:9f2c4a10bb73d5e6f8a1c0d2e3b45678",
        displayName = "Панам Палмер",
        nickname = "panam_palmer",
        localName = "Панам Палмер",
        fingerprint = "AB12CD34EF56AB78CD90EF12AB34CD56",
        verified = true,
        trust = ContactTrust.IN_PERSON,
        e2eeActive = true,
    )

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(card.matches(""))
        assertTrue(card.matches("   "))
    }

    @Test
    fun matchesNameCaseInsensitively() {
        assertTrue(card.matches("панам"))
        assertTrue(card.matches("ПАНАМ"))
        assertTrue(card.matches("палмер"))
    }

    @Test
    fun matchesExchangeNicknameEvenWhenLocalNameDiffers() {
        val renamed = card.copy(displayName = "Кочевница", localName = "Кочевница")
        assertTrue(renamed.matches("panam_palmer"))
        assertTrue(renamed.matches("кочевн"))
    }

    @Test
    fun matchesAccountIdWithAndWithoutSeparators() {
        assertTrue(card.matches("9f2c4a10"))
        assertTrue(card.matches("aegis:9f2c4a10"))
        assertTrue(card.matches("9F2C 4A10"))
    }

    @Test
    fun matchesGroupedFingerprintAsShownOnScreen() {
        // Ровно то, что пользователь скопирует с экрана сверки.
        assertTrue(card.matches("AB12 CD34 EF56"))
        assertTrue(card.matches("ab12cd34ef56"))
    }

    @Test
    fun doesNotMatchForeignText() {
        assertFalse(card.matches("wakako"))
        assertFalse(card.matches("deadbeef"))
    }

    @Test
    fun punctuationOnlyQueryDoesNotMatchEverything() {
        // Запрос из одних разделителей схлопнулся бы в пустой код и без
        // проверки совпал бы с любым контактом.
        assertFalse(card.matches("···"))
        assertFalse(card.matches("--"))
    }

    @Test
    fun emptyFieldsDoNotCrossMatch() {
        val bare = ContactCard(id = "aegis:00", displayName = "aegis:00")
        assertFalse(bare.matches("panam"))
        assertTrue(bare.matches("00"))
    }
}
