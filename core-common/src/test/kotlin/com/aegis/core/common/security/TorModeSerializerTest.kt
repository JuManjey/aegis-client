package com.aegis.core.common.security

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TorModeSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun storedNamesAreUnchanged() {
        assertEquals("\"ALWAYS\"", json.encodeToString(TorMode.serializer(), TorMode.ALWAYS))
        assertEquals("\"OFF\"", json.encodeToString(TorMode.serializer(), TorMode.OFF))
    }

    @Test
    fun legacyMessagesOnlyBecomesAlways() {
        assertEquals(TorMode.ALWAYS, json.decodeFromString(TorMode.serializer(), "\"MESSAGES_ONLY\""))
    }

    @Test
    fun unknownValueFallsBackToTorNotToFailure() {
        val decoded = json.decodeFromString(ListSerializer(TorMode.serializer()), "[\"ALWAYS\",\"OFF\",\"BRIDGES\",\"\"]")
        assertEquals(listOf(TorMode.ALWAYS, TorMode.OFF, TorMode.ALWAYS, TorMode.ALWAYS), decoded)
    }

    @Test
    fun onlyExactOffDisablesTor() {
        assertEquals(TorMode.OFF, TorModeSerializer.fromStored("OFF"))
        assertEquals(TorMode.OFF, TorModeSerializer.fromStored(" OFF "))
        assertEquals(TorMode.ALWAYS, TorModeSerializer.fromStored("off"))
        assertEquals(TorMode.ALWAYS, TorModeSerializer.fromStored("OFFLINE"))
    }
}
