package com.aegis.messenger.account

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.account.AccountState
import com.aegis.core.common.contact.DiscoverySettings
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.device.LinkedDevice
import com.aegis.core.common.model.Contact
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StorageLockedException
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.multidevice.MdTestDevice
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
 * Регрессия уязвимости «экран входа → Забыли пароль? → Быстрое создание →
 * Создать»: новый аккаунт открывался поверх данных прежнего и показывал его
 * переписку без пароля (локальные хранилища зашифрованы ключом устройства, а
 * не аккаунта).
 *
 * Проверяется контракт [AccountManager]: замена хранимого аккаунта требует
 * явного подтверждения и выполняется только после полной очистки устройства;
 * восстановление СВОЕЙ фразой («забыли пароль») данные сохраняет. Очистка в
 * [MdTestDevice] не трогает общие на процесс ключи Keystore, файлы же стирает
 * по-настоящему — их и проверяем.
 */
@RunWith(AndroidJUnit4::class)
class AccountReplaceWipeTest {

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
    fun createOverLockedAccount_requiresConfirmation_thenWipesEverything() = runBlocking {
        val dev = device("replace-create")
        val aliceId = createAlice(dev)
        seedAccountData(dev)
        dev.accountManager.lock()

        val unconfirmed = dev.accountManager.createAccount(
            mode = AccountCreationMode.AUTOMATIC,
            login = "",
            password = "",
        )
        assertEquals(AccountCreationResult.ReplaceConfirmationRequired, unconfirmed)
        assertStorageLocked(dev)
        unlockAlice(dev)
        assertAliceDataIntact(dev, aliceId)
        dev.accountManager.lock()

        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.AUTOMATIC,
            login = "",
            password = "",
            replaceExisting = true,
        )
        assertTrue("createAccount: $created", created is AccountCreationResult.Success)
        val newId = (created as AccountCreationResult.Success).profile.identity.accountId
        assertNotEquals("должна появиться новая личность", aliceId, newId)
        assertEquals(newId, dev.accountStore.load()?.profile?.identity?.accountId)
        assertTrue(dev.accountManager.state.value is AccountState.Unlocked)
        assertDeviceClean(dev)
    }

    @Test
    fun restoreWithOwnPhrase_keepsData() = runBlocking {
        val dev = device("replace-forgot")
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "alice",
            password = PASSWORD,
        )
        assertTrue("createAccount: $created", created is AccountCreationResult.Success)
        val mnemonic = (created as AccountCreationResult.Success).mnemonic
        val aliceId = created.profile.identity.accountId
        seedAccountData(dev)
        dev.accountManager.lock()
        assertStorageLocked(dev)

        // Своя фраза + новый пароль — тот самый сценарий «забыли пароль»: ключ
        // хранилищ выводится из фразы, поэтому прежняя база открывается.
        val restored = dev.accountManager.recoverAccount(mnemonic = mnemonic, password = "newpassword1")
        assertTrue("recoverAccount: $restored", restored is AccountCreationResult.Success)
        assertEquals(aliceId, dev.accountManager.currentIdentity()?.accountId)
        assertAliceDataIntact(dev, aliceId)
    }

    @Test
    fun restoreWithForeignPhrase_requiresConfirmation_thenWipesEverything() = runBlocking {
        val dev = device("replace-foreign")
        val aliceId = createAlice(dev)
        seedAccountData(dev)
        dev.accountManager.lock()

        val foreign = AccountCrypto.generateMnemonic(24)
        val foreignId = AccountCrypto.deriveIdentity(foreign).accountId

        val unconfirmed = dev.accountManager.recoverAccount(mnemonic = foreign, password = PASSWORD)
        assertEquals(AccountCreationResult.ReplaceConfirmationRequired, unconfirmed)
        assertStorageLocked(dev)
        unlockAlice(dev)
        assertAliceDataIntact(dev, aliceId)
        dev.accountManager.lock()

        val restored = dev.accountManager.recoverAccount(
            mnemonic = foreign,
            password = PASSWORD,
            replaceExisting = true,
        )
        assertTrue("recoverAccount: $restored", restored is AccountCreationResult.Success)
        assertEquals(foreignId, dev.accountManager.currentIdentity()?.accountId)
        assertDeviceClean(dev)
    }

    @Test
    fun replacingUnlockedAccount_isRefused() = runBlocking {
        val dev = device("replace-unlocked")
        val aliceId = createAlice(dev)
        seedAccountData(dev)

        val result = dev.accountManager.createAccount(
            mode = AccountCreationMode.AUTOMATIC,
            login = "",
            password = "",
            replaceExisting = true,
        )
        assertTrue("ожидался отказ: $result", result is AccountCreationResult.Failure)
        assertAliceDataIntact(dev, aliceId)
    }

    // --- Вспомогательное ---

    private suspend fun createAlice(dev: MdTestDevice): String {
        val created = dev.accountManager.createAccount(
            mode = AccountCreationMode.MANUAL,
            login = "alice",
            password = PASSWORD,
        )
        assertTrue("createAccount: $created", created is AccountCreationResult.Success)
        return (created as AccountCreationResult.Success).profile.identity.accountId
    }

    private suspend fun unlockAlice(dev: MdTestDevice) {
        val result = dev.accountManager.unlock(PASSWORD)
        assertTrue("unlock: $result", result is UnlockResult.Success)
    }

    /** После блокировки переписка и PSK нечитаемы: ключ хранилищ ушёл из памяти. */
    private suspend fun assertStorageLocked(dev: MdTestDevice) {
        val psk = runCatching { dev.pskStore.getPskB64(BOB_ID) }.exceptionOrNull()
        assertTrue("PSK-стор должен быть закрыт: $psk", psk is StorageLockedException)
        val db = runCatching { dev.database.contactStore.get(BOB_ID) }.exceptionOrNull()
        assertTrue("БД должна быть закрыта: $db", db is StorageLockedException)
    }

    /** Данные прежнего аккаунта во ВСЕХ хранилищах, которые обязана стереть замена. */
    private suspend fun seedAccountData(dev: MdTestDevice) {
        dev.database.contactStore.upsert(StoredContact(contact = Contact(id = BOB_ID, nickname = "Bob")))
        dev.database.messageStore.insert(
            Message(
                id = "msg-1",
                contactId = BOB_ID,
                direction = MessageDirection.INCOMING,
                body = "секрет прежнего аккаунта",
                timestampMs = System.currentTimeMillis(),
                status = MessageStatus.RECEIVED,
            ),
        )
        val psk = CryptoModule.psk.generate()
        dev.pskStore.put(
            PskRecord(
                contactId = BOB_ID,
                pskB64 = CryptoModule.psk.toBase64(psk),
                source = PskSource.GENERATED,
                pskFingerprintHex = CryptoModule.psk.fingerprintHex(psk),
                label = "Bob",
            ),
        )
        psk.fill(0)
        dev.secureStore.saveDeviceSettings(
            DeviceSettings(localDeviceId = 3, label = "Old", linkedDevices = listOf(LinkedDevice(deviceId = 1))),
        )
        dev.secureStore.saveDiscoverySettings(DiscoverySettings(tagB64 = "old-tag", alias = "alice"))
    }

    private suspend fun assertAliceDataIntact(dev: MdTestDevice, aliceId: String) {
        assertEquals(aliceId, dev.accountStore.load()?.profile?.identity?.accountId)
        assertNotNull("контакт должен остаться", dev.database.contactStore.get(BOB_ID))
        assertEquals(1, dev.database.messageStore.messagesFor(BOB_ID).size)
        assertNotNull("PSK должен остаться", dev.pskStore.getPskB64(BOB_ID))
        assertEquals(3, dev.secureStore.loadDeviceSettings().localDeviceId)
        assertEquals("old-tag", dev.secureStore.loadDiscoverySettings().tagB64)
    }

    private suspend fun assertDeviceClean(dev: MdTestDevice) {
        assertTrue("контакты прежнего аккаунта", dev.database.contactStore.all().isEmpty())
        assertTrue("переписка прежнего аккаунта", dev.database.messageStore.allMessages().isEmpty())
        assertTrue("PSK прежнего аккаунта", dev.pskStore.list().isEmpty())
        assertEquals(DeviceSettings.DEFAULT, dev.secureStore.loadDeviceSettings())
        assertEquals(DiscoverySettings.DEFAULT, dev.secureStore.loadDiscoverySettings())
    }

    private companion object {
        const val PASSWORD = "password123"
        const val BOB_ID = "aegis:bob"
    }
}
