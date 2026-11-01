package com.aegis.messenger.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aegis.core.common.privacy.PrivacySettings
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudRow
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Секция «Приватность» (§14.2): отчёты о прочтении (галочки), индикатор
 * «печатает…», онлайн-статус, вход в чёрный список. Управляемые метаданные
 * (§10.9) — по сети сервер их не видит, но пользователь вправе не раскрывать их
 * и собеседнику.
 */
@Composable
fun PrivacySettingsSection(
    settings: PrivacySettings,
    onSetPrivacySettings: (PrivacySettings) -> Unit,
    onOpenBlockList: () -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary

    AegisHudCard(
        title = stringResource(R.string.privacy_section_title),
        icon = Icons.Outlined.VisibilityOff,
    ) {
        AegisHudSwitchRow(
            title = stringResource(R.string.privacy_read_receipts_title),
            subtitle = stringResource(R.string.privacy_read_receipts_sub),
            checked = settings.sendReadReceipts,
            onCheckedChange = { onSetPrivacySettings(settings.copy(sendReadReceipts = it)) },
        )
        AegisHudSwitchRow(
            title = stringResource(R.string.privacy_typing_title),
            subtitle = stringResource(R.string.privacy_typing_sub),
            checked = settings.sendTypingIndicators,
            onCheckedChange = { onSetPrivacySettings(settings.copy(sendTypingIndicators = it)) },
        )
        AegisHudSwitchRow(
            title = stringResource(R.string.privacy_online_title),
            subtitle = stringResource(R.string.privacy_online_sub),
            checked = settings.shareOnlineStatus,
            onCheckedChange = { onSetPrivacySettings(settings.copy(shareOnlineStatus = it)) },
        )

        AegisHudRow(
            modifier = Modifier.clickable(onClick = onOpenBlockList),
            trailing = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.privacy_blocklist_open_cd),
                    tint = secondary,
                )
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.privacy_blocklist_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.privacy_blocklist_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                )
            }
        }
    }
}
