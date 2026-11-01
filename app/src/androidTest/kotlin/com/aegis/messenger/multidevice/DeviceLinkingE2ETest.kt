package com.aegis.messenger.multidevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.model.Contact
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.account.AccountCreationResult
import com.aegis.messenger.account.LinkApplyResult
import com.aegis.messenger.account.LinkBuildResult
import com.aegis.messenger.account.LinkOpenResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка device-linking (мультидевайс §7.1.1, `docs/multidevice.md` §5) на
 * реальном рантайме: первичное устройство собирает пакет линковки, чистое —
 * распечатывает и применяет. Проверяем выдачу `deviceId=2`, разворот контактов/PSK,
 * защиту паролем и enforcement `multiDeviceAllowed` (§8). Реальные Argon2id + каскад
 * слоя A + SQLCipher; сеть/Tor не задействованы (линковка идёт вне сети).
 */
@RunWith(AndroidJUnit4::class)
class DeviceLinkingE2ETest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()

    private fun device(name: String) = MdTestDevice(ctx, name).also { devices += it }

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
    fun linkAssignsDeviceId2_transfersContactsAndPsk() = runBlocking {
        val password = "linkpass1"

        // --- Первичное устройство (deviceId=1) с разрешённым мультидевайсом ---
        val primary = device("primary")
        val created = primary.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "alice",
            password = "password123",
            multiDeviceAllowed = true,
        )
        assertTrue(created is AccountCreationResult.Success)
        primary.reloadDeviceSettings()

        // Один контакт + его PSK (публичные данные, которые уедут в пакете).
        val bobId = "aegis:bob"
        primary.database.contactStore.upsert(
            StoredContact(
                contact = Contact(id = bobId, nickname = "Bob"),
                remoteIdentityB64 = "bob-identity",
                remoteDevice = 1,
                bundleJson = "{\"bundle\":\"bob\"}",
            ),
        )
        val pskBytes = CryptoModule.psk.generate()
        val pskB64 = CryptoModule.psk.toBase64(pskBytes)
        primary.pskStore.put(
            PskRecord(
                contactId = bobId,
                pskB64 = pskB64,
                source = PskSource.GENERATED,
                pskFingerprintHex = CryptoModule.psk.fingerprintHex(pskBytes),
                label = "Bob",
            ),
        )
        pskBytes.fill(0)

        // --- Сборка пакета линковки: должен выдать deviceId=2 ---
        val build = primary.deviceLinking.buildEnvelope(
            password = password,
            newDeviceLabel = "Tablet",
        )
        assertTrue("buildEnvelope: $build", build is LinkBuildResult.Success)
        val envelopeJson = (build as LinkBuildResult.Success).envelopeJson
        assertEquals(2, build.summary.assignedDeviceId)
        assertEquals(1, build.summary.contactCount)
        assertEquals(1, build.summary.pskCount)

        // Первичное зарегистрировало новое устройство и подвинуло счётчик id.
        val primaryDev = primary.secureStore.loadDeviceSettings()
        assertEquals(3, primaryDev.nextAssignableDeviceId)
        assertTrue(primaryDev.linkedDevices.any { it.deviceId == 2 })

        // --- Чистое устройство: неверный пароль отвергается ---
        val secondary = device("secondary")
        val wrong = secondary.deviceLinking.openEnvelope(envelopeJson, "totally-wrong")
        assertTrue("wrong password должен быть отвергнут: $wrong", wrong is LinkOpenResult.WrongPassword)

        // --- Верный пароль: распечатать и применить ---
        val open = secondary.deviceLinking.openEnvelope(envelopeJson, password)
        assertTrue("openEnvelope: $open", open is LinkOpenResult.Success)
        val payload = (open as LinkOpenResult.Success).payload
        assertEquals(2, payload.assignedDeviceId)

        val applied = secondary.deviceLinking.apply(payload, password)
        assertTrue("apply: $applied", applied is LinkApplyResult.Success)

        // Идентичность нового устройства = deviceId 2; развернулись контакт и PSK.
        val secDev = secondary.secureStore.loadDeviceSettings()
        assertEquals(2, secDev.localDeviceId)
        assertTrue(secDev.linkedDevices.any { it.deviceId == 1 })

        assertNotNull("контакт должен развернуться", secondary.database.contactStore.get(bobId))
        assertNotNull("PSK должен развернуться", secondary.pskStore.getPskB64(bobId))

        val record = secondary.accountStore.load()
        assertNotNull("аккаунт должен создаться из мнемоники", record)
        assertTrue("мультидевайс включён на линкованном", record!!.profile.multiDeviceAllowed)
    }

    @Test
    fun buildEnvelope_disabledMultiDevice_isRefused() = runBlocking {
        val device = device("no-md")
        val created = device.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "carol",
            password = "password123",
            multiDeviceAllowed = false,
        )
        assertTrue(created is AccountCreationResult.Success)
        device.reloadDeviceSettings()

        val build = device.deviceLinking.buildEnvelope(password = "linkpass1", newDeviceLabel = "Tablet")
        assertTrue("линковка при выключенном мультидевайсе должна быть инертна: $build",
            build is LinkBuildResult.Failure)
    }
}
