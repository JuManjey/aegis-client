package com.aegis.messenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.diag.DiagnosticSettings
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.common.security.BuiltInBridge
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.core.common.security.CoverTrafficMode
import com.aegis.core.common.security.FanoutJitterMode
import com.aegis.core.common.security.HttpsRelayAddress
import com.aegis.core.common.security.SecurityPreset
import com.aegis.core.common.security.OnionRelayAddress
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudOptionRow
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreenDefaults
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.hud.AegisHudTextField
import com.aegis.core.ui.hud.aegisHudTextFieldColors
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.data.storage.KeyStoreManager
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.messaging.RelayUnavailableReason
import com.aegis.network.tor.PtBinaries
import com.aegis.network.tor.TorStatus
import kotlinx.coroutines.launch

/**
 * Раздел «Настройки» (§14). Группа «Сеть и Tor» (§14.7) — переключатель Tor
 * (выключение только через диалог с предупреждением), мосты / pluggable
 * transports (obfs4/snowflake/meek) и адреса релея с проверкой соединения.
 * Остальное — диагностика профиля безопасности и крипто-ядра.
 *
 * Шапка раздела приходит извне (общий каркас нижней навигации), поэтому своего
 * `Scaffold`/`AegisHudScreen` здесь нет — только скроллящаяся лента карточек.
 */
@Composable
fun SettingsScreen(
    activePreset: SecurityPreset,
    nativeCryptoVersion: String?,
    network: NetworkUi,
    torStatus: TorStatus,
    torSupported: Boolean,
    torNetworkSettings: TorNetworkSettings,
    accountProfile: AccountProfile,
    accountManager: AccountManager,
    keyStoreManager: KeyStoreManager,
    callsEnabled: Boolean,
    /** Идёт звонок: переключатель Tor недоступен (режим под звонком не меняется). */
    callLive: Boolean,
    autoLockMinutes: Int,
    deliverWhileLocked: Boolean,
    onSetDeliverWhileLocked: (Boolean) -> Unit,
    autoDeleteDays: Int,
    autoDownloadMedia: Boolean,
    fanoutJitter: FanoutJitterMode,
    coverTraffic: CoverTrafficMode,
    mediaEngineReal: Boolean,
    onUpdateTorNetwork: (TorNetworkSettings) -> Unit,
    onSetCallsEnabled: (Boolean) -> Unit,
    onSetAutoLockMinutes: (Int) -> Unit,
    onSetAutoDeleteDays: (Int) -> Unit,
    onSetAutoDownloadMedia: (Boolean) -> Unit,
    onSetFanoutJitter: (FanoutJitterMode) -> Unit,
    onSetCoverTraffic: (CoverTrafficMode) -> Unit,
    privacySettings: PrivacySettings,
    onSetPrivacySettings: (PrivacySettings) -> Unit,
    /**
     * Удалённое добавление контактов (§8, `docs/contact_requests.md`). Отдельная
     * ось от `keys.overSignal`: секрет слоя A здесь не передаётся, а выводится.
     */
    contactRequestMode: ContactRequestMode = ContactRequestMode.ADDRESS,
    contactDeclineNotify: Boolean = true,
    onSetContactRequestMode: (ContactRequestMode) -> Unit = {},
    onSetContactDeclineNotify: (Boolean) -> Unit = {},
    notificationSettings: NotificationSettings,
    onSetNotificationSettings: (NotificationSettings) -> Unit,
    onSnoozeAllNotifications: (Long) -> Unit,
    onOpenBlockList: () -> Unit,
    appearanceSettings: AppearanceSettings,
    onSetAppearance: (AppearanceSettings) -> Unit,
    diagnosticSettings: DiagnosticSettings = DiagnosticSettings.DEFAULT,
    onSetDiagnosticLogEnabled: (Boolean) -> Unit = {},
    diagnosticLogSizeBytes: () -> Long = { 0L },
    onClearDiagnosticLog: () -> Unit = {},
    prepareDiagnosticExport: suspend () -> java.io.File? = { null },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AegisHudScreenDefaults.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(AegisHudScreenDefaults.CardGap),
    ) {
        AegisHudCard(
            title = stringResource(R.string.settings_title),
            icon = Icons.Outlined.Shield,
        ) {
            Text(
                text = stringResource(R.string.settings_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
            )
        }
        AegisHudCard(
            title = stringResource(R.string.settings_security_profile_title),
            icon = Icons.Outlined.Shield,
        ) {
            Text(
                text = stringResource(
                    R.string.settings_security_profile_active,
                    activePreset.displayName(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
            )
        }

        SecuritySettingsSection(
            profile = accountProfile,
            accountManager = accountManager,
            keyStoreManager = keyStoreManager,
            autoLockMinutes = autoLockMinutes,
            onSetAutoLockMinutes = onSetAutoLockMinutes,
            deliverWhileLocked = deliverWhileLocked,
            onSetDeliverWhileLocked = onSetDeliverWhileLocked,
        )

        MessageSettingsSection(
            autoDeleteDays = autoDeleteDays,
            onSetAutoDeleteDays = onSetAutoDeleteDays,
            autoDownloadMedia = autoDownloadMedia,
            onSetAutoDownloadMedia = onSetAutoDownloadMedia,
        )

        PrivacySettingsSection(
            settings = privacySettings,
            onSetPrivacySettings = onSetPrivacySettings,
            onOpenBlockList = onOpenBlockList,
        )

        ContactRequestSettingsSection(
            mode = contactRequestMode,
            declineNotify = contactDeclineNotify,
            onSetMode = onSetContactRequestMode,
            onSetDeclineNotify = onSetContactDeclineNotify,
        )

        val context = LocalContext.current
        // Системный статус уведомлений перечитываем при каждой перерисовке экрана
        // (пользователь мог сменить его в настройках ОС и вернуться).
        val systemAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        NotificationSettingsSection(
            settings = notificationSettings,
            onSetNotificationSettings = onSetNotificationSettings,
            onSnoozeAll = onSnoozeAllNotifications,
            systemNotificationsAllowed = systemAllowed,
            onRequestSystemPermission = { openAppNotificationSettings(context) },
        )

        CallSettingsSection(
            callsEnabled = callsEnabled,
            torEnabled = network.torEnabled,
            mediaEngineReal = mediaEngineReal,
            onSetCallsEnabled = onSetCallsEnabled,
        )

        // Системные разрешения, без которых входящий звонок не поднимает экран, а
        // приём в фоне «засыпает». Карточка сама скрывается, когда всё выдано.
        BackgroundDeliverySection()

        AppearanceSettingsSection(
            settings = appearanceSettings,
            onSetAppearance = onSetAppearance,
        )

        MetadataPrivacyCard(
            fanoutJitter = fanoutJitter,
            coverTraffic = coverTraffic,
            onSetFanoutJitter = onSetFanoutJitter,
            onSetCoverTraffic = onSetCoverTraffic,
        )

        TorNetworkCard(
            network = network,
            callLive = callLive,
            torStatus = torStatus,
            torSupported = torSupported,
            settings = torNetworkSettings,
            onUpdateTorNetwork = onUpdateTorNetwork,
        )

        AegisHudCard(
            title = stringResource(R.string.settings_crypto_core_title),
            icon = Icons.Outlined.Memory,
        ) {
            AegisHudStatusLine(
                text = nativeCryptoVersion
                    ?.let { stringResource(R.string.crypto_native_ready, it) }
                    ?: stringResource(R.string.crypto_native_absent),
                ok = nativeCryptoVersion != null,
            )
        }

        DiagnosticSettingsSection(
            settings = diagnosticSettings,
            logSizeBytes = diagnosticLogSizeBytes,
            onSetEnabled = onSetDiagnosticLogEnabled,
            onClear = onClearDiagnosticLog,
            prepareExport = prepareDiagnosticExport,
        )
    }
}

@Composable
private fun TorNetworkCard(
    network: NetworkUi,
    callLive: Boolean,
    torStatus: TorStatus,
    torSupported: Boolean,
    settings: TorNetworkSettings,
    onUpdateTorNetwork: (TorNetworkSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val secondary = AegisTheme.extendedColors.textSecondary
    val torEnabled = network.torEnabled

    val context = LocalContext.current
    // Какие PT-бинари реально вложены в эту сборку/ABI (§14.7).
    val ptAvailable = remember { PtBinaries.availableProtocols(context) }

    // Локальное редактируемое состояние; ресинхронизируется при внешней смене.
    var bridgeMode by remember(settings) { mutableStateOf(settings.toBridgeUiMode()) }
    var bridgeText by remember(settings) { mutableStateOf(settings.bridgeLines.joinToString("\n")) }
    var onionAddress by remember(settings) { mutableStateOf(settings.onionRelayAddress) }
    var httpsAddress by remember(settings) { mutableStateOf(settings.httpsRelayAddress) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var confirmTorOff by remember { mutableStateOf(false) }

    AegisHudCard(
        title = stringResource(R.string.tor_section_title),
        icon = Icons.Outlined.Router,
    ) {
        if (torEnabled) {
            AegisHudStatusLine(
                text = stringResource(R.string.tor_status_line, torStatus.displayText()),
                ok = torStatus is TorStatus.Connected,
            )
            if (!torSupported) {
                Text(
                    text = stringResource(R.string.tor_not_built_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            // Режим без Tor — выбор пользователя, а не ошибка Tor.
            AegisHudStatusLine(
                text = stringResource(R.string.tor_status_line, stringResource(R.string.net_status_direct)),
                ok = false,
                failColor = AegisTheme.extendedColors.accentPrimary,
            )
        }
        network.relayUnavailable?.let { reason ->
            relayUnavailableText(reason)?.let { text ->
                AegisHudStatusLine(text = text, ok = false, failColor = MaterialTheme.colorScheme.error)
            }
        }

        AegisHudSwitchRow(
            title = stringResource(R.string.tor_toggle_title),
            subtitle = stringResource(if (torEnabled) R.string.tor_mode_always else R.string.tor_mode_off),
            checked = torEnabled,
            enabled = !callLive && !network.switching,
            onCheckedChange = { wantTor ->
                if (wantTor) network.setTorEnabled(true) else confirmTorOff = true
            },
        )
        if (callLive) {
            Text(
                text = stringResource(R.string.tor_toggle_call_locked),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        } else if (network.switching) {
            Text(
                text = stringResource(R.string.net_status_switching),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }

        if (confirmTorOff) {
            AegisHudDialog(
                title = stringResource(R.string.tor_off_dialog_title),
                onDismiss = { confirmTorOff = false },
                confirmLabel = stringResource(R.string.tor_off_dialog_confirm),
                onConfirm = {
                    confirmTorOff = false
                    network.setTorEnabled(false)
                },
                dismissLabel = stringResource(R.string.tor_off_dialog_cancel),
                onDismissAction = { confirmTorOff = false },
                danger = true,
            ) {
                Text(
                    text = stringResource(R.string.tor_off_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (torEnabled) {
            TorBridgesBlock(
                ptAvailable = ptAvailable,
                bridgeMode = bridgeMode,
                onBridgeMode = { bridgeMode = it },
                bridgeText = bridgeText,
                onBridgeText = { bridgeText = it },
                onApply = {
                    val lines = bridgeText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                    onUpdateTorNetwork(
                        settings.copy(
                            bridgesEnabled = bridgeMode != BridgeUiMode.OFF,
                            builtInBridge = bridgeMode.toBuiltIn(),
                            bridgeLines = lines,
                        ),
                    )
                },
            )
        }

        AegisHudGroupLabel(text = stringResource(R.string.relay_address_title))
        // §12.2: адреса обычно определяются автоматически (зашитые дефолты +
        // подписанный конфиг с сервера). Показываем текущий источник.
        Text(
            text = stringResource(
                if (settings.relayManual) R.string.tor_onion_source_manual
                else R.string.tor_onion_source_auto,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        Text(
            text = stringResource(R.string.relay_onion_label),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudTextField(
            value = onionAddress,
            onValueChange = { onionAddress = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.tor_onion_hint),
        )
        Text(
            text = stringResource(R.string.relay_https_label),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudTextField(
            value = httpsAddress,
            onValueChange = { httpsAddress = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.relay_https_hint),
        )
        Text(
            text = stringResource(if (torEnabled) R.string.relay_test_hint_tor else R.string.relay_test_hint_direct),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudButton(
            text = stringResource(if (torEnabled) R.string.tor_onion_test else R.string.relay_https_test),
            enabled = !network.switching,
            onClick = {
                val onion = onionAddress.trim()
                val https = httpsAddress.trim()
                // Проверяется адрес ТЕКУЩЕГО режима — и только им: проверка
                // HTTPS при включённом Tor раскрыла бы IP серверу.
                val checkedError = if (torEnabled) {
                    if (onion.isEmpty()) R.string.onion_err_empty
                    else if (OnionRelayAddress.parse(onion) == null) R.string.onion_err_invalid
                    else null
                } else {
                    if (https.isEmpty()) R.string.https_err_empty
                    else if (HttpsRelayAddress.parse(https) == null) R.string.https_err_invalid
                    else null
                }
                if (checkedError != null) {
                    testResult = context.getString(checkedError)
                    return@AegisHudButton
                }
                val normalizedOnion = OnionRelayAddress.normalize(onion)
                val normalizedHttps = HttpsRelayAddress.normalize(https)
                onionAddress = normalizedOnion
                httpsAddress = normalizedHttps
                // Изменённые адреса сохраняются ПАРОЙ как ручные: onion и HTTPS
                // обязаны вести к одному релею, автоматический второй адрес к
                // ручному первому не подставляется.
                if (normalizedOnion != settings.onionRelayAddress || normalizedHttps != settings.httpsRelayAddress) {
                    onUpdateTorNetwork(
                        settings.copy(
                            onionRelayAddress = normalizedOnion,
                            httpsRelayAddress = normalizedHttps,
                            httpsPins = emptyList(),
                            relayManual = true,
                        ),
                    )
                }
                scope.launch {
                    testResult = if (torEnabled) network.testOnion(normalizedOnion) else network.testHttps(normalizedHttps)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (settings.relayManual) {
            // Вернуть управление адресами авто-обнаружению (сброс к дефолту/конфигу).
            AegisHudOutlinedButton(
                text = stringResource(R.string.tor_onion_reset_auto),
                onClick = {
                    onionAddress = ""
                    httpsAddress = ""
                    onUpdateTorNetwork(
                        settings.copy(
                            onionRelayAddress = "",
                            httpsRelayAddress = "",
                            httpsPins = emptyList(),
                            relayManual = false,
                            configVersion = 0,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        testResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}

/** Мосты и pluggable transports (§14.7) — только в режиме Tor. */
@Composable
private fun TorBridgesBlock(
    ptAvailable: Set<String>,
    bridgeMode: BridgeUiMode,
    onBridgeMode: (BridgeUiMode) -> Unit,
    bridgeText: String,
    onBridgeText: (String) -> Unit,
    onApply: () -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary
    Column(verticalArrangement = Arrangement.spacedBy(AegisHudScreenDefaults.CardGap / 2)) {
        AegisHudGroupLabel(text = stringResource(R.string.tor_bridges_title))
        // Индикатор доступности вложенных PT-бинарей (§14.7).
        val ptStatus = if (ptAvailable.isEmpty()) {
            stringResource(R.string.tor_pt_none)
        } else {
            val list = buildList {
                if ("obfs4" in ptAvailable) add("obfs4")
                if ("meek_lite" in ptAvailable) add("meek")
                if ("snowflake" in ptAvailable) add("snowflake")
            }.joinToString(" · ")
            stringResource(R.string.tor_pt_available, list)
        }
        AegisHudStatusLine(
            text = ptStatus,
            ok = ptAvailable.isNotEmpty(),
            failColor = MaterialTheme.colorScheme.error,
        )

        AegisHudOptionRow(
            title = stringResource(R.string.tor_bridge_off),
            selected = bridgeMode == BridgeUiMode.OFF,
            onClick = { onBridgeMode(BridgeUiMode.OFF) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.tor_bridge_obfs4),
            selected = bridgeMode == BridgeUiMode.OBFS4,
            onClick = { onBridgeMode(BridgeUiMode.OBFS4) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.tor_bridge_snowflake),
            selected = bridgeMode == BridgeUiMode.SNOWFLAKE,
            onClick = { onBridgeMode(BridgeUiMode.SNOWFLAKE) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.tor_bridge_custom),
            selected = bridgeMode == BridgeUiMode.CUSTOM,
            onClick = { onBridgeMode(BridgeUiMode.CUSTOM) },
        )

        if (bridgeMode == BridgeUiMode.CUSTOM) {
            Text(
                text = stringResource(R.string.tor_bridge_custom_help),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
            // Поле остаётся Material-`OutlinedTextField`: строк мостов несколько,
            // а `AegisHudTextField` не принимает `minLines` и лейбл.
            OutlinedTextField(
                value = bridgeText,
                onValueChange = onBridgeText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tor_bridge_lines_label)) },
                minLines = 2,
                maxLines = 5,
                shape = AegisChipShape,
                colors = aegisHudTextFieldColors(),
            )
        } else if (bridgeMode != BridgeUiMode.OFF) {
            Text(
                text = stringResource(R.string.tor_bridge_rotate_note),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }

        AegisHudButton(
            text = stringResource(R.string.tor_bridge_apply),
            onClick = onApply,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Понятная причина, почему сеть сейчас не работает; `null` — не показывать (идёт смена режима). */
@Composable
private fun relayUnavailableText(reason: RelayUnavailableReason): String? = when (reason) {
    RelayUnavailableReason.SWITCHING -> null
    RelayUnavailableReason.ONION_NOT_SET -> stringResource(R.string.relay_reason_onion_not_set)
    RelayUnavailableReason.ONION_INVALID -> stringResource(R.string.relay_reason_onion_invalid)
    RelayUnavailableReason.TOR_NOT_READY -> stringResource(R.string.relay_reason_tor_not_ready)
    RelayUnavailableReason.HTTPS_NOT_SET -> stringResource(R.string.relay_reason_https_not_set)
    RelayUnavailableReason.HTTPS_INVALID -> stringResource(R.string.relay_reason_https_invalid)
}

/**
 * Карточка «Приватность метаданных» (§14.7, №3b): вынос параметров смягчения
 * тайминг-корреляции fan-out ([FanoutJitterMode]) и прикрытия факта/объёма отправки
 * cover-traffic'ом ([CoverTrafficMode]) в явные пользовательские настройки. Раньше
 * оба были жёстко привязаны к `meta.minimize` активного профиля; режим «Авто»
 * сохраняет это поведение (по умолчанию), остальные — переопределяют его.
 */
@Composable
private fun MetadataPrivacyCard(
    fanoutJitter: FanoutJitterMode,
    coverTraffic: CoverTrafficMode,
    onSetFanoutJitter: (FanoutJitterMode) -> Unit,
    onSetCoverTraffic: (CoverTrafficMode) -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary

    AegisHudCard(
        title = stringResource(R.string.meta_privacy_title),
        icon = Icons.Outlined.Shield,
    ) {
        Text(
            text = stringResource(R.string.meta_privacy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        AegisHudGroupLabel(text = stringResource(R.string.meta_privacy_jitter_title))
        Text(
            text = stringResource(R.string.meta_privacy_jitter_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_mode_auto),
            selected = fanoutJitter == FanoutJitterMode.AUTO,
            onClick = { onSetFanoutJitter(FanoutJitterMode.AUTO) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_jitter_max),
            selected = fanoutJitter == FanoutJitterMode.MAXIMUM,
            onClick = { onSetFanoutJitter(FanoutJitterMode.MAXIMUM) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_jitter_moderate),
            selected = fanoutJitter == FanoutJitterMode.MODERATE,
            onClick = { onSetFanoutJitter(FanoutJitterMode.MODERATE) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_jitter_off),
            selected = fanoutJitter == FanoutJitterMode.OFF,
            onClick = { onSetFanoutJitter(FanoutJitterMode.OFF) },
        )

        AegisHudGroupLabel(text = stringResource(R.string.meta_privacy_cover_title))
        Text(
            text = stringResource(R.string.meta_privacy_cover_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_mode_auto),
            selected = coverTraffic == CoverTrafficMode.AUTO,
            onClick = { onSetCoverTraffic(CoverTrafficMode.AUTO) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_cover_on),
            selected = coverTraffic == CoverTrafficMode.ON,
            onClick = { onSetCoverTraffic(CoverTrafficMode.ON) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.meta_privacy_cover_off),
            selected = coverTraffic == CoverTrafficMode.OFF,
            onClick = { onSetCoverTraffic(CoverTrafficMode.OFF) },
        )
    }
}

/** Режим мостов на экране «Сеть и Tor» (§14.7). */
private enum class BridgeUiMode { OFF, OBFS4, SNOWFLAKE, CUSTOM }

private fun BridgeUiMode.toBuiltIn(): BuiltInBridge = when (this) {
    BridgeUiMode.OBFS4 -> BuiltInBridge.OBFS4
    BridgeUiMode.SNOWFLAKE -> BuiltInBridge.SNOWFLAKE
    BridgeUiMode.OFF, BridgeUiMode.CUSTOM -> BuiltInBridge.NONE
}

private fun TorNetworkSettings.toBridgeUiMode(): BridgeUiMode = when {
    !bridgesEnabled -> BridgeUiMode.OFF
    builtInBridge == BuiltInBridge.OBFS4 -> BridgeUiMode.OBFS4
    builtInBridge == BuiltInBridge.SNOWFLAKE -> BridgeUiMode.SNOWFLAKE
    else -> BridgeUiMode.CUSTOM
}

@Composable
private fun TorStatus.displayText(): String = when (this) {
    is TorStatus.Connected -> stringResource(R.string.tor_status_connected)
    is TorStatus.Bootstrapping -> stringResource(R.string.tor_diag_bootstrapping, progress)
    is TorStatus.Starting -> stringResource(R.string.tor_diag_starting)
    is TorStatus.Disabled -> stringResource(R.string.tor_diag_disabled)
    is TorStatus.Unavailable -> stringResource(R.string.tor_status_unavailable)
    is TorStatus.Error -> stringResource(R.string.tor_diag_error, reason)
}

/** Открывает системные настройки уведомлений AEGIS (§14.5), если запрещены. */
private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun SecurityPreset.displayName(): String = when (this) {
    SecurityPreset.PARANOID -> stringResource(R.string.preset_paranoid)
    SecurityPreset.BALANCED -> stringResource(R.string.preset_balanced)
    SecurityPreset.FAST -> stringResource(R.string.preset_fast)
    SecurityPreset.CUSTOM -> stringResource(R.string.preset_custom)
}
