package com.aegis.data.storage

import com.aegis.core.common.security.CoverTrafficMode
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тот же путь разбора, что в [FileSecureStore.loadSecuritySettings]. Ошибка
 * здесь в проде не видна: хранилище молча вернуло бы [SecuritySettings.DEFAULT].
 */
class SettingsCodecTest {

    @Test
    fun legacyFileWithMessagesOnlyKeepsOtherFields() {
        val legacy = """{"netTor":"MESSAGES_ONLY","callsMediaOverTor":false,"autoDeleteDays":2,""" +
            """"keysRequireVerify":false,"coverTraffic":"OFF","deliverWhileLocked":true}"""
        val s = SettingsCodec.decodeSecurity(legacy)
        assertEquals(TorMode.ALWAYS, s.netTor)
        assertEquals(2, s.autoDeleteDays)
        assertFalse(s.keysRequireVerify)
        assertEquals(CoverTrafficMode.OFF, s.coverTraffic)
        assertTrue(s.deliverWhileLocked)
    }

    @Test
    fun legacyFileWithUnconfirmedOffReturnsToTor() {
        val s = SettingsCodec.decodeSecurity("""{"netTor":"OFF","callsMediaOverTor":false}""")
        assertEquals(TorMode.ALWAYS, s.netTor)
        assertFalse(s.torOffConfirmed)
    }

    @Test
    fun roundTripKeepsConfirmedOff() {
        val off = SecuritySettings.DEFAULT.copy(autoDeleteDays = 6).withTorMode(TorMode.OFF)
        val restored = SettingsCodec.decodeSecurity(SettingsCodec.encodeSecurity(off))
        assertEquals(off, restored)
    }

    @Test
    fun defaultsAreNotWrittenSoOldFilesStayCompatible() {
        assertEquals("{}", SettingsCodec.encodeSecurity(SecuritySettings.DEFAULT))
    }
}
