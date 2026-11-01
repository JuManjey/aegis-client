package com.aegis.core.common.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTraceTest {

    @Test
    fun `sdp summary counts media and candidates without secrets`() {
        val sdp = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=setup:actpass
            a=fingerprint:sha-256 AA:BB
            a=candidate:1 1 udp 1 10.0.0.1 9 typ host
            a=candidate:2 1 udp 1 1.2.3.4 9 typ relay
        """.trimIndent()
        val map = CallTrace.sdpFields(sdp).toMap()
        assertEquals(1, map["mAudio"])
        assertEquals(1, map["bundle"])
        assertEquals(1, map["fingerprint"])
        assertEquals("actpass", map["setup"])
        assertEquals(1, map["candHost"])
        assertEquals(1, map["candRelay"])
        assertTrue((map["sdpБайт"] as Int) > 50)
    }

    @Test
    fun `ice uri is classified without exposing the host`() {
        val map = CallTrace.iceUriFields("turn:192.168.0.12:3478?transport=tcp").toMap()
        assertEquals("turn", map["схема"])
        assertEquals("ipv4", map["hostKind"])
        assertEquals(true, map["портОк"])
        assertEquals("tcp", map["транспорт"])
        assertEquals("loopback", CallTrace.hostKind("127.0.0.1"))
        assertEquals("loopbackAlt", CallTrace.hostKind("127.0.0.2"))
        assertEquals("name", CallTrace.hostKind("localhost.local"))
        assertEquals("127.0.0.2", CallTrace.iceUriHost("turn:127.0.0.2:38377?transport=tcp"))
    }

    @Test
    fun `redact hides addresses`() {
        val redacted = CallTrace.redactNet("Trying TURN via tcp @ 10.1.2.3:3478")
        assertTrue(!redacted.contains("10.1.2.3"))
        assertTrue(redacted.contains("x.x.x.x"))
    }

    @Test
    fun `redact keeps C++ namespaces`() {
        val cpp = "audio_processing_impl.cc AudioProcessing::Config{ pipeline"
        assertEquals(cpp, CallTrace.redactNet(cpp))
        assertTrue(CallTrace.looksLikeIpv6("fe80::1"))
        assertTrue(CallTrace.looksLikeIpv6("::1"))
        assertEquals("addr ipv6 ok", CallTrace.redactNet("addr fe80::1 ok"))
        assertEquals("loop ipv6 done", CallTrace.redactNet("loop ::1 done"))
    }

    @Test
    fun `key material lines from webrtc-sdk are recognised`() {
        val key32 = List(32) { 17 }.joinToString(",", prefix = "[", postfix = ",]")
        val salt = "aegis/call/frame-cryptor/v1".map { it.code }.joinToString(",", prefix = "[", postfix = ",]")
        val key16 = List(16) { 200 }.joinToString(",", prefix = "[", postfix = ",]")
        val line = "frame_crypto_transformer.cc (frame_crypto_transformer.cc:189): raw_key $key32 len 32 " +
            "slat << $salt len 27\n derived_key $key16 len 16"
        assertTrue(CallTrace.carriesKeyMaterial(line))
        assertTrue(CallTrace.carriesKeyMaterial(CallTrace.redactNet(line)))
        assertTrue(CallTrace.carriesKeyMaterial("unlabelled $key16"))
        assertTrue(CallTrace.carriesKeyMaterial(List(16) { it }.joinToString(", ", prefix = "[", postfix = "]")))
    }

    @Test
    fun `ordinary native lines are kept`() {
        assertFalse(CallTrace.carriesKeyMaterial("Set ICE ufrag: abcd pwd length 24"))
        assertFalse(CallTrace.carriesKeyMaterial("audio_processing_impl.cc AudioProcessing::Config{ pipeline"))
        assertFalse(CallTrace.carriesKeyMaterial("Payload types [111, 63, 9, 0, 8, 13, 110, 126]"))
        assertFalse(CallTrace.carriesKeyMaterial("FrameCryptorTransformer::decryptFrame() ratcheted to key_index=0"))
        assertFalse(CallTrace.carriesKeyMaterial("SSRCs [1234567890, 2345678901]"))
    }
}
