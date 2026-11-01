package com.aegis.messenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.AegisConstants
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.hud.AegisHudToggleChip
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Секция «Чаты и сообщения» (§14.4). Пока — управление сроком хранения
 * переписки (§7.3): ЖЁСТКИЙ потолок 7 дней (нельзя превысить и нельзя
 * отключить), можно выбрать меньший срок (1–6 дней). Значение применяется ко
 * ВСЕМ чатам; более старые сообщения не хранятся ни локально, ни на сервере.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageSettingsSection(
    autoDeleteDays: Int,
    onSetAutoDeleteDays: (Int) -> Unit,
    autoDownloadMedia: Boolean,
    onSetAutoDownloadMedia: (Boolean) -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary
    val accent = AegisTheme.extendedColors.securityAccent

    AegisHudCard(
        title = stringResource(R.string.msg_section_title),
        icon = Icons.Outlined.Timer,
    ) {
        AegisHudGroupLabel(text = stringResource(R.string.msg_retention_title))
        Text(
            text = stringResource(R.string.msg_retention_desc, AegisConstants.AUTO_DELETE_MAX_DAYS),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (days in AegisConstants.AUTO_DELETE_MIN_DAYS..AegisConstants.AUTO_DELETE_MAX_DAYS) {
                AegisHudToggleChip(
                    text = dayLabel(days),
                    selected = autoDeleteDays == days,
                    onClick = { onSetAutoDeleteDays(days) },
                )
            }
        }

        val currentLabel = dayLabel(autoDeleteDays)
        Text(
            text = if (autoDeleteDays == AegisConstants.AUTO_DELETE_MAX_DAYS) {
                stringResource(R.string.msg_retention_current_max, currentLabel)
            } else {
                stringResource(R.string.msg_retention_current, currentLabel)
            },
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )

        AegisHudDivider()

        AegisHudSwitchRow(
            title = stringResource(R.string.msg_autodownload_title),
            subtitle = stringResource(
                R.string.msg_autodownload_desc,
                (AegisConstants.ATTACHMENT_AUTO_DOWNLOAD_MAX_BYTES / (1024 * 1024)).toInt(),
            ),
            checked = autoDownloadMedia,
            onCheckedChange = onSetAutoDownloadMedia,
        )
    }
}

/** Число дней с корректным склонением (plurals ресурс, §14.6). */
@Composable
private fun dayLabel(days: Int): String = pluralStringResource(R.plurals.days_count, days, days)
