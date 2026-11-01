package com.aegis.messenger.multidevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.model.MessageDirection
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.messaging.SelfSyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка self-sync копий сообщений (мультидевайс §6) на РЕАЛЬНОМ рантайме
 * ПОСЛЕ follow-up №2.3: копия едет двойным конвейером слой A (каскад на
 * `selfSyncSecret`) → слой B (per-device Signal-сессия между МОИМИ устройствами,
 * forward secrecy, MD-1). Первичное устройство (deviceId=1) шлёт копию входящего
 * планшету (deviceId=2); тот обязан отразить её в своём хранилище, дедуплицировать
 * по `msgId`, пропускать собственное эхо и быть ПОЛНОСТЬЮ инертным при выключенном
 * `multiDeviceAllowed` (§8).
 *
 * Оба устройства — один аккаунт (одна мнемоника → один `accountId`), но с разными
 * Signal-identity. Конверт строится ровно как `SelfSyncManager.fanOut` (реальный
 * каскад + libsignal X3DH/ratchet), сеть не задействуем — подаём его напрямую в
 * продуктовый [SelfSyncManager.onWire], как это сделал бы приёмный цикл релея.
 */
@RunWith(AndroidJUnit4::class)
class SelfSyncCopyTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()
    private val contactId = "aegis:bob"

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
    fun incomingCopy_isStored_dedupedByMsgId_andSelfEchoSkipped() = runBlocking {
        val primary = MdTestDevice(ctx, "sc-primary").also { devices += it }
        val tablet = MdTestDevice(ctx, "sc-tablet").also { devices += it }

        // Один аккаунт на двух устройствах: primary=1 (отправитель копий), tablet=2
        // (получатель под тестом). У каждого — свой Signal identity (MD-1).
        val mnemonic = SelfSyncTestSupport.primary(primary)
        SelfSyncTestSupport.linked(tablet, mnemonic, deviceId = 2, label = "Tablet")
        val accountId = SelfSyncTestSupport.accountId(primary)
        val secret = SelfSyncTestSupport.selfSyncSecret(mnemonic)
        // Бандл планшета — чтобы первичное могло поднять к нему self-sync Signal-сессию.
        val tabletBundle = SelfSyncTestSupport.bundleOf(tablet)

        // 1) Копия входящего от устройства 1 → отражается в хранилище планшета
        //    (расшифровка слой B (Signal) → слой A (каскад)).
        val env1 = SelfSyncTestSupport.buildWire(
            sender = primary, accountId = accountId, senderDevice = 1,
            targetDeviceId = 2, targetBundle = tabletBundle, secret = secret,
            kind = SelfSyncManager.KIND_MSG,
            contentJson = SelfSyncTestSupport.msgContent(contactId, "m-1", "hello from primary"),
        )
        assertTrue(tablet.selfSyncManager.onWire(env1))
        val msgs = tablet.database.messageStore.messagesFor(contactId)
        assertEquals("копия должна сохраниться", 1, msgs.size)
        assertEquals("hello from primary", msgs[0].body)
        assertEquals(MessageDirection.INCOMING, msgs[0].direction)

        // 2) Тот же msgId, но НОВЫЙ шифртекст (следующий Signal-конверт, Whisper) →
        //    дедуп по msgId (ON CONFLICT id), запись не удваивается.
        val env1b = SelfSyncTestSupport.buildWire(
            sender = primary, accountId = accountId, senderDevice = 1,
            targetDeviceId = 2, targetBundle = tabletBundle, secret = secret,
            kind = SelfSyncManager.KIND_MSG,
            contentJson = SelfSyncTestSupport.msgContent(contactId, "m-1", "hello from primary"),
        )
        assertTrue(tablet.selfSyncManager.onWire(env1b))
        assertEquals("дедуп по msgId", 1, tablet.database.messageStore.messagesFor(contactId).size)

        // 3) Собственное эхо (senderDevice == мой id=2) → распознано, но не
        //    сохраняется (early-return ещё до расшифровки).
        val echo =
            """{"channel":"self-sync","kind":"msg","senderDevice":2,"ciphertextB64":"AAAA","sigType":"Whisper"}"""
        assertTrue(tablet.selfSyncManager.onWire(echo))
        assertEquals(
            "своё эхо не должно порождать запись", 1,
            tablet.database.messageStore.messagesFor(contactId).size,
        )

        secret.fill(0)
    }

    @Test
    fun selfSync_isInert_whenMultiDeviceDisabled() = runBlocking {
        val tablet = MdTestDevice(ctx, "sc-inert").also { devices += it }
        val mnemonic = SelfSyncTestSupport.primary(tablet, login = "user")
        // Делаем это устройство линкованным (id=2) и выключаем мультидевайс (§8).
        SelfSyncTestSupport.linked(tablet, mnemonic, deviceId = 2, label = "Tablet")
        assertTrue(tablet.accountManager.setMultiDeviceAllowed(false))

        assertNull(
            "self-sync inbox недоступен при выключенном мультидевайсе",
            tablet.selfSyncManager.myInbox(),
        )
        assertFalse(
            "isAvailable=false при выключенном мультидевайсе",
            tablet.selfSyncManager.isAvailable,
        )

        // Любой конверт (даже структурно валидный) должен игнорироваться до крипты.
        val env =
            """{"channel":"self-sync","kind":"msg","senderDevice":1,"ciphertextB64":"AAAA","sigType":"PreKey"}"""
        assertFalse("onWire должен игнорировать конверт", tablet.selfSyncManager.onWire(env))
        assertEquals(
            "ничего не должно сохраниться", 0,
            tablet.database.messageStore.messagesFor(contactId).size,
        )
    }
}
