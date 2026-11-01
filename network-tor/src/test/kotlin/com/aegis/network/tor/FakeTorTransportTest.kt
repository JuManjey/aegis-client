package com.aegis.network.tor

import com.aegis.core.common.security.TorMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTorTransportTest {

    @Test
    fun neverReportsConnectedOrSocks() = runBlocking {
        val t = FakeTorTransport()
        assertTrue(t.status.value is TorStatus.Unavailable)
        assertNull(t.socksPort)

        t.applyMode(TorMode.ALWAYS)
        assertTrue(t.status.value is TorStatus.Unavailable)
        assertNull(t.socksPort)

        t.restart("тест")
        assertTrue(t.status.value is TorStatus.Unavailable)
        assertNull(t.socksPort)

        t.applyMode(TorMode.OFF)
        assertTrue(t.status.value is TorStatus.Disabled)
        assertNull(t.socksPort)
    }
}
