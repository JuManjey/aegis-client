package com.aegis.messenger.multidevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.security.CoverTrafficMode
import com.aegis.core.common.security.FanoutJitterMode
import com.aegis.core.common.security.MetadataMinimization
import com.aegis.core.common.security.SecurityPreset
import com.aegis.core.common.security.SecuritySettings
import com.aegis.messenger.messaging.CoverTraffic
import com.aegis.messenger.messaging.FanoutJitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Вынос параметров джиттера/cover-traffic в пользовательские настройки (§14.7, №3b;
 * `docs/multidevice.md` §12/§1.40/§1.41). Проверяет, что:
 *  - режим AUTO точно воспроизводит прежнее поведение (следует `meta.minimize`);
 *  - явные режимы переопределяют пресет независимо от `meta.minimize`;
 *  - `matchingPreset()` остаётся каноничным при AUTO и становится CUSTOM при ручном
 *    переопределении (§8);
 *  - хелперы [FanoutJitter]/[CoverTraffic] честно реагируют на эффективные значения
 *    (та же проводка `levelProvider`, что и в `AppContainer`).
 */
@RunWith(AndroidJUnit4::class)
class FanoutPrivacySettingsTest {

    // ---- effectiveFanoutJitter() ------------------------------------------------

    @Test
    fun jitterAuto_followsMetaMinimize() {
        for (level in MetadataMinimization.entries) {
            val s = SecuritySettings(metaMinimize = level, fanoutJitter = FanoutJitterMode.AUTO)
            assertEquals("AUTO ⇒ джиттер = meta.minimize ($level)", level, s.effectiveFanoutJitter())
        }
    }

    @Test
    fun jitterExplicit_overridesPreset() {
        // Даже при meta.minimize=BASIC (пресет «Быстро») явный MAXIMUM даёт MAXIMUM.
        val boosted = SecuritySettings(
            metaMinimize = MetadataMinimization.BASIC,
            fanoutJitter = FanoutJitterMode.MAXIMUM,
        )
        assertEquals(MetadataMinimization.MAXIMUM, boosted.effectiveFanoutJitter())

        // И наоборот: при meta.minimize=MAXIMUM (Параноик) явный OFF выключает джиттер.
        val disabled = SecuritySettings(
            metaMinimize = MetadataMinimization.MAXIMUM,
            fanoutJitter = FanoutJitterMode.OFF,
        )
        assertEquals(MetadataMinimization.BASIC, disabled.effectiveFanoutJitter())

        val moderate = SecuritySettings(
            metaMinimize = MetadataMinimization.BASIC,
            fanoutJitter = FanoutJitterMode.MODERATE,
        )
        assertEquals(MetadataMinimization.MODERATE, moderate.effectiveFanoutJitter())
    }

    // ---- coverTrafficActive() ---------------------------------------------------

    @Test
    fun coverAuto_onlyAtMaximum() {
        assertTrue(
            "AUTO + MAXIMUM ⇒ cover включён",
            SecuritySettings(metaMinimize = MetadataMinimization.MAXIMUM, coverTraffic = CoverTrafficMode.AUTO)
                .coverTrafficActive(),
        )
        for (level in listOf(MetadataMinimization.MODERATE, MetadataMinimization.BASIC)) {
            assertFalse(
                "AUTO + $level ⇒ cover выключен",
                SecuritySettings(metaMinimize = level, coverTraffic = CoverTrafficMode.AUTO).coverTrafficActive(),
            )
        }
    }

    @Test
    fun coverExplicit_overridesPreset() {
        // ON включает cover даже вне «Параноика».
        assertTrue(
            SecuritySettings(metaMinimize = MetadataMinimization.BASIC, coverTraffic = CoverTrafficMode.ON)
                .coverTrafficActive(),
        )
        // OFF выключает cover даже в «Параноике».
        assertFalse(
            SecuritySettings(metaMinimize = MetadataMinimization.MAXIMUM, coverTraffic = CoverTrafficMode.OFF)
                .coverTrafficActive(),
        )
    }

    // ---- matchingPreset() / обратная совместимость ------------------------------

    @Test
    fun defaultsAreAuto_andPresetsStayCanonical() {
        val paranoid = SecuritySettings.forPreset(SecurityPreset.PARANOID)
        assertEquals("новый аккаунт: джиттер = AUTO", FanoutJitterMode.AUTO, paranoid.fanoutJitter)
        assertEquals("новый аккаунт: cover = AUTO", CoverTrafficMode.AUTO, paranoid.coverTraffic)
        // AUTO/AUTO не ломает распознавание пресета (§8).
        assertEquals(SecurityPreset.PARANOID, paranoid.matchingPreset())
        assertEquals(SecurityPreset.BALANCED, SecuritySettings.forPreset(SecurityPreset.BALANCED).matchingPreset())
        assertEquals(SecurityPreset.FAST, SecuritySettings.forPreset(SecurityPreset.FAST).matchingPreset())
    }

    @Test
    fun manualOverride_becomesCustom() {
        val paranoid = SecuritySettings.forPreset(SecurityPreset.PARANOID)
        assertEquals(SecurityPreset.CUSTOM, paranoid.copy(fanoutJitter = FanoutJitterMode.OFF).matchingPreset())
        assertEquals(SecurityPreset.CUSTOM, paranoid.copy(coverTraffic = CoverTrafficMode.OFF).matchingPreset())
    }

    // ---- интеграция с хелперами (та же проводка, что в AppContainer) ------------

    @Test
    fun helpers_honorEffectiveSettings() {
        // «Быстро» (BASIC), но пользователь вручную включил максимальную приватность.
        val custom = SecuritySettings(
            metaMinimize = MetadataMinimization.BASIC,
            fanoutJitter = FanoutJitterMode.MAXIMUM,
            coverTraffic = CoverTrafficMode.ON,
        )
        val jitter = FanoutJitter(levelProvider = { custom.effectiveFanoutJitter() })
        assertEquals(FanoutJitter.MAXIMUM_JITTER_MAX_MS, jitter.maxDelayMs())

        val cover = CoverTraffic(
            levelProvider = {
                if (custom.coverTrafficActive()) MetadataMinimization.MAXIMUM else MetadataMinimization.BASIC
            },
        )
        assertTrue("cover включён по ручной настройке вне Параноика", cover.enabled)

        // «Параноик», но пользователь вручную ослабил обе меры.
        val relaxed = SecuritySettings(
            metaMinimize = MetadataMinimization.MAXIMUM,
            fanoutJitter = FanoutJitterMode.OFF,
            coverTraffic = CoverTrafficMode.OFF,
        )
        val jitterOff = FanoutJitter(levelProvider = { relaxed.effectiveFanoutJitter() })
        assertEquals(0L, jitterOff.maxDelayMs())
        val coverOff = CoverTraffic(
            levelProvider = {
                if (relaxed.coverTrafficActive()) MetadataMinimization.MAXIMUM else MetadataMinimization.BASIC
            },
        )
        assertFalse("cover выключен по ручной настройке в Параноике", coverOff.enabled)
    }
}
