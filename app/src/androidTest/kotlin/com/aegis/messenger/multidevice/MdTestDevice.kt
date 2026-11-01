package com.aegis.messenger.multidevice

import android.content.Context
import android.content.ContextWrapper
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.crypto.AccountCrypto
import com.aegis.data.storage.AccountStore
import com.aegis.data.storage.AegisDatabase
import com.aegis.data.storage.AndroidEmergencyWipe
import com.aegis.data.storage.AndroidKeyStoreManager
import com.aegis.data.storage.FileAccountStore
import com.aegis.data.storage.FilePskStore
import com.aegis.data.storage.FileSecureStore
import com.aegis.data.storage.KeyStoreManager
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.SecureStore
import com.aegis.messenger.account.AccountBoundStorage
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.account.UnlockThrottle
import com.aegis.messenger.call.CallSignaling
import com.aegis.messenger.messaging.ControlChannel
import com.aegis.messenger.messaging.DeviceAnnouncer
import com.aegis.messenger.messaging.MessagePipeline
import com.aegis.messenger.messaging.RelayGateway
import com.aegis.messenger.messaging.SelfSyncManager
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.messenger.psk.PskManager
import com.aegis.messenger.security.AccountStorageKey
import com.aegis.network.tor.FakeTorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Изолированное «устройство» для живой проверки мультидевайса (§7.1.1) на реальном
 * Android-рантайме: своя папка [dir] → свои SQLCipher-БД, Keystore-обёрнутые
 * файлы (аккаунт/PSK/настройки/идентичность устройства). Так в ОДНОМ процессе
 * инструментального теста поднимаются два независимых устройства одного аккаунта.
 *
 * Используется реальная нативная крипта (`libaegis_core.so`: Argon2id, каскад
 * слоя A, HKDF mailbox v2, self-sync secret) и реальный SQLCipher — крипто-логика
 * НЕ подменяется. Релей/Tor не поднимаем: [RelayGateway.clientOrNull] возвращает
 * `null` (пустой адрес релея), поэтому сетевые операции инертны — проверяем
 * протокол доставки/адресации/линковки, а не транспорт (он проверен §1.16).
 */
class MdTestDevice(base: Context, name: String) {

    val dir: File = File(base.filesDir, "mdtest/$name").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Контекст с подменённым filesDir (и applicationContext=self, т.к. БД читает его). */
    private val ctx: Context = object : ContextWrapper(base.applicationContext) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyStore: KeyStoreManager = AndroidKeyStoreManager(ctx)
    private val storageKey = AccountStorageKey()
    val accountStore: AccountStore = FileAccountStore(ctx, keyStore)
    val secureStore: SecureStore = FileSecureStore(ctx, keyStore)
    val pskStore: PskStore = FilePskStore(ctx, keyStore, storageKey)
    val database: AegisDatabase = AegisDatabase(ctx, keyStore, storageKey)

    /** Кэш идентичности устройства (как `_deviceSettings` в [com.aegis.messenger.AppContainer]). */
    private val deviceSettings = MutableStateFlow(DeviceSettings.DEFAULT)

    /**
     * Очистка ТОЛЬКО этого «устройства». Боевая очистка уничтожает ключи Keystore,
     * а их алиасы общие на весь процесс: создание аккаунта (оно начинается с
     * очистки, [AccountManager.createAccount]) на одном тестовом устройстве
     * обесценило бы файлы всех остальных. Файлы стираются по-настоящему — каталог
     * у каждого устройства свой.
     */
    private val emergencyWipe = AndroidEmergencyWipe(
        keyStoreManager = object : KeyStoreManager by keyStore {
            override fun destroyAllKeys() = Unit
        },
        accountStore = accountStore,
        pskStore = pskStore,
        database = database,
        secureStore = secureStore,
        scratchDir = dir,
    )

    val accountManager = AccountManager(
        appContext = ctx,
        accountStore = accountStore,
        emergencyWipe = emergencyWipe,
        keyStoreManager = keyStore,
        storage = object : AccountBoundStorage {
            override fun open(mnemonic: String) = storageKey.unlockWith(mnemonic)

            override fun close() {
                storageKey.lock()
                database.close()
            }
        },
        unlockThrottle = UnlockThrottle(ctx, keyStore),
    )

    val deviceLinking = DeviceLinking(
        appContext = ctx,
        accountManager = accountManager,
        accountStore = accountStore,
        contactStore = database.contactStore,
        peerDeviceStore = database.peerDeviceStore,
        pskStore = pskStore,
        secureStore = secureStore,
    )

    val signalSessionManager = SignalSessionManager(
        stateStore = database.signalStateStore,
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        localDeviceIdProvider = { deviceSettings.value.localDeviceId },
    )

    val pskManager = PskManager(ctx, pskStore)

    private val torTransport = FakeTorTransport()

    // Сессия транспорта не открывается и адрес не задан ⇒ clientOrNull()=null (инертно).
    val relayGateway = RelayGateway(
        pskManager = pskManager,
        torTransport = torTransport,
        modeProvider = { TorMode.ALWAYS },
        networkSettingsProvider = { TorNetworkSettings.DEFAULT },
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        scope = scope,
    )

    val messagePipeline = MessagePipeline(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        messageStore = database.messageStore,
        relayGateway = relayGateway,
        localDeviceIdProvider = { deviceSettings.value.localDeviceId },
    )

    val selfSyncManager = SelfSyncManager(
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        mnemonicProvider = { accountManager.unlockedMnemonic },
        deviceSettingsProvider = { deviceSettings.value },
        onDeviceSettingsUpdate = { updated ->
            deviceSettings.value = updated
            secureStore.saveDeviceSettings(updated)
        },
        sessionManager = signalSessionManager,
        relayGateway = relayGateway,
        multiDeviceAllowedProvider = { accountManager.multiDeviceAllowedNow() },
    )

    val controlChannel = ControlChannel(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        relayGateway = relayGateway,
        localDeviceIdProvider = { deviceSettings.value.localDeviceId },
    )

    val callSignaling = CallSignaling(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        relayGateway = relayGateway,
        localDeviceIdProvider = { deviceSettings.value.localDeviceId },
    )

    val deviceAnnouncer = DeviceAnnouncer(
        contactStore = database.contactStore,
        peerDeviceStore = database.peerDeviceStore,
        controlChannel = controlChannel,
        sessionManager = signalSessionManager,
        deviceSettingsProvider = { deviceSettings.value },
    )

    init {
        // Та же проводка self-sync, что в AppContainer (§6): исходящие/входящие
        // зеркалим на свои устройства, входящие копии отражаем в хранилище.
        messagePipeline.selfSyncMirror = { copy -> selfSyncManager.mirrorMessage(copy) }
        selfSyncManager.onMessageCopy = { copy -> messagePipeline.applySelfSyncCopy(copy) }
    }

    /**
     * Открывает хранилища без аккаунта — для тестов, которым аккаунт не нужен:
     * без ключа из мнемоники БД и PSK-стор закрыты.
     */
    fun openStorageWithoutAccount() {
        storageKey.unlockWith(AccountCrypto.generateMnemonic(12))
    }

    /** Перечитывает идентичность устройства из хранилища (после линковки/анонса). */
    suspend fun reloadDeviceSettings() {
        deviceSettings.value = secureStore.loadDeviceSettings()
    }

    fun cleanup() {
        runCatching { database.close() }
        runCatching { dir.deleteRecursively() }
    }
}
