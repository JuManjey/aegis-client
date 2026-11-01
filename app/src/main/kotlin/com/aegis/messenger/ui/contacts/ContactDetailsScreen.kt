package com.aegis.messenger.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.contact.ContactTrust
import com.aegis.core.common.model.ContactCard
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.crypto.PskCrypto
import com.aegis.messenger.R
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone

/**
 * Подробности контакта (§14.1/§14.3), HUD-раскладка — `docs/ui_hud.md`.
 *
 * Экран отвечает на один вопрос: «кто это на самом деле и что я могу с ним
 * сделать». Поэтому наверху идёт ИДЕНТИЧНОСТЬ (accountId и отпечаток) в
 * отдельной рамке и моноширинно — её сверяют символ в символ; ниже состояние
 * (сверен, ключи слоя B, блокировка, тишина) и только потом действия.
 *
 * Удаление — только через подтверждение и только с полным перечислением того,
 * что исчезнет: операция необратима и сносит ключ обмена, то есть вернуть
 * контакт «как было» невозможно даже теоретически.
 *
 * Экран не владеет удалением: его запускает и переживает хозяин вкладки
 * ([ContactsScreen]). Иначе корутина удаления жила бы в композиции ЭТОГО
 * экрана, а он закрывается ровно посреди операции — как только контакт
 * пропадает из списка, — и остаток работы (заявки, уведомления) отменился бы.
 */
@Composable
fun ContactDetailsScreen(
    contact: ContactCard,
    onOpenChat: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    deleting: Boolean = false,
    errorMessage: String? = null,
) {
    val colors = AegisTheme.extendedColors
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        AegisHudSectionHeader(
            title = stringResource(R.string.contacts_details_title),
            subtitle = contact.displayName,
            icon = Icons.Outlined.Person,
            leading = {
                AegisHudIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onBack,
                    size = 34.dp,
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            errorMessage?.let { message ->
                AegisHudBanner(text = message, tone = HudBannerTone.DANGER)
            }

            // --- Идентичность: то, ЧТО НУЖНО СВЕРИТЬ ---
            AegisHudCard(
                title = stringResource(R.string.contacts_details_identity),
                icon = Icons.Outlined.Fingerprint,
            ) {
                // Отдельная рубленая рамка и моноширинный стиль: блок должен
                // читаться как «данные для сверки», а не как подпись карточки.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AegisHudKeyValue(
                        label = stringResource(R.string.contacts_details_account),
                        value = contact.id.removePrefix("aegis:").uppercase(),
                    )
                    AegisHudKeyValue(
                        label = stringResource(R.string.contacts_details_fingerprint),
                        value = contact.fingerprint.takeIf { it.isNotBlank() }
                            ?.let { PskCrypto.fingerprintGrouped(it) }
                            ?: stringResource(R.string.contacts_details_fingerprint_absent),
                        valueColor = if (contact.fingerprint.isBlank()) {
                            colors.textSecondary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (contact.nickname.isNotBlank()) {
                    AegisHudKeyValue(
                        label = stringResource(R.string.contacts_details_nickname),
                        value = contact.nickname,
                    )
                }
                if (contact.localName.isNotBlank()) {
                    AegisHudKeyValue(
                        label = stringResource(R.string.contacts_details_local_name),
                        value = contact.localName,
                    )
                }
                Text(
                    text = stringResource(R.string.contacts_details_identity_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            // --- Состояние ---
            AegisHudCard(
                title = stringResource(R.string.contacts_details_state),
                icon = Icons.Outlined.Info,
            ) {
                AegisHudKeyValue(
                    label = stringResource(R.string.contacts_details_trust),
                    value = trustLabel(contact),
                    valueColor = trustAccent(contact),
                )
                AegisHudStatusLine(
                    text = stringResource(
                        if (contact.verified) {
                            R.string.verify_status_verified
                        } else {
                            R.string.verify_status_unverified
                        },
                    ),
                    ok = contact.verified,
                )
                AegisHudStatusLine(
                    text = stringResource(
                        if (contact.e2eeActive) {
                            R.string.contacts_details_e2ee_on
                        } else {
                            R.string.contacts_details_e2ee_off
                        },
                    ),
                    ok = contact.e2eeActive,
                )
                AegisHudStatusLine(
                    text = stringResource(
                        if (contact.blocked) {
                            R.string.blocklist_status_blocked
                        } else {
                            R.string.blocklist_status_active
                        },
                    ),
                    ok = !contact.blocked,
                    failColor = colors.danger,
                )
                AegisHudStatusLine(
                    text = stringResource(
                        if (contact.muted) {
                            R.string.contacts_details_muted
                        } else {
                            R.string.contacts_details_unmuted
                        },
                    ),
                    ok = !contact.muted,
                )
                Text(
                    text = stringResource(R.string.contacts_details_devices, contact.deviceCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            // --- Действия ---
            AegisHudCard(
                title = stringResource(R.string.contacts_details_actions),
                titleTrailing = { if (deleting) AegisHudBusy(size = 14.dp) },
            ) {
                AegisHudButton(
                    text = stringResource(R.string.contacts_details_open_chat),
                    onClick = { onOpenChat(contact.id) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleting,
                )
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contacts_details_verify),
                    onClick = { onVerify(contact.id) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleting,
                )
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contacts_details_delete),
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deleting,
                    icon = Icons.Filled.DeleteForever,
                    danger = true,
                )
            }
        }
    }

    if (confirmDelete) {
        AegisHudDialog(
            title = stringResource(R.string.contacts_delete_title),
            onDismiss = { confirmDelete = false },
            icon = Icons.Filled.DeleteForever,
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { confirmDelete = false },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.contacts_delete_body, contact.displayName),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Подпись способа добавления (§14.3). Способ и ручная сверка — два независимых
 * факта, поэтому сверка добавляется суффиксом, а не подменяет способ.
 */
@Composable
private fun trustLabel(contact: ContactCard): String {
    val base = stringResource(
        when (contact.trust) {
            ContactTrust.IN_PERSON -> R.string.trust_in_person
            ContactTrust.REMOTE_ADDRESS -> R.string.trust_remote_address
            ContactTrust.UNKNOWN -> R.string.trust_unknown
        },
    )
    return if (contact.verified) base + stringResource(R.string.trust_verified_suffix) else base
}

/**
 * Цвет способа добавления: цианом отмечен только личный обмен — единственный
 * путь, где секрет слоя A вообще не проходил через сеть. Удалённое добавление
 * и «способ неизвестен» остаются служебно-серыми, чтобы зелёный акцент не
 * читался как «проверено».
 */
@Composable
private fun trustAccent(contact: ContactCard) = when (contact.trust) {
    ContactTrust.IN_PERSON -> AegisTheme.extendedColors.securityAccent
    ContactTrust.REMOTE_ADDRESS, ContactTrust.UNKNOWN -> AegisTheme.extendedColors.textSecondary
}
