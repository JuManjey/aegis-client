package com.aegis.core.common.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCryptoInfoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun recorded_isTrueWhenLayerBPresent() {
        val info = MessageCryptoInfo(layerBType = "Whisper")
        assertTrue(info.recorded)
    }

    @Test
    fun recorded_isFalseWhenEmpty() {
        assertFalse(MessageCryptoInfo().recorded)
    }

    @Test
    fun json_roundtripKeepsAlgorithms() {
        val info = MessageCryptoInfo(
            layerA = true,
            layerBType = "Whisper",
            layerBProtocol = "Signal Protocol (X3DH/PQXDH + Double Ratchet)",
            cascadeAlgorithms = listOf(
                "XChaCha20-Poly1305",
                "AES-256-GCM",
                "Camellia-256-EAX",
                "Twofish-256-EAX",
            ),
            attachmentEncrypted = false,
            transport = "Tor (SOCKS5 → onion-релей)",
        )
        val encoded = json.encodeToString(MessageCryptoInfo.serializer(), info)
        val decoded = json.decodeFromString(MessageCryptoInfo.serializer(), encoded)
        assertEquals(info, decoded)
    }

    @Test
    fun json_oldPayloadWithoutCryptoFields_defaults() {
        val decoded = json.decodeFromString(MessageCryptoInfo.serializer(), "{}")
        assertFalse(decoded.recorded)
        assertEquals(emptyList<String>(), decoded.cascadeAlgorithms)
    }
}
