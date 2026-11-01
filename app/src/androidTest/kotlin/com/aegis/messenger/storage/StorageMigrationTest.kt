package com.aegis.messenger.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.model.Contact
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.AegisDatabase
import com.aegis.data.storage.AndroidKeyStoreManager
import com.aegis.data.storage.FilePskStore
import com.aegis.data.storage.StorageLockedException
import com.aegis.data.storage.StoragePurpose
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.security.AccountStorageKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Хранилища прежнего формата (ключ БД и файл PSK только под Keystore) после
 * обновления: при закрытом аккаунте нечитаемы, при открытом переносятся под ключ
 * аккаунта без потери данных, а чужим ключом не открываются и не затираются.
 */
@RunWith(AndroidJUnit4::class)
class StorageMigrationTest {

    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(base.filesDir, "storage-migration").apply {
        deleteRecursively()
        mkdirs()
    }
    private val ctx: Context = object : ContextWrapper(base.applicationContext) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }
    private val keyStore = AndroidKeyStoreManager(ctx)
    private val storageKey = AccountStorageKey()

    @Before
    fun ready() {
        assertTrue("Нативное ядро должно быть доступно", CryptoModule.isNativeReady)
    }

    @After
    fun tearDown() {
        storageKey.lock()
        dir.deleteRecursively()
    }

    @Test
    fun legacyStores_areClosedWhenLocked_migrateOnUnlock_andRejectForeignKey() = runBlocking {
        val mnemonic = AccountCrypto.generateMnemonic(24)
        writeLegacyStores(mnemonic)

        val database = AegisDatabase(ctx, keyStore, storageKey)
        val pskStore = FilePskStore(ctx, keyStore, storageKey)

        val lockedDb = runCatching { database.contactStore.get(BOB_ID) }.exceptionOrNull()
        assertTrue("БД при закрытом аккаунте: $lockedDb", lockedDb is StorageLockedException)
        val lockedPsk = runCatching { pskStore.list() }.exceptionOrNull()
        assertTrue("PSK при закрытом аккаунте: $lockedPsk", lockedPsk is StorageLockedException)

        storageKey.unlockWith(mnemonic)
        assertNotNull("контакт пережил перенос", database.contactStore.get(BOB_ID))
        assertEquals(PSK_B64, pskStore.getPskB64(BOB_ID))
        assertFalse(File(dir, "db_key.aegis").exists())
        assertFalse(File(dir, "psk_store.aegis").exists())
        assertTrue(File(dir, "db_key.v2.aegis").isFile)
        val pskFile = File(dir, "psk_store.v2.aegis")
        val sealedPsk = pskFile.readBytes()

        storageKey.unlockWith(AccountCrypto.generateMnemonic(24))
        database.close()
        assertTrue(runCatching { database.contactStore.get(BOB_ID) }.isFailure)
        assertTrue(runCatching { pskStore.put(record("aegis:eve")) }.isFailure)
        assertArrayEquals("чужой ключ не должен затереть PSK", sealedPsk, pskFile.readBytes())

        storageKey.unlockWith(mnemonic)
        assertEquals(PSK_B64, pskStore.getPskB64(BOB_ID))
        database.close()
    }

    /** Состояние устройства до обновления: оба файла обёрнуты только Keystore. */
    private suspend fun writeLegacyStores(mnemonic: String) {
        storageKey.unlockWith(mnemonic)
        val database = AegisDatabase(ctx, keyStore, storageKey)
        database.contactStore.upsert(StoredContact(contact = Contact(id = BOB_ID, nickname = "Bob")))
        database.close()
        val keyFile = File(dir, "db_key.v2.aegis")
        val passphrase = storageKey.open(keyStore.unwrap(keyFile.readBytes()), StoragePurpose.DATABASE_KEY)
        File(dir, "db_key.aegis").writeBytes(keyStore.wrap(passphrase))
        passphrase.fill(0)
        keyFile.delete()

        val entry = Json.encodeToString(PskRecord.serializer(), record(BOB_ID))
        val legacyJson = """{"version":1,"entries":{"$BOB_ID":$entry}}"""
        File(dir, "psk_store.aegis").writeBytes(keyStore.wrap(legacyJson.encodeToByteArray()))
        storageKey.lock()
    }

    private fun record(contactId: String) = PskRecord(
        contactId = contactId,
        pskB64 = PSK_B64,
        source = PskSource.GENERATED,
        pskFingerprintHex = "00",
        label = contactId,
    )

    private companion object {
        const val BOB_ID = "aegis:bob"
        const val PSK_B64 = "c2VjcmV0LXBzay1mb3ItbWlncmF0aW9uLXRlc3QhIQ=="
    }
}
