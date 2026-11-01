package com.aegis.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.aegis.core.common.AegisConstants
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.appearance.ChatWallpaper
import com.aegis.core.common.call.CallDirection
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.core.common.security.CoverTrafficMode
import com.aegis.core.common.security.FanoutJitterMode
import com.aegis.core.common.security.SecuritySettings
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.core.ui.theme.aegisHudBackdrop
import com.aegis.data.storage.KeyStoreManager
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.AccountTransfer
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.ui.device.DeviceLinkScreen
import com.aegis.messenger.ui.device.MyDevicesScreen
import com.aegis.messenger.call.CallManager
import com.aegis.core.common.model.AttachmentKind
import com.aegis.messenger.messaging.AttachmentReader
import com.aegis.messenger.messaging.CameraCapture
import com.aegis.messenger.messaging.ChatSignals
import com.aegis.messenger.messaging.ContactVerification
import com.aegis.messenger.messaging.MessagingRepository
import com.aegis.messenger.psk.PskManager
import com.aegis.messenger.ui.call.CallScreen
import com.aegis.messenger.ui.call.DeliverySetupPrompt
import com.aegis.messenger.ui.call.rememberCallPermissionRequester
import com.aegis.messenger.ui.chats.ChatScreen
import com.aegis.messenger.ui.chats.ChatsScreen
import com.aegis.messenger.ui.components.AegisBottomBar
import com.aegis.messenger.ui.components.AegisTopBar
import com.aegis.messenger.ui.contacts.ContactVerificationScreen
import com.aegis.messenger.ui.contacts.ContactRequestsUi
import com.aegis.messenger.ui.contacts.ContactsScreen
import com.aegis.messenger.ui.privacy.BlockListScreen
import com.aegis.messenger.ui.navigation.TopLevelDestination
import com.aegis.messenger.ui.profile.ProfileScreen
import com.aegis.messenger.ui.settings.LocalTorEnabled
import com.aegis.messenger.ui.settings.NetworkUi
import com.aegis.messenger.ui.settings.SettingsScreen
import com.aegis.messenger.ui.transfer.TransferExportScreen
import com.aegis.messenger.ui.transfer.TransferImportMode
import com.aegis.messenger.ui.transfer.TransferImportScreen
import com.aegis.network.tor.TorStatus
import com.aegis.network.tor.TorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Корневой composable приложения. Внешний NavHost разделяет главный экран с
 * вкладками ([MainScaffold]) и полноэкранный чат ([ChatRoute]).
 */
@Composable
fun AegisApp(
    torTransport: TorTransport,
    accountProfile: AccountProfile,
    accountManager: AccountManager,
    accountTransfer: AccountTransfer,
    deviceLinking: DeviceLinking,
    appScope: CoroutineScope,
    deviceSettings: DeviceSettings,
    onReloadAfterImport: suspend () -> Unit,
    onReloadDeviceSettings: suspend () -> Unit,
    onRenameThisDevice: suspend (String) -> Unit = {},
    onRenameLinkedDevice: suspend (Int, String) -> Unit = { _, _ -> },
    onRevokeLinkedDevice: suspend (Int) -> Unit = {},
    keyStoreManager: KeyStoreManager,
    pskManager: PskManager,
    messagingRepository: MessagingRepository,
    callManager: CallManager,
    contactVerification: ContactVerification,
    chatSignals: ChatSignals,
    securitySettings: SecuritySettings = SecuritySettings.DEFAULT,
    torNetworkSettings: TorNetworkSettings = TorNetworkSettings.DEFAULT,
    privacySettings: PrivacySettings = PrivacySettings.DEFAULT,
    notificationSettings: NotificationSettings = NotificationSettings.DEFAULT,
    appearanceSettings: AppearanceSettings = AppearanceSettings.DEFAULT,
    nativeCryptoVersion: String? = null,
    torSupported: Boolean = false,
    pendingChatOpen: String? = null,
    pendingCallAccept: Boolean = false,
    /** Режим сети, причина недоступности релея, переключатель Tor и проверки соединения. */
    network: NetworkUi = NetworkUi(),
    onUpdateTorNetwork: (TorNetworkSettings) -> Unit = {},
    onSetCallsEnabled: (Boolean) -> Unit = {},
    onSetAutoLockMinutes: (Int) -> Unit = {},
    onSetDeliverWhileLocked: (Boolean) -> Unit = {},
    onSetAutoDeleteDays: (Int) -> Unit = {},
    onSetAutoDownloadMedia: (Boolean) -> Unit = {},
    onSetFanoutJitter: (FanoutJitterMode) -> Unit = {},
    onSetCoverTraffic: (CoverTrafficMode) -> Unit = {},
    onSetPrivacySettings: (PrivacySettings) -> Unit = {},
    onSetContactBlocked: (String, Boolean) -> Unit = { _, _ -> },
    onSetNotificationSettings: (NotificationSettings) -> Unit = {},
    onSnoozeAllNotifications: (Long) -> Unit = {},
    onSetContactMuted: (String, Long) -> Unit = { _, _ -> },
    onSetContactNotificationSounds: (String, String, String) -> Unit = { _, _, _ -> },
    onRenameChat: (String, String) -> Unit = { _, _ -> },
    onChatVisible: (String) -> Unit = {},
    onChatHidden: (String) -> Unit = {},
    onRetryKeyExchange: (String) -> Unit = {},
    onConsumePendingChatOpen: () -> Unit = {},
    onConsumePendingCallAccept: () -> Unit = {},
    /**
     * Принять/отклонить входящий звонок. Идут через контейнер, а не напрямую в
     * [CallManager]: рингтон и вибрацию нужно погасить синхронно с нажатием
     * (см. `AppContainer.acceptIncomingCall`).
     */
    onAcceptCall: () -> Unit = {},
    onDeclineCall: () -> Unit = {},
    onSetMultiDeviceAllowed: (Boolean) -> Unit = {},
    onSetAppearance: (AppearanceSettings) -> Unit = {},
    diagnosticSettings: DiagnosticSettings = DiagnosticSettings.DEFAULT,
    onSetDiagnosticLogEnabled: (Boolean) -> Unit = {},
    diagnosticLogSizeBytes: () -> Long = { 0L },
    onClearDiagnosticLog: () -> Unit = {},
    prepareDiagnosticExport: suspend () -> java.io.File? = { null },
    /**
     * Удалённое добавление контактов (`docs/contact_requests.md`) — одним
     * холдером, чтобы не раздувать три сигнатуры десятком колбэков.
     */
    contactRequests: ContactRequestsUi = ContactRequestsUi.DISABLED,
    onSetContactRequestMode: (ContactRequestMode) -> Unit = {},
    onSetContactDeclineNotify: (Boolean) -> Unit = {},
    /**
     * Полное локальное удаление контакта (`AppContainer.deleteContact`): и
     * «удалить чат» в переписке, и удаление из раздела «Контакты» идут сюда,
     * чтобы набор вычищаемых хранилищ не разъезжался между двумя экранами.
     */
    onDeleteContact: suspend (String) -> Unit = {},
    /** Запрос перехода на экран заявок из уведомления. */
    pendingContactRequestsOpen: Boolean = false,
    onConsumePendingContactRequestsOpen: () -> Unit = {},
) {
    val rootNav = rememberNavController()
    val call by callManager.state.collectAsStateWithLifecycle()
    // Runtime-разрешения (микрофон/камера) запрашиваются ПЕРЕД поднятием движка.
    val requestCallPerms = rememberCallPermissionRequester()

    // Разрешение на уведомления (Android 13+): просим один раз при входе в
    // разблокированный интерфейс, если пользователь ещё не решал (§14.5).
    RequestNotificationPermissionOnce()

    // Переход в чат из уведомления (§14.5): контейнер выставил id контакта —
    // открываем чат поверх текущего экрана и сбрасываем запрос.
    LaunchedEffect(pendingChatOpen) {
        val target = pendingChatOpen
        if (!target.isNullOrBlank()) {
            rootNav.navigate("chat/$target") {
                launchSingleTop = true
            }
            onConsumePendingChatOpen()
        }
    }

    // Приём звонка из уведомления (§14.5): пользователь нажал «Принять» — просим
    // разрешения (микрофон/камера) и принимаем. Если звонок уже не звонит
    // (отменён/принят иначе) — просто гасим флаг.
    LaunchedEffect(pendingCallAccept, call.phase) {
        if (!pendingCallAccept) return@LaunchedEffect
        val ringingIncoming =
            call.phase == CallPhase.RINGING && call.direction == CallDirection.INCOMING
        if (ringingIncoming) {
            requestCallPerms(call.media == CallMedia.VIDEO) { onAcceptCall() }
        }
        // В любом случае сбрасываем флаг: приняли либо звонок уже не звонит.
        onConsumePendingCallAccept()
    }

    // Адрес релея, без которого сеть не работает, зависит от режима: onion с Tor,
    // HTTPS без Tor. Баннеры в глубине экранов берут тексты из LocalTorEnabled.
    val relayConfigured = torNetworkSettings.relayConfiguredFor(securitySettings.netTor)

    CompositionLocalProvider(LocalTorEnabled provides network.torEnabled) {
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = rootNav, startDestination = "main") {
            composable("main") {
                MainScaffold(
                    torTransport = torTransport,
                    accountProfile = accountProfile,
                    accountManager = accountManager,
                    keyStoreManager = keyStoreManager,
                    pskManager = pskManager,
                    messagingRepository = messagingRepository,
                    securitySettings = securitySettings,
                    torNetworkSettings = torNetworkSettings,
                    relayConfigured = relayConfigured,
                    nativeCryptoVersion = nativeCryptoVersion,
                    torSupported = torSupported,
                    network = network,
                    callLive = call.phase.isLive,
                    onUpdateTorNetwork = onUpdateTorNetwork,
                    mediaEngineReal = callManager.mediaAvailable,
                    onSetCallsEnabled = onSetCallsEnabled,
                    onSetAutoLockMinutes = onSetAutoLockMinutes,
                    onSetDeliverWhileLocked = onSetDeliverWhileLocked,
                    onSetAutoDeleteDays = onSetAutoDeleteDays,
                    onSetAutoDownloadMedia = onSetAutoDownloadMedia,
                    onSetFanoutJitter = onSetFanoutJitter,
                    onSetCoverTraffic = onSetCoverTraffic,
                    privacySettings = privacySettings,
                    onSetPrivacySettings = onSetPrivacySettings,
                    notificationSettings = notificationSettings,
                    onSetNotificationSettings = onSetNotificationSettings,
                    onSnoozeAllNotifications = onSnoozeAllNotifications,
                    onOpenBlockList = { rootNav.navigate("blocklist") },
                    onOpenChat = { contactId -> rootNav.navigate("chat/$contactId") },
                    onRenameChat = onRenameChat,
                    onSetMultiDeviceAllowed = onSetMultiDeviceAllowed,
                    onOpenTransferExport = { rootNav.navigate("transfer/export") },
                    onOpenMergeImport = { rootNav.navigate("transfer/import") },
                    onOpenLinkDevice = { rootNav.navigate("link/device") },
                    onOpenMyDevices = { rootNav.navigate("devices") },
                    appearanceSettings = appearanceSettings,
                    onSetAppearance = onSetAppearance,
                    diagnosticSettings = diagnosticSettings,
                    onSetDiagnosticLogEnabled = onSetDiagnosticLogEnabled,
                    diagnosticLogSizeBytes = diagnosticLogSizeBytes,
                    onClearDiagnosticLog = onClearDiagnosticLog,
                    prepareDiagnosticExport = prepareDiagnosticExport,
                    contactRequests = contactRequests,
                    onSetContactRequestMode = onSetContactRequestMode,
                    onSetContactDeclineNotify = onSetContactDeclineNotify,
                    onVerifyContact = { contactId -> rootNav.navigate("verify/$contactId") },
                    onDeleteContact = onDeleteContact,
                    pendingContactRequestsOpen = pendingContactRequestsOpen,
                    onConsumePendingContactRequestsOpen = onConsumePendingContactRequestsOpen,
                )
            }
            composable("devices") {
                MyDevicesScreen(
                    deviceSettings = deviceSettings,
                    multiDeviceAllowed = accountProfile.multiDeviceAllowed,
                    onRenameThisDevice = onRenameThisDevice,
                    onRenameLinkedDevice = onRenameLinkedDevice,
                    onRevokeLinkedDevice = onRevokeLinkedDevice,
                    onBack = { rootNav.popBackStack() },
                )
            }
            composable("transfer/export") {
                TransferExportScreen(
                    accountTransfer = accountTransfer,
                    multiDeviceAllowed = accountProfile.multiDeviceAllowed,
                    onBack = { rootNav.popBackStack() },
                )
            }
            composable("transfer/import") {
                TransferImportScreen(
                    accountTransfer = accountTransfer,
                    appScope = appScope,
                    onImported = onReloadAfterImport,
                    onBack = { rootNav.popBackStack() },
                    mode = TransferImportMode.Merge,
                )
            }
            composable("link/device") {
                DeviceLinkScreen(
                    deviceLinking = deviceLinking,
                    multiDeviceAllowed = accountProfile.multiDeviceAllowed,
                    onLinked = onReloadDeviceSettings,
                    onBack = { rootNav.popBackStack() },
                )
            }
            composable("chat/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId").orEmpty()
                ChatRoute(
                    contactId = contactId,
                    repository = messagingRepository,
                    chatSignals = chatSignals,
                    callsEnabled = securitySettings.callsEnabled,
                    requireVerify = securitySettings.keysRequireVerify,
                    wallpaper = appearanceSettings.chatWallpaper,
                    onStartCall = { media ->
                        requestCallPerms(media == CallMedia.VIDEO) {
                            callManager.startCall(contactId, media)
                        }
                    },
                    onVerify = { rootNav.navigate("verify/$contactId") },
                    onSetContactBlocked = onSetContactBlocked,
                    onSetContactMuted = onSetContactMuted,
                    onSetContactNotificationSounds = onSetContactNotificationSounds,
                    onRenameChat = onRenameChat,
                    onChatVisible = onChatVisible,
                    onChatHidden = onChatHidden,
                    onRetryKeyExchange = onRetryKeyExchange,
                    onDeleteContact = onDeleteContact,
                    onBack = { rootNav.popBackStack() },
                    relayConfigured = relayConfigured,
                )
            }
            composable("blocklist") {
                BlockListScreen(
                    repository = messagingRepository,
                    onSetBlocked = onSetContactBlocked,
                    onBack = { rootNav.popBackStack() },
                )
            }
            composable("verify/{contactId}") { backStackEntry ->
                val contactId = backStackEntry.arguments?.getString("contactId").orEmpty()
                // Заголовок — имя из списка контактов: сырой accountId в шапке
                // экрана сверки не помогает понять, чей отпечаток сверяется.
                val contacts by messagingRepository.contacts.collectAsStateWithLifecycle()
                ContactVerificationScreen(
                    contactId = contactId,
                    title = contacts.firstOrNull { it.id == contactId }?.displayName ?: contactId,
                    verification = contactVerification,
                    onBack = { rootNav.popBackStack() },
                )
            }
        }

        // Мастер настроек, без которых звонок не доходит до свёрнутого приложения:
        // после установки и после каждого обновления спрашивает то, что не
        // выставлено. Во время звонка молчит: диалог лёг бы поверх «Принять».
        if (call.phase == CallPhase.IDLE) {
            DeliverySetupPrompt(
                callsEnabled = securitySettings.callsEnabled,
                deliverWhileLocked = securitySettings.deliverWhileLocked,
                onEnableDeliverWhileLocked = { onSetDeliverWhileLocked(true) },
            )
        }

        // Оверлей активного звонка (P8, §7.5) — поверх любого экрана.
        if (call.phase != CallPhase.IDLE) {
            CallScreen(
                call = call,
                mediaEngineReal = callManager.mediaAvailable,
                onAccept = {
                    requestCallPerms(call.media == CallMedia.VIDEO) { onAcceptCall() }
                },
                onDecline = onDeclineCall,
                onHangUp = { callManager.hangUp() },
                onToggleMute = { muted -> callManager.setMuted(muted) },
                eglBaseContext = callManager.eglBaseContext,
                onSetLocalSink = { sink -> callManager.setLocalVideoSink(sink) },
                onSetRemoteSink = { sink -> callManager.setRemoteVideoSink(sink) },
                onToggleCamera = { enabled -> callManager.setCameraEnabled(enabled) },
                onSwitchCamera = { callManager.switchCamera() },
            )
        }
    }
    }
}

@Composable
private fun ChatRoute(
    contactId: String,
    repository: MessagingRepository,
    chatSignals: ChatSignals,
    callsEnabled: Boolean,
    requireVerify: Boolean,
    wallpaper: ChatWallpaper,
    onStartCall: (CallMedia) -> Unit,
    onVerify: () -> Unit,
    onSetContactBlocked: (String, Boolean) -> Unit,
    onSetContactMuted: (String, Long) -> Unit,
    onSetContactNotificationSounds: (String, String, String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onChatVisible: (String) -> Unit,
    onChatHidden: (String) -> Unit,
    onRetryKeyExchange: (String) -> Unit,
    onDeleteContact: suspend (String) -> Unit,
    onBack: () -> Unit,
    relayConfigured: Boolean = true,
) {
    val messages by repository.activeMessages.collectAsStateWithLifecycle()
    val conversations by repository.conversations.collectAsStateWithLifecycle()
    val signals by chatSignals.signals.collectAsStateWithLifecycle()
    var contact by remember { mutableStateOf<StoredContact?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Открытие чата: загрузить контакт, отметить входящие прочитанными и (если
    // разрешено) анонсировать онлайн-статус. Также помечаем чат видимым, чтобы
    // подавлять по нему уведомления (§14.5). Уход — снять всё это.
    androidx.compose.runtime.DisposableEffect(contactId) {
        onChatVisible(contactId)
        var opened: StoredContact? = null
        val job = scope.launch {
            opened = repository.openConversation(contactId)
            contact = opened
            opened?.let { chatSignals.onConversationOpened(it) }
        }
        onDispose {
            job.cancel()
            onChatHidden(contactId)
            opened?.let { chatSignals.onConversationClosed(it) }
        }
    }

    // Бейдж «проверен»/блокировка реактивно обновляются через список чатов.
    val summary = conversations.firstOrNull { it.id == contactId }
    val verified = summary?.verified ?: (contact?.contact?.verified ?: false)
    val blocked = summary?.blocked ?: (contact?.contact?.blocked ?: false)
    // Индикатор E2EE — тоже из списка чатов: ключи слоя B могут доехать уже при
    // открытом чате (догоняющий обмен, §6.2), и подзаголовок должен это отразить
    // без переоткрытия экрана.
    val e2eeReady = summary?.e2eeActive
        ?: contact?.let { it.sessionReady || it.bundleJson != null }
        ?: false
    val peerStatus = signals[contactId]?.let { peerStatusText(it) }
    val newDevicePending = signals[contactId]?.newDevicePending ?: false

    ChatScreen(
        title = summary?.displayName
            ?: contact?.contact?.displayLabel()
            ?: contactId,
        e2eeReady = e2eeReady,
        messages = messages,
        callsEnabled = callsEnabled,
        verified = verified,
        requireVerify = requireVerify,
        newDevicePending = newDevicePending,
        blocked = blocked,
        peerStatus = peerStatus,
        onAcknowledgeNewDevice = { chatSignals.acknowledgeNewDevice(contactId) },
        onRetryKeyExchange = { onRetryKeyExchange(contactId) },
        relayConfigured = relayConfigured,
        mutedUntilMs = contact?.contact?.mutedUntilMs ?: 0L,
        messageSoundUri = contact?.contact?.notificationSoundUri ?: "",
        callRingtoneUri = contact?.contact?.callRingtoneUri ?: "",
        wallpaper = wallpaper,
        onVerify = onVerify,
        onInputChange = { contact?.let { chatSignals.onLocalTyping(it) } },
        onToggleBlock = { onSetContactBlocked(contactId, !blocked) },
        onSetContactMuted = { until ->
            onSetContactMuted(contactId, until)
            // Оптимистично обновляем локальную запись — диалог реагирует сразу.
            contact = contact?.let { it.copy(contact = it.contact.copy(mutedUntilMs = until)) }
        },
        onSetContactSounds = { msg, call ->
            onSetContactNotificationSounds(contactId, msg, call)
            contact = contact?.let {
                it.copy(contact = it.contact.copy(notificationSoundUri = msg, callRingtoneUri = call))
            }
        },
        onDeleteForMe = { msg -> contact?.let { chatSignals.deleteForMe(it, listOf(msg.id)) } },
        onDeleteForEveryone = { msg -> contact?.let { chatSignals.deleteForEveryone(it, listOf(msg.id)) } },
        onClearChat = { scope.launch { repository.clearConversation(contactId) } },
        nickname = summary?.nickname ?: (contact?.contact?.nickname ?: ""),
        onRename = { newName -> onRenameChat(contactId, newName) },
        onDeleteChat = {
            // «Удалить чат» сносит и сам контакт целиком — тем же путём, что
            // раздел «Контакты» (`AppContainer.deleteContact`): переписку,
            // вложения, устройства, ключ обмена (PSK) и заявки собеседника.
            // После этого можно провести чистый обмен ключами заново (§6.2).
            scope.launch {
                onDeleteContact(contactId)
                onBack()
            }
        },
        onStartAudioCall = { onStartCall(CallMedia.AUDIO) },
        onStartVideoCall = { onStartCall(CallMedia.VIDEO) },
        onSend = { text -> scope.launch { repository.sendMessage(contactId, text) } },
        onAttachmentPicked = { uri ->
            scope.launch {
                when (val result = AttachmentReader.read(context, uri)) {
                    is AttachmentReader.Result.Ok -> {
                        val d = result.draft
                        repository.sendAttachment(contactId, d.bytes, d.fileName, d.mimeType, d.kind)
                    }
                    is AttachmentReader.Result.Fail -> {
                        val msg = when (result.error) {
                            is AttachmentReader.Error.TooLarge ->
                                context.getString(
                                    R.string.chat_attach_too_large,
                                    (AegisConstants.ATTACHMENT_MAX_BYTES / (1024 * 1024)).toInt(),
                                )
                            AttachmentReader.Error.Unreadable ->
                                context.getString(R.string.chat_attach_unreadable)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        onCameraCaptured = { file ->
            scope.launch {
                val bytes = CameraCapture.readAndShred(file)
                when {
                    bytes == null ->
                        Toast.makeText(context, context.getString(R.string.chat_attach_unreadable), Toast.LENGTH_SHORT).show()
                    bytes.size > AegisConstants.ATTACHMENT_MAX_BYTES ->
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.chat_attach_too_large,
                                (AegisConstants.ATTACHMENT_MAX_BYTES / (1024 * 1024)).toInt(),
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    else -> repository.sendAttachment(
                        contactId,
                        bytes,
                        CameraCapture.fileName(file),
                        CameraCapture.MIME_JPEG,
                        AttachmentKind.IMAGE,
                    )
                }
            }
        },
        onVoiceRecorded = { recorded ->
            // Запись уже прошла проверки записывающего слоя (длительность,
            // непустой поток), поэтому здесь остаётся только отправка тем же
            // конвейером, что и любое вложение: блоб под каскадом слоя A на
            // случайном ключе → релей, ссылка → слой A(PSK)+B(Signal) (§14.4).
            scope.launch {
                repository.sendVoice(
                    contactId = contactId,
                    bytes = recorded.bytes,
                    durationMs = recorded.durationMs,
                    waveformB64 = recorded.waveformB64,
                )
            }
        },
        attachmentDownloader = { msg -> repository.downloadAttachment(contactId, msg) },
        messageRetrier = { msg -> repository.retrySendMessage(contactId, msg) },
        attachmentBytesProvider = { messageId -> repository.attachmentBytes(messageId) },
        onBack = onBack,
    )
}

/**
 * Однократный запрос системного разрешения на уведомления (§14.5). На Android 13+
 * (API 33) POST_NOTIFICATIONS — runtime-разрешение; на более старых версиях
 * уведомления разрешены по умолчанию, поэтому ничего не запрашиваем.
 */
@Composable
private fun RequestNotificationPermissionOnce() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* результат не критичен: без разрешения просто не показываем баннеры */ }
    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Человекочитаемый эфемерный статус собеседника (§14.2). */
@Composable
private fun peerStatusText(signal: ChatSignals.PeerSignal): String? = when {
    signal.typing -> stringResource(R.string.presence_typing)
    signal.online -> stringResource(R.string.presence_online)
    signal.lastSeenMs > 0L -> stringResource(R.string.presence_last_seen, relativeSeen(signal.lastSeenMs))
    else -> null
}

@Composable
private fun relativeSeen(lastSeenMs: Long): String {
    val diff = System.currentTimeMillis() - lastSeenMs
    val minutes = diff / 60_000L
    return when {
        minutes < 1 -> stringResource(R.string.presence_just_now)
        minutes < 60 -> stringResource(R.string.presence_min_ago, minutes.toInt())
        minutes < 1440 -> stringResource(R.string.presence_hour_ago, (minutes / 60).toInt())
        else -> stringResource(R.string.presence_day_ago, (minutes / 1440).toInt())
    }
}

/**
 * Главный экран с вкладками (§13): шапка, нижняя навигация и NavHost вкладок.
 * Нижнее меню — только иконки; название текущего раздела остаётся в шапке.
 */
@Composable
private fun MainScaffold(
    torTransport: TorTransport,
    accountProfile: AccountProfile,
    accountManager: AccountManager,
    keyStoreManager: KeyStoreManager,
    pskManager: PskManager,
    messagingRepository: MessagingRepository,
    securitySettings: SecuritySettings,
    torNetworkSettings: TorNetworkSettings,
    relayConfigured: Boolean,
    nativeCryptoVersion: String?,
    torSupported: Boolean,
    network: NetworkUi,
    callLive: Boolean,
    onUpdateTorNetwork: (TorNetworkSettings) -> Unit,
    mediaEngineReal: Boolean,
    onSetCallsEnabled: (Boolean) -> Unit,
    onSetAutoLockMinutes: (Int) -> Unit,
    onSetDeliverWhileLocked: (Boolean) -> Unit,
    onSetAutoDeleteDays: (Int) -> Unit,
    onSetAutoDownloadMedia: (Boolean) -> Unit,
    onSetFanoutJitter: (FanoutJitterMode) -> Unit,
    onSetCoverTraffic: (CoverTrafficMode) -> Unit,
    privacySettings: PrivacySettings,
    onSetPrivacySettings: (PrivacySettings) -> Unit,
    notificationSettings: NotificationSettings,
    onSetNotificationSettings: (NotificationSettings) -> Unit,
    onSnoozeAllNotifications: (Long) -> Unit,
    onOpenBlockList: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onSetMultiDeviceAllowed: (Boolean) -> Unit,
    onOpenTransferExport: () -> Unit,
    onOpenMergeImport: () -> Unit,
    onOpenLinkDevice: () -> Unit,
    onOpenMyDevices: () -> Unit,
    appearanceSettings: AppearanceSettings,
    onSetAppearance: (AppearanceSettings) -> Unit,
    diagnosticSettings: DiagnosticSettings = DiagnosticSettings.DEFAULT,
    onSetDiagnosticLogEnabled: (Boolean) -> Unit = {},
    diagnosticLogSizeBytes: () -> Long = { 0L },
    onClearDiagnosticLog: () -> Unit = {},
    prepareDiagnosticExport: suspend () -> java.io.File? = { null },
    /**
     * Удалённое добавление контактов (`docs/contact_requests.md`) — одним
     * холдером, чтобы не раздувать три сигнатуры десятком колбэков.
     */
    contactRequests: ContactRequestsUi = ContactRequestsUi.DISABLED,
    onSetContactRequestMode: (ContactRequestMode) -> Unit = {},
    onSetContactDeclineNotify: (Boolean) -> Unit = {},
    /** Переход к ручной сверке отпечатка контакта (§14.3). */
    onVerifyContact: (String) -> Unit = {},
    /** Полное локальное удаление контакта (`AppContainer.deleteContact`). */
    onDeleteContact: suspend (String) -> Unit = {},
    /** Запрос перехода на экран заявок из уведомления. */
    pendingContactRequestsOpen: Boolean = false,
    onConsumePendingContactRequestsOpen: () -> Unit = {},
) {
    val navController = rememberNavController()
    val torStatus: TorStatus by torTransport.status.collectAsStateWithLifecycle()

    // Синхронизируем контакты с установленными PSK (§6.2): каждый ключ = чат.
    val pskEntries by pskManager.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { pskManager.refresh() }
    LaunchedEffect(pskEntries) {
        pskEntries.forEach { meta ->
            messagingRepository.ensureContact(
                contactId = meta.contactId,
                nickname = meta.label,
                fingerprint = meta.peerFingerprint,
            )
        }
        messagingRepository.refreshConversations()
    }
    val conversations by messagingRepository.conversations.collectAsStateWithLifecycle()
    val contactCards by messagingRepository.contacts.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTopLevel = TopLevelDestination.entries.firstOrNull { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    } ?: TopLevelDestination.CHATS

    val title = if (currentTopLevel == TopLevelDestination.CHATS) {
        stringResource(R.string.app_name)
    } else {
        stringResource(currentTopLevel.labelRes)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AegisTopBar(
                title = title,
                torStatus = torStatus,
                torEnabled = network.torEnabled,
                networkSwitching = network.switching,
                // Маршрут раздела — статический литерал; данные пользователя в
                // декоративные коды попадать не должны (см. AegisHudCodes).
                slug = currentTopLevel.route,
                icon = currentTopLevel.icon,
            )
        },
        bottomBar = {
            AegisBottomBar(
                current = currentTopLevel,
                onSelect = { dest -> navController.navigateToTopLevel(dest) },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.CHATS.route,
            modifier = Modifier
                .padding(innerPadding)
                .aegisHudBackdrop(),
        ) {
            composable(TopLevelDestination.CHATS.route) {
                ChatsScreen(
                    chats = conversations,
                    onOpenChat = onOpenChat,
                    onRenameChat = onRenameChat,
                    relayConfigured = relayConfigured,
                )
            }
            composable(TopLevelDestination.CONTACTS.route) {
                ContactsScreen(
                    pskManager = pskManager,
                    selfIdentity = accountProfile.identity,
                    selfLogin = accountProfile.login,
                    relayConfigured = relayConfigured,
                    contacts = contactCards,
                    contactRequests = contactRequests,
                    openRequestsSignal = pendingContactRequestsOpen,
                    onConsumeOpenRequests = onConsumePendingContactRequestsOpen,
                    onOpenChat = onOpenChat,
                    onVerifyContact = onVerifyContact,
                    onDeleteContact = onDeleteContact,
                )
            }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    contactRequestMode = securitySettings.contactRequests,
                    contactDeclineNotify = securitySettings.contactDeclineNotify,
                    onSetContactRequestMode = onSetContactRequestMode,
                    onSetContactDeclineNotify = onSetContactDeclineNotify,
                    activePreset = securitySettings.matchingPreset(),
                    nativeCryptoVersion = nativeCryptoVersion,
                    network = network,
                    torStatus = torStatus,
                    torSupported = torSupported,
                    torNetworkSettings = torNetworkSettings,
                    accountProfile = accountProfile,
                    accountManager = accountManager,
                    keyStoreManager = keyStoreManager,
                    callsEnabled = securitySettings.callsEnabled,
                    callLive = callLive,
                    autoLockMinutes = securitySettings.autoLockMinutes,
                    deliverWhileLocked = securitySettings.deliverWhileLocked,
                    onSetDeliverWhileLocked = onSetDeliverWhileLocked,
                    autoDeleteDays = securitySettings.autoDeleteDays,
                    autoDownloadMedia = securitySettings.autoDownloadMedia,
                    fanoutJitter = securitySettings.fanoutJitter,
                    coverTraffic = securitySettings.coverTraffic,
                    mediaEngineReal = mediaEngineReal,
                    onUpdateTorNetwork = onUpdateTorNetwork,
                    onSetCallsEnabled = onSetCallsEnabled,
                    onSetAutoLockMinutes = onSetAutoLockMinutes,
                    onSetAutoDeleteDays = onSetAutoDeleteDays,
                    onSetAutoDownloadMedia = onSetAutoDownloadMedia,
                    onSetFanoutJitter = onSetFanoutJitter,
                    onSetCoverTraffic = onSetCoverTraffic,
                    privacySettings = privacySettings,
                    onSetPrivacySettings = onSetPrivacySettings,
                    notificationSettings = notificationSettings,
                    onSetNotificationSettings = onSetNotificationSettings,
                    onSnoozeAllNotifications = onSnoozeAllNotifications,
                    onOpenBlockList = onOpenBlockList,
                    appearanceSettings = appearanceSettings,
                    onSetAppearance = onSetAppearance,
                    diagnosticSettings = diagnosticSettings,
                    onSetDiagnosticLogEnabled = onSetDiagnosticLogEnabled,
                    diagnosticLogSizeBytes = diagnosticLogSizeBytes,
                    onClearDiagnosticLog = onClearDiagnosticLog,
                    prepareDiagnosticExport = prepareDiagnosticExport,
                )
            }
            composable(TopLevelDestination.PROFILE.route) {
                ProfileScreen(
                    profile = accountProfile,
                    accountManager = accountManager,
                    onSetMultiDeviceAllowed = onSetMultiDeviceAllowed,
                    onOpenTransfer = onOpenTransferExport,
                    onOpenMergeImport = onOpenMergeImport,
                    onOpenLinkDevice = onOpenLinkDevice,
                    onOpenMyDevices = onOpenMyDevices,
                )
            }
        }
    }
}

/** Навигация к разделу нижнего меню с корректной работой back stack. */
private fun androidx.navigation.NavHostController.navigateToTopLevel(dest: TopLevelDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
