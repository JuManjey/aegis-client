package com.aegis.messenger.multidevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.model.Contact
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StoredContact
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка реестра устройств контакта (`peer_devices`, мультидевайс §5/§10):
 * device-announce регистрирует ДОПОЛНИТЕЛЬНОЕ устройство собеседника (непроверенным —
 * повод для UX-баннера «новое устройство»), попадающее в набор fan-out
 * ([StoredContact.activeDevices]); device-revoke убирает его из доставки; анонс
 * первичного (`deviceId=1`) игнорируется (оно уже в колонках контакта).
 */
@RunWith(AndroidJUnit4::class)
class PeerDeviceRegistryTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()
    private val bobId = "aegis:bob"

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
    fun announceRegistersExtraDevice_revokeRemovesFromFanout() = runBlocking {
        val dev = MdTestDevice(ctx, "registry").also { devices += it }
        dev.openStorageWithoutAccount()

        // Контакт с известным первичным устройством (deviceId=1).
        dev.database.contactStore.upsert(
            StoredContact(
                contact = Contact(id = bobId, nickname = "Bob"),
                remoteIdentityB64 = "bob-identity",
                remoteDevice = 1,
                bundleJson = "{\"bundle\":\"bob-1\"}",
            ),
        )
        val stored = dev.database.contactStore.get(bobId)!!

        // Анонс первичного (deviceId=1) — игнорируется (уже в колонках контакта).
        dev.deviceAnnouncer.onAnnounce(
            stored,
            ControlMessage(type = ControlMessage.TYPE_DEVICE_ANNOUNCE, deviceId = 1),
        )
        assertTrue("анонс первичного не должен писать в peer_devices",
            dev.database.peerDeviceStore.listFor(bobId).isEmpty())

        // Анонс дополнительного устройства (deviceId=2).
        dev.deviceAnnouncer.onAnnounce(
            stored,
            ControlMessage(
                type = ControlMessage.TYPE_DEVICE_ANNOUNCE,
                deviceId = 2,
                deviceIdentityB64 = "bob-2-identity",
                deviceBundleJson = "{\"bundle\":\"bob-2\"}",
                deviceLabel = "Bob Tablet",
            ),
        )

        val afterAnnounce = dev.database.peerDeviceStore.listFor(bobId)
        assertEquals(1, afterAnnounce.size)
        assertEquals(2, afterAnnounce[0].deviceId)
        assertFalse("новое устройство должно быть непроверенным (баннер)", afterAnnounce[0].verified)
        assertFalse(afterAnnounce[0].revoked)

        // Fan-out видит оба устройства (1 и 2).
        val active = dev.database.contactStore.get(bobId)!!.activeDevices().map { it.deviceId }.toSet()
        assertEquals(setOf(1, 2), active)

        // Отзыв устройства 2 → выпадает из fan-out, но остаётся в реестре как revoked.
        dev.deviceAnnouncer.onRevoke(
            stored,
            ControlMessage(type = ControlMessage.TYPE_DEVICE_REVOKE, deviceId = 2),
        )
        val activeAfterRevoke =
            dev.database.contactStore.get(bobId)!!.activeDevices().map { it.deviceId }.toSet()
        assertEquals("отозванное устройство не должно участвовать в доставке", setOf(1), activeAfterRevoke)

        val revokedEntry = dev.database.peerDeviceStore.listFor(bobId).first { it.deviceId == 2 }
        assertTrue(revokedEntry.revoked)
    }
}
