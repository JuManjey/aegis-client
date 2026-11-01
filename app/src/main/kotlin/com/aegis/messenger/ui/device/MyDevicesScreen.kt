package com.aegis.messenger.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.device.LinkedDevice
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudRow
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.hud.AegisHudScrollRail
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.onboarding.LabeledTextField
import kotlinx.coroutines.launch

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * device id: код попадает на любой скриншот (§6 `docs/ui_hud.md`).
 */
private const val DEVICES_HUD_SLUG = "devices"

/**
 * Экран «Мои устройства» (мультидевайс §7.1.1, `docs/multidevice.md` §5/§10,
 * UX-долг Этапа 5) в HUD-раскладке (`docs/ui_hud.md`).
 *
 * Показывает реестр СВОИХ устройств аккаунта: текущее ([DeviceSettings.localDeviceId])
 * и линкованные ([DeviceSettings.linkedDevices]). Позволяет:
 *  - переименовать любое своё устройство (метка локальна, разъезжается по
 *    self-sync-анонсам);
 *  - ОТОЗВАТЬ линкованное устройство ([onRevokeLinkedDevice] → device-revoke
 *    контактам, `revoked=true` в реестре) — fan-out/self-sync на него
 *    прекращается.
 *
 * Отзыв — честная граница (§10): «перестать слать/принимать», а не ретроактивная
 * защита; уже доставленные на устройство сообщения там остаются.
 */
@Composable
fun MyDevicesScreen(
    deviceSettings: DeviceSettings,
    multiDeviceAllowed: Boolean,
    onRenameThisDevice: suspend (String) -> Unit,
    onRenameLinkedDevice: suspend (Int, String) -> Unit,
    onRevokeLinkedDevice: suspend (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Диалог переименования: null = закрыт; deviceId = редактируемое устройство.
    var renameTarget by remember { mutableStateOf<Int?>(null) }
    var revokeTarget by remember { mutableStateOf<LinkedDevice?>(null) }

    val linked = deviceSettings.linkedDevices.sortedBy { it.deviceId }

    AegisHudScreen(
        title = stringResource(R.string.devices_title),
        modifier = modifier,
        slug = DEVICES_HUD_SLUG,
        icon = Icons.Outlined.Devices,
        onBack = onBack,
        backContentDescription = stringResource(R.string.common_back),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.devices_desc),
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )

            if (!multiDeviceAllowed) {
                AegisHudBanner(
                    text = stringResource(R.string.devices_disabled_notice),
                    tone = HudBannerTone.DANGER,
                )
            }
            if (linked.isEmpty()) {
                AegisHudBanner(
                    text = stringResource(R.string.devices_no_linked),
                    tone = HudBannerTone.SECURITY,
                )
            }

            AegisHudPanel(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 10.dp),
                contentPadding = 4.dp,
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Текущее устройство. Его id в реестре линкованных не
                        // встречается, поэтому ключи строк не конфликтуют.
                        item(key = deviceSettings.localDeviceId) {
                            DeviceRow(
                                deviceId = deviceSettings.localDeviceId,
                                label = deviceSettings.label,
                                isThisDevice = true,
                                revoked = false,
                                onRename = { renameTarget = deviceSettings.localDeviceId },
                                onRevoke = null,
                            )
                        }
                        items(items = linked, key = { it.deviceId }) { device ->
                            DeviceRow(
                                deviceId = device.deviceId,
                                label = device.label,
                                isThisDevice = false,
                                revoked = device.revoked,
                                onRename = { renameTarget = device.deviceId },
                                onRevoke = if (device.revoked) null else { { revokeTarget = device } },
                            )
                        }
                    }
                    AegisHudScrollRail(
                        listState = listState,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }

    renameTarget?.let { targetId ->
        val current = if (targetId == deviceSettings.localDeviceId) {
            deviceSettings.label
        } else {
            deviceSettings.linkedDevices.firstOrNull { it.deviceId == targetId }?.label ?: ""
        }
        RenameDialog(
            initial = current,
            onDismiss = { renameTarget = null },
            onConfirm = { newLabel ->
                renameTarget = null
                scope.launch {
                    if (targetId == deviceSettings.localDeviceId) {
                        onRenameThisDevice(newLabel)
                    } else {
                        onRenameLinkedDevice(targetId, newLabel)
                    }
                }
            },
        )
    }

    revokeTarget?.let { target ->
        AegisHudDialog(
            title = stringResource(R.string.devices_revoke_confirm_title),
            onDismiss = { revokeTarget = null },
            icon = Icons.Outlined.LinkOff,
            confirmLabel = stringResource(R.string.devices_revoke),
            onConfirm = {
                revokeTarget = null
                scope.launch { onRevokeLinkedDevice(target.deviceId) }
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { revokeTarget = null },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.devices_revoke_confirm_text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Строка реестра: «ручка» HUD, имя устройства, строка состояния «ID · это
 * устройство / линкованное / отозвано» и действия справа.
 *
 * Имя устройства и его id выводятся как есть: имя — пользовательский ввод, id
 * сверяют с экраном другого устройства, и менять регистр им нельзя.
 *
 * Клика на всей строке нет — действий два, и оба висят на своих кнопках;
 * иначе у строки было бы три конкурирующих цели касания.
 */
@Composable
private fun DeviceRow(
    deviceId: Int,
    label: String,
    isThisDevice: Boolean,
    revoked: Boolean,
    onRename: () -> Unit,
    onRevoke: (() -> Unit)?,
) {
    val colors = AegisTheme.extendedColors
    AegisHudRow(
        minHeight = 56.dp,
        trailing = {
            AegisHudIconButton(
                icon = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.devices_rename),
                onClick = onRename,
                size = 34.dp,
            )
            if (onRevoke != null) {
                AegisHudIconButton(
                    icon = Icons.Outlined.LinkOff,
                    contentDescription = stringResource(R.string.devices_revoke),
                    onClick = onRevoke,
                    accent = colors.danger,
                    size = 34.dp,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label.ifBlank { stringResource(R.string.devices_unnamed) },
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AegisHudStatusLine(
                text = when {
                    revoked -> stringResource(R.string.devices_id_revoked, deviceId)
                    isThisDevice -> stringResource(R.string.devices_id_this, deviceId)
                    else -> stringResource(R.string.devices_id_linked, deviceId)
                },
                ok = !revoked,
                failColor = colors.danger,
            )
        }
    }
}

/**
 * Диалог переименования. Подпись поля остаётся в слоте `label`, а не уходит в
 * placeholder: только так TalkBack связывает её с вводом, и она видна, когда в
 * поле уже есть текущее имя.
 */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AegisHudDialog(
        title = stringResource(R.string.devices_rename),
        onDismiss = onDismiss,
        icon = Icons.Outlined.Edit,
        confirmLabel = stringResource(R.string.common_continue),
        onConfirm = { onConfirm(value) },
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
    ) {
        LabeledTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.devices_rename_label),
            supporting = null,
        )
    }
}
