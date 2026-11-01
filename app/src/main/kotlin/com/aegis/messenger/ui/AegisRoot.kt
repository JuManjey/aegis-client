package com.aegis.messenger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.core.common.account.AccountState
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.security.TorMode
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.theme.aegisHudBackdrop
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.AppContainer
import com.aegis.messenger.messaging.NetworkModeController
import com.aegis.messenger.ui.contacts.ContactRequestsUi
import com.aegis.messenger.ui.settings.NetworkUi
import com.aegis.messenger.ui.onboarding.OnboardingFlow
import com.aegis.messenger.ui.onboarding.RecoveryPhraseScreen
import com.aegis.messenger.ui.onboarding.UnlockScreen
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Корневой composable-«привратник» (auth-gate). По состоянию аккаунта решает,
 * что показать:
 *  - None — онбординг (создание/восстановление);
 *  - Locked — экран входа (разблокировка);
 *  - Unlocked + есть неподтверждённая мнемоника — одноразовый показ фразы;
 *  - Unlocked — главный интерфейс [AegisApp].
 */
@Composable
fun AegisRoot(
    container: AppContainer,
    nativeCryptoVersion: String?,
    nativeReady: Boolean,
) {
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.initialize()
        initialized = true
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        if (!initialized) {
            LoadingBox()
            return@Surface
        }

        val accountManager = container.accountManager
        val accountState by accountManager.state.collectAsStateWithLifecycle()
        val pendingRecovery by accountManager.pendingRecovery.collectAsStateWithLifecycle()
        val securitySettings by container.securitySettings.collectAsStateWithLifecycle()
        val torNetworkSettings by container.torNetworkSettings.collectAsStateWithLifecycle()
        val privacySettings by container.privacySettings.collectAsStateWithLifecycle()
        val notificationSettings by container.notificationSettings.collectAsStateWithLifecycle()
        val appearanceSettings by container.appearanceSettings.collectAsStateWithLifecycle()
        val diagnosticSettings by container.diagnosticSettings.collectAsStateWithLifecycle()
        val deviceSettings by container.deviceSettings.collectAsStateWithLifecycle()
        val pendingChatOpen by container.pendingChatOpen.collectAsStateWithLifecycle()
        val pendingCallAccept by container.pendingCallAccept.collectAsStateWithLifecycle()
        val contactRequestViews by container.contactRequests.collectAsStateWithLifecycle()
        val discoverySettings by container.discoverySettings.collectAsStateWithLifecycle()
        val pendingRequestsOpen by
            container.pendingContactRequestsOpen.collectAsStateWithLifecycle()
        val networkState by container.networkModeState.collectAsStateWithLifecycle()
        val relayUnavailable by container.relayUnavailable.collectAsStateWithLifecycle()
        val uiScope = rememberCoroutineScope()

        val networkUi = NetworkUi(
            mode = securitySettings.netTor,
            switching = networkState is NetworkModeController.State.Switching,
            relayUnavailable = relayUnavailable,
            setTorEnabled = { enabled ->
                uiScope.launch { container.setTorMode(if (enabled) TorMode.ALWAYS else TorMode.OFF) }
            },
            testOnion = { addr -> container.testOnion(addr) },
            testHttps = { addr -> container.testHttps(addr) },
        )

        // Удалённое добавление контактов одним холдером: иначе десяток колбэков
        // пришлось бы протаскивать через три сигнатуры (`docs/contact_requests.md`).
        val contactRequestsUi = ContactRequestsUi(
            mode = securitySettings.contactRequests,
            requests = contactRequestViews,
            alias = discoverySettings.alias,
            myAddress = { container.myContactAddress() },
            sendRequest = { query, greeting ->
                // Ник или адрес различаем здесь: пользователю не нужно выбирать
                // тип ввода — он вставляет то, что ему дали.
                if (ContactAddress.looksLikeAddress(query)) {
                    container.contactRequestManager.sendByAddress(query, greeting)
                } else {
                    container.contactRequestManager.sendByAlias(query, greeting)
                }
            },
            accept = { id -> container.contactRequestManager.accept(id) },
            decline = { id -> container.contactRequestManager.decline(id) },
            cancel = { id -> container.contactRequestManager.cancelOutgoing(id) },
            claimAlias = { alias -> container.claimContactAlias(alias) },
            releaseAlias = { container.releaseContactAlias() },
            rotateAddress = { container.rotateDiscoveryTag() },
        )

        // Удаление контакта идёт через контейнер, а не через репозиторий: снести
        // нужно ещё и ключ обмена, заявки и уведомления (`deleteContact`).
        //
        // Работа запускается в scope ПРИЛОЖЕНИЯ, а вызывающий только ждёт её
        // результата. Экран, с которого нажали «удалить», закрывается посреди
        // операции (карточка контакта — сразу, как контакт исчез из списка;
        // вся Activity — при повороте экрана), и корутина в его композиции
        // была бы отменена на середине: часть хранилищ вычищена, часть нет.
        // `async` вместо `launch` — чтобы ошибка всё же доехала до UI, а не
        // утонула в обработчике исключений scope.
        val deleteContact: suspend (String) -> Unit = remember(container) {
            { id -> container.applicationScope.async { container.deleteContact(id) }.await() }
        }

        // Локальный флаг: с экрана входа пользователь может уйти в восстановление.
        var forceOnboarding by remember { mutableStateOf(false) }

        // Звонок, пришедший при закрытом аккаунте: кто звонит, станет известно
        // только после входа, поэтому экран входа лишь сообщает о звонке.
        val lockedIncomingCall by container.lockedIncomingCall.collectAsStateWithLifecycle()

        when (val state = accountState) {
            AccountState.None -> OnboardingFlow(
                accountManager = accountManager,
                nativeReady = nativeReady,
                accountTransfer = container.accountTransfer,
                deviceLinking = container.deviceLinking,
                appScope = container.applicationScope,
                onImported = { container.reloadAfterImport() },
            )

            is AccountState.Locked -> {
                if (forceOnboarding) {
                    // На устройстве уже лежит аккаунт: создание нового или
                    // восстановление чужой фразой его сотрёт (с подтверждением) —
                    // иначе этот путь открывал переписку без пароля. Перенос и
                    // линковка здесь недоступны: они рассчитаны на чистое устройство.
                    OnboardingFlow(
                        accountManager = accountManager,
                        nativeReady = nativeReady,
                        replacesExistingAccount = true,
                        onExit = { forceOnboarding = false },
                    )
                } else {
                    UnlockScreen(
                        profile = state.profile,
                        accountManager = accountManager,
                        keyStoreManager = container.keyStoreManager,
                        onRestore = { forceOnboarding = true },
                        incomingCall = lockedIncomingCall,
                    )
                }
            }

            is AccountState.Unlocked -> {
                forceOnboarding = false
                val recovery = pendingRecovery
                if (recovery != null) {
                    RecoveryPhraseScreen(
                        mnemonic = recovery,
                        identity = state.profile.identity,
                        onAcknowledge = { accountManager.acknowledgeRecovery() },
                    )
                } else {
                    AegisApp(
                        torTransport = container.torTransport,
                        securitySettings = securitySettings,
                        torNetworkSettings = torNetworkSettings,
                        privacySettings = privacySettings,
                        notificationSettings = notificationSettings,
                        appearanceSettings = appearanceSettings,
                        pendingChatOpen = pendingChatOpen,
                        pendingCallAccept = pendingCallAccept,
                        nativeCryptoVersion = nativeCryptoVersion,
                        torSupported = CryptoModule.hasTorSupport,
                        accountProfile = state.profile,
                        accountManager = accountManager,
                        accountTransfer = container.accountTransfer,
                        deviceLinking = container.deviceLinking,
                        appScope = container.applicationScope,
                        deviceSettings = deviceSettings,
                        onReloadAfterImport = { container.reloadAfterImport() },
                        onReloadDeviceSettings = { container.reloadDeviceSettings() },
                        onRenameThisDevice = { label -> container.renameThisDevice(label) },
                        onRenameLinkedDevice = { id, label -> container.renameLinkedDevice(id, label) },
                        onRevokeLinkedDevice = { id -> container.revokeLinkedDevice(id) },
                        keyStoreManager = container.keyStoreManager,
                        pskManager = container.pskManager,
                        messagingRepository = container.messagingRepository,
                        callManager = container.callManager,
                        contactVerification = container.contactVerification,
                        chatSignals = container.chatSignals,
                        network = networkUi,
                        onUpdateTorNetwork = { s -> uiScope.launch { container.setTorNetworkSettings(s) } },
                        onSetCallsEnabled = { e -> uiScope.launch { container.setCallsEnabled(e) } },
                        onSetAutoLockMinutes = { m -> uiScope.launch { container.setAutoLockMinutes(m) } },
                        onSetDeliverWhileLocked = { e ->
                            uiScope.launch { container.setDeliverWhileLocked(e) }
                        },
                        onSetAutoDeleteDays = { d -> uiScope.launch { container.setAutoDeleteDays(d) } },
                        onSetAutoDownloadMedia = { e -> uiScope.launch { container.setAutoDownloadMedia(e) } },
                        onSetFanoutJitter = { m -> uiScope.launch { container.setFanoutJitterMode(m) } },
                        onSetCoverTraffic = { m -> uiScope.launch { container.setCoverTrafficMode(m) } },
                        onSetPrivacySettings = { p -> uiScope.launch { container.setPrivacySettings(p) } },
                        onSetContactBlocked = { id, b -> uiScope.launch { container.setContactBlocked(id, b) } },
                        onSetNotificationSettings = { s -> uiScope.launch { container.setNotificationSettings(s) } },
                        onSnoozeAllNotifications = { until -> uiScope.launch { container.snoozeAllNotifications(until) } },
                        onSetContactMuted = { id, until -> uiScope.launch { container.setContactMuted(id, until) } },
                        onSetContactNotificationSounds = { id, msg, call ->
                            uiScope.launch { container.setContactNotificationSounds(id, msg, call) }
                        },
                        onRenameChat = { id, name -> uiScope.launch { container.renameChat(id, name) } },
                        onChatVisible = { id -> container.onChatVisible(id) },
                        onChatHidden = { id -> container.onChatHidden(id) },
                        onRetryKeyExchange = { id -> uiScope.launch { container.retryKeyExchange(id) } },
                        onConsumePendingChatOpen = { container.consumePendingChatOpen() },
                        onConsumePendingCallAccept = { container.consumePendingCallAccept() },
                        onAcceptCall = { container.acceptIncomingCall() },
                        onDeclineCall = { container.declineIncomingCall() },
                        onSetMultiDeviceAllowed = { a -> uiScope.launch { container.setMultiDeviceAllowed(a) } },
                        onSetAppearance = { s -> uiScope.launch { container.setAppearanceSettings(s) } },
                        diagnosticSettings = diagnosticSettings,
                        onSetDiagnosticLogEnabled = { e -> uiScope.launch { container.setDiagnosticLogEnabled(e) } },
                        diagnosticLogSizeBytes = { container.diagnosticLogSizeBytes() },
                        onClearDiagnosticLog = { container.clearDiagnosticLog() },
                        prepareDiagnosticExport = { container.diagnosticLogForExport() },
                        contactRequests = contactRequestsUi,
                        onSetContactRequestMode = { m ->
                            uiScope.launch { container.setContactRequestMode(m) }
                        },
                        onSetContactDeclineNotify = { e ->
                            uiScope.launch { container.setContactDeclineNotify(e) }
                        },
                        onDeleteContact = deleteContact,
                        pendingContactRequestsOpen = pendingRequestsOpen,
                        onConsumePendingContactRequestsOpen = {
                            container.consumePendingContactRequestsOpen()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Экран загрузки до готовности контейнера: фон HUD и кольцо-индикатор в цвете
 * акцента. Это самый первый кадр приложения, поэтому он тоже в общем стиле —
 * иначе запуск начинается со стокового Material-индикатора.
 */
@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .aegisHudBackdrop(),
        contentAlignment = Alignment.Center,
    ) {
        AegisHudBusy(size = 36.dp)
    }
}
