package com.aegis.messenger.multidevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.device.LinkedDevice
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.messaging.SelfSyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка self-sync-отзыва устройства (мультидевайс §10) ПОСЛЕ follow-up
 * №2.3: конверт `revoke` идёт двойным конвейером слой A (каскад на `selfSyncSecret`)
 * → слой B (per-device Signal-сессия между МОИМИ устройствами, forward secrecy,
 * MD-1). Первичное устройство (deviceId=1) отзывает устройство у планшета
 * (deviceId=2, получатель под тестом); тот обязан пометить отозванное
 * `revoked=true` в СВОЁМ реестре ([com.aegis.core.common.device.DeviceSettings.linkedDevices]),
 * чтобы прекратить fan-out/анонс на него.
 *
 * Оба устройства — один аккаунт (одна мнемоника → один `accountId`), с разными
 * Signal-identity. Конверт строится ровно как `SelfSyncManager.fanOut`; сеть не
 * задействуем — подаём его в продуктовый [SelfSyncManager.onWire].
 */
@RunWith(AndroidJUnit4::class)
class SelfSyncRevokeTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()

    @Before
    fun ready() {
        assertTrue("Нативное ядро должно быть доступно", CryptoModule.isNativeReady)
    }

    @After
    fun tearDown() {
        devices.forEach { it.cleanup() }
        devices.clear()
    }

    @Test
    fun incomingRevoke_marksDeviceRevoked_createsTombstone_idempotent_selfIgnored() = runBlocking {
        val primary = MdTestDevice(ctx, "rv-primary").also { devices += it }
        val tablet = MdTestDevice(ctx, "rv-tablet").also { devices += it }

        val mnemonic = SelfSyncTestSupport.primary(primary)
        // Планшет (id=2) знает активное устройство 3, которое сейчас отзовёт primary.
        SelfSyncTestSupport.linked(
            tablet, mnemonic, deviceId = 2, label = "Tablet",
            registry = listOf(LinkedDevice(deviceId = 3, label = "Phone3", addedAtMs = 1L)),
        )
        val accountId = SelfSyncTestSupport.accountId(primary)
        val secret = SelfSyncTestSupport.selfSyncSecret(mnemonic)
        val tabletBundle = SelfSyncTestSupport.bundleOf(tablet)

        fun revokeWire(revokedDevice: Int): String = SelfSyncTestSupport.buildWire(
            sender = primary, accountId = accountId, senderDevice = 1,
            targetDeviceId = 2, targetBundle = tabletBundle, secret = secret,
            kind = SelfSyncManager.KIND_REVOKE,
            contentJson = SelfSyncTestSupport.revokeContent(revokedDevice),
        )

        // 1) Отзыв устройства 3 первичным (sender=1) → помечаем 3 revoked=true.
        assertTrue(tablet.selfSyncManager.onWire(revokeWire(3)))
        val d3 = tablet.secureStore.loadDeviceSettings().linkedDevices.first { it.deviceId == 3 }
        assertTrue("устройство 3 должно стать отозванным", d3.revoked)
        assertEquals("метка не должна теряться", "Phone3", d3.label)
        assertTrue(
            "отозванное устройство исключается из адресатов self-sync",
            tablet.secureStore.loadDeviceSettings().activeLinkedDevices().none { it.deviceId == 3 },
        )

        // 2) Повторный отзыв 3 новым шифртекстом → идемпотентно (без дублей).
        assertTrue(tablet.selfSyncManager.onWire(revokeWire(3)))
        assertEquals(
            "устройство 3 не должно дублироваться", 1,
            tablet.secureStore.loadDeviceSettings().linkedDevices.count { it.deviceId == 3 },
        )

        // 3) Отзыв ещё не известного устройства 4 → tombstone revoked=true
        //    (поздний device-announce его не «воскресит»).
        assertTrue(tablet.selfSyncManager.onWire(revokeWire(4)))
        val d4 = tablet.secureStore.loadDeviceSettings().linkedDevices.firstOrNull { it.deviceId == 4 }
        assertNotNull("tombstone для неизвестного устройства должен появиться", d4)
        assertTrue("tombstone должен быть revoked", d4!!.revoked)

        // 4) Отзыв самого себя (deviceId=2 == локальный) → распознан, но записи о
        //    себе в реестре не появляется (авто-crypto-shred осознанно не делаем, §10).
        assertTrue(tablet.selfSyncManager.onWire(revokeWire(2)))
        assertNull(
            "запись о самом себе не должна создаваться",
            tablet.secureStore.loadDeviceSettings().linkedDevices.firstOrNull { it.deviceId == 2 },
        )

        secret.fill(0)
    }

    @Test
    fun revoke_isInert_whenMultiDeviceDisabled() = runBlocking {
        val tablet = MdTestDevice(ctx, "rv-inert").also { devices += it }
        val mnemonic = SelfSyncTestSupport.primary(tablet, login = "user")
        SelfSyncTestSupport.linked(
            tablet, mnemonic, deviceId = 2, label = "Tablet",
            registry = listOf(LinkedDevice(deviceId = 3, label = "Phone3", addedAtMs = 1L)),
        )
        // Выключаем мультидевайс (§8) — self-sync обязан стать инертным.
        assertTrue(tablet.accountManager.setMultiDeviceAllowed(false))

        val env =
            """{"channel":"self-sync","kind":"revoke","senderDevice":1,"ciphertextB64":"AAAA","sigType":"PreKey"}"""
        assertFalse("onWire должен игнорировать конверт", tablet.selfSyncManager.onWire(env))
        assertFalse(
            "реестр не должен меняться при выключенном мультидевайсе",
            tablet.secureStore.loadDeviceSettings().linkedDevices.first { it.deviceId == 3 }.revoked,
        )
    }
}
