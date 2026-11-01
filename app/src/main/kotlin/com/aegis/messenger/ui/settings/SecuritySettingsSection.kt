package com.aegis.messenger.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudCheckRow
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.hud.AegisHudToggleChip
import com.aegis.core.ui.hud.aegisHudTextFieldColors
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.data.storage.KeyStoreManager
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.AccountUpdateResult
import com.aegis.messenger.ui.account.LoginCredentialsDialog
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.security.BiometricAuthResult
import com.aegis.messenger.ui.security.authenticateBiometric
import com.aegis.messenger.ui.security.isBiometricAvailable
import kotlinx.coroutines.launch

/**
 * Секция «Безопасность» (§14.3): биометрический вход, пароль-обманка (duress) и
 * экстренная очистка. Собирает воедино заготовки P7 с реальным UI/BiometricPrompt.
 */
@Composable
fun SecuritySettingsSection(
    profile: AccountProfile,
    accountManager: AccountManager,
    keyStoreManager: KeyStoreManager,
    autoLockMinutes: Int,
    onSetAutoLockMinutes: (Int) -> Unit,
    deliverWhileLocked: Boolean,
    onSetDeliverWhileLocked: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val secondary = AegisTheme.extendedColors.textSecondary

    var biometricConfigured by remember { mutableStateOf(false) }
    var duressConfigured by remember { mutableStateOf(false) }
    var wipeAfterFailures by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var showDuressDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    var showCredentialsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        biometricConfigured = accountManager.isBiometricUnlockConfigured()
        duressConfigured = accountManager.isDuressConfigured()
        wipeAfterFailures = accountManager.isWipeAfterFailedUnlocksEnabled()
    }

    val biometricSupported = activity != null && isBiometricAvailable(activity)

    AegisHudCard(
        title = stringResource(R.string.sec_section_title),
        icon = Icons.Outlined.Shield,
    ) {
        // --- Логин / пароль входа (§14.1 / §14.3) ---
        AegisHudGroupLabel(text = stringResource(R.string.sec_credentials_title))
        if (!profile.hasPassword) {
            AegisHudBanner(
                text = stringResource(R.string.sec_no_password_warning),
                tone = HudBannerTone.DANGER,
            )
        }
        Text(
            text = stringResource(R.string.sec_credentials_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        Text(
            text = stringResource(
                R.string.sec_credentials_current,
                profile.login.ifBlank { stringResource(R.string.profile_no_login) },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AegisHudOutlinedButton(
            text = stringResource(
                when {
                    !profile.hasPassword -> R.string.sec_credentials_add
                    profile.login.isBlank() -> R.string.sec_credentials_set_login
                    else -> R.string.sec_credentials_change_login
                },
            ),
            onClick = { showCredentialsDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        // --- Биометрический вход (§14.3) ---
        // Заголовком строки идёт НАЗВАНИЕ функции, а не её состояние: TalkBack
        // читает имя переключателя вслух, и «Включён, переключатель, включено»
        // сообщало бы состояние дважды, не называя саму настройку. Состояние
        // видно по самому ползунку — для этого он и нужен.
        AegisHudSwitchRow(
            title = stringResource(R.string.sec_biometric_title),
            subtitle = stringResource(R.string.sec_biometric_desc),
            checked = biometricConfigured,
            onCheckedChange = { enable ->
                if (activity == null) return@AegisHudSwitchRow
                busy = true
                statusMessage = null
                scope.launch {
                    if (enable) {
                        val ok = enableBiometric(activity, accountManager, keyStoreManager)
                        biometricConfigured = ok
                        statusMessage = context.getString(
                            if (ok) R.string.sec_biometric_on_msg else R.string.sec_biometric_on_fail,
                        )
                    } else {
                        accountManager.disableBiometricUnlock()
                        biometricConfigured = false
                        statusMessage = context.getString(R.string.sec_biometric_off_msg)
                    }
                    busy = false
                }
            },
            enabled = !busy && profile.hasPassword && (biometricConfigured || biometricSupported),
        )
        if (!profile.hasPassword) {
            Text(
                text = stringResource(R.string.sec_biometric_no_password),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        } else if (!biometricSupported && !biometricConfigured) {
            Text(
                text = stringResource(R.string.sec_biometric_no_strong),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }

        // --- Автоблокировка по бездействию (§14.3) ---
        AegisHudGroupLabel(text = stringResource(R.string.sec_autolock_title))
        Text(
            text = stringResource(R.string.sec_autolock_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AUTO_LOCK_OPTIONS.forEach { minutes ->
                AegisHudToggleChip(
                    text = autoLockOptionLabel(minutes),
                    selected = autoLockMinutes == minutes,
                    onClick = { onSetAutoLockMinutes(minutes) },
                )
            }
        }
        if (autoLockMinutes == 0) {
            Text(
                text = stringResource(R.string.sec_autolock_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }

        // Приём при закрытом аккаунте. Стоит РЯДОМ с автоблокировкой не
        // случайно: это ровно её побочный эффект, и пользователь должен видеть
        // связь между «блокируется через 15 минут» и «сообщения перестают
        // приходить через 15 минут». Показывается и без автоблокировки: аккаунт
        // можно закрыть вручную.
        AegisHudSwitchRow(
            title = stringResource(R.string.sec_deliver_locked_title),
            subtitle = stringResource(R.string.sec_deliver_locked_sub),
            checked = deliverWhileLocked,
            onCheckedChange = onSetDeliverWhileLocked,
        )
        Text(
            text = stringResource(
                if (deliverWhileLocked) {
                    R.string.sec_deliver_locked_on_note
                } else {
                    R.string.sec_deliver_locked_off_note
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        // --- Duress-пароль (§7.4/§8) ---
        AegisHudGroupLabel(text = stringResource(R.string.sec_duress_title))
        Text(
            text = stringResource(R.string.sec_duress_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AegisHudOutlinedButton(
                text = stringResource(
                    if (duressConfigured) R.string.sec_duress_change else R.string.sec_duress_set,
                ),
                onClick = { showDuressDialog = true },
                modifier = Modifier.weight(1f),
                enabled = !busy && profile.hasPassword,
            )
            if (duressConfigured) {
                // Отключение обманки ослабляет защиту — маркируем как опасное.
                AegisHudOutlinedButton(
                    text = stringResource(R.string.sec_duress_disable),
                    onClick = {
                        busy = true
                        scope.launch {
                            accountManager.clearDuressPassword()
                            duressConfigured = false
                            statusMessage = context.getString(R.string.sec_duress_disabled_msg)
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    danger = true,
                )
            }
        }

        // --- Неверные пароли (§14.3): задержка всегда, очистка — по выбору ---
        AegisHudSwitchRow(
            title = stringResource(R.string.sec_wipe_failed_title),
            subtitle = stringResource(R.string.sec_wipe_failed_sub),
            checked = wipeAfterFailures,
            onCheckedChange = { enable ->
                busy = true
                scope.launch {
                    if (accountManager.setWipeAfterFailedUnlocks(enable)) wipeAfterFailures = enable
                    busy = false
                }
            },
            enabled = !busy && profile.hasPassword,
        )

        statusMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = secondary)
        }

        // --- Экстренная очистка (§7.4) ---
        AegisHudGroupLabel(text = stringResource(R.string.sec_wipe_title))
        Text(
            text = stringResource(R.string.sec_wipe_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        AegisHudButton(
            text = stringResource(R.string.sec_wipe_title),
            onClick = { showWipeDialog = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            danger = true,
        )
    }

    if (showCredentialsDialog) {
        LoginCredentialsDialog(
            requirePassword = !profile.hasPassword,
            initialLogin = profile.login,
            onDismiss = { showCredentialsDialog = false },
            onSubmit = { login, password ->
                val result = if (!profile.hasPassword) {
                    accountManager.addLoginAndPassword(login, password)
                } else {
                    accountManager.setLogin(login)
                }
                if (result is AccountUpdateResult.Success) {
                    statusMessage = context.getString(
                        if (!profile.hasPassword) R.string.sec_credentials_saved
                        else R.string.sec_credentials_login_saved,
                    )
                }
                result
            },
        )
    }

    if (showDuressDialog) {
        DuressPasswordDialog(
            onDismiss = { showDuressDialog = false },
            onConfirm = { pwd ->
                showDuressDialog = false
                busy = true
                scope.launch {
                    val ok = accountManager.setDuressPassword(pwd)
                    duressConfigured = ok || accountManager.isDuressConfigured()
                    statusMessage = context.getString(
                        if (ok) R.string.sec_duress_saved_msg else R.string.sec_duress_save_fail,
                    )
                    busy = false
                }
            },
        )
    }

    if (showWipeDialog) {
        EmergencyWipeDialog(
            onDismiss = { showWipeDialog = false },
            onConfirm = { bestEffort ->
                showWipeDialog = false
                busy = true
                scope.launch {
                    // После очистки состояние → None и auth-gate уводит на онбординг.
                    accountManager.wipe(includeBestEffort = bestEffort)
                }
            },
        )
    }
}

private suspend fun enableBiometric(
    activity: FragmentActivity,
    accountManager: AccountManager,
    keyStoreManager: KeyStoreManager,
): Boolean {
    if (!keyStoreManager.ensureBiometricBoundKey()) return false
    // Аннулированный новым отпечатком ключ biometricEncryptCipher удаляет — создаём свежий.
    val cipher = keyStoreManager.biometricEncryptCipher()
        ?: keyStoreManager.takeIf { it.ensureBiometricBoundKey() }?.biometricEncryptCipher()
        ?: return false
    val result = authenticateBiometric(
        activity = activity,
        title = activity.getString(R.string.sec_biometric_prompt_title),
        subtitle = activity.getString(R.string.sec_biometric_prompt_subtitle),
        negativeText = activity.getString(R.string.common_cancel),
        cipher = cipher,
    )
    return when (result) {
        is BiometricAuthResult.Success ->
            result.cipher != null && accountManager.enableBiometricUnlock(result.cipher)
        else -> false
    }
}

/**
 * Поля пароля остаются Material-`OutlinedTextField` с HUD-палитрой: `AegisHudTextField`
 * не принимает `visualTransformation`, без которой пароль виден на экране.
 */
@Composable
private fun DuressPasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pwd.length >= 8 && pwd == confirm
    AegisHudDialog(
        title = stringResource(R.string.sec_duress_dialog_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_save),
        onConfirm = { onConfirm(pwd) },
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
        confirmEnabled = valid,
    ) {
        Text(
            text = stringResource(R.string.sec_duress_dialog_desc),
            style = MaterialTheme.typography.bodySmall,
            color = AegisTheme.extendedColors.textSecondary,
        )
        OutlinedTextField(
            value = pwd,
            onValueChange = { pwd = it },
            label = { Text(stringResource(R.string.sec_duress_field)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = AegisChipShape,
            colors = aegisHudTextFieldColors(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.sec_repeat)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = AegisChipShape,
            colors = aegisHudTextFieldColors(),
        )
    }
}

@Composable
private fun EmergencyWipeDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var bestEffort by remember { mutableStateOf(false) }
    AegisHudDialog(
        title = stringResource(R.string.sec_wipe_dialog_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.sec_wipe_confirm),
        onConfirm = { onConfirm(bestEffort) },
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
        danger = true,
    ) {
        Text(
            text = stringResource(R.string.sec_wipe_dialog_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AegisHudCheckRow(
            title = stringResource(R.string.sec_wipe_best_effort),
            checked = bestEffort,
            onCheckedChange = { bestEffort = it },
        )
    }
}

/**
 * Предлагаемые в UI варианты таймаута автоблокировки (§14.3), в минутах.
 * `0` — выключено. Все значения в пределах [AegisConstants.AUTO_LOCK_MAX_MINUTES].
 */
private val AUTO_LOCK_OPTIONS = listOf(0, 1, 5, 15, 30, 60)

@Composable
private fun autoLockOptionLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.sec_autolock_off) else stringResource(R.string.sec_autolock_minutes, minutes)

/** Достаёт [FragmentActivity] из Compose-контекста (для BiometricPrompt). */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
