package com.aegis.messenger.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Секция «Звонки» (§14.9): разрешить звонки, признак доступности нативного
 * WebRTC и информационная строка о маршруте медиа. Отдельного переключателя
 * «медиа через Tor» нет: маршрут выводится из режима сети («Сеть и Tor»).
 */
@Composable
fun CallSettingsSection(
    callsEnabled: Boolean,
    torEnabled: Boolean,
    mediaEngineReal: Boolean,
    onSetCallsEnabled: (Boolean) -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary
    AegisHudCard(
        title = stringResource(R.string.call_settings_title),
        icon = Icons.Outlined.Call,
    ) {
        AegisHudSwitchRow(
            title = stringResource(R.string.call_settings_enable_title),
            subtitle = stringResource(R.string.call_settings_enable_sub),
            checked = callsEnabled,
            onCheckedChange = onSetCallsEnabled,
        )

        AegisHudStatusLine(
            text = stringResource(if (torEnabled) R.string.call_settings_route_tor else R.string.call_settings_route_direct),
            ok = torEnabled,
            failColor = AegisTheme.extendedColors.accentPrimary,
        )

        if (!mediaEngineReal) {
            AegisHudStatusLine(
                text = stringResource(R.string.call_settings_no_webrtc),
                ok = false,
                failColor = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = stringResource(R.string.call_settings_meta_note),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}
