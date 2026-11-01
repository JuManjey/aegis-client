package com.aegis.network.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBindAddressTest {
    @Test
    fun `wifi scores higher than cellular`() {
        assertTrue(LocalBindAddress.scoreInterface("wlan0") > LocalBindAddress.scoreInterface("rmnet0"))
        assertTrue(LocalBindAddress.scoreInterface("eth0") > LocalBindAddress.scoreInterface("tun0"))
    }

    @Test
    fun `dummy is lowest`() {
        assertEquals(0, LocalBindAddress.scoreInterface("dummy0"))
    }

    @Test
    fun `bind candidates start with webrtc-safe loopback`() {
        val candidates = LocalBindAddress.bindCandidates()
        assertEquals(LocalBindAddress.LOOPBACK_ALT, candidates.first())
        assertTrue(candidates.contains(LocalBindAddress.LOOPBACK))
        assertTrue(LocalBindAddress.isLoopbackHost("127.0.0.2"))
        assertTrue(LocalBindAddress.isLoopbackHost("127.0.0.1"))
    }
}
