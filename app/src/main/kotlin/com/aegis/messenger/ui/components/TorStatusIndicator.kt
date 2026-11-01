package com.aegis.messenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudChip
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.network.tor.TorStatus

/**
 * Индикатор состояния транспорта для шапки раздела (§13) — HUD-чип со скобками.
 *
 * Активная защита подсвечивается циановым акцентом, ошибка — цветом danger,
 * промежуточные состояния остаются приглушёнными: «жёлтая тревога» на каждом
 * bootstrap-проценте была бы шумом.
 *
 * Режим без Tor — не ошибка Tor, а выбор пользователя: чип говорит «Без Tor /
 * прямое соединение» нейтральным цветом, а статус Arti (он в этом режиме
 * остановлен) не показывается вовсе.
 *
 * Состояние озвучивается TalkBack через `contentDescription` чипа, поэтому у
 * иконки внутри своего описания нет (иначе статус читался бы дважды).
 */
@Composable
fun TorStatusIndicator(
    status: TorStatus,
    modifier: Modifier = Modifier,
    torEnabled: Boolean = true,
    switching: Boolean = false,
) {
    val colors = AegisTheme.extendedColors

    if (!torEnabled) {
        val label = stringResource(if (switching) R.string.net_status_switching else R.string.net_status_direct)
        AegisHudChip(
            text = label,
            modifier = modifier,
            icon = Icons.Outlined.Public,
            accent = colors.textSecondary,
            contentDescription = label,
            iconSize = 16.dp,
        )
        return
    }

    val label: String = when (status) {
        is TorStatus.Connected -> stringResource(R.string.tor_status_connected)
        is TorStatus.Bootstrapping ->
            stringResource(R.string.tor_status_bootstrapping, status.progress)
        is TorStatus.Starting -> stringResource(R.string.tor_status_starting)
        is TorStatus.Disabled -> stringResource(R.string.tor_status_disabled)
        is TorStatus.Unavailable -> stringResource(R.string.tor_status_unavailable)
        is TorStatus.Error -> stringResource(R.string.tor_status_error)
    }
    val accent: Color = when (status) {
        is TorStatus.Connected -> colors.securityAccent
        is TorStatus.Error, is TorStatus.Unavailable -> MaterialTheme.colorScheme.error
        else -> colors.textSecondary
    }

    AegisHudChip(
        text = label,
        modifier = modifier,
        icon = if (status is TorStatus.Error || status is TorStatus.Unavailable) {
            Icons.Outlined.GppBad
        } else {
            Icons.Filled.Shield
        },
        accent = accent,
        contentDescription = label,
        // Крупнее базового чипа: щит в шапке — индикатор состояния защиты, его
        // считывают мельком, не вчитываясь в подпись.
        iconSize = 16.dp,
    )
}
