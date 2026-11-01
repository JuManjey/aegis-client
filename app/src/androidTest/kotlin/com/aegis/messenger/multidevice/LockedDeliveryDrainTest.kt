package com.aegis.messenger.multidevice

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.model.Contact
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.LockedInboxStore
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.account.AccountCreationResult
import com.aegis.messenger.messaging.RelayReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Разбор очереди, накопленной при закрытом аккаунте: после входа ящик заново
 * сопоставляется с контактом через PSK, конверты идут тем же путём, что живые,
 * в порядке поступления, звонок — со временем прихода, а конверты исчезнувших
 * ящиков отбрасываются. Очередь после разбора пуста.
 */
@RunWith(AndroidJUnit4::class)
class LockedDeliveryDrainTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dev: MdTestDevice

    @Before
    fun setUp() {
        assertTrue("Нативное ядро должно быть доступно", CryptoModule.isNativeReady)
        dev = MdTestDevice(ctx, "locked-drain")
    }

    @After
    fun tearDown() {
        dev.cleanup()
    }

    @Test
    fun queuedEnvelopes_areDispatchedToTheirContact_inOrder_afterUnlock() = runBlocking {
        val created = dev.accountManager.createAccount(AccountCreationMode.MANUAL, "alice", "password123")
        assertTrue("createAccount: $created", created is AccountCreationResult.Success)
        val bob = StoredContact(contact = Contact(id = BOB_ID, nickname = "Bob"))
        dev.database.contactStore.upsert(bob)
        val psk = CryptoModule.psk.generate()
        dev.pskStore.put(
            PskRecord(
                contactId = BOB_ID,
                pskB64 = CryptoModule.psk.toBase64(psk),
                source = PskSource.GENERATED,
                pskFingerprintHex = CryptoModule.psk.fingerprintHex(psk),
            ),
        )
        psk.fill(0)
        val bobInbox = dev.relayGateway.conversationInboxesForMe(bob, listOf(1)).single()

        val store = LockedInboxStore(storeContext(), dev.keyStore)
        store.enqueue(bobInbox, MESSAGE, receivedAtMs = 1_000)
        store.enqueue("unknown-inbox", MESSAGE, receivedAtMs = 2_000)
        store.enqueue(bobInbox, CALL, receivedAtMs = 3_000)

        val events = mutableListOf<String>()
        val receiver = RelayReceiver(
            gateway = dev.relayGateway,
            contactsProvider = { dev.database.contactStore.all() },
            onMessage = { contact, json, _ -> events += "message:${contact.contact.id}:$json" },
            onCall = { contact, json, receivedAtMs -> events += "call:${contact.contact.id}:$json:$receivedAtMs" },
            onControl = { _, _ -> events += "control" },
            enabledProvider = { false },
            scope = dev.scope,
            lockedInbox = store,
        )
        receiver.drainLockedInbox()

        assertEquals(listOf("message:$BOB_ID:$MESSAGE", "call:$BOB_ID:$CALL:3000"), events)
        assertTrue("очередь разобрана", store.pending().isEmpty())
    }

    private fun storeContext(): Context {
        val dir = File(dev.dir, "locked").apply { mkdirs() }
        return object : ContextWrapper(ctx.applicationContext) {
            override fun getFilesDir(): File = dir
            override fun getApplicationContext(): Context = this
        }
    }

    private companion object {
        const val BOB_ID = "aegis:bob"
        const val MESSAGE = """{"channel":"message","type":"Whisper","ciphertextB64":"AAAA"}"""
        const val CALL = """{"channel":"call","type":"Whisper","ciphertextB64":"BBBB"}"""
    }
}
