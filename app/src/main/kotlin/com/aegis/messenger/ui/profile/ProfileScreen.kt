package com.aegis.messenger.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.ui.hud.AegisHudAvatar
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.ui.account.LoginCredentialsDialog
import kotlinx.coroutines.launch

/**
 * Раздел «Мой профиль» (§14.1) в HUD-раскладке (`docs/ui_hud.md`): «карточка
 * личного дела» — аватар и логин, ниже служебные параметры идентичности
 * (ID аккаунта, отпечаток для верификации), мультидевайс и действия
 * (линковка, перенос, показ фразы, удаление = crypto-shredding).
 *
 * Шапку раздела и микро-коды рисует `MainScaffold` — это вкладка нижнего меню,
 * поэтому своего [com.aegis.core.ui.hud.AegisHudScreen] здесь быть не должно:
 * иначе экран получил бы вторую шапку и двойную виньетку фона.
 */
@Composable
fun ProfileScreen(
    profile: AccountProfile,
    accountManager: AccountManager,
    onSetMultiDeviceAllowed: (Boolean) -> Unit = {},
    onOpenTransfer: () -> Unit = {},
    onOpenMergeImport: () -> Unit = {},
    onOpenLinkDevice: () -> Unit = {},
    onOpenMyDevices: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val scope = rememberCoroutineScope()
    var showRecovery by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IdentityCard(login = profile.login, onEdit = { showLoginDialog = true })

        AegisHudCard {
            AegisHudKeyValue(
                label = stringResource(R.string.profile_account_id),
                value = profile.identity.accountId,
            )
            AegisHudDivider()
            AegisHudKeyValue(
                label = stringResource(R.string.profile_fingerprint),
                value = profile.identity.fingerprintGrouped(),
            )
            AegisHudDivider()
            AegisHudKeyValue(
                label = stringResource(R.string.profile_creation_mode),
                value = when (profile.creationMode) {
                    AccountCreationMode.MANUAL -> stringResource(R.string.profile_creation_manual)
                    AccountCreationMode.AUTOMATIC -> stringResource(R.string.profile_creation_auto)
                },
            )
        }

        AegisHudCard {
            AegisHudSwitchRow(
                title = stringResource(R.string.profile_multidevice),
                checked = profile.multiDeviceAllowed,
                onCheckedChange = onSetMultiDeviceAllowed,
                subtitle = stringResource(R.string.account_multidevice_hint),
            )
        }

        AegisHudOutlinedButton(
            text = stringResource(
                when {
                    !profile.hasPassword -> R.string.sec_credentials_add
                    profile.login.isBlank() -> R.string.sec_credentials_set_login
                    else -> R.string.sec_credentials_change_login
                },
            ),
            onClick = { showLoginDialog = true },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Badge,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_link_device),
            onClick = onOpenLinkDevice,
            modifier = Modifier.fillMaxWidth(),
            enabled = profile.multiDeviceAllowed,
            icon = Icons.Outlined.AddLink,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_my_devices),
            onClick = onOpenMyDevices,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Devices,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_transfer_account),
            onClick = onOpenTransfer,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Upload,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_merge_import),
            onClick = onOpenMergeImport,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Download,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_show_recovery),
            onClick = { showRecovery = true },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Key,
        )
        // Блокировка — ЗАЩИТНОЕ действие, а не опасное, поэтому без `danger`.
        // Красной она стояла вплотную к красному «Удалить аккаунт»: две кнопки
        // одного цвета рядом, из которых одна запирает приложение, а вторая
        // стирает аккаунт без возврата, — это приглашение к промаху.
        AegisHudOutlinedButton(
            text = stringResource(R.string.profile_lock),
            onClick = { accountManager.lock() },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Lock,
        )
        AegisHudButton(
            text = stringResource(R.string.profile_delete),
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.DeleteForever,
            danger = true,
        )
    }

    if (showLoginDialog) {
        LoginCredentialsDialog(
            requirePassword = !profile.hasPassword,
            initialLogin = profile.login,
            onDismiss = { showLoginDialog = false },
            onSubmit = { login, password ->
                if (!profile.hasPassword) {
                    accountManager.addLoginAndPassword(login, password)
                } else {
                    accountManager.setLogin(login)
                }
            },
        )
    }

    if (showRecovery) {
        val mnemonic = accountManager.unlockedMnemonic
        AegisHudDialog(
            title = stringResource(R.string.recovery_title),
            onDismiss = { showRecovery = false },
            icon = Icons.Outlined.Key,
            confirmLabel = stringResource(R.string.common_continue),
            onConfirm = { showRecovery = false },
        ) {
            // Фраза выводится моноширинным и без смены регистра: её
            // переписывают слово в слово с экрана на бумагу (§7.1).
            Text(
                text = mnemonic ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = colors.securityAccent,
            )
            Text(
                text = stringResource(R.string.recovery_warning),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }

    if (confirmDelete) {
        AegisHudDialog(
            title = stringResource(R.string.profile_delete_confirm_title),
            onDismiss = { confirmDelete = false },
            icon = Icons.Outlined.DeleteForever,
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = false
                scope.launch { accountManager.wipe() }
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { confirmDelete = false },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.profile_delete_confirm_text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Шапка «личного дела»: квадратный аватар-ячейка, подпись поля и логин крупным
 * узким прописным начертанием. Вся карточка — кнопка правки логина, как и было
 * до перехода на HUD; иконка несёт описание действия для TalkBack, потому что
 * из видимого текста назначение нажатия не следует.
 */
@Composable
private fun IdentityCard(login: String, onEdit: () -> Unit) {
    val colors = AegisTheme.extendedColors
    AegisHudCard(onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AegisHudAvatar(initials = loginInitial(login), size = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.profile_login).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                // Логин выводится ДОСЛОВНО, без uppercase. `AccountLogin.normalize`
                // делает только `trim()`, то есть регистр — часть логина. Это та
                // самая строка, которую собеседник вводит, чтобы добавить нас, и
                // которая уезжает в QR обмена PSK: показать её прописной значит
                // показать логин, которого не существует.
                Text(
                    text = login.ifBlank { stringResource(R.string.profile_no_login) },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(R.string.sec_credentials_change_login),
                tint = colors.securityAccent,
            )
        }
    }
}

/**
 * Первая буква логина для квадратного аватара.
 *
 * Берётся кодовая ТОЧКА, а не `Char`: у логина, начинающегося с символа вне
 * BMP, `first()` вернул бы половину surrogate-пары и в аватаре появился бы «?».
 */
private fun loginInitial(login: String): String {
    val trimmed = login.trim()
    if (trimmed.isEmpty()) return "?"
    return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
}
