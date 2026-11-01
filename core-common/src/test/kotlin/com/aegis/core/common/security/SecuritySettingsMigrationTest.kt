package com.aegis.core.common.security

import com.aegis.core.common.device.LinkPayload
import com.aegis.core.common.transfer.TransferPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Миграция `MESSAGES_ONLY` / `callsMediaOverTor` / унаследованного OFF.
 *
 * Главное, что здесь проверяется: старые данные ЧИТАЮТСЯ, а не падают. Падение
 * разбора в `FileSecureStore` означало бы тихий сброс всех настроек
 * безопасности к умолчанию, в пакете переноса и линковки — отказ импорта.
 */
class SecuritySettingsMigrationTest {

    /** Как в `FileSecureStore`: дефолтные поля не пишутся. */
    private val storeJson = Json { ignoreUnknownKeys = true }

    /** Как в `AccountTransfer` / `DeviceLinking`: пишутся все поля. */
    private val packageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decodeStored(text: String): SecuritySettings =
        storeJson.decodeFromString(SecuritySettings.serializer(), text).normalized()

    @Test
    fun legacyMessagesOnlyKeepsAllOtherFields() {
        val legacy = """
            {"netTor":"MESSAGES_ONLY","callsMediaOverTor":false,"keysOverSignal":true,
             "contactRequests":"ADDRESS_AND_ALIAS","autoDeleteDays":3,"autoLockMinutes":15,
             "deliverWhileLocked":true,"metaMinimize":"MODERATE","fanoutJitter":"MAXIMUM",
             "coverTraffic":"ON","uiBlockRooted":"WARN","wipeDuress":false}
        """.trimIndent()
        val s = decodeStored(legacy)
        assertEquals(TorMode.ALWAYS, s.netTor)
        assertTrue(s.torEnabled)
        assertTrue(s.keysOverSignal)
        assertEquals(ContactRequestMode.ADDRESS_AND_ALIAS, s.contactRequests)
        assertEquals(3, s.autoDeleteDays)
        assertEquals(15, s.autoLockMinutes)
        assertTrue(s.deliverWhileLocked)
        assertEquals(MetadataMinimization.MODERATE, s.metaMinimize)
        assertEquals(FanoutJitterMode.MAXIMUM, s.fanoutJitter)
        assertEquals(CoverTrafficMode.ON, s.coverTraffic)
        assertEquals(RootedDevicePolicy.WARN, s.uiBlockRooted)
        assertFalse(s.wipeDuress)
    }

    @Test
    fun legacyOffWithoutConfirmationReturnsToTor() {
        val s = decodeStored("""{"netTor":"OFF","callsMediaOverTor":false,"autoDeleteDays":5}""")
        assertEquals(TorMode.ALWAYS, s.netTor)
        assertFalse(s.torOffConfirmed)
        assertEquals(5, s.autoDeleteDays)
    }

    @Test
    fun confirmedOffSurvivesRoundTrip() {
        val off = SecuritySettings.DEFAULT.withTorMode(TorMode.OFF)
        val restored = decodeStored(storeJson.encodeToString(SecuritySettings.serializer(), off))
        assertEquals(TorMode.OFF, restored.netTor)
        assertTrue(restored.torOffConfirmed)
        assertFalse(restored.torEnabled)
    }

    @Test
    fun turningTorBackOnClearsConfirmation() {
        val back = SecuritySettings.DEFAULT.withTorMode(TorMode.OFF).withTorMode(TorMode.ALWAYS)
        assertEquals(SecuritySettings.DEFAULT, back)
        // Рассогласованная пара (подтверждение без OFF) при чтении тоже чинится.
        assertEquals(SecuritySettings.DEFAULT, decodeStored("""{"torOffConfirmed":true}"""))
    }

    @Test
    fun legacyTransferPackageWithMessagesOnlyImports() {
        val legacySettings = legacyFullSettingsJson(netTor = "MESSAGES_ONLY", callsMediaOverTor = false)
        val payload = """
            {"v":1,"createdAtMs":1,"sourceAccountId":"aegis:00","mnemonic":"x y z",
             "securitySettings":$legacySettings}
        """.trimIndent()
        val decoded = packageJson.decodeFromString(TransferPayload.serializer(), payload)
        assertNotNull(decoded.securitySettings)
        val settings = decoded.securitySettings!!.normalized()
        assertEquals(TorMode.ALWAYS, settings.netTor)
        assertEquals(4, settings.autoDeleteDays)
        assertTrue(settings.keysOverSignal)
    }

    @Test
    fun legacyLinkPackageWithMessagesOnlyImports() {
        val legacySettings = legacyFullSettingsJson(netTor = "MESSAGES_ONLY", callsMediaOverTor = true)
        val payload = """
            {"v":1,"accountId":"aegis:00","mnemonic":"x y z","assignedDeviceId":2,
             "securitySettings":$legacySettings}
        """.trimIndent()
        val decoded = packageJson.decodeFromString(LinkPayload.serializer(), payload)
        assertEquals(TorMode.ALWAYS, decoded.securitySettings!!.normalized().netTor)
        assertEquals(2, decoded.assignedDeviceId)
    }

    @Test
    fun newPackageIsReadableByFieldSet() {
        // Новый пакет не содержит callsMediaOverTor, но содержит torOffConfirmed;
        // старый клиент читает его с ignoreUnknownKeys и получает дефолт медиа через Tor.
        val encoded = packageJson.encodeToString(
            TransferPayload.serializer(),
            TransferPayload(securitySettings = SecuritySettings.DEFAULT),
        )
        assertFalse(encoded.contains("callsMediaOverTor"))
        assertTrue(encoded.contains("\"netTor\":\"ALWAYS\""))
    }

    /** Полный объект настроек, как его писал старый клиент с `encodeDefaults = true`. */
    private fun legacyFullSettingsJson(netTor: String, callsMediaOverTor: Boolean): String = """
        {"netTor":"$netTor","keysOverSignal":true,"keysRequireVerify":true,
         "contactRequests":"ADDRESS","contactDeclineNotify":true,"cryptoCascade":true,
         "autoDeleteDays":4,"callsEnabled":true,"callsMediaOverTor":$callsMediaOverTor,
         "autoDownloadMedia":false,"storeHwKeystore":true,"storeBiometricLock":true,
         "autoLockMinutes":5,"deliverWhileLocked":false,"uiFlagSecure":true,
         "uiBlockRooted":"BLOCK","wipeDuress":true,"metaMinimize":"MAXIMUM",
         "fanoutJitter":"AUTO","coverTraffic":"AUTO"}
    """.trimIndent()
}
