package com.aegis.core.common.security

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPresetTest {

    private val storeJson = Json { ignoreUnknownKeys = true }

    @Test
    fun presetsChooseTransport() {
        assertEquals(TorMode.ALWAYS, SecuritySettings.forPreset(SecurityPreset.PARANOID).netTor)
        assertEquals(TorMode.ALWAYS, SecuritySettings.forPreset(SecurityPreset.BALANCED).netTor)
        assertEquals(TorMode.OFF, SecuritySettings.forPreset(SecurityPreset.FAST).netTor)
        assertTrue(SecuritySettings.forPreset(SecurityPreset.FAST).torOffConfirmed)
    }

    @Test
    fun newAccountGetsTor() {
        assertEquals(TorMode.ALWAYS, SecuritySettings.DEFAULT.netTor)
        assertTrue(SecuritySettings.DEFAULT.torEnabled)
        assertFalse(SecuritySettings.DEFAULT.torOffConfirmed)
    }

    @Test
    fun presetsMatchThemselves() {
        for (preset in listOf(SecurityPreset.PARANOID, SecurityPreset.BALANCED, SecurityPreset.FAST)) {
            val settings = SecuritySettings.forPreset(preset)
            assertEquals(preset, settings.matchingPreset())
            assertEquals(settings, settings.normalized())
        }
    }

    /**
     * «Баланс», сохранённый старой версией: `callsMediaOverTor=false` (медиа напрямую)
     * при Tor ВКЛ. Удалённый ключ игнорируется — профиль остаётся «Балансом», а
     * звонки теперь идут через Tor.
     */
    @Test
    fun legacyBalancedStaysBalanced() {
        val balanced = SecuritySettings.forPreset(SecurityPreset.BALANCED)
        val legacy = storeJson.encodeToString(SecuritySettings.serializer(), balanced)
            .removeSuffix("}") + ",\"callsMediaOverTor\":false}"
        val restored = storeJson.decodeFromString(SecuritySettings.serializer(), legacy).normalized()
        assertEquals(SecurityPreset.BALANCED, restored.matchingPreset())
        assertTrue(restored.torEnabled)
    }

    /** Унаследованный «Быстро» (OFF без подтверждения) после чтения уже не «Быстро». */
    @Test
    fun legacyFastLosesUnconfirmedOff() {
        val legacyFast = SecuritySettings.forPreset(SecurityPreset.FAST).copy(torOffConfirmed = false)
        val restored = legacyFast.normalized()
        assertEquals(TorMode.ALWAYS, restored.netTor)
        assertEquals(SecurityPreset.CUSTOM, restored.matchingPreset())
    }

    @Test
    fun manualToggleMakesProfileCustom() {
        val paranoidWithoutTor = SecuritySettings.forPreset(SecurityPreset.PARANOID).withTorMode(TorMode.OFF)
        assertEquals(SecurityPreset.CUSTOM, paranoidWithoutTor.matchingPreset())
        assertEquals(
            SecurityPreset.PARANOID,
            paranoidWithoutTor.withTorMode(TorMode.ALWAYS).matchingPreset(),
        )
    }
}
