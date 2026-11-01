package com.aegis.core.common.wire

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireChannelTest {

    @Serializable
    private data class ControlLikeEnvelope(
        val type: String,
        val layerA: Boolean,
        val ciphertextB64: String,
        val senderDevice: Int = 0,
        val channel: String = WireChannel.CONTROL,
        val v: Int = 1,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class MarkedEnvelope(
        val type: String,
        @EncodeDefault val channel: String = WireChannel.CONTROL,
    )

    @Test
    fun encodeDefaultsFalseDropsChannel() {
        val json = Json { encodeDefaults = false }
        val encoded = json.encodeToString(
            ControlLikeEnvelope.serializer(),
            ControlLikeEnvelope("Whisper", true, "AAA", senderDevice = 1),
        )
        assertFalse(
            "дефолт kotlinx.serialization не кодирует channel — это и был баг утечки в чат",
            encoded.contains("channel"),
        )
        assertEquals(WireChannel.MESSAGE, WireChannel.resolve(encoded))
    }

    @Test
    fun encodeDefaultsTrueKeepsChannel() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(
            ControlLikeEnvelope.serializer(),
            ControlLikeEnvelope("Whisper", true, "AAA", senderDevice = 1),
        )
        assertTrue(encoded.contains("\"channel\":\"control\""))
        assertEquals(WireChannel.CONTROL, WireChannel.resolve(encoded))
    }

    @Test
    fun encodeDefaultAnnotationKeepsChannelEvenIfEncodeDefaultsFalse() {
        val json = Json { encodeDefaults = false }
        val encoded = json.encodeToString(
            MarkedEnvelope.serializer(),
            MarkedEnvelope(type = "Whisper"),
        )
        assertTrue(encoded.contains("\"channel\":\"control\""))
        assertEquals(WireChannel.CONTROL, WireChannel.resolve(encoded))
    }

    @Test
    fun resolveExplicitChannels() {
        assertEquals(WireChannel.CALL, WireChannel.resolve("""{"type":"Whisper","channel":"call"}"""))
        assertEquals(WireChannel.CONTROL, WireChannel.resolve("""{"type":"Whisper","channel":"control"}"""))
        assertEquals(WireChannel.KEYX, WireChannel.resolve("""{"ciphertextB64":"AAA","channel":"keyx"}"""))
        assertEquals(WireChannel.MESSAGE, WireChannel.resolve("""{"type":"Whisper","layerA":true,"channel":"message"}"""))
    }

    @Test
    fun resolveMissingChannelLooksLikeMessageEnvelope() {
        assertEquals(
            WireChannel.MESSAGE,
            WireChannel.resolve("""{"type":"Whisper","layerA":true,"ciphertextB64":"AAA","senderDevice":1}"""),
        )
    }

    @Test
    fun resolveMissingChannelLooksLikeKeyx() {
        assertEquals(
            WireChannel.KEYX,
            WireChannel.resolve("""{"ciphertextB64":"AAA","senderDevice":1}"""),
        )
    }

    @Test
    fun classifyPlaintextControlAndCall() {
        assertEquals(
            WireChannel.Kind.CONTROL,
            WireChannel.classifyPlaintext("""{"type":"typing","atMs":1786817282240}"""),
        )
        assertEquals(
            WireChannel.Kind.CONTROL,
            WireChannel.classifyPlaintext("""{"type":"presence","online":true,"atMs":1}"""),
        )
        assertEquals(
            WireChannel.Kind.CONTROL,
            WireChannel.classifyPlaintext("""{"type":"read","messageIds":["a"],"atMs":1}"""),
        )
        assertEquals(
            WireChannel.Kind.CALL,
            WireChannel.classifyPlaintext("""{"type":"offer","callId":"c1","sdp":"v=0"}"""),
        )
        assertEquals(
            WireChannel.Kind.CALL,
            WireChannel.classifyPlaintext("""{"type":"hangup","callId":"c1","reason":"ended"}"""),
        )
        assertNull(WireChannel.classifyPlaintext("наконец, заработало"))
        assertNull(WireChannel.classifyPlaintext("111"))
        assertNull(WireChannel.classifyPlaintext("""{"text":"111","v":1}"""))
    }

    @Test
    fun storedLeakOnlyForSignalingJson() {
        assertTrue(WireChannel.isStoredLeak("""{"type":"typing","atMs":1}"""))
        assertFalse(WireChannel.isStoredLeak("""{"type":"typing","atMs":1}""", hasAttachment = true))
        assertFalse(WireChannel.isStoredLeak("111"))
    }
}
