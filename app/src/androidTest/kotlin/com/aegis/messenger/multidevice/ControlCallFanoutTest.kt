package com.aegis.messenger.multidevice

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.call.CallControl
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.model.Contact
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.account.AccountCreationResult
import com.aegis.messenger.psk.PrekeyBundle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка перевода control-канала (§14.2) и сигналинга звонков (§7.5) на
 * per-device подканал v2 (мультидевайс §7.1.1, `docs/multidevice.md` §4.1/§6,
 * follow-up №2.2). До этого control/звонки шли в общий v1-mailbox (роль 0/1) с
 * одним ratchet первичного устройства — при нескольких устройствах возникала
 * гонка деструктивного ack. Теперь они fan-out'ятся в per-device conversation-
 * mailbox КАЖДОГО устройства (свой ratchet, MD-1; свой ящик ⇒ свой ack).
 *
 * Крипта настоящая (Argon2id, каскад слоя A, libsignal per-device X3DH/ratchet,
 * HKDF mailbox v2), два устройства-аккаунта (Alice/Bob) поднимаются в одном
 * процессе ([MdTestDevice]); сеть не задействуем — конверт формируем ровно так,
 * как это делает `send`, и подаём в продуктовый `onWire` (как сделал бы приёмный
 * цикл релея после демультиплексирования по `channel`).
 */
@RunWith(AndroidJUnit4::class)
class ControlCallFanoutTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun ready() {
        assertTrue("Нативное ядро должно быть доступно", CryptoModule.isNativeReady)
    }

    @After
    fun tearDown() {
        devices.forEach { it.cleanup() }
        devices.clear()
    }

    /** Пара «Alice знает Bob / Bob знает Alice» + общий PSK; возвращает контакты. */
    private data class Link(
        val bobId: String,
        val aliceId: String,
        val bobBundle: String,
        val bobOnAlice: StoredContact,
        val aliceOnBob: StoredContact,
    )

    private suspend fun createAccount(dev: MdTestDevice, login: String) {
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = login,
            password = "password123",
            multiDeviceAllowed = true,
        )
        assertTrue("createAccount($login): $created", created is AccountCreationResult.Success)
        dev.reloadDeviceSettings()
    }

    private suspend fun link(alice: MdTestDevice, bob: MdTestDevice): Link {
        val aliceId = alice.accountManager.currentIdentity()!!.accountId
        val bobId = bob.accountManager.currentIdentity()!!.accountId
        val aliceIdentity = alice.signalSessionManager.identityKeyB64()
        val bobIdentity = bob.signalSessionManager.identityKeyB64()
        val bobBundle = bob.signalSessionManager.publishBundleJson()

        // Общий PSK (обменян вручную вне сети, §6.2): одинаковые байты на обеих
        // сторонах, хранится под id собеседника.
        val pskBytes = CryptoModule.psk.generate()
        val pskB64 = CryptoModule.psk.toBase64(pskBytes)
        val fp = CryptoModule.psk.fingerprintHex(pskBytes)
        pskBytes.fill(0)
        alice.pskStore.put(
            PskRecord(contactId = bobId, pskB64 = pskB64, source = PskSource.GENERATED, pskFingerprintHex = fp, label = "Bob"),
        )
        bob.pskStore.put(
            PskRecord(contactId = aliceId, pskB64 = pskB64, source = PskSource.GENERATED, pskFingerprintHex = fp, label = "Alice"),
        )

        alice.database.contactStore.upsert(
            StoredContact(
                contact = Contact(id = bobId, nickname = "Bob"),
                remoteIdentityB64 = bobIdentity,
                remoteDevice = 1,
                bundleJson = bobBundle,
            ),
        )
        bob.database.contactStore.upsert(
            StoredContact(
                contact = Contact(id = aliceId, nickname = "Alice"),
                remoteIdentityB64 = aliceIdentity,
                remoteDevice = 1,
                bundleJson = null,
            ),
        )
        return Link(
            bobId = bobId,
            aliceId = aliceId,
            bobBundle = bobBundle,
            bobOnAlice = alice.database.contactStore.get(bobId)!!,
            aliceOnBob = bob.database.contactStore.get(aliceId)!!,
        )
    }

    /** Шифрует control ровно как ControlChannel.send: слой A(PSK)→слой B(Signal). */
    private suspend fun sealControl(
        alice: MdTestDevice, bobId: String, bobBundle: String, msg: ControlMessage,
    ): Pair<String, String> {
        val plaintext = json.encodeToString(ControlMessage.serializer(), msg).toByteArray(Charsets.UTF_8)
        val inner = alice.pskManager.cipherFor(bobId)!!.encrypt(plaintext)
        val env = alice.signalSessionManager.encryptTo(bobId, 1, bobBundle, inner, PrekeyBundle.identityB64(bobBundle))
        return env.type to Base64.encodeToString(env.ciphertext, Base64.NO_WRAP)
    }

    private fun controlWire(type: String, ciphertextB64: String, senderDevice: Int): String =
        """{"type":"$type","layerA":true,"ciphertextB64":"$ciphertextB64","senderDevice":$senderDevice,"channel":"control"}"""

    @Test
    fun control_perDeviceRoundTrip_and_senderDeviceRouting() = runBlocking {
        val alice = MdTestDevice(ctx, "cc-alice").also { devices += it }
        val bob = MdTestDevice(ctx, "cc-bob").also { devices += it }
        createAccount(alice, "alice")
        createAccount(bob, "bob")
        val l = link(alice, bob)

        // 1) Первое control-сообщение (PreKey) → Bob расшифровывает по senderDevice=1.
        val read = ControlMessage(type = ControlMessage.TYPE_READ, messageIds = listOf("m-a", "m-b"), atMs = 1000)
        val (type1, ct1) = sealControl(alice, l.bobId, l.bobBundle, read)
        val recovered = bob.controlChannel.onWire(l.aliceOnBob, controlWire(type1, ct1, senderDevice = 1))
        assertEquals(ControlMessage.TYPE_READ, recovered.type)
        assertEquals("control должен доехать в целости (слой A+B, per-device)", listOf("m-a", "m-b"), recovered.messageIds)

        // 2) Второе сообщение (уже Whisper): один и тот же шифртекст.
        val typing = ControlMessage(type = ControlMessage.TYPE_TYPING, typing = true, atMs = 2000)
        val (type2, ct2) = sealControl(alice, l.bobId, l.bobBundle, typing)

        // 2a) Подача с ЧУЖИМ senderDevice=2 → нет ratchet устройства 2 ⇒ провал
        //     (onWire маршрутизирует по senderDevice, а не по contact.remoteDevice).
        var routedBySender = false
        try {
            bob.controlChannel.onWire(l.aliceOnBob, controlWire(type2, ct2, senderDevice = 2))
        } catch (_: Exception) {
            routedBySender = true
        }
        assertTrue("onWire обязан выбирать ratchet по senderDevice (чужой id → провал)", routedBySender)

        // 2b) Тот же шифртекст с верным senderDevice=1 → расшифровывается (провал
        //     по device 2 не тронул состояние ratchet устройства 1).
        val recovered2 = bob.controlChannel.onWire(l.aliceOnBob, controlWire(type2, ct2, senderDevice = 1))
        assertEquals(ControlMessage.TYPE_TYPING, recovered2.type)
        assertTrue(recovered2.typing)
    }

    @Test
    fun call_perDeviceRoundTrip() = runBlocking {
        val alice = MdTestDevice(ctx, "call-alice").also { devices += it }
        val bob = MdTestDevice(ctx, "call-bob").also { devices += it }
        createAccount(alice, "alice")
        createAccount(bob, "bob")
        val l = link(alice, bob)

        // offer с корнем ключей звонка (§7.5) — тем же per-device конвейером.
        val offer = CallControl(
            type = CallControl.TYPE_OFFER,
            callId = "call-1",
            media = CallMedia.AUDIO,
            callSecretB64 = Base64.encodeToString(ByteArray(32) { it.toByte() }, Base64.NO_WRAP),
            sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0",
        )
        val plaintext = json.encodeToString(CallControl.serializer(), offer).toByteArray(Charsets.UTF_8)
        val inner = alice.pskManager.cipherFor(l.bobId)!!.encrypt(plaintext)
        val env = alice.signalSessionManager.encryptTo(
            l.bobId, 1, l.bobBundle, inner, PrekeyBundle.identityB64(l.bobBundle),
        )
        val wire = """{"type":"${env.type}","layerA":true,"ciphertextB64":"${
            Base64.encodeToString(env.ciphertext, Base64.NO_WRAP)
        }","senderDevice":1,"channel":"call"}"""

        val recovered = bob.callSignaling.onWire(l.aliceOnBob, wire)
        assertEquals(CallControl.TYPE_OFFER, recovered.type)
        assertEquals("call-1", recovered.callId)
        assertEquals("SDP звонка должен доехать (слой A+B, per-device)", offer.sdp, recovered.sdp)
        assertEquals("корень ключей звонка не должен исказиться", offer.callSecretB64, recovered.callSecretB64)
    }

    @Test
    fun controlAndCall_targetPeerDeviceConversationMailbox() = runBlocking {
        val alice = MdTestDevice(ctx, "addr-alice").also { devices += it }
        val bob = MdTestDevice(ctx, "addr-bob").also { devices += it }
        createAccount(alice, "alice")
        createAccount(bob, "bob")
        val l = link(alice, bob)

        // Куда Alice постит control/звонки (RelayGateway.postToPeerDevices →
        // convMailboxFor(contactId, ownerAccount=contact.id, deviceId)).
        val aliceTargetsBobDev1 = alice.pskManager.convMailboxFor(l.bobId, l.bobId, 1)
        // Что реально слушает Bob на СВОЁМ устройстве 1 (RelayReceiver).
        val bobListensDev1 = bob.relayGateway.conversationInboxesForMe(l.aliceOnBob, listOf(1)).firstOrNull()

        assertEquals(
            "control/звонки должны попадать в тот же per-device conversation-mailbox, что слушает Bob (нет v1 роли 0/1, нет гонки ack)",
            bobListensDev1, aliceTargetsBobDev1,
        )

        // Разные устройства — разные подканалы (изоляция ack по устройству).
        val aliceTargetsBobDev2 = alice.pskManager.convMailboxFor(l.bobId, l.bobId, 2)
        assertNotEquals("per-device подканалы разных устройств должны различаться", aliceTargetsBobDev1, aliceTargetsBobDev2)
    }
}
