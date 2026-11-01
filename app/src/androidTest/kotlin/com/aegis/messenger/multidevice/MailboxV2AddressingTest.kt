package com.aegis.messenger.multidevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.PskCrypto
import com.aegis.messenger.account.AccountCreationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Живая проверка криптографической адресации mailbox v2 (§1.32,
 * `docs/multidevice.md` §4) на реальном нативном ядре: детерминизм self-sync
 * секрета, per-device раздельность подканалов, разделение доменов
 * (SELF_SYNC vs CONVERSATION) и scope (accountId владельца), а также соответствие
 * прикладной обёртки [com.aegis.messenger.psk.PskManager.convMailboxFor] низкоуровневому
 * примитиву. Все id — производные HKDF; секреты в сеть не уходят (§7.6).
 */
@RunWith(AndroidJUnit4::class)
class MailboxV2AddressingTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val devices = mutableListOf<MdTestDevice>()
    private val psk get() = CryptoModule.psk

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
    fun selfSyncSecret_isDeterministic_perDeviceMailboxesDiffer() = runBlocking {
        val dev = MdTestDevice(ctx, "addr").also { devices += it }
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "user",
            password = "password123",
            multiDeviceAllowed = true,
        )
        assertTrue(created is AccountCreationResult.Success)
        val mnemonic = (created as AccountCreationResult.Success).mnemonic
        val accountId = dev.accountManager.currentIdentity()!!.accountId

        // Секрет self-sync детерминирован из мнемоники → одинаков на всех устройствах.
        val secret1 = psk.deriveSelfSyncSecret(mnemonic)
        val secret2 = psk.deriveSelfSyncSecret(mnemonic)
        assertNotNull(secret1)
        assertNotNull(secret2)
        assertTrue("self-sync секрет должен быть детерминирован",
            secret1!!.contentEquals(secret2!!))

        // Per-device подканалы self-sync различны, но детерминированы.
        val d1 = psk.mailboxIdDevice(secret1, PskCrypto.MailboxKind.SELF_SYNC, accountId, 1)
        val d2 = psk.mailboxIdDevice(secret1, PskCrypto.MailboxKind.SELF_SYNC, accountId, 2)
        val d1again = psk.mailboxIdDevice(secret1, PskCrypto.MailboxKind.SELF_SYNC, accountId, 1)
        assertNotEquals("устройства 1 и 2 должны иметь разные self-sync ящики", d1, d2)
        assertEquals("адресация должна быть детерминирована", d1, d1again)

        secret1.fill(0)
        secret2.fill(0)
    }

    @Test
    fun domainAndScopeSeparation_holds() = runBlocking {
        val dev = MdTestDevice(ctx, "sep").also { devices += it }
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "user",
            password = "password123",
            multiDeviceAllowed = true,
        )
        assertTrue(created is AccountCreationResult.Success)
        val mnemonic = (created as AccountCreationResult.Success).mnemonic
        val accountId = dev.accountManager.currentIdentity()!!.accountId

        val selfSecret = psk.deriveSelfSyncSecret(mnemonic)!!
        val convSecret = psk.generate() // роль PSK переписки

        // Домен: тот же device/scope, но SELF_SYNC (self-secret) ≠ CONVERSATION (PSK).
        val selfMbox = psk.mailboxIdDevice(selfSecret, PskCrypto.MailboxKind.SELF_SYNC, accountId, 1)
        val convMbox = psk.mailboxIdDevice(convSecret, PskCrypto.MailboxKind.CONVERSATION, accountId, 1)
        assertNotEquals("домены SELF_SYNC и CONVERSATION не должны пересекаться", selfMbox, convMbox)

        // Scope: тот же PSK и device, но разный владелец (accountId) → разные ящики.
        val otherAccount = "aegis:00112233445566778899aabbccddeeff"
        val convMine = psk.mailboxIdDevice(convSecret, PskCrypto.MailboxKind.CONVERSATION, accountId, 1)
        val convPeer = psk.mailboxIdDevice(convSecret, PskCrypto.MailboxKind.CONVERSATION, otherAccount, 1)
        assertNotEquals("ящики разных владельцев (scope) должны различаться", convMine, convPeer)

        // Прикладная обёртка PskManager.convMailboxFor должна совпадать с примитивом.
        val contactId = "aegis:peer"
        dev.pskStore.put(
            PskRecord(
                contactId = contactId,
                pskB64 = psk.toBase64(convSecret),
                source = PskSource.GENERATED,
                pskFingerprintHex = psk.fingerprintHex(convSecret),
                label = "peer",
            ),
        )
        val viaManagerD1 = dev.pskManager.convMailboxFor(contactId, accountId, 1)
        val viaManagerD2 = dev.pskManager.convMailboxFor(contactId, accountId, 2)
        assertEquals("обёртка должна давать тот же id, что примитив", convMine, viaManagerD1)
        assertNotEquals("per-device подканалы переписки должны различаться", viaManagerD1, viaManagerD2)

        selfSecret.fill(0)
        convSecret.fill(0)
    }
}
