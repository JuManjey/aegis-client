package com.aegis.messenger.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudOptionRow
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Настройка удалённого добавления контактов (§8, `docs/contact_requests.md`).
 *
 * ОТДЕЛЬНАЯ ОСЬ от `keys.overSignal`: там речь о ПЕРЕДАЧЕ готового секрета слоя
 * A по сети, здесь секрет никуда не передаётся — он выводится из подписанного
 * рукопожатия на обеих сторонах независимо. Поэтому режим «по адресу» допустим
 * и в пресете «Параноик», а вот публичный `@ник` — нет: он делает пользователя
 * перечислимым, и это решение он принимает сам, видя цену.
 */
@Composable
fun ContactRequestSettingsSection(
    mode: ContactRequestMode,
    declineNotify: Boolean,
    onSetMode: (ContactRequestMode) -> Unit,
    onSetDeclineNotify: (Boolean) -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary

    AegisHudCard(title = stringResource(R.string.settings_contact_requests)) {
        Text(
            text = stringResource(R.string.settings_contact_requests_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudDivider()

        AegisHudOptionRow(
            title = stringResource(R.string.settings_contact_requests_off),
            selected = mode == ContactRequestMode.OFF,
            onClick = { onSetMode(ContactRequestMode.OFF) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.settings_contact_requests_address),
            selected = mode == ContactRequestMode.ADDRESS,
            onClick = { onSetMode(ContactRequestMode.ADDRESS) },
        )
        AegisHudOptionRow(
            title = stringResource(R.string.settings_contact_requests_alias),
            selected = mode == ContactRequestMode.ADDRESS_AND_ALIAS,
            onClick = { onSetMode(ContactRequestMode.ADDRESS_AND_ALIAS) },
        )

        // Цену публичного ника показываем РЯДОМ с выбором, а не только на
        // экране добавления: иначе решение принималось бы без неё.
        if (mode == ContactRequestMode.ADDRESS_AND_ALIAS) {
            Text(
                text = stringResource(R.string.contact_add_alias_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (mode != ContactRequestMode.OFF) {
            AegisHudDivider()
            AegisHudSwitchRow(
                title = stringResource(R.string.settings_contact_decline_notify),
                subtitle = stringResource(R.string.settings_contact_decline_notify_desc),
                checked = declineNotify,
                onCheckedChange = onSetDeclineNotify,
            )
        }
    }
}
