package com.aegis.messenger

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.aegis.core.common.AegisConstants
import com.aegis.core.common.account.AccountState
import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.common.call.CallDirection
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.call.CallTimeouts
import com.aegis.core.common.wire.WireChannel
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.security.CoverTrafficMode
import com.aegis.core.common.security.FanoutJitterMode
import com.aegis.core.common.security.HttpsRelayAddress
import com.aegis.core.common.security.MetadataMinimization
import com.aegis.core.common.security.OnionRelayAddress
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.AccountStore
import com.aegis.data.storage.AegisDatabase
import com.aegis.data.storage.AndroidEmergencyWipe
import com.aegis.data.storage.AndroidKeyStoreManager
import com.aegis.data.storage.EmergencyWipe
import com.aegis.data.storage.FileAccountStore
import com.aegis.data.storage.FilePskStore
import com.aegis.data.storage.FileSecureStore
import com.aegis.data.storage.KeyStoreManager
import com.aegis.data.storage.LockedInboxStore
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.SecureStore
import com.aegis.data.storage.WipeReport
import com.aegis.messenger.account.AccountBoundStorage
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.AccountTransfer
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.account.UnlockThrottle
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.messenger.call.CallAudioSession
import com.aegis.messenger.call.CallManager
import com.aegis.messenger.call.CallRingtonePlayer
import com.aegis.messenger.call.CallSignaling
import com.aegis.messenger.call.CallToneController
import com.aegis.messenger.call.CallIcePlan
import com.aegis.messenger.call.CallTonePlayer
import com.aegis.messenger.call.MediaEngine
import com.aegis.messenger.call.PlaceholderMediaEngine
import com.aegis.messenger.call.TurnProvisioner
import com.aegis.messenger.call.WebRtcMediaEngine
import com.aegis.messenger.messaging.CameraCapture
import com.aegis.messenger.messaging.ChatSignals
import com.aegis.messenger.messaging.ConfigSync
import com.aegis.messenger.messaging.ContactVerification
import com.aegis.messenger.messaging.ControlChannel
import com.aegis.messenger.messaging.DeviceAnnouncer
import com.aegis.messenger.messaging.CoverTraffic
import com.aegis.messenger.messaging.FanoutJitter
import com.aegis.messenger.messaging.KeyExchangeChannel
import com.aegis.messenger.messaging.MessagePipeline
import com.aegis.messenger.messaging.MessagingRepository
import com.aegis.messenger.messaging.NetworkModeController
import com.aegis.messenger.messaging.RelayGateway
import com.aegis.messenger.messaging.RelayReceiver
import com.aegis.messenger.messaging.RelayUnavailableReason
import com.aegis.messenger.messaging.RetentionEnforcer
import com.aegis.messenger.messaging.SelfSyncManager
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.messenger.notifications.AegisNotifier
import com.aegis.messenger.notifications.CallMediaService
import com.aegis.messenger.notifications.IncomingCallWaker
import com.aegis.messenger.notifications.MessageSyncService
import com.aegis.messenger.notifications.SystemGrants
import com.aegis.messenger.notifications.IncomingCallPresenter
import com.aegis.messenger.nfc.PskNfcPayloadHolder
import com.aegis.messenger.psk.PskManager
import com.aegis.messenger.security.AccountStorageKey
import com.aegis.messenger.security.IdleLockController
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.contact.ContactRequestView
import com.aegis.core.common.contact.DiscoverySettings
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.crypto.DiscoveryCrypto
import com.aegis.crypto.PskCrypto
import com.aegis.messenger.contacts.ContactRequestManager
import com.aegis.messenger.contacts.ContactRequestTransport
import com.aegis.messenger.contacts.DirectoryLookup
import com.aegis.messenger.contacts.DirectoryPublisher
import com.aegis.messenger.contacts.InviteReceiver
import com.aegis.network.tor.ArtiTorTransport
import com.aegis.network.tor.FakeTorTransport
import com.aegis.network.tor.NetworkWatcher
import com.aegis.network.tor.RelayClient
import com.aegis.network.tor.RelayHealth
import com.aegis.network.tor.TorStatus
import com.aegis.network.tor.TorSupervisor
import com.aegis.network.tor.TorTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Простой контейнер зависимостей уровня приложения (ручной DI).
 *
 * Намеренно НЕ используем Hilt/Dagger: ручной контейнер проще аудировать и не
 * тянет кодогенерацию. Перейти на Hilt можно позже без изменения экранов (они
 * получают зависимости через параметры).
 */
class AppContainer(appContext: Context) {

    private val appContext = appContext.applicationContext

    /** Долгоживущий scope приложения (для фоновых задач транспорта/настроек). */
    val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            DiagnosticLog.e(DIAG_TAG, e) { "необработанная ошибка корутины" }
        },
    )

    /** Текущие настройки безопасности. По умолчанию — «Параноик» (§8). */
    private val _securitySettings = MutableStateFlow(SecuritySettings.DEFAULT)
    val securitySettings: StateFlow<SecuritySettings> = _securitySettings.asStateFlow()

    /** Настройки сети/Tor: мосты, PT, onion-релей (§14.7). */
    private val _torNetworkSettings = MutableStateFlow(TorNetworkSettings.DEFAULT)
    val torNetworkSettings: StateFlow<TorNetworkSettings> = _torNetworkSettings.asStateFlow()

    /** Настройки приватности (§14.2): галочки, «печатает…», онлайн-статус. */
    private val _privacySettings = MutableStateFlow(PrivacySettings.DEFAULT)
    val privacySettings: StateFlow<PrivacySettings> = _privacySettings.asStateFlow()

    /** Настройки уведомлений (§14.5): звук/вибрация, превью, «в приложении». */
    private val _notificationSettings = MutableStateFlow(NotificationSettings.DEFAULT)
    val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

    /** Настройки оформления (§14.6): тема и обои чата. Язык — через LocaleManager. */
    private val _appearanceSettings = MutableStateFlow(AppearanceSettings.DEFAULT)
    val appearanceSettings: StateFlow<AppearanceSettings> = _appearanceSettings.asStateFlow()

    /**
     * Идентичность ЭТОГО устройства (мультидевайс §7.1.1): `localDeviceId` (1 =
     * первичное) + метка + счётчик выдачи id. Кэшируется, чтобы синхронный
     * [SignalSessionManager] мог читать device id без suspend. По умолчанию —
     * первичное устройство (прежнее одноустройственное поведение).
     */
    private val _deviceSettings = MutableStateFlow(DeviceSettings.DEFAULT)
    val deviceSettings: StateFlow<DeviceSettings> = _deviceSettings.asStateFlow()

    /**
     * Настройки диагностики: включён ли журнал отладки ([DiagnosticLog]). Выключен
     * по умолчанию; пользователь включает его перед проблемным действием.
     */
    private val _diagnosticSettings = MutableStateFlow(DiagnosticSettings.DEFAULT)
    val diagnosticSettings: StateFlow<DiagnosticSettings> = _diagnosticSettings.asStateFlow()

    /**
     * Состояние удалённого добавления контактов (`docs/contact_requests.md`):
     * теги обнаружения (СЕКРЕТЫ), занятый публичный `@ник`, антиоткатные метки
     * записей каталога. Хранится обёрнутым ключом Keystore.
     */
    private val _discoverySettings = MutableStateFlow(DiscoverySettings.DEFAULT)
    val discoverySettings: StateFlow<DiscoverySettings> = _discoverySettings.asStateFlow()

    /**
     * Заявки на добавление в контакты для UI (бейдж на вкладке + экран заявок).
     * Обновляется из [contactRequestManager] после каждого изменения.
     */
    private val _contactRequests = MutableStateFlow<List<ContactRequestView>>(emptyList())
    val contactRequests: StateFlow<List<ContactRequestView>> = _contactRequests.asStateFlow()

    /**
     * Отложенный переход в чат из уведомления (§14.5): id контакта, который UI
     * должен открыть при появлении. Устанавливается из [MainActivity] по extra
     * интента, сбрасывается корневым UI после навигации.
     */
    private val _pendingChatOpen = MutableStateFlow<String?>(null)
    val pendingChatOpen: StateFlow<String?> = _pendingChatOpen.asStateFlow()

    /**
     * Отложенный переход на экран ЗАЯВОК из уведомления. Ставится из
     * [MainActivity] по extra интента, сбрасывается UI после навигации.
     */
    private val _pendingContactRequestsOpen = MutableStateFlow(false)
    val pendingContactRequestsOpen: StateFlow<Boolean> = _pendingContactRequestsOpen.asStateFlow()

    /** Защёлка однократной [initialize]: Activity пересоздаётся, контейнер — нет. */
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Сериализует [initialize]: её зовут и экран, и сервис приёма. */
    private val initMutex = Mutex()

    /** Локальные уведомления (§14.5): без FCM, формируются из расшифрованного. */
    val notifier: AegisNotifier = AegisNotifier(this.appContext)

    /**
     * Синтезатор/проигрыватель тонов звонка (§7.5): «гудки», согласование,
     * соединение, завершение и встроенный рингтон. Ассетов нет — звук
     * рендерится из патчей ([com.aegis.core.common.call.tone.CallTonePalette]).
     */
    private val callTonePlayer: CallTonePlayer = CallTonePlayer(this.appContext, applicationScope)

    /** Непрерывный рингтон/вибрация входящего звонка (§14.5): звук ведёт он, не канал. */
    val callRingtonePlayer: CallRingtonePlayer =
        CallRingtonePlayer(this.appContext, callTonePlayer)

    /**
     * Включение экрана на входящем звонке (§14.5).
     *
     * Нужен именно как отдельный механизм: без разрешения на full-screen intent
     * (Android 14+ выдаёт его не всем) экран звонка сам не поднимается, и раньше
     * пользователь получал звонящий телефон с погашенным экраном без единой
     * подсказки, что происходит и от какого приложения.
     */
    private val incomingCallWaker: IncomingCallWaker = IncomingCallWaker(this.appContext)

    /**
     * Подъём экрана звонка на входящем вызове — как в Telegram/WhatsApp: карточка
     * появляется поверх экрана блокировки СРАЗУ, поэтому после ввода PIN она уже
     * там. Дополнительно повторяет попытку в момент разблокировки, если запуск в
     * момент звонка система не разрешила.
     */
    private val incomingCallPresenter: IncomingCallPresenter = IncomingCallPresenter(
        context = this.appContext,
        // Проверка результата: фоновый запуск Activity при отказе не бросает
        // исключение, поэтому судим по факту появления видимого экрана.
        foregroundProbe = { idleLockController.isInForeground() },
    )

    /**
     * Озвучивание хода звонка (§7.5): подписан на [CallManager.state] и сам
     * решает, что должно звучать в каждой фазе. Подписка поднимается в
     * [initialize] — до неё звонков быть не может.
     */
    private val callToneController: CallToneController = CallToneController(
        player = callTonePlayer,
        scope = applicationScope,
        tonesEnabled = { _notificationSettings.value.callProgressTones },
    )

    /**
     * Отложенный приём звонка из уведомления (§14.5): пользователь нажал
     * «Принять» — UI ([ui.AegisApp]) запросит разрешения и вызовет
     * `acceptIncoming`, затем сбросит флаг.
     */
    private val _pendingCallAccept = MutableStateFlow(false)
    val pendingCallAccept: StateFlow<Boolean> = _pendingCallAccept.asStateFlow()

    /** id контакта, чей чат сейчас открыт на экране (для подавления уведомлений §14.5). */
    @Volatile
    private var visibleChatContactId: String? = null

    /**
     * Транспорт Tor (§7.2, P5). Если нативный Arti собран в `.so`
     * ([CryptoModule.hasTorSupport]) — реальный [ArtiTorTransport] поверх Arti;
     * иначе — [FakeTorTransport] (статус Unavailable, без фейкового «подключено»).
     */
    val torTransport: TorTransport =
        if (CryptoModule.hasTorSupport) {
            ArtiTorTransport(
                appContext = this.appContext,
                networkSettingsProvider = { _torNetworkSettings.value },
                scope = applicationScope,
            )
        } else {
            FakeTorTransport()
        }

    /**
     * Наблюдатель переключений сети. Встроенный Arti о них не узнаёт сам: после
     * перехода Wi-Fi ⇄ мобильный (или подъёма/падения VPN) цепи держатся на уже
     * мёртвых TCP-соединениях, а статус остаётся «Connected».
     */
    private val networkWatcher: NetworkWatcher = NetworkWatcher(this.appContext)

    /**
     * Сторож соединения с Tor (§7.2): делает связь самовосстанавливающейся —
     * лечит зависший bootstrap, потерю сети и «зелёный статус при недостижимом
     * релее». Запускается в [initialize], останавливается вместе с процессом.
     *
     * Условие работы намеренно шире, чем «аккаунт разблокирован»: Tor нужен и на
     * экране входа (например, [ConfigSync] тянет адрес релея), поэтому сторож
     * смотрит только на режим сети.
     */
    val torSupervisor: TorSupervisor = TorSupervisor(
        transport = torTransport,
        networkWatcher = networkWatcher,
        // В режиме без Tor сторож бездействует: Arti там не запущен вообще, и
        // «лечить» его значило бы поднять Tor, который пользователь выключил.
        enabledProvider = { _securitySettings.value.netTor == TorMode.ALWAYS },
        onTorRestarted = {
            // Порт SOCKS после перезапуска ДРУГОЙ, а long-poll циклы держат
            // клиента релея, созданного со старым портом: без пересборки они
            // до своего reconcile (до 20 с) стучались бы в закрытый порт.
            relayReceiver.restartLoops()
            inviteReceiver.restartLoops()
        },
        scope = applicationScope,
    )

    /** Аппаратное хранилище ключей (Keystore/StrongBox, §6.3). */
    val keyStoreManager: KeyStoreManager = AndroidKeyStoreManager(this.appContext)

    /**
     * Ключ локальных хранилищ из мнемоники открытого аккаунта: второй слой поверх
     * Keystore для БД и файла PSK. Пуст, пока аккаунт закрыт.
     */
    private val storageKey = AccountStorageKey()

    /** Запечатанное хранилище записи аккаунта (§7.1). */
    val accountStore: AccountStore = FileAccountStore(this.appContext, keyStoreManager)

    /** Запечатанное хранилище настроек безопасности. */
    val secureStore: SecureStore = FileSecureStore(this.appContext, keyStoreManager)

    /** Защищённое хранилище ключей контейнера (PSK, P4 §6.2). */
    val pskStore: PskStore = FilePskStore(this.appContext, keyStoreManager, storageKey)

    /** Зашифрованная БД (SQLCipher, P6 §6.3): сообщения/контакты/Signal-сессии. */
    val database: AegisDatabase = AegisDatabase(this.appContext, keyStoreManager, storageKey)

    /** Ящики и очередь приёма при закрытом аккаунте: без ключа хранилищ, без имён контактов. */
    private val lockedInbox = LockedInboxStore(this.appContext, keyStoreManager)

    /**
     * Хранилищная часть экстренной очистки (§7.4): ступень 1 crypto-shredding
     * (ключи, аккаунт, PSK, БД, настройки) + ступень 2 best-effort перезапись
     * свободного места в `cacheDir` (≤90%). Напрямую не вызывается: память
     * процесса она не трогает — вся очистка идёт через [emergencyWipe].
     */
    private val storageWipe: AndroidEmergencyWipe = AndroidEmergencyWipe(
        keyStoreManager = keyStoreManager,
        accountStore = accountStore,
        pskStore = pskStore,
        database = database,
        secureStore = secureStore,
        scratchDir = this.appContext.cacheDir,
    )

    /**
     * Экстренная очистка уровня приложения (§7.4) — единственная точка, через
     * которую идут «Удалить аккаунт», экстренная очистка, duress и создание
     * нового аккаунта поверх прежнего ([AccountManager]). См. [wipeLocalData].
     */
    val emergencyWipe: EmergencyWipe = object : EmergencyWipe {
        override suspend fun wipe(includeBestEffort: Boolean): WipeReport =
            wipeLocalData(includeBestEffort)
    }

    /** Счётчик неверных паролей с нарастающей задержкой (§14.3). */
    private val unlockThrottle = UnlockThrottle(this.appContext, keyStoreManager)

    /**
     * Хранилища открываются и закрываются вместе с аккаунтом. При закрытии из
     * памяти уходит и всё уже прочитанное из них: списки чатов, контактов и PSK.
     */
    private val accountBoundStorage = object : AccountBoundStorage {
        override fun open(mnemonic: String) = storageKey.unlockWith(mnemonic)

        override fun close() {
            storageKey.lock()
            database.close()
            messagingRepository.clearCache()
            pskManager.clearCache()
        }
    }

    /** Оркестратор аккаунта (P1, §7.1). */
    val accountManager: AccountManager = AccountManager(
        appContext = this.appContext,
        accountStore = accountStore,
        emergencyWipe = emergencyWipe,
        keyStoreManager = keyStoreManager,
        storage = accountBoundStorage,
        unlockThrottle = unlockThrottle,
    )

    /**
     * Перенос аккаунта между устройствами (§7.1.2): сборка/распечатывание/
     * применение зашифрованного паролем пакета (Argon2id + каскад слоя A), вне
     * сети (файл/QR). Использует те же хранилища, что и остальное приложение.
     */
    val accountTransfer: AccountTransfer = AccountTransfer(
        appContext = this.appContext,
        accountManager = accountManager,
        accountStore = accountStore,
        contactStore = database.contactStore,
        messageStore = database.messageStore,
        signalStateStore = database.signalStateStore,
        pskStore = pskStore,
        secureStore = secureStore,
    )

    /**
     * Device-linking (мультидевайс §7.1.1, `docs/multidevice.md` §5): сборка/
     * распечатывание/применение пакета линковки (Argon2id + каскад слоя A), вне
     * сети (файл/QR). НЕ клонирует Signal-состояние (MD-1) — новое устройство
     * держит свой identity и свои per-device сессии.
     */
    val deviceLinking: DeviceLinking = DeviceLinking(
        appContext = this.appContext,
        accountManager = accountManager,
        accountStore = accountStore,
        contactStore = database.contactStore,
        peerDeviceStore = database.peerDeviceStore,
        pskStore = pskStore,
        secureStore = secureStore,
    )

    /**
     * Автоблокировка по таймауту бездействия (§14.3). Наблюдает состояние
     * аккаунта и взаимодействия/жизненный цикл Activity; по истечении таймаута
     * (из [SecuritySettings.autoLockMinutes]) выполняет «мягкую» блокировку
     * ([AccountManager.lock]). Регистрируется в [AegisApplication].
     */
    val idleLockController: IdleLockController = IdleLockController(
        scope = applicationScope,
        accountState = accountManager.state,
        timeoutMillisProvider = { _securitySettings.value.autoLockMinutes * 60_000L },
        onLock = { accountManager.lock() },
        isBusyProvider = { callIsLive() },
    )

    /** Оркестратор ручного обмена PSK (P4 §6.2/§10.3, слой A). */
    val pskManager: PskManager = PskManager(this.appContext, pskStore)

    /** Stateful-менеджер Signal-сессий (слой B, P6 §6.1) поверх SQLCipher. */
    val signalSessionManager: SignalSessionManager = SignalSessionManager(
        stateStore = database.signalStateStore,
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
    )

    /**
     * Смягчение тайминг-корреляции fan-out (§12 `docs/multidevice.md`,
     * `meta.minimize` §8): рандомизация порядка + джиттер per-device дропов.
     * Общий инстанс для переписки/control/звонков ([relayGateway]) и self-sync
     * ([selfSyncManager]); уровень читается «на лету» из активного профиля.
     */
    val fanoutJitter: FanoutJitter = FanoutJitter(
        levelProvider = { _securitySettings.value.effectiveFanoutJitter() },
    )

    /**
     * Прикрытие факта/объёма отправки — cover-traffic / dummy-дропы (§12 `docs/
     * multidevice.md`, §3a; `meta.minimize` §8). Планировщик фоновых decoy-дропов
     * ([start] в [initialize]) + фабрика паддинга для fan-out ([relayGateway]/
     * [selfSyncManager]). Активен только при `MAXIMUM` (Параноик) и разблокированном
     * аккаунте с поднятым Tor/релеем; `clientProvider` разрешается «на лету».
     */
    val coverTraffic: CoverTraffic = CoverTraffic(
        // CoverTraffic активен при уровне MAXIMUM; мапим пользовательскую настройку
        // (§14.7, №3b) в этот уровень, чтобы не менять API/тесты хелпера.
        levelProvider = {
            if (_securitySettings.value.coverTrafficActive()) {
                MetadataMinimization.MAXIMUM
            } else {
                MetadataMinimization.BASIC
            }
        },
        clientProvider = { relayGateway.clientOrNull() },
        activeProvider = {
            accountManager.state.value is AccountState.Unlocked &&
                signalSessionManager.isAvailable &&
                !callIsLive()
        },
        scope = applicationScope,
    )

    /**
     * Шлюз к релею (§7.6/§12.2): построение [com.aegis.network.tor.RelayClient]
     * строго по режиму сети (Tor → onion, без Tor → HTTPS к тому же релею) и
     * вывод mailbox-адресов из PSK контакта. Отправка и приём используют его.
     */
    val relayGateway: RelayGateway = RelayGateway(
        pskManager = pskManager,
        torTransport = torTransport,
        modeProvider = { _securitySettings.value.netTor },
        networkSettingsProvider = { _torNetworkSettings.value },
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        scope = applicationScope,
        fanoutJitter = fanoutJitter,
        coverTraffic = coverTraffic,
    )

    /** Почему сейчас нет клиента релея (`null` — есть) — для баннеров и статуса сети в UI. */
    val relayUnavailable: StateFlow<RelayUnavailableReason?> get() = relayGateway.unavailable

    /**
     * Авто-обнаружение адресов релея (§12.2): зашитые в сборку дефолты (onion и
     * HTTPS) + периодический подписанный конфиг с сервера (`GET /v1/config`)
     * текущим транспортом. Подпись проверяется зашитым в APK публичным ключом
     * ([BuildConfig.RELAY_CONFIG_PUBKEY]). Ручной ввод (если задан) не трогается.
     */
    val configSync: ConfigSync = ConfigSync(
        relayConfigPubkeyHex = BuildConfig.RELAY_CONFIG_PUBKEY,
        defaultRelayOnion = BuildConfig.DEFAULT_RELAY_ONION,
        defaultRelayHttps = BuildConfig.DEFAULT_RELAY_HTTPS,
        defaultHttpsPins = bakedHttpsPins(),
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        settings = { _torNetworkSettings.value },
        persist = { updated -> setTorNetworkSettings(updated) },
        fetchConfig = { relayGateway.fetchServerConfig() },
        verify = { doc, sig ->
            CryptoModule.verifyEd25519(BuildConfig.RELAY_CONFIG_PUBKEY, doc, sig)
        },
        transportReady = { relayGateway.clientOrNull() != null },
        scope = applicationScope,
    )

    /**
     * Синхронизация СВОИХ устройств (мультидевайс §7.1.1, `docs/multidevice.md`
     * §4.2/§6): device-announce (Этап 3) и копии сообщений (Этап 4) через
     * self-sync mailbox поверх onion-релея. Секрет выводится из мнемоники, поэтому
     * доступен каждому линкованному устройству без передачи по сети.
     */
    val selfSyncManager: SelfSyncManager = SelfSyncManager(
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        mnemonicProvider = { accountManager.unlockedMnemonic },
        deviceSettingsProvider = { _deviceSettings.value },
        onDeviceSettingsUpdate = { updated -> updateDeviceSettings(updated) },
        sessionManager = signalSessionManager,
        relayGateway = relayGateway,
        multiDeviceAllowedProvider = { accountManager.multiDeviceAllowedNow() },
        fanoutJitter = fanoutJitter,
        coverTraffic = coverTraffic,
    )

    /** Конвейер отправки/приёма: слой A → слой B → Tor → onion-релей (§5/§6.1/§7.2). */
    val messagePipeline: MessagePipeline = MessagePipeline(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        messageStore = database.messageStore,
        relayGateway = relayGateway,
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
    )

    /** Репозиторий переписки для UI (наблюдаемые чаты/сообщения). */
    val messagingRepository: MessagingRepository = MessagingRepository(
        database = database,
        pipeline = messagePipeline,
        retentionDaysProvider = { _securitySettings.value.autoDeleteDays },
        autoDownloadProvider = { _securitySettings.value.autoDownloadMedia },
    )

    /**
     * Автоудаление по сроку хранения (§7.3/§14.4): фоновый цикл чистит БД от
     * сообщений старше выбранного срока (жёсткий потолок 7 дней). По факту
     * удаления перечитывает список чатов. Запускается в [initialize].
     */
    val retentionEnforcer: RetentionEnforcer = RetentionEnforcer(
        messageStore = database.messageStore,
        daysProvider = { _securitySettings.value.autoDeleteDays },
        scope = applicationScope,
        onPurged = { messagingRepository.refreshConversations() },
    )

    /**
     * Верификация отпечатка / safety number контакта (§14.3, анти-MITM §6.2):
     * код сверки из обоих identity-ключей + ручного PSK; отметка «проверен».
     */
    val contactVerification: ContactVerification = ContactVerification(
        appContext = this.appContext,
        repository = messagingRepository,
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        peerDeviceStore = database.peerDeviceStore,
    )

    /**
     * Сигналинг звонков (P8, §7.5): управляющие сообщения через тот же конвейер
     * слой A→B→Tor→onion, что и текст.
     */
    val callSignaling: CallSignaling = CallSignaling(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        relayGateway = relayGateway,
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
    )

    /**
     * Control-канал переписки (§14.2/§7.3): отчёты о прочтении, «печатает…»,
     * онлайн-статус и «удалить у всех» — тем же защищённым конвейером, что текст.
     */
    val controlChannel: ControlChannel = ControlChannel(
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        relayGateway = relayGateway,
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
    )

    /**
     * Догоняющий обмен ПУБЛИЧНЫМИ ключами слоя B поверх слоя A (§6.2). Чинит
     * ситуацию, когда личный обмен состоялся (PSK есть), а prekey-бандл до
     * собеседника не доехал: в QR он не влезает, а релей в момент встречи мог
     * быть недоступен. Control-канал для этого не годится — он сам требует слоя B.
     */
    val keyExchangeChannel: KeyExchangeChannel = KeyExchangeChannel(
        contactStore = database.contactStore,
        peerDeviceStore = database.peerDeviceStore,
        sessionManager = signalSessionManager,
        pskManager = pskManager,
        relayGateway = relayGateway,
        scope = applicationScope,
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
        enabledProvider = {
            accountManager.state.value is AccountState.Unlocked && signalSessionManager.isAvailable
        },
    )

    /**
     * Анонс/отзыв моих устройств контактам и ведение реестра устройств
     * собеседников (мультидевайс §5/§10, `peer_devices`). Идёт по control-каналу.
     */
    val deviceAnnouncer: DeviceAnnouncer = DeviceAnnouncer(
        contactStore = database.contactStore,
        peerDeviceStore = database.peerDeviceStore,
        controlChannel = controlChannel,
        sessionManager = signalSessionManager,
        deviceSettingsProvider = { _deviceSettings.value },
    )

    /**
     * Координатор эфемерных сигналов чата (§14.2/§7.3): «печатает…»/онлайн (в
     * памяти), отчёты прочтения и удаление (в БД). Уважает [privacySettings].
     */
    val chatSignals: ChatSignals = ChatSignals(
        controlChannel = controlChannel,
        repository = messagingRepository,
        privacyProvider = { _privacySettings.value },
        scope = applicationScope,
    )

    /**
     * Аренда TURN для медиа звонков (§7.5): в режиме Tor — креды через Tor и
     * локальный форвардер в onion coturn; без Tor — креды по HTTPS и клирнет
     * STUN/TURN своего сервера напрямую.
     */
    val turnProvisioner: TurnProvisioner = TurnProvisioner(
        relayGateway = relayGateway,
        scope = applicationScope,
    )

    /**
     * Фабрика медиадвижка (§7.5/§10.11). Пытаемся поднять реальный WebRTC
     * (DTLS-SRTP) по плану ICE для текущего режима сети; если артефакт/нативная
     * библиотека недоступны — честный откат на [PlaceholderMediaEngine]
     * (сигналинг+ключи+SAS без медиапотока).
     */
    private fun createMediaEngine(plan: CallIcePlan): MediaEngine =
        runCatching { WebRtcMediaEngine(appContext = this.appContext, plan = plan) as MediaEngine }
            .getOrElse { PlaceholderMediaEngine() }

    /** Оркестратор звонков (P8, §7.5/§10.10): машина состояний + ключи звонка. */
    val callManager: CallManager = CallManager(
        appContext = this.appContext,
        contactProvider = { id -> messagingRepository.contact(id) },
        pskManager = pskManager,
        signaling = callSignaling,
        mediaEngineFactory = { plan -> createMediaEngine(plan) },
        callsEnabledProvider = { _securitySettings.value.callsEnabled },
        // Маршрут медиа выводится из режима сети, отдельной настройки нет.
        modeProvider = { _securitySettings.value.netTor },
        scope = applicationScope,
        turnProvisioner = turnProvisioner,
    )

    /** Звонок в процессе: не глушим приём idle-lock'ом и не шумим cover-traffic'ом. */
    private fun callIsLive(): Boolean =
        runCatching { callManager.state.value.phase.isLive }.getOrDefault(false)

    /**
     * Живой приём из релея (§7.6/§12.2): long-poll входящих подканалов PSK-
     * контактов через Tor. Принятые конверты расшифровываются B→A и
     * диспетчеризуются в переписку ([MessagingRepository.receive]) либо в
     * сигналинг звонка ([CallSignaling.onWire] → [CallManager.handleControl]).
     * При открытом аккаунте расшифровывает сразу; при закрытом, если включён
     * приём при закрытом аккаунте (§8 `deliverWhileLocked`), складывает конверты
     * в очередь без расшифровки и разбирает её после входа.
     */
    val relayReceiver: RelayReceiver = RelayReceiver(
        gateway = relayGateway,
        contactsProvider = { withContext(Dispatchers.IO) { database.contactStore.all() } },
        onMessage = { contact, wireJson, transport ->
            val message = messagingRepository.receive(contact.contact.id, wireJson, transport)
            if (message != null) maybeNotifyMessage(contact, message)
        },
        onCall = { contact, wireJson, receivedAtMs ->
            // Чёрный список (§14.2): звонки от заблокированного контакта игнорируем.
            if (!contact.contact.blocked) {
                val control = callSignaling.onWire(contact, wireJson)
                // Расшифровываем в любом случае: храповик обязан идти дальше. Но
                // вызов из очереди, который уже отзвонил или отклонён с экрана
                // блокировки, не должен зазвонить после входа.
                if (lockedCallExpired(receivedAtMs)) {
                    DiagnosticLog.event(DIAG_TAG, "сигналинг звонка из очереди устарел", "тип" to control.type)
                } else {
                    callManager.handleControl(contact, control)
                }
            }
        },
        onControl = { contact, wireJson ->
            chatSignals.onControl(contact, wireJson)
        },
        onKeyExchange = { contact, wireJson ->
            keyExchangeChannel.onWire(contact, wireJson)
        },
        enabledProvider = { deliveryAllowedNow() && signalSessionManager.isAvailable },
        scope = applicationScope,
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
        selfSyncInboxProvider = { selfSyncManager.myInbox() },
        onSelfSync = { wireJson -> selfSyncManager.onWire(wireJson) },
        // Здоровье цепи до onion-релея — единственный честный признак того, что
        // Tor «жив, но не работает»: статус транспорта в этом случае зелёный.
        // В режиме без Tor сторож выключен и счётчик просто не используется.
        onRelayOk = { torSupervisor.noteRelaySuccess() },
        onRelayFail = { torSupervisor.noteRelayFailure() },
        lockedInbox = lockedInbox,
        lockedModeProvider = { lockedDeliveryActive() },
        onLockedArrival = { channel -> onLockedArrival(channel) },
    )

    // --- Удалённое добавление контактов (RCH v1, `docs/contact_requests.md`) ---

    /**
     * Публичная идентичность аккаунта для записи каталога и нагрузок
     * рукопожатия. `null`, пока аккаунт заблокирован.
     */
    private suspend fun accountPublicIdentity(): DirectoryPublisher.AccountPublicIdentity? {
        val identity = accountManager.currentIdentity() ?: return null
        return DirectoryPublisher.AccountPublicIdentity(
            accountId = identity.accountId,
            ed25519Hex = identity.ed25519PublicHex,
            x25519Hex = identity.x25519PublicHex,
            fingerprint = identity.fingerprint,
        )
    }

    /**
     * Поиск владельца адреса/`@ника` в каталоге с ПОЛНОЙ проверкой найденной
     * записи (подпись + привязка accountId↔ключ + коммитмент тега + сроки).
     */
    val directoryLookup: DirectoryLookup = DirectoryLookup(
        relayGateway = relayGateway,
        knownIssuedAtProvider = { accountId ->
            _discoverySettings.value.knownRecordIssuedAtMs[accountId] ?: 0L
        },
        onRecordAccepted = { accountId, issuedAtMs ->
            rememberDirectoryRecord(accountId, issuedAtMs)
        },
    )

    /**
     * Транспорт заявок: ящики адресуются ТЕГОМ обнаружения (не PSK — его ещё
     * нет), нагрузка едет внутри Signal-сообщения слоя B.
     */
    val contactRequestTransport: ContactRequestTransport = ContactRequestTransport(
        sessionManager = signalSessionManager,
        clientProvider = { relayGateway.clientOrNull() },
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
    )

    /** Оркестратор заявок: отправка, приём, принятие/отклонение, активация. */
    val contactRequestManager: ContactRequestManager = ContactRequestManager(
        requestStore = database.contactRequestStore,
        contactStore = database.contactStore,
        pskStore = pskStore,
        sessionManager = signalSessionManager,
        transport = contactRequestTransport,
        directoryLookup = directoryLookup,
        mnemonicProvider = { accountManager.unlockedMnemonic },
        identityProvider = { accountPublicIdentity() },
        nicknameProvider = { accountManager.currentLogin() },
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
        modeProvider = { _securitySettings.value.contactRequests },
        declineNotifyProvider = { _securitySettings.value.contactDeclineNotify },
    )

    /**
     * Публикация СВОЕЙ записи каталога (только публичные ключи, запечатано
     * ключом из тега) и заявки на публичный `@ник`.
     */
    val directoryPublisher: DirectoryPublisher = DirectoryPublisher(
        relayGateway = relayGateway,
        sessionManager = signalSessionManager,
        scope = applicationScope,
        tagProvider = { ensureDiscoveryTag() },
        publicTagProvider = { ensurePublicDiscoveryTag() },
        mnemonicProvider = { accountManager.unlockedMnemonic },
        identityProvider = { accountPublicIdentity() },
        nicknameProvider = { accountManager.currentLogin() },
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
        modeProvider = { _securitySettings.value.contactRequests },
        aliasProvider = { _discoverySettings.value.alias },
    )

    /**
     * Приём заявок. Отдельный от [relayReceiver] цикл: тот адресует ящики из PSK
     * существующих контактов, а заявка приходит от того, с кем контакта ещё нет —
     * поэтому этот цикл обязан работать и при НУЛЕ контактов.
     */
    val inviteReceiver: InviteReceiver = InviteReceiver(
        transport = contactRequestTransport,
        manager = contactRequestManager,
        requestStore = database.contactRequestStore,
        clientProvider = { relayGateway.clientOrNull() },
        tagProvider = { _discoverySettings.value.tagB64.ifBlank { null } },
        publicTagProvider = { _discoverySettings.value.publicTagB64.ifBlank { null } },
        accountIdProvider = { accountManager.currentIdentity()?.accountId },
        localDeviceIdProvider = { _deviceSettings.value.localDeviceId },
        modeProvider = { _securitySettings.value.contactRequests },
        enabledProvider = {
            accountManager.state.value is AccountState.Unlocked && signalSessionManager.isAvailable
        },
        scope = applicationScope,
    )

    /**
     * Применение режима сети (Tor ВКЛ / ВЫКЛ) и переключение на лету: закрыть
     * соединения прежнего режима и дождаться их, остановить/поднять Arti, открыть
     * сессию нового режима, перезапустить сетевые циклы. Единственное место, где
     * вызывается `torTransport.applyMode`.
     */
    val networkModeController: NetworkModeController = NetworkModeController(
        torTransport = torTransport,
        gateway = relayGateway,
        modeProvider = { _securitySettings.value.netTor },
        networkEpochs = networkWatcher.epoch,
        onBeforeSwitch = {
            // Шлюз уже не выдаёт клиентов: циклы гаснут и не поднимутся до конца
            // переключения. Висящие запросы оборвёт закрытие сессии транспорта.
            relayReceiver.restartLoops()
            inviteReceiver.restartLoops()
            turnProvisioner.closeAll()
        },
        onAfterSwitch = { _ ->
            relayReceiver.restartLoops()
            inviteReceiver.restartLoops()
            configSync.syncSoon()
            // Сервис приёма сокетов не держит (только FGS и замок процессора) —
            // перезапускать его незачем; меняется лишь текст его уведомления.
            MessageSyncService.refreshNotification(this.appContext)
        },
        scope = applicationScope,
    )

    /** Состояние переключения режима сети — для UI («Переключение…»). */
    val networkModeState: StateFlow<NetworkModeController.State> get() = networkModeController.state

    // --- Приём при закрытом аккаунте: состояние ------------------------------
    //
    // Объявлено ДО `init`: подписки, которые он запускает, работают на другом
    // потоке и читают эти поля уже на первом кадре — раньше, чем конструктор
    // дошёл бы до объявлений ниже. Там подписка на звонок падает на null при
    // старте процесса и больше не живёт: телефон молчит на всех вызовах при
    // открытом аккаунте, хотя экран звонка в приложении появляется.

    /** Звонок пришёл при закрытом аккаунте и звонит (для баннера на экране входа). */
    private val _lockedIncomingCall = MutableStateFlow(false)
    val lockedIncomingCall: StateFlow<Boolean> = _lockedIncomingCall.asStateFlow()

    private var lockedRingJob: Job? = null

    /** До этого момента (мс эпохи) новый звонковый конверт не будит: хвост отзвонившего вызова. */
    @Volatile
    private var lockedRingQuietUntilMs = 0L

    /** Звонковые конверты, пришедшие не позже этого момента, после входа не звонят: вызов отклонён. */
    @Volatile
    private var lockedCallDismissedAtMs = 0L

    /** До этого момента (мс эпохи) входящий звонок после входа принимается сам: нажали «Ответить». */
    @Volatile
    private var answerAfterUnlockUntilMs = 0L

    private val lockedMessageCount = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        // Связываем ручной обмен PSK со слоем B (§6.2): код обмена
        // несёт наш identity+бандл; при импорте кода собеседника сохраняем его
        // контакт с бандлом, чтобы Signal-сессия устанавливалась без сети.
        if (signalSessionManager.isAvailable) {
            pskManager.identityProvider = {
                runCatching { signalSessionManager.identityKeyB64() }.getOrNull()
            }
            pskManager.bundleProvider = {
                runCatching { signalSessionManager.publishBundleJson() }.getOrNull()
            }
        }
        pskManager.onImportedExchange = { contactId, payload ->
            messagingRepository.ensureContact(
                contactId = contactId,
                nickname = payload.label,
                fingerprint = payload.fromFingerprint,
                exchange = payload,
            )
            messagingRepository.refreshConversations()
            // Слой A (PSK) установлен. Бандл слоя B теперь едет в QR (v3); если
            // его всё же не было — догоняем по keyx, когда появится релей.
            applicationScope.launch {
                runCatching { keyExchangeChannel.announceTo(contactId, request = true) }
            }
        }
        // Взаимный обмен по QR (§6.2): публичный prekey-бандл слоя B не влезает в
        // QR (Kyber1024), поэтому едет через релей — публикуем свой при показе и
        // тянем чужой при сканировании. Секрет (PSK) остаётся в QR (§10.7).
        pskManager.uploadBundle = { id, bundleJson -> relayGateway.putPrekeyBundle(id, bundleJson) }
        pskManager.fetchBundle = { id -> relayGateway.getPrekeyBundle(id) }

        // Обмен ключами слоя B (§6.2). Отправка упёрлась в отсутствие бандла —
        // просим ключи; ключи пришли — поднимаем индикатор E2EE в списке чатов и
        // досылаем то, что из-за них не ушло (статус FAILED → SENT).
        messagePipeline.onMissingPeerKeys = { contact ->
            keyExchangeChannel.announce(contact, request = true)
        }
        keyExchangeChannel.onKeysReceived = { contactId ->
            messagingRepository.refreshConversations()
            runCatching { messagingRepository.retryFailedMessages(contactId) }
        }
        // `session_ready` раньше не выставлялся никогда: флаг есть в схеме, а
        // писать его было некому. Теперь его поднимает сам факт установления
        // исходящей сессии по бандлу (X3DH/PQXDH завершён).
        signalSessionManager.onSessionEstablished = { contactId, deviceId ->
            runCatching {
                val contact = database.contactStore.get(contactId)
                if (contact != null && deviceId == contact.remoteDevice) {
                    database.contactStore.setSessionReady(contactId, true)
                } else {
                    database.peerDeviceStore.setSessionReady(contactId, deviceId, true)
                }
            }
        }

        // Self-sync (мультидевайс §6): исходящие/входящие зеркалим на свои другие
        // устройства, а входящие копии — отражаем в хранилище и обновляем чаты.
        messagePipeline.selfSyncMirror = { copy -> selfSyncManager.mirrorMessage(copy) }
        selfSyncManager.onMessageCopy = { copy ->
            messagePipeline.applySelfSyncCopy(copy)
            messagingRepository.refreshConversations()
        }

        // Служебные события без `channel` во внешнем конверте расшифровываются
        // как «сообщение»; ratchet уже сдвинут — отдаём plaintext в обработчики.
        messagePipeline.onMisroutedControl = { contact, msg ->
            chatSignals.apply(contact, msg)
        }
        messagePipeline.onMisroutedCall = { contact, control ->
            if (!contact.contact.blocked) {
                callManager.handleControl(contact, control)
            }
        }

        // Устройства контактов (§5/§10): device-announce/revoke по control-каналу
        // ведут реестр peer_devices, по которому идёт fan-out (Этап 4).
        chatSignals.onDeviceAnnounce = { contact, msg -> deviceAnnouncer.onAnnounce(contact, msg) }
        chatSignals.onDeviceRevoke = { contact, msg -> deviceAnnouncer.onRevoke(contact, msg) }

        // Заявки на добавление в контакты (`docs/contact_requests.md`).
        contactRequestManager.onIncomingRequest = { request ->
            val settings = _notificationSettings.value
            val allowed = settings.enabled && !settings.isMutedNow()
            if (allowed) {
                notifier.notifyContactRequest(
                    displayName = request.peerNickname.ifBlank {
                        ContactAddress.shortAccount(request.peerAccountId)
                    },
                    greeting = request.greeting,
                    pendingCount = withContext(Dispatchers.IO) {
                        database.contactRequestStore.incomingPendingCount()
                    },
                    settings = settings,
                )
            }
        }
        contactRequestManager.onRequestsChanged = { refreshContactRequests() }
        contactRequestManager.onContactActivated = { contactId ->
            // Контакт готов: перечитываем чаты и сразу же анонсируем свои
            // устройства/ключи по уже установленному слою A (обычным путём).
            messagingRepository.refreshConversations()
            applicationScope.launch {
                runCatching { keyExchangeChannel.announceTo(contactId, request = true) }
            }
        }

        // Foreground-service фонового приёма (§14.5/§7.6) и очистка уведомлений
        // синхронно с состоянием аккаунта: разблокировали — держим процесс живым
        // для long-poll поверх Tor; заблокировали — убираем все уведомления (в
        // них могли быть превью) и либо гасим сервис, либо оставляем его для
        // приёма в очередь (§8 `deliverWhileLocked`).
        applicationScope.launch {
            var wasUnlocked = false
            accountManager.state.collect { state ->
                val unlocked = state is AccountState.Unlocked
                val previouslyUnlocked = wasUnlocked
                wasUnlocked = unlocked
                survive("аккаунт") {
                    if (unlocked && !previouslyUnlocked) {
                        DiagnosticLog.event(DIAG_TAG, "аккаунт разблокирован — запуск приёма/анонсов")
                        // Гудок и уведомления закрытого режима больше не нужны: очередь
                        // разберётся сейчас же, живой звонок зазвонит уже с именем.
                        stopLockedRinging()
                        notifier.cancelLockedMessages()
                        lockedMessageCount.set(0)
                        relayReceiver.restartLoops()
                        // Пока аккаунт был закрыт, база была недоступна и автоудаление
                        // простаивало: истёкшее за это время удаляем сразу.
                        applicationScope.launch { runCatching { retentionEnforcer.enforceNow() } }
                        MessageSyncService.start(this@AppContainer.appContext)
                        // Добор ключей слоя B для контактов, у которых их ещё нет
                        // (§6.2): цикл сам простаивает, когда добирать нечего.
                        keyExchangeChannel.start()
                        // Удалённое добавление контактов: приём заявок обязан работать
                        // и при нуле контактов, поэтому цикл отдельный от relayReceiver.
                        // Запись каталога переопубликовывается (срок и одноразовые
                        // prekeys истекают) — оба цикла сами простаивают, если функция
                        // выключена настройками либо каталог выключен на релее.
                        inviteReceiver.start()
                        directoryPublisher.start()
                        applicationScope.launch { runCatching { refreshContactRequests() } }
                        // Мультидевайс (§7.1.1): анонсируем себя другим своим устройствам
                        // (identity+bundle), чтобы они установили self-sync-сессии, и —
                        // если это линкованное устройство — контактам (§5, шаг 3), чтобы
                        // они вели fan-out и на нас. Best-effort; no-op для одного устройства.
                        applicationScope.launch { runCatching { selfSyncManager.announce() } }
                        applicationScope.launch { runCatching { deviceAnnouncer.announceSelfToContacts() } }
                    } else if (!unlocked && previouslyUnlocked) {
                        // Хранилища уже закрыты ([AccountManager.lock]): расшифровывать
                        // нечем, поэтому живой приём и звонок прекращаются всегда. Если
                        // пользователь разрешил приём при закрытом аккаунте (§8
                        // `deliverWhileLocked`), сервис, замок процессора и Tor остаются —
                        // их остановка и выглядела как «приложение засыпает», — а
                        // входящие копятся в очереди до следующего входа.
                        val keepReceiving = lockedDeliveryActive()
                        DiagnosticLog.event(
                            DIAG_TAG, "аккаунт заблокирован",
                            "приёмВОчередь" to keepReceiving,
                        )
                        callRingtonePlayer.stop()
                        incomingCallWaker.release()
                        notifier.cancelAll()
                        lockedMessageCount.set(0)
                        // Эти циклы держатся на мнемонике, которую `lock()` затирает,
                        // поэтому они останавливаются в ЛЮБОМ случае: без секрета они
                        // всё равно только жгли бы батарею впустую.
                        keyExchangeChannel.stop()
                        inviteReceiver.stop()
                        directoryPublisher.stop()
                        // Список заявок держит ники/приветствия: в locked-состоянии
                        // показывать их нельзя (БД недоступна, а UI за auth-gate).
                        _contactRequests.value = emptyList()
                        // Тишина ДО сброса звонка: иначе принудительный hangUp
                        // перевёл бы фазу в ENDED и озвучка честно проиграла бы
                        // тон завершения — в ухо, уже после того, как пользователь
                        // убрал телефон.
                        runCatching { callToneController.silence() }
                        runCatching { callManager.hangUp() }
                        // Страховка на случай, если движок не успеет освободить
                        // аудиосессию сам (его close() ждёт поток WebRTC до 5 с): в
                        // locked-состоянии устройство не должно оставаться в режиме
                        // разговора. Повторный release движка при нулевом счётчике
                        // безопасен — CallAudioSession его игнорирует.
                        CallAudioSession.reset(this@AppContainer.appContext)
                        CallMediaService.stop(this@AppContainer.appContext)
                        if (!keepReceiving) MessageSyncService.stop(this@AppContainer.appContext)
                        relayReceiver.restartLoops()
                    }
                }
            }
        }

        // Входящий звонок (§14.5): непрерывный рингтон/вибрацию ([CallRingtonePlayer])
        // ведём ВСЁ время дозвона — и в foreground (оверлей CallScreen), и в фоне.
        // Уведомление с кнопками принять/отклонить показываем ТОЛЬКО когда
        // приложение не на переднем плане (в foreground звонок и так виден
        // оверлеем). Всё гасим при любом другом состоянии звонка.
        applicationScope.launch {
            var wasLive = false
            var signalledCallId = ""
            callManager.state.collect { call ->
                // Автоблокировка, отложенная на время звонка (§14.3), должна
                // примениться сразу по его завершении — иначе отложенная
                // блокировка «потерялась» бы до следующего ухода в фон.
                val live = call.phase.isLive
                val finished = wasLive && !live
                wasLive = live
                if (finished) survive("автоблокировка после звонка") { idleLockController.onBusyFinished() }
                survive("звонок") {
                    val incomingRinging =
                        call.phase == CallPhase.RINGING && call.direction == CallDirection.INCOMING
                    if (!incomingRinging) {
                        // Рингтон и уведомление сейчас может держать звонок закрытого
                        // режима — он о фазах CallManager ничего не знает.
                        if (_lockedIncomingCall.value) return@collect
                        callRingtonePlayer.stop()
                        incomingCallWaker.release()
                        incomingCallPresenter.stop()
                        notifier.cancelCall()
                        return@collect
                    }
                    val settings = _notificationSettings.value
                    val contact = runCatching { messagingRepository.contact(call.contactId) }.getOrNull()
                    val allowed = settings.enabled && !settings.isMutedNow() &&
                        contact?.contact?.isMutedNow() != true
                    if (!allowed) {
                        if (signalledCallId != call.callId) {
                            signalledCallId = call.callId
                            DiagnosticLog.event(
                                DIAG_TAG, "входящий звонок без сигнала",
                                "уведомления" to settings.enabled,
                                "паузаВсех" to settings.isMutedNow(),
                                "контактЗаглушён" to (contact?.contact?.isMutedNow() == true),
                            )
                        }
                        callRingtonePlayer.stop()
                        incomingCallWaker.release()
                        incomingCallPresenter.stop()
                        notifier.cancelCall()
                        return@collect
                    }
                    // Пока читали контакт из БД, пользователь мог нажать «Принять»
                    // или «Отклонить». Завести рингтон по устаревшему снимку —
                    // значит зазвонить на уже принятом звонке.
                    val now = callManager.state.value
                    if (now.callId != call.callId || now.phase != CallPhase.RINGING) {
                        callRingtonePlayer.stop()
                        return@collect
                    }
                    // «Ответить» нажали ещё при закрытом аккаунте: звонок дождался
                    // входа и разбора очереди — принимаем без второго гудка.
                    if (System.currentTimeMillis() < answerAfterUnlockUntilMs) {
                        answerAfterUnlockUntilMs = 0L
                        requestCallAccept()
                        return@collect
                    }
                    val ring = ringSourceFor(settings, contact?.contact?.callRingtoneUri ?: "")
                    val inForeground = idleLockController.isInForeground()
                    if (signalledCallId != call.callId) {
                        signalledCallId = call.callId
                        DiagnosticLog.event(
                            DIAG_TAG, "входящий звонок: сигнал",
                            "наЭкране" to inForeground,
                            "мелодия" to (ring.uri != null),
                            "синтез" to ring.synth,
                            "вибро" to settings.callVibrate,
                        )
                    }
                    callRingtonePlayer.start(ring.uri, settings.callVibrate, synth = ring.synth)
                    if (!inForeground) {
                        // Порядок важен: сначала уведомление, потом подсветка экрана.
                        // Иначе экран успевает зажечься до публикации уведомления, и
                        // на блокировке в первый момент нет ни карточки звонка, ни
                        // heads-up — ровно та «пустая» секунда, из-за которой звонок
                        // выглядел как звонок «от неизвестного приложения».
                        notifier.notifyIncomingCall(
                            contactId = call.contactId,
                            callId = call.callId,
                            displayName = call.displayName,
                            media = call.media,
                            settings = settings,
                        )
                        // Экран звонка поднимаем ВСЕГДА, а не только когда нет
                        // разрешения на полноэкранный звонок.
                        //
                        // Так это и работает в Telegram: карточка вызова появляется
                        // поверх блокировки СРАЗУ, и после ввода PIN пользователь
                        // видит именно её. Если full-screen intent разрешён, он
                        // сделает это сам, а наш запуск придёт в уже созданную
                        // Activity как onNewIntent (launchMode=singleTop) — то есть
                        // безвредно. Если не разрешён, наш запуск остаётся
                        // единственным шансом.
                        incomingCallPresenter.start(onUnlocked = {
                            // Уведомление публикуем заново: на только что
                            // разблокированном экране heads-up должен быть виден
                            // даже если поднять Activity из фона не разрешат.
                            notifier.notifyIncomingCall(
                                contactId = call.contactId,
                                callId = call.callId,
                                displayName = call.displayName,
                                media = call.media,
                                settings = settings,
                            )
                        })
                        // Без разрешения на полноэкранный звонок экран сам не
                        // включится (`turnScreenOn` действует только когда Activity
                        // реально запускают) — будим замком, чтобы карточка и кнопки
                        // были видны, а не звонили в темноту.
                        if (!notifier.fullScreenAllowed()) {
                            DiagnosticLog.w(DIAG_TAG) {
                                "нет разрешения на полноэкранный звонок — будим экран замком " +
                                    "(включите «Full screen intents» для приложения в системных настройках)"
                            }
                            incomingCallWaker.wake()
                        }
                    } else {
                        incomingCallWaker.release()
                        incomingCallPresenter.stop()
                        notifier.cancelCall()
                    }
                }
            }
        }

        // Озвучивание хода звонка (§7.5): «гудки» исходящего, пинги
        // согласования, соединение/разрыв. Отдельной подписке, а не ветке в
        // обработчике выше: там решения зависят от БД (контакт) и от режима
        // звонка устройства, а тоны разговора — только от фазы.
        callToneController.bind(callManager.state)
    }

    /**
     * Реакция на кадр долгоживущей подписки, которую исключение не убивает.
     *
     * Упавший `collect` молча перестаёт получать кадры до конца жизни процесса.
     * Для подписки на звонок это телефон, который больше не звонит ни на одном
     * вызове, хотя экран звонка в приложении работает: он читает состояние сам.
     */
    private inline fun survive(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "сбой подписки «$what» — кадр пропущен, подписка жива" }
        }
    }

    /**
     * Можно ли расшифровывать входящие ПРЯМО СЕЙЧАС: только при открытом аккаунте,
     * когда открыты хранилища с PSK и Signal-сессиями.
     */
    fun deliveryAllowedNow(): Boolean = accountManager.state.value is AccountState.Unlocked

    /**
     * Аккаунт закрыт, но пользователь включил приём при закрытом аккаунте
     * ([SecuritySettings.deliverWhileLocked]): конверты копятся в очереди без
     * расшифровки, а звонок звонит без имени.
     */
    fun lockedDeliveryActive(): Boolean =
        accountManager.state.value is AccountState.Locked && _securitySettings.value.deliverWhileLocked

    /** Нужен ли сейчас фоновый приём ([MessageSyncService]) — живой или в очередь. */
    fun receivingAllowedNow(): Boolean = deliveryAllowedNow() || lockedDeliveryActive()

    // --- Приём при закрытом аккаунте: уведомления и звонок без имени ---------

    /** Конверт лёг в очередь закрытого режима: сообщить о факте, не раскрывая ни имени, ни текста. */
    private fun onLockedArrival(channel: String) {
        when (channel) {
            WireChannel.CALL -> startLockedRinging()
            WireChannel.MESSAGE -> {
                val settings = _notificationSettings.value
                if (!settings.enabled || settings.isMutedNow()) return
                notifier.notifyLockedMessages(lockedMessageCount.incrementAndGet(), settings)
            }
        }
    }

    /**
     * Гудок звонка, пришедшего при закрытом аккаунте. Звонящий ждёт не дольше
     * [CallTimeouts.RING_MS] — столько и звоним. Отбой тоже зашифрован, поэтому
     * положенную раньше трубку здесь не увидеть.
     */
    @Synchronized
    private fun startLockedRinging() {
        if (_lockedIncomingCall.value || System.currentTimeMillis() < lockedRingQuietUntilMs) return
        val settings = _notificationSettings.value
        if (!settings.enabled || settings.isMutedNow()) return
        DiagnosticLog.event(DIAG_TAG, "звонок при закрытом аккаунте")
        _lockedIncomingCall.value = true
        val ring = ringSourceFor(settings, contactRingtoneUri = "")
        callRingtonePlayer.start(ring.uri, settings.callVibrate, synth = ring.synth)
        notifier.notifyLockedIncomingCall(settings)
        if (!notifier.fullScreenAllowed()) incomingCallWaker.wake()
        lockedRingJob = applicationScope.launch {
            delay(CallTimeouts.RING_MS)
            stopLockedRinging()
        }
    }

    /** Гасит гудок закрытого режима; хвост того же вызова ещё [LOCKED_RING_QUIET_MS] не разбудит телефон. */
    @Synchronized
    private fun stopLockedRinging() {
        if (!_lockedIncomingCall.value) return
        lockedRingJob?.cancel()
        lockedRingJob = null
        _lockedIncomingCall.value = false
        lockedRingQuietUntilMs = System.currentTimeMillis() + LOCKED_RING_QUIET_MS
        callRingtonePlayer.stop()
        incomingCallWaker.release()
        notifier.cancelCall()
    }

    /** «Отклонить» на звонке при закрытом аккаунте: тишина, и после входа этот вызов не зазвонит. */
    fun dismissLockedCall() {
        lockedCallDismissedAtMs = System.currentTimeMillis()
        answerAfterUnlockUntilMs = 0L
        stopLockedRinging()
    }

    /**
     * «Ответить» на звонке при закрытом аккаунте: гудок стихает, баннер на экране
     * входа остаётся, а звонок примется сам, как только после входа разберётся очередь.
     */
    fun answerLockedCallAfterUnlock() {
        answerAfterUnlockUntilMs = System.currentTimeMillis() + CallTimeouts.RING_MS
        callRingtonePlayer.stop()
        incomingCallWaker.release()
        notifier.cancelCall()
    }

    /** Звонковый конверт из очереди отзвонил (или вызов отклонили с экрана блокировки). */
    private fun lockedCallExpired(receivedAtMs: Long): Boolean =
        System.currentTimeMillis() - receivedAtMs > CallTimeouts.RING_MS ||
            receivedAtMs <= lockedCallDismissedAtMs

    /** Источник мелодии входящего звонка: URI, встроенный синтез или тишина. */
    private data class RingSource(val uri: Uri?, val synth: Boolean) {
        companion object {
            val SILENT = RingSource(uri = null, synth = false)
        }
    }

    /**
     * Выбор мелодии входящего звонка (§14.5). Приоритет намеренно такой:
     *
     *  1. звук звонка выключен → тишина (вибрация решается отдельно);
     *  2. у контакта задана ПЕРСОНАЛЬНАЯ мелодия → она, всегда. Явный выбор для
     *     конкретного человека не должен отменяться глобальным стилем;
     *  3. включён встроенный рингтон → синтез в стиле приложения;
     *  4. иначе → системная мелодия по умолчанию.
     */
    private fun ringSourceFor(
        settings: NotificationSettings,
        contactRingtoneUri: String,
    ): RingSource = when {
        !settings.callSound -> RingSource.SILENT
        contactRingtoneUri.isNotBlank() -> {
            val custom = runCatching { Uri.parse(contactRingtoneUri) }.getOrNull()
            if (custom != null) {
                RingSource(uri = custom, synth = false)
            } else {
                // URI был, но испорчен (например, после переустановки источника
                // мелодии). Молчать нельзя — это пропущенный звонок.
                DiagnosticLog.w(DIAG_TAG) { "персональный рингтон контакта не разобран" }
                systemOrSynthRing(settings)
            }
        }
        else -> systemOrSynthRing(settings)
    }

    private fun systemOrSynthRing(settings: NotificationSettings): RingSource =
        if (settings.callSynthRingtone) {
            RingSource(uri = null, synth = true)
        } else {
            RingSource(
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                synth = false,
            )
        }

    /**
     * Принять входящий звонок.
     *
     * Рингтон и вибрация гасятся ЗДЕСЬ, синхронно, а не только реактивно по
     * смене фазы: реактивный обработчик читает контакт из БД и потому может
     * отстать на десятки миллисекунд, а телефон должен замолчать в момент
     * нажатия. Уведомление снимаем тем же движением.
     */
    fun acceptIncomingCall() {
        callRingtonePlayer.stop()
        incomingCallWaker.release()
        incomingCallPresenter.stop()
        notifier.cancelCall()
        callManager.acceptIncoming()
    }

    /** Отклонить входящий звонок (та же мгновенная тишина, что и в приёме). */
    fun declineIncomingCall() {
        callRingtonePlayer.stop()
        incomingCallWaker.release()
        incomingCallPresenter.stop()
        notifier.cancelCall()
        callManager.declineIncoming()
    }

    /** Запрос приёма звонка из уведомления (§14.5): выставляется из [MainActivity]. */
    fun requestCallAccept() {
        _pendingCallAccept.value = true
    }

    /** Сбросить отложенный приём звонка после обработки в UI (§14.5). */
    fun consumePendingCallAccept() {
        _pendingCallAccept.value = false
    }

    // --- Удалённое добавление контактов: API для UI ---------------------------

    /** Запрос открытия экрана заявок из уведомления (ставится из [MainActivity]). */
    fun requestContactRequestsOpen() {
        _pendingContactRequestsOpen.value = true
    }

    /** Сбросить отложенный переход на экран заявок после навигации. */
    fun consumePendingContactRequestsOpen() {
        _pendingContactRequestsOpen.value = false
    }

    /** Перечитывает список заявок для UI и гасит уведомление, когда решать нечего. */
    suspend fun refreshContactRequests() {
        val all = withContext(Dispatchers.IO) { database.contactRequestStore.all() }
        _contactRequests.value = all
            .filter { it.state != ContactRequestState.COMPLETED }
            .map { it.toView() }
        val incomingPending = _contactRequests.value.count {
            it.state == ContactRequestState.INCOMING_PENDING
        }
        if (incomingPending == 0) notifier.cancelContactRequests()
    }

    /**
     * Приватный тег обнаружения; создаётся при первом обращении.
     *
     * Тег — СЕКРЕТ (знание тега = право прислать заявку), поэтому живёт только в
     * обёрнутом Keystore файле. `null`, если нативное ядро недоступно.
     */
    suspend fun ensureDiscoveryTag(): String? = discoveryMutex.withLock {
        val current = _discoverySettings.value
        if (current.tagB64.isNotBlank()) return@withLock current.tagB64
        if (!DiscoveryCrypto.isReady) return@withLock null
        val tag = runCatching { DiscoveryCrypto.generateTag() }.getOrNull() ?: return@withLock null
        val b64 = try {
            PskCrypto.toBase64(tag)
        } finally {
            tag.fill(0)
        }
        persistDiscovery(current.copy(tagB64 = b64))
        DiagnosticLog.event(DIAG_TAG, "создан тег обнаружения")
        b64
    }

    /** Публичный тег для `@ника` (отдельный от приватного); создаётся при нужде. */
    suspend fun ensurePublicDiscoveryTag(): String? = discoveryMutex.withLock {
        val current = _discoverySettings.value
        if (current.publicTagB64.isNotBlank()) return@withLock current.publicTagB64
        if (!DiscoveryCrypto.isReady) return@withLock null
        val tag = runCatching { DiscoveryCrypto.generateTag() }.getOrNull() ?: return@withLock null
        val b64 = try {
            PskCrypto.toBase64(tag)
        } finally {
            tag.fill(0)
        }
        persistDiscovery(current.copy(publicTagB64 = b64))
        b64
    }

    /**
     * Мой адрес контакта (`aegis:1:<accountId>:<tag>`) для копирования/показа.
     * Создаёт тег при первом вызове. `null`, если аккаунт заблокирован либо
     * нативное ядро недоступно.
     */
    suspend fun myContactAddress(): String? {
        ensureDiscoveryTag() ?: return null
        return directoryPublisher.myAddress()
    }

    /**
     * РОТАЦИЯ приватного тега: мгновенно обесценивает все ранее выданные адреса
     * (по ним больше не найти запись и не написать заявку). Старая запись
     * снимается с публикации, новая публикуется под новым индексом.
     *
     * Существующие контакты НЕ затрагиваются: их переписка адресуется секретом
     * слоя A, а не тегом.
     */
    suspend fun rotateDiscoveryTag(): String? {
        val previous = _discoverySettings.value.tagB64
        if (previous.isNotBlank()) {
            runCatching {
                relayGateway.deleteDirectoryRecord(DiscoveryCrypto.deriveIds(previous).index)
            }
        }
        discoveryMutex.withLock {
            persistDiscovery(_discoverySettings.value.copy(tagB64 = ""))
        }
        DiagnosticLog.event(DIAG_TAG, "тег обнаружения ротирован — прежние адреса недействительны")
        val fresh = ensureDiscoveryTag()
        runCatching { directoryPublisher.publish() }
        return fresh?.let { directoryPublisher.myAddress() }
    }

    /**
     * Занимает/продлевает публичный `@ник`. Возвращает итог для показа в UI.
     * Требует [ContactRequestMode.ADDRESS_AND_ALIAS] и включённых алиасов на релее.
     */
    suspend fun claimContactAlias(alias: String): DirectoryPublisher.AliasResult {
        ensurePublicDiscoveryTag()
        val result = directoryPublisher.claimAlias(alias)
        if (result is DirectoryPublisher.AliasResult.Ok) {
            discoveryMutex.withLock {
                persistDiscovery(
                    _discoverySettings.value.copy(
                        alias = result.normalized,
                        aliasRenewedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return result
    }

    /**
     * Отказ от публичного `@ника`: ротирует ПУБЛИЧНЫЙ тег, из-за чего заявка
     * алиаса перестаёт указывать на действующую запись. Приватные адреса,
     * выданные доверенным людям, при этом не затрагиваются.
     */
    suspend fun releaseContactAlias() {
        val previous = _discoverySettings.value.publicTagB64
        if (previous.isNotBlank()) {
            runCatching {
                relayGateway.deleteDirectoryRecord(DiscoveryCrypto.deriveIds(previous).index)
            }
        }
        discoveryMutex.withLock {
            persistDiscovery(
                _discoverySettings.value.copy(publicTagB64 = "", alias = "", aliasRenewedAtMs = 0L),
            )
        }
        DiagnosticLog.event(DIAG_TAG, "публичный ник отключён (публичный тег ротирован)")
    }

    /**
     * Режим удалённого добавления (§8). При выключении записи каталога сразу
     * снимаются с публикации — иначе адрес продолжал бы «работать» после того,
     * как пользователь функцию отключил.
     */
    suspend fun setContactRequestMode(mode: ContactRequestMode) {
        val updated = _securitySettings.value.copy(contactRequests = mode)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "contactRequests" to mode.name)
        if (mode == ContactRequestMode.OFF) {
            inviteReceiver.stop()
            directoryPublisher.stop()
            runCatching { directoryPublisher.unpublish() }
        } else if (accountManager.state.value is AccountState.Unlocked) {
            inviteReceiver.start()
            directoryPublisher.start()
        }
    }

    /** Отправлять ли собеседнику явный отказ при отклонении заявки. */
    suspend fun setContactDeclineNotify(enabled: Boolean) {
        val updated = _securitySettings.value.copy(contactDeclineNotify = enabled)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "contactDeclineNotify" to enabled)
    }

    /**
     * Запоминает `issuedAtMs` принятой записи каталога — защита от того, что
     * сервер подсунет СТАРУЮ запись с уже отозванными ключами.
     */
    suspend fun rememberDirectoryRecord(accountId: String, issuedAtMs: Long) {
        discoveryMutex.withLock {
            val updated = _discoverySettings.value.rememberRecord(accountId, issuedAtMs)
            if (updated != _discoverySettings.value) persistDiscovery(updated)
        }
    }

    private suspend fun persistDiscovery(settings: DiscoverySettings) {
        _discoverySettings.value = settings
        secureStore.saveDiscoverySettings(settings)
    }

    /** Сериализует создание/ротацию тегов: гонка породила бы два разных тега. */
    private val discoveryMutex = Mutex()

    /**
     * Решает, показывать ли уведомление о входящем сообщении (§14.5), и публикует
     * его. Подавляем, если: уведомления выключены/на паузе, контакт заглушён,
     * этот чат сейчас открыт на экране, либо приложение в foreground при
     * выключенных «внутренних» уведомлениях.
     */
    private suspend fun maybeNotifyMessage(
        contact: com.aegis.data.storage.StoredContact,
        message: com.aegis.core.common.model.Message,
    ) {
        val settings = _notificationSettings.value
        if (!settings.enabled || settings.isMutedNow()) return
        if (contact.contact.isMutedNow()) return
        val contactId = contact.contact.id
        val inForeground = idleLockController.isInForeground()
        // Открытый чат: сообщение и так видно (и уже помечается прочитанным §14.2).
        if (inForeground && visibleChatContactId == contactId) return
        // «Внутренние уведомления» выключены — в foreground не отвлекаем.
        if (inForeground && !settings.inAppNotifications) return

        val unread = runCatching {
            withContext(Dispatchers.IO) { database.messageStore.unreadCount(contactId) }
        }.getOrDefault(1)
        notifier.notifyMessage(
            contactId = contactId,
            displayName = contact.contact.displayLabel(),
            message = message,
            settings = settings,
            contactSoundUri = contact.contact.notificationSoundUri,
            unreadCount = unread,
        )
    }

    /**
     * Загружает персистентное состояние (настройки + аккаунт) при старте.
     *
     * Выполняется РОВНО ОДИН РАЗ на процесс. Вызывается из корневого composable
     * ([com.aegis.messenger.ui.AegisRoot]), а тот пересоздаётся вместе с
     * Activity: поворот экрана, смена темы, свайп приложения из списка недавних
     * при живом процессе (его держит foreground-service приёма). Контейнер при
     * этом тот же, поэтому повторный прогон не нужен и вреден — он заново читал
     * восемь настроек из запечатанного хранилища (распечатывание ключом
     * Keystore) и повторно поднимал фоновые циклы приёма, автоудаления,
     * cover-traffic и синхронизации конфига.
     *
     * Вызывается и из [MessageSyncService]: система перезапускает сервис после
     * выгрузки процесса без всякого интерфейса. Одновременный второй вызов ЖДЁТ
     * окончания первого — вернувшись раньше, экран увидел бы состояние аккаунта
     * `None` и показал бы онбординг поверх существующего аккаунта.
     */
    suspend fun initialize() {
        initMutex.withLock {
            if (initialized.get()) {
                DiagnosticLog.event(DIAG_TAG, "initialize: повторный вызов пропущен")
                return
            }
            // Сорвавшаяся инициализация защёлку не ставит: иначе контейнер
            // навсегда остался бы полупустым, а повтор был бы отброшен.
            initializeOnce()
            initialized.set(true)
        }
    }

    private suspend fun initializeOnce() {
        // Журнал поднимаем РАНЬШЕ остального: если запись была включена в прошлый
        // раз, в него должны попасть и чтение настроек (сбой разбора откатывает
        // их к умолчанию — это видно только в журнале), и подъём транспорта.
        _diagnosticSettings.value = secureStore.loadDiagnosticSettings()
        applyDiagnosticLog(_diagnosticSettings.value)
        _securitySettings.value = secureStore.loadSecuritySettings()
        _torNetworkSettings.value = loadNormalizedTorSettings()
        _privacySettings.value = secureStore.loadPrivacySettings()
        _notificationSettings.value = secureStore.loadNotificationSettings()
        _appearanceSettings.value = secureStore.loadAppearanceSettings()
        _deviceSettings.value = secureStore.loadDeviceSettings()
        _discoverySettings.value = secureStore.loadDiscoverySettings()
        DiagnosticLog.event(DIAG_TAG, "initialize: настройки загружены")
        // Системные разрешения, которые нельзя получить кодом, но без которых
        // приложение работает не так, как ждёт пользователь (полноэкранный звонок,
        // фоновый приём). Пишем их состояние сразу: иначе разбор жалоб «не вижу
        // звонка» / «не приходят сообщения» начинается с угадывания.
        SystemGrants.logState(this.appContext)
        accountManager.initialize()
        // Процесс поднялся с закрытым аккаунтом (перезапуск, перезагрузка): приём
        // в очередь не ждёт входа, иначе после перезапуска до нас не дозвониться.
        if (lockedDeliveryActive()) MessageSyncService.start(this.appContext)
        // Сторож Tor: лечит зависший bootstrap, смену сети и недостижимый релей.
        // Поднимается ДО циклов приёма — они зависят от живого порта SOCKS.
        torSupervisor.start()
        // Режим сети применяет контейнер, а не экран: иначе после перезапуска
        // процесса с закрытым аккаунтом Tor не поднимался до входа, и приём при
        // закрытом аккаунте молчал. В режиме без Tor Arti не запускается вовсе.
        networkModeController.start()
        // Запускаем надзор приёма: он сам простаивает, пока аккаунт заблокирован
        // или клиента релея нет, и активируется, когда условия выполнены.
        relayReceiver.start()
        // Автоудаление по сроку хранения (§7.3): фоновая чистка БД от просроченного.
        retentionEnforcer.start()
        // §3a: фоновый cover-traffic (прикрытие факта отправки). Сам простаивает,
        // пока настройка cover-traffic неактивна (§14.7) / аккаунт заблокирован /
        // Tor не поднят.
        coverTraffic.start()
        // §12.2: авто-обнаружение адресов релея — ставит зашитые дефолты (если
        // адреса не заданы вручную) и периодически подхватывает подписанный конфиг
        // с сервера. Сам простаивает без pubkey/транспорта (fail-closed).
        configSync.start()
        DiagnosticLog.event(DIAG_TAG, "initialize: фоновые циклы запущены")
    }

    private suspend fun loadNormalizedTorSettings(): TorNetworkSettings {
        val loaded = secureStore.loadTorNetworkSettings()
        val next = normalizedTorSettings(loaded)
        if (next != loaded) secureStore.saveTorNetworkSettings(next)
        return next
    }

    /** Канонические адреса + зашитые в сборку дефолты для незаполненных полей. */
    private fun normalizedTorSettings(settings: TorNetworkSettings): TorNetworkSettings =
        settings.copy(
            onionRelayAddress = OnionRelayAddress.normalize(settings.onionRelayAddress),
            httpsRelayAddress = HttpsRelayAddress.normalize(settings.httpsRelayAddress),
            httpsPins = HttpsRelayAddress.normalizePins(settings.httpsPins),
        ).withBakedRelayIfNeeded(BuildConfig.DEFAULT_RELAY_ONION, BuildConfig.DEFAULT_RELAY_HTTPS, bakedHttpsPins())

    /** Пины SPKI, зашитые в сборку (`-Paegis.relayHttpsPins`, через запятую). */
    private fun bakedHttpsPins(): List<String> =
        HttpsRelayAddress.normalizePins(BuildConfig.RELAY_HTTPS_PINS.split(','))

    /**
     * Срок хранения переписки в днях (§7.3/§14.4). Значение нормализуется в
     * допустимый диапазон [AegisConstants.AUTO_DELETE_MIN_DAYS..AUTO_DELETE_MAX_DAYS]
     * (жёсткий потолок 7 дней нельзя превысить), сохраняется и применяется сразу:
     * фоновый [RetentionEnforcer] и чтения репозитория используют новый срок.
     */
    suspend fun setAutoDeleteDays(days: Int) {
        val clamped = days.coerceIn(
            AegisConstants.AUTO_DELETE_MIN_DAYS,
            AegisConstants.AUTO_DELETE_MAX_DAYS,
        )
        val updated = _securitySettings.value.copy(autoDeleteDays = clamped)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "autoDeleteDays" to clamped)
        // Применяем новый (возможно, более короткий) срок немедленно.
        retentionEnforcer.enforceNow()
    }

    /**
     * Автозагрузка входящих вложений (§14.4): сохраняет флаг и обновляет поток.
     * Репозиторий читает его «на лету» (autoDownloadProvider) — применяется сразу.
     */
    suspend fun setAutoDownloadMedia(enabled: Boolean) {
        val updated = _securitySettings.value.copy(autoDownloadMedia = enabled)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "autoDownloadMedia" to enabled)
    }

    /**
     * Режим джиттера fan-out (§14.7, №3b; `docs/multidevice.md` §12/§1.40). Сохраняет
     * настройку и обновляет поток — [fanoutJitter] читает `effectiveFanoutJitter()`
     * «на лету», поэтому применяется к следующему же fan-out без перезапуска.
     */
    suspend fun setFanoutJitterMode(mode: FanoutJitterMode) {
        val updated = _securitySettings.value.copy(fanoutJitter = mode)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "fanoutJitter" to mode.name)
    }

    /**
     * Режим cover-traffic (§14.7, №3b; `docs/multidevice.md` §12/§1.41). Сохраняет
     * настройку и обновляет поток; фоновый планировщик [coverTraffic] уже запущен в
     * [initialize] и перечитывает `coverTrafficActive()` «на лету» (idle-backoff),
     * поэтому включение начинает слать decoy без перезапуска. `start()` идемпотентен.
     */
    suspend fun setCoverTrafficMode(mode: CoverTrafficMode) {
        val updated = _securitySettings.value.copy(coverTraffic = mode)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "coverTraffic" to mode.name)
        coverTraffic.start()
    }

    /**
     * Переключатель «Tor» (§8 net.tor) на лету. Выключение UI делает только после
     * диалога с предупреждением — отсюда отметка [SecuritySettings.torOffConfirmed].
     *
     * Во время звонка режим не меняется (возвращает `false`): переключатель в UI
     * недоступен, а здесь — страховка. Сменить транспорт под живым звонком нельзя
     * без его обрыва: медиа идёт через TURN-аренду, взятую в прежнем режиме.
     *
     * Сохранение — сразу, само переключение транспорта — в scope приложения:
     * экран, с которого его начали, может закрыться посреди ожидания.
     */
    suspend fun setTorMode(mode: TorMode): Boolean {
        if (callIsLive()) {
            DiagnosticLog.w(DIAG_TAG) { "режим сети не изменён: идёт звонок" }
            return false
        }
        val updated = _securitySettings.value.withTorMode(mode)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "torMode" to mode.name)
        applicationScope.launch { networkModeController.switchTo(mode, "пользователь") }
        return true
    }

    /**
     * Обновляет настройки сети/Tor (мосты, PT, адреса релея). Сохраняет и
     * обновляет поток; смена мостов перезапускает Tor (в режиме Tor).
     */
    suspend fun setTorNetworkSettings(settings: TorNetworkSettings) {
        val previous = _torNetworkSettings.value
        val next = normalizedTorSettings(settings)
        _torNetworkSettings.value = next
        secureStore.saveTorNetworkSettings(next)
        DiagnosticLog.event(
            DIAG_TAG, "настройка сети",
            "onionЗадан" to next.onionRelayAddress.isNotBlank(),
            "httpsЗадан" to next.httpsRelayAddress.isNotBlank(),
            "пинов" to next.effectiveHttpsPins().size,
            "релейВручную" to next.relayManual,
            "мосты" to next.bridgesEnabled,
            "встроенныйМост" to next.builtInBridge.name,
        )
        if (next.torClientRestartToken() != previous.torClientRestartToken()) {
            applicationScope.launch { networkModeController.reapplyTorConfig() }
        }
    }

    /**
     * Разрешает/запрещает мультидевайс (§7.1.1): клиентская политика для
     * предупреждений и доступности переноса аккаунта. Обновляет запись/профиль.
     */
    suspend fun setMultiDeviceAllowed(allowed: Boolean) {
        accountManager.setMultiDeviceAllowed(allowed)
    }

    /**
     * Атомарно обновляет идентичность/реестр устройства (§7.1.1): сохраняет в
     * SecureStore и публикует в живой поток [deviceSettings] (его читает
     * [SignalSessionManager] и self-sync).
     */
    suspend fun updateDeviceSettings(settings: DeviceSettings) {
        _deviceSettings.value = settings
        secureStore.saveDeviceSettings(settings)
    }

    /** Перечитывает идентичность устройства из хранилища (после линковки/анонса). */
    suspend fun reloadDeviceSettings() {
        _deviceSettings.value = secureStore.loadDeviceSettings()
    }

    /**
     * Переименовать ЭТО устройство (экран «Мои устройства», §14.1/§7.1.1). Метка
     * локальна и попадёт к другим моим устройствам при следующем self-sync-анонсе
     * ([SelfSyncManager.announce]) и контактам — при device-announce.
     */
    suspend fun renameThisDevice(label: String) {
        updateDeviceSettings(_deviceSettings.value.copy(label = label.trim()))
    }

    /**
     * Переименовать МОЁ линкованное устройство [deviceId] в локальном реестре
     * (§7.1.1). Меняет только человекочитаемую метку; крипто-состояние не трогает.
     */
    suspend fun renameLinkedDevice(deviceId: Int, label: String) {
        val device = _deviceSettings.value
        val updated = device.linkedDevices.map {
            if (it.deviceId == deviceId) it.copy(label = label.trim()) else it
        }
        updateDeviceSettings(device.copy(linkedDevices = updated))
    }

    /**
     * Отозвать МОЁ линкованное устройство [deviceId] (§10, `docs/multidevice.md`):
     * помечает его `revoked=true` в локальном реестре (self-sync/fan-out на него с
     * этого устройства прекращается — [DeviceSettings.activeLinkedDevices]),
     * рассылает device-revoke всем контактам ([DeviceAnnouncer.revokeMyDeviceToContacts]),
     * чтобы и они убрали его из fan-out, И оповещает ОСТАЛЬНЫЕ мои устройства по
     * self-sync ([SelfSyncManager.revokeDevice]), чтобы и они перестали слать/анонсировать
     * на отозванное устройство. Честная граница (§10): отзыв — это «перестать
     * слать/принимать», а не ретроактивная защита.
     */
    suspend fun revokeLinkedDevice(deviceId: Int) {
        val device = _deviceSettings.value
        if (deviceId == device.localDeviceId) return
        val updated = device.linkedDevices.map {
            if (it.deviceId == deviceId) it.copy(revoked = true) else it
        }
        updateDeviceSettings(device.copy(linkedDevices = updated))
        runCatching { deviceAnnouncer.revokeMyDeviceToContacts(deviceId) }
        runCatching { selfSyncManager.revokeDevice(deviceId) }
    }

    /**
     * Перечитывает состояние после применённого переноса аккаунта (§7.1.2):
     * подхватывает восстановленные настройки, PSK и список чатов в живые потоки.
     */
    suspend fun reloadAfterImport() {
        _securitySettings.value = secureStore.loadSecuritySettings()
        _torNetworkSettings.value = loadNormalizedTorSettings()
        _privacySettings.value = secureStore.loadPrivacySettings()
        _notificationSettings.value = secureStore.loadNotificationSettings()
        _appearanceSettings.value = secureStore.loadAppearanceSettings()
        _deviceSettings.value = secureStore.loadDeviceSettings()
        // Импортированный профиль может нести другой режим сети: транспорт
        // переключается тем же упорядоченным путём, что и переключателем.
        networkModeController.switchTo(_securitySettings.value.netTor, "импорт")
        pskManager.refresh()
        messagingRepository.refreshConversations()
    }

    /**
     * Обновляет настройки оформления (§14.6): тема и обои чата. Сохраняет и
     * обновляет поток — тема/обои применяются «на лету» (Compose перерисовывает
     * UI, перезапуск Activity не нужен). Язык сюда НЕ входит (см. LocaleManager).
     */
    suspend fun setAppearanceSettings(settings: AppearanceSettings) {
        _appearanceSettings.value = settings
        secureStore.saveAppearanceSettings(settings)
    }

    /** Разрешает/запрещает звонки (§14.9). Сохраняет и обновляет поток. */
    suspend fun setCallsEnabled(enabled: Boolean) {
        val updated = _securitySettings.value.copy(callsEnabled = enabled)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "настройка", "callsEnabled" to enabled)
    }

    /**
     * Обновляет настройки приватности (§14.2): отчёты о прочтении, индикатор
     * «печатает…», онлайн-статус. Сохраняет и обновляет поток — [ChatSignals]
     * подхватывает значения через провайдер при следующей отправке.
     */
    suspend fun setPrivacySettings(settings: PrivacySettings) {
        _privacySettings.value = settings
        secureStore.savePrivacySettings(settings)
        DiagnosticLog.event(
            DIAG_TAG, "настройка приватности",
            "прочтение" to settings.sendReadReceipts,
            "печатает" to settings.sendTypingIndicators,
            "онлайн" to settings.shareOnlineStatus,
        )
    }

    /** Блокировка/разблокировка контакта (§14.2, чёрный список). */
    suspend fun setContactBlocked(contactId: String, blocked: Boolean) {
        DiagnosticLog.event(
            DIAG_TAG, "контакт блокировка",
            "id" to DiagnosticLog.shortId(contactId),
            "заблокирован" to blocked,
        )
        messagingRepository.setBlocked(contactId, blocked)
    }

    /**
     * ПОЛНОЕ локальное удаление контакта: единственная точка, из которой это
     * делается (раздел «Контакты» и «удалить чат» в переписке зовут её же).
     *
     * Удаление размазано по пяти хранилищам, и пропуск любого из них — не
     * косметика:
     *
     *  * PSK слоя A сносится ПЕРВЫМ. Список установленных PSK — источник истины
     *    для синхронизации контактов (`MainScaffold` зовёт `ensureContact` на
     *    каждый ключ), поэтому оставшийся ключ воскресил бы запись контакта
     *    сразу после удаления.
     *  * Сообщения, вложения, устройства собеседника и сама запись контакта
     *    уходят вместе ([MessagingRepository.deleteConversation]).
     *  * Заявки этого собеседника (`contact_requests`) удаляются тоже: иначе
     *    отклонённая/завершённая заявка осталась бы висеть с его ником и
     *    приветствием — теми самыми данными, которые пользователь удалял.
     *  * Живой звонок с этим контактом кладётся: после сноса PSK шифровать
     *    сигналинг всё равно нечем, и звонок молча завис бы.
     *  * Показанные уведомления от контакта убираются — в шторке остались бы
     *    его имя и текст.
     *
     * Операция локальная: собеседник о ней не узнаёт (§10.9), а идентичность
     * его аккаунта не затрагивается — контакт можно завести заново с нуля.
     */
    suspend fun deleteContact(contactId: String) {
        DiagnosticLog.event(
            DIAG_TAG, "контакт удалён",
            "id" to DiagnosticLog.shortId(contactId),
        )
        if (callManager.state.value.contactId == contactId && callIsLive()) {
            runCatching { callManager.hangUp() }
        }
        runCatching { pskManager.remove(contactId) }.onFailure { error ->
            DiagnosticLog.e(DIAG_TAG, error) {
                "PSK контакта не удалён ${DiagnosticLog.shortId(contactId)}"
            }
        }
        messagingRepository.deleteConversation(contactId)
        withContext(Dispatchers.IO) {
            database.contactRequestStore.all()
                .filter { it.peerAccountId == contactId }
                .forEach { database.contactRequestStore.delete(it.id) }
        }
        refreshContactRequests()
        notifier.cancelMessages(contactId)
    }

    /**
     * ПОЛНАЯ очистка устройства от данных аккаунта (§7.4).
     *
     * Стереть хранилища мало: процесс живёт дальше, и всё, что из них уже
     * прочитано, остаётся в памяти — списки чатов и контактов, PSK, заявки, теги
     * обнаружения, реестр устройств, настройки. Новый аккаунт увидел бы это
     * сразу, а фоновые циклы дописали бы данные прежнего уже в новую БД
     * (ключ БД после очистки создаётся заново). Поэтому порядок такой:
     *
     *  1. погасить всё, что пишет, показывает или озвучивает данные аккаунта;
     *  2. ступень 1: crypto-shredding хранилищ, журнал диагностики, снимки камеры;
     *  3. контрольный проход по файлам хранилищ (см. ниже);
     *  4. сбросить память к состоянию чистого устройства;
     *  5. ступень 2 (если выбрана) — уже при пустом интерфейсе: она идёт минутами;
     *  6. вернуть процессные циклы — без разблокированного аккаунта они простаивают.
     *
     * Контрольный проход нужен потому, что отменённый на шаге 1 цикл, который
     * уже расшифровывал конверт, дописывает начатое — в пересозданную БД. Новых
     * записей после отмены не бывает: каждый обработчик входящих начинается с
     * `withContext`, через который отменённая корутина не проходит.
     *
     * Шаги изолированы: сбой одного не должен оставить остальные несделанными.
     * Вызывающий ([AccountManager]) держит это в `NonCancellable`.
     */
    private suspend fun wipeLocalData(includeBestEffort: Boolean): WipeReport {
        DiagnosticLog.event(DIAG_TAG, "полная очистка устройства", "ступень2" to includeBestEffort)
        // Подписанный конфиг релея публичный и общий для всех клиентов — данных
        // аккаунта в нём нет. Со сбросом новый аккаунт до следующей синхронизации
        // (до 6 ч) ходил бы на зашитый в сборку адрес, а защита от отката конфига
        // обнулилась бы. Адрес, введённый вручную, — выбор прежнего владельца.
        val relayConfig = _torNetworkSettings.value.let { tor ->
            if (tor.relayManual) {
                TorNetworkSettings.DEFAULT
            } else {
                TorNetworkSettings.DEFAULT.copy(
                    onionRelayAddress = tor.onionRelayAddress,
                    httpsRelayAddress = tor.httpsRelayAddress,
                    httpsPins = tor.httpsPins,
                    configVersion = tor.configVersion,
                )
            }
        }

        quiesceAccountActivity()
        // Ключ — до стирания файлов: запоздалая запись не воссоздаст файл под
        // ключом прежнего аккаунта, а упадёт.
        storageKey.lock()

        // Теги обнаружения пишутся под discoveryMutex: держим его всю хранилищную
        // фазу, чтобы начатый ensureDiscoveryTag не вернул на диск теги прежнего
        // аккаунта уже после контрольного прохода.
        val shredded = discoveryMutex.withLock {
            val shredReport = wipeStep("хранилища") { storageWipe.cryptoShred() }
            wipeStep("журнал диагностики") { DiagnosticLog.purge(diagnosticLogDir()) }
            wipeStep("метка процесса") { com.aegis.messenger.diag.ProcessDeathReport.clearState(appContext) }
            wipeStep("снимки камеры") { CameraCapture.purgeAll(appContext) }
            wipeStep("контрольный проход") { storageWipe.deleteStorageFiles() }
            wipeStep("очередь закрытого режима") { lockedInbox.clear() }
            _discoverySettings.value = DiscoverySettings.DEFAULT
            shredReport
        }

        resetInMemoryState(relayConfig)

        val report = if (includeBestEffort) {
            wipeStep("ступень 2") { storageWipe.overwriteFreeSpace() } ?: shredded
        } else {
            shredded
        }

        if (initialized.get()) resumeProcessLoops()
        return report ?: WipeReport(
            cryptoShreddingDone = false,
            bestEffortDone = false,
            note = "Crypto-shredding не завершён — см. журнал диагностики.",
        )
    }

    /** Шаг 1 [wipeLocalData]: гасит всё, что пишет, показывает или озвучивает данные аккаунта. */
    private fun quiesceAccountActivity() {
        relayReceiver.stop()
        inviteReceiver.stop()
        keyExchangeChannel.stop()
        directoryPublisher.stop()
        retentionEnforcer.stop()
        coverTraffic.stop()
        pskManager.cancelExchange()
        PskNfcPayloadHolder.clear()
        // Тишина ДО сброса звонка: иначе принудительный hangUp проиграл бы тон
        // завершения (см. ветку блокировки в init).
        runCatching { callToneController.silence() }
        runCatching { callManager.hangUp() }
        runCatching { stopLockedRinging() }
        runCatching { callRingtonePlayer.stop() }
        runCatching { incomingCallWaker.release() }
        runCatching { incomingCallPresenter.stop() }
        runCatching { CallAudioSession.reset(appContext) }
        runCatching { CallMediaService.stop(appContext) }
        runCatching { MessageSyncService.stop(appContext) }
        runCatching { notifier.cancelAll() }
    }

    /**
     * Шаг 4 [wipeLocalData]: память — к состоянию чистого устройства. Хранилища
     * уже пусты, поэтому настройки — значения по умолчанию (как у нового
     * аккаунта, [SecuritySettings.DEFAULT]), а адрес релея — [relayConfig] поверх
     * зашитого в сборку. Теги обнаружения сбрасываются раньше, под своим мьютексом.
     */
    private suspend fun resetInMemoryState(relayConfig: TorNetworkSettings) {
        _securitySettings.value = SecuritySettings.DEFAULT
        _privacySettings.value = PrivacySettings.DEFAULT
        _notificationSettings.value = NotificationSettings.DEFAULT
        _appearanceSettings.value = AppearanceSettings.DEFAULT
        _deviceSettings.value = DeviceSettings.DEFAULT
        _diagnosticSettings.value = DiagnosticSettings.DEFAULT
        val tor = normalizedTorSettings(relayConfig)
        _torNetworkSettings.value = tor
        if (tor != TorNetworkSettings.DEFAULT) {
            wipeStep("настройки сети") { secureStore.saveTorNetworkSettings(tor) }
        }

        _contactRequests.value = emptyList()
        _pendingChatOpen.value = null
        _pendingContactRequestsOpen.value = false
        _pendingCallAccept.value = false
        visibleChatContactId = null
        answerAfterUnlockUntilMs = 0L
        lockedCallDismissedAtMs = 0L
        lockedMessageCount.set(0)

        messagingRepository.clearCache()
        wipeStep("сигналы чатов") { chatSignals.reset() }
        wipeStep("список PSK") { pskManager.refresh() }
    }

    /** Шаг 6 [wipeLocalData]: процессные циклы из [initialize] — обратно. */
    private suspend fun resumeProcessLoops() {
        // Настройки сброшены к умолчанию, то есть к Tor ВКЛ: если до очистки
        // был режим без Tor, транспорт переключается упорядоченно.
        networkModeController.switchTo(_securitySettings.value.netTor, "очистка")
        relayReceiver.start()
        retentionEnforcer.start()
        coverTraffic.start()
    }

    /** Шаг очистки, сбой которого записывается в журнал, но не прерывает остальные. */
    private inline fun <T> wipeStep(name: String, block: () -> T): T? =
        runCatching(block)
            .onFailure { e -> DiagnosticLog.e(DIAG_TAG, e) { "очистка: шаг «$name» не выполнен" } }
            .getOrNull()

    /**
     * Обновляет глобальные настройки уведомлений (§14.5). Сохраняет и обновляет
     * поток; [AegisNotifier] подхватывает значения при следующем показе (каналы
     * пересоздаются под новую конфигурацию звука/вибрации).
     */
    suspend fun setNotificationSettings(settings: NotificationSettings) {
        _notificationSettings.value = settings
        secureStore.saveNotificationSettings(settings)
    }

    /**
     * Приостановить все уведомления на время (§14.5): `untilMs` — Unix-время,
     * [NotificationSettings.MUTE_FOREVER] — навсегда, 0 — снять паузу.
     */
    suspend fun snoozeAllNotifications(untilMs: Long) {
        setNotificationSettings(_notificationSettings.value.copy(muteAllUntilMs = untilMs))
    }

    /** Персональное отключение уведомлений контакта (§14.5). */
    suspend fun setContactMuted(contactId: String, untilMs: Long) {
        messagingRepository.setContactMuted(contactId, untilMs)
        notifier.cancelMessages(contactId)
    }

    /** Персональные звуки уведомлений контакта: сообщение и звонок (§14.5). */
    suspend fun setContactNotificationSounds(
        contactId: String,
        messageSoundUri: String,
        callRingtoneUri: String,
    ) {
        messagingRepository.setContactNotificationSounds(contactId, messageSoundUri, callRingtoneUri)
    }

    /**
     * Локальное переименование чата. Имя не логируется (PII), только id контакта.
     * Собеседника и ключи это не затрагивает.
     */
    suspend fun renameChat(contactId: String, rawName: String) {
        DiagnosticLog.event(
            DIAG_TAG, "чат переименование",
            "id" to DiagnosticLog.shortId(contactId),
        )
        messagingRepository.setChatLocalName(contactId, rawName)
    }

    /**
     * Чат стал видимым (§14.5): подавляем его уведомления и убираем уже
     * показанные для этого контакта.
     */
    fun onChatVisible(contactId: String) {
        visibleChatContactId = contactId
        notifier.cancelMessages(contactId)
    }

    /** Чат скрыт (§14.5): снимаем пометку видимого чата. */
    fun onChatHidden(contactId: String) {
        if (visibleChatContactId == contactId) visibleChatContactId = null
    }

    /**
     * Ручной повтор догоняющего обмена ключами слоя B (§6.2, кнопка в баннере
     * чата): не ждать периодический цикл, а запросить бандл собеседника сразу.
     * Best-effort — без Tor/релея ничего не произойдёт.
     */
    suspend fun retryKeyExchange(contactId: String) {
        DiagnosticLog.event(DIAG_TAG, "ручной повтор обмена ключами", "contact" to DiagnosticLog.shortId(contactId))
        runCatching { keyExchangeChannel.announceTo(contactId, request = true) }
    }

    // --- Диагностика ---------------------------------------------------------

    /**
     * Включает/выключает журнал отладки ([DiagnosticLog]) и запоминает выбор.
     * При включении сразу пишет снимок окружения ([dumpEnvironment]) — без него
     * журнал начинается «с середины» и по нему не понять исходное состояние.
     */
    suspend fun setDiagnosticLogEnabled(enabled: Boolean) {
        val updated = _diagnosticSettings.value.copy(logEnabled = enabled)
        _diagnosticSettings.value = updated
        applyDiagnosticLog(updated)
        secureStore.saveDiagnosticSettings(updated)
    }

    /** Применяет настройку журнала к [DiagnosticLog] (каталог — приватный кэш). */
    private suspend fun applyDiagnosticLog(settings: DiagnosticSettings) {
        if (settings.logEnabled) {
            DiagnosticLog.enable(diagnosticLogDir())
            // Причина смерти ПРОШЛОГО процесса по данным системы. Пишем сразу
            // после enable: если приложение упало в звонке, это первое, что
            // нужно видеть в выгрузке — до всех наших догадок.
            com.aegis.messenger.diag.ProcessDeathReport.dumpOnce(appContext)
            dumpEnvironment("журнал включён")
        } else {
            DiagnosticLog.disable()
            // Метка состояния живёт в системной записи ApplicationExitInfo, вне
            // приватного каталога. При выключенной диагностике следа звонка там
            // остаться не должно (§10.10).
            com.aegis.messenger.diag.ProcessDeathReport.clearState(appContext)
        }
    }

    /** Каталог журнала: приватный кэш, доступен FileProvider'у для отправки. */
    private fun diagnosticLogDir(): java.io.File =
        java.io.File(appContext.cacheDir, "diagnostics")

    /** Стирает журнал (кнопка «Очистить» в настройках). */
    fun clearDiagnosticLog() = DiagnosticLog.clear()

    /** Текущий размер журнала в байтах (для показа в настройках). */
    fun diagnosticLogSizeBytes(): Long = DiagnosticLog.sizeBytes()

    /**
     * Готовит файл журнала к отправке: до-пишет свежий снимок окружения, чтобы в
     * выгрузке было ИТОГОВОЕ состояние (ключи контактов, Tor, релей), и отдаёт
     * снимок (текущий + ротированный предыдущий). `null`, если журнал пуст.
     */
    suspend fun diagnosticLogForExport(): java.io.File? {
        dumpEnvironment("выгрузка журнала")
        return DiagnosticLog.snapshotForExport() ?: DiagnosticLog.fileOrNull()
    }

    /**
     * Снимок окружения — самая полезная часть журнала: по нему сразу видно, есть
     * ли вообще транспорт (адрес релея, порт SOCKS, слой B) и в каком состоянии
     * ключи каждого контакта. Именно тут проявляется типичная причина «ошибки
     * E2EE»: пустой `relay` ⇒ ни бандл, ни сообщения физически никуда не уходят.
     *
     * Секретов не раскрывает: accountId усечён, ящики — отпечатками, PSK/бандлы —
     * только признак наличия и отпечаток.
     */
    suspend fun dumpEnvironment(reason: String) {
        if (!DiagnosticLog.isEnabled) return
        runCatching {
            val tor = _torNetworkSettings.value
            val device = _deviceSettings.value
            val identity = accountManager.currentIdentity()
            val sec = _securitySettings.value
            val priv = _privacySettings.value
            DiagnosticLog.event(
                DIAG_TAG, "снимок окружения",
                "причина" to reason,
                "сессия" to DiagnosticLog.sessionId(),
                "версия" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "android" to android.os.Build.VERSION.SDK_INT,
                "устройство" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "abi" to android.os.Build.SUPPORTED_ABIS.joinToString(","),
                "отброшено" to DiagnosticLog.droppedCount(),
                "памятьМб" to (Runtime.getRuntime().totalMemory() / (1024 * 1024)),
                "свободноМб" to (Runtime.getRuntime().freeMemory() / (1024 * 1024)),
                "лимитHeapМб" to (Runtime.getRuntime().maxMemory() / (1024 * 1024)),
                "nativeКб" to (android.os.Debug.getNativeHeapAllocatedSize() / 1024),
                "pid" to android.os.Process.myPid(),
                "потоки" to Thread.activeCount(),
                "производителиЯдер" to Runtime.getRuntime().availableProcessors(),
                // Дескрипторы/RSS/oom_score_adj: по ним видно смерть от LMK или
                // исчерпания fd — то, чего нативный обработчик сигналов не ловит.
                *com.aegis.core.common.diag.ProcSelf.fields(),
            )
            DiagnosticLog.event(
                DIAG_TAG, "нативное ядро",
                "готово" to CryptoModule.isNativeReady,
                "версия" to CryptoModule.nativeVersion(),
                "слойB" to signalSessionManager.isAvailable,
                "tor" to CryptoModule.hasTorSupport,
                "psk" to pskManager.isReady,
                "звонки" to CryptoModule.calls.isAvailable,
            )
            DiagnosticLog.event(
                DIAG_TAG, "настройки безопасности",
                "пресет" to sec.matchingPreset().name,
                "tor" to sec.netTor.name,
                "звонки" to sec.callsEnabled,
                "автоблокМин" to sec.autoLockMinutes,
                "хранениеДней" to sec.autoDeleteDays,
                "автомедиа" to sec.autoDownloadMedia,
                "джиттер" to sec.fanoutJitter.name,
                "cover" to sec.coverTraffic.name,
            )
            DiagnosticLog.event(
                DIAG_TAG, "настройки приватности",
                "прочтение" to priv.sendReadReceipts,
                "печатает" to priv.sendTypingIndicators,
                "онлайн" to priv.shareOnlineStatus,
            )
            // Ключевое: без адреса релея для текущего режима (и порта SOCKS в
            // режиме Tor) сеть недоступна ЦЕЛИКОМ, сколько бы раз ни повторяли обмен ключами.
            DiagnosticLog.event(
                DIAG_TAG, "транспорт",
                "режим" to sec.netTor.name,
                "состояние" to networkModeController.state.value.toString(),
                "onionЗадан" to tor.onionRelayAddress.isNotBlank(),
                "httpsЗадан" to tor.httpsRelayAddress.isNotBlank(),
                "пинов" to tor.effectiveHttpsPins().size,
                "релейВручную" to tor.relayManual,
                "onionИзСборки" to BuildConfig.DEFAULT_RELAY_ONION.isNotBlank(),
                "httpsИзСборки" to BuildConfig.DEFAULT_RELAY_HTTPS.isNotBlank(),
                "ключКонфига" to BuildConfig.RELAY_CONFIG_PUBKEY.isNotBlank(),
                "socksПорт" to torTransport.socksPort,
                "torСтатус" to torTransport.status.value,
                "мосты" to tor.bridgesEnabled,
                "клиентРелея" to relayGateway.clientOrNull()?.transportKind?.name,
                "причинаБезКлиента" to relayGateway.unavailable.value?.name,
            )
            DiagnosticLog.event(
                DIAG_TAG, "аккаунт",
                "состояние" to accountManager.state.value.label,
                "accountId" to DiagnosticLog.shortId(identity?.accountId),
                "отпечаток" to DiagnosticLog.fp(identity?.fingerprint),
                "устройство" to device.localDeviceId,
                "первичное" to device.isPrimary,
            )
            val contacts = withContext(Dispatchers.IO) { database.contactStore.all() }
            DiagnosticLog.event(DIAG_TAG, "контакты", "всего" to contacts.size)
            for (contact in contacts) {
                val psk = withContext(Dispatchers.IO) {
                    pskManager.cipherFor(contact.contact.id) != null
                }
                DiagnosticLog.event(
                    DIAG_TAG, "контакт",
                    "id" to DiagnosticLog.shortId(contact.contact.id),
                    "ник" to contact.contact.nickname.ifBlank { "-" },
                    "pskЕсть" to psk,
                    "identityЕсть" to !contact.remoteIdentityB64.isNullOrBlank(),
                    "бандлЕсть" to !contact.bundleJson.isNullOrBlank(),
                    "сессия" to contact.sessionReady,
                    "проверен" to contact.contact.verified,
                    "устройств" to contact.activeDevices().size,
                )
            }
        }
    }

    /** Запрос открыть чат из уведомления (§14.5): выставляется из [MainActivity]. */
    fun requestOpenChat(contactId: String) {
        _pendingChatOpen.value = contactId
    }

    /** Сбросить отложенный переход в чат после навигации (§14.5). */
    fun consumePendingChatOpen() {
        _pendingChatOpen.value = null
    }

    /**
     * Таймаут автоблокировки по бездействию (§14.3), в минутах;
     * [AegisConstants.AUTO_LOCK_OFF] (0) отключает. Значение нормализуется в
     * допустимый диапазон, сохраняется и обновляет поток — [IdleLockController]
     * подхватывает новый таймаут через провайдер.
     */
    suspend fun setAutoLockMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(AegisConstants.AUTO_LOCK_OFF, AegisConstants.AUTO_LOCK_MAX_MINUTES)
        val updated = _securitySettings.value.copy(autoLockMinutes = clamped)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
    }

    /**
     * Приём сообщений и звонков при закрытом аккаунте (§8 `deliverWhileLocked`).
     *
     * Включение применяется СРАЗУ, не дожидаясь следующей разблокировки: если
     * пользователь щёлкнул переключатель, находясь на закрытом аккаунте (такое
     * возможно только с уже открытого — но состояние может смениться, пока
     * настройка сохраняется), сервис приёма поднимается здесь же. Выключение
     * симметрично гасит сервис, когда аккаунт уже закрыт.
     */
    suspend fun setDeliverWhileLocked(enabled: Boolean) {
        val updated = _securitySettings.value.copy(deliverWhileLocked = enabled)
        _securitySettings.value = updated
        secureStore.saveSecuritySettings(updated)
        DiagnosticLog.event(DIAG_TAG, "приём при блокировке", "включён" to enabled)
        if (receivingAllowedNow()) {
            MessageSyncService.start(this.appContext)
            relayReceiver.restartLoops()
        } else {
            MessageSyncService.stop(this.appContext)
        }
    }

    /**
     * Диагностика (§14.7): проверяет достижимость onion-релея через встроенный
     * Tor. Адрес — `host.onion[:port]` или полный URL; схема `https://` отбрасывается.
     * В сеть НЕ публикуется (только локальная проверка). Только в режиме Tor: без
     * Tor Arti не запущен, и запускать его ради проверки нельзя.
     */
    suspend fun testOnion(address: String): String = withContext(Dispatchers.IO) {
        val res = LocaleManager.wrap(appContext)
        if (_securitySettings.value.netTor != TorMode.ALWAYS) {
            return@withContext res.getString(R.string.onion_err_tor_off)
        }
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return@withContext res.getString(R.string.onion_err_empty)
        val parsed = OnionRelayAddress.parse(trimmed)
            ?: return@withContext res.getString(R.string.onion_err_invalid)
        if (!CryptoModule.hasTorSupport) return@withContext res.getString(R.string.onion_err_no_tor)
        DiagnosticLog.event(
            DIAG_TAG, "проверка onion",
            "хост" to DiagnosticLog.fp(parsed.host),
            "порт" to parsed.port,
        )
        when (val ready = waitForTorReady(BOOTSTRAP_WAIT_MS)) {
            TorStatus.Disabled, is TorStatus.Error, TorStatus.Unavailable -> {
                DiagnosticLog.w(DIAG_TAG) { "проверка onion: Tor не запущен ($ready)" }
                return@withContext res.getString(R.string.onion_err_not_running)
            }
            is TorStatus.Starting, is TorStatus.Bootstrapping -> {
                DiagnosticLog.w(DIAG_TAG) { "проверка onion: Tor ещё подключается ($ready)" }
                return@withContext res.getString(R.string.onion_err_bootstrapping)
            }
            TorStatus.Connected -> Unit
        }
        when (val result = CryptoModule.tor.testConnect(parsed.host, parsed.port, TEST_TIMEOUT_MS)) {
            null -> {
                DiagnosticLog.w(DIAG_TAG) { "проверка onion: Tor не запущен" }
                res.getString(R.string.onion_err_not_running)
            }
            "+ok" -> {
                DiagnosticLog.event(DIAG_TAG, "проверка onion: ок")
                res.getString(R.string.onion_ok)
            }
            else -> {
                val raw = result.removePrefix("-")
                DiagnosticLog.w(DIAG_TAG) { "проверка onion: $raw" }
                mapOnionConnectError(res, raw)
            }
        }
    }

    /**
     * Проверка соединения с релеем по HTTPS (`GET /v1/health`) — аналог
     * [testOnion] для режима без Tor. Только в этом режиме: в режиме Tor прямое
     * соединение раскрыло бы IP серверу. Соединение принадлежит текущей сессии
     * транспорта, поэтому переключение режима его оборвёт. Пины проверяются,
     * если адрес — тот же автоматический, к которому они выданы.
     */
    suspend fun testHttps(address: String): String = withContext(Dispatchers.IO) {
        val res = LocaleManager.wrap(appContext)
        if (_securitySettings.value.netTor != TorMode.OFF) {
            return@withContext res.getString(R.string.https_err_tor_on)
        }
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return@withContext res.getString(R.string.https_err_empty)
        val parsed = HttpsRelayAddress.parse(trimmed)
            ?: return@withContext res.getString(R.string.https_err_invalid)
        val settings = _torNetworkSettings.value
        val pins = if (parsed.stored == settings.httpsRelayAddress) settings.effectiveHttpsPins() else emptyList()
        val transport = relayGateway.probeHttpsTransport(parsed.stored, pins)
            ?: return@withContext res.getString(R.string.https_err_switching)
        DiagnosticLog.event(DIAG_TAG, "проверка HTTPS", "хост" to DiagnosticLog.fp(parsed.host), "пинов" to pins.size)
        val health = runCatching { RelayClient(transport).health() }
        health.fold(
            onSuccess = { result ->
                when (result) {
                    RelayHealth.Ok -> res.getString(R.string.https_ok)
                    RelayHealth.NotARelay -> res.getString(R.string.https_err_not_relay)
                    is RelayHealth.HttpError -> res.getString(R.string.https_err_status, result.status)
                }
            },
            onFailure = { e ->
                DiagnosticLog.w(DIAG_TAG) { "проверка HTTPS не прошла: ${e.javaClass.simpleName}" }
                when (e) {
                    is javax.net.ssl.SSLException -> res.getString(R.string.https_err_tls)
                    is java.net.UnknownHostException -> res.getString(R.string.https_err_dns)
                    is java.net.SocketTimeoutException -> res.getString(R.string.https_err_timeout)
                    else -> res.getString(R.string.https_err_unreachable)
                }
            },
        )
    }

    private suspend fun waitForTorReady(timeoutMs: Long): TorStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val status = torTransport.status.value
            when (status) {
                TorStatus.Connected -> return status
                TorStatus.Disabled, is TorStatus.Error, TorStatus.Unavailable -> return status
                else -> {
                    if (System.currentTimeMillis() >= deadline) return status
                    delay(200)
                }
            }
        }
    }

    private fun mapOnionConnectError(ctx: Context, raw: String): String {
        val lower = raw.lowercase()
        return when {
            "hostname lookup" in lower || "protocol error while launching" in lower ->
                ctx.getString(R.string.onion_err_unreachable)
            "таймаут" in lower || "timeout" in lower ->
                ctx.getString(R.string.onion_err_timeout)
            else -> ctx.getString(R.string.onion_err_prefix, raw)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 20_000
        const val BOOTSTRAP_WAIT_MS = 25_000L

        /**
         * Сколько после гудка закрытого режима звонковые конверты не будят снова:
         * отбой звонящего по его таймауту приходит как раз к концу нашего гудка.
         */
        const val LOCKED_RING_QUIET_MS = 30_000L

        /** Тег записей уровня приложения в журнале диагностики. */
        const val DIAG_TAG = "app"
    }
}
