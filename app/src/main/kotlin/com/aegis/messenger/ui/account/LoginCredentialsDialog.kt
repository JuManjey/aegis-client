package com.aegis.messenger.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.aegis.core.common.account.AccountLogin
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountUpdateResult
import com.aegis.messenger.account.stringRes
import com.aegis.messenger.ui.onboarding.AuthErrorText
import com.aegis.messenger.ui.onboarding.LabeledTextField
import com.aegis.messenger.ui.onboarding.PasswordField
import kotlinx.coroutines.launch

/**
 * Диалог задания/смены логина и (для авто-аккаунта) пароля входа.
 *
 * [requirePassword] = true — перезапечатывание мнемоники паролем; после успеха
 * биометрия и duress в настройках становятся доступны.
 */
@Composable
fun LoginCredentialsDialog(
    requirePassword: Boolean,
    initialLogin: String,
    onDismiss: () -> Unit,
    onSubmit: suspend (login: String, password: String) -> AccountUpdateResult,
) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf(initialLogin) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val normalized = AccountLogin.normalize(login)
    val loginError = AccountLogin.validate(normalized)
    val passwordOk = !requirePassword ||
        (password.length >= AccountLogin.PASSWORD_MIN_CHARS && password == confirm)
    val canSave = !busy && loginError == null && passwordOk

    val mismatchMsg = stringResource(R.string.account_password_mismatch)
    val shortMsg = stringResource(R.string.account_password_too_short)

    fun submit() {
        error = null
        if (loginError != null) {
            error = null
            return
        }
        if (requirePassword) {
            if (password.length < AccountLogin.PASSWORD_MIN_CHARS) {
                error = shortMsg
                return
            }
            if (password != confirm) {
                error = mismatchMsg
                return
            }
        }
        busy = true
        scope.launch {
            when (val result = onSubmit(normalized, password)) {
                AccountUpdateResult.Success -> onDismiss()
                is AccountUpdateResult.Failure -> {
                    error = result.reason
                    busy = false
                }
            }
        }
    }

    AegisHudDialog(
        title = stringResource(
            if (requirePassword) R.string.sec_credentials_dialog_title_add
            else R.string.sec_credentials_dialog_title_login,
        ),
        // Пока идёт запрос, закрытие заблокировано и по тапу вне окна, и кнопкой
        // «Отмена» — иначе пользователь закроет диалог посреди перезапечатывания
        // мнемоники и не узнает результат.
        onDismiss = { if (!busy) onDismiss() },
        confirmLabel = stringResource(R.string.common_save),
        onConfirm = { submit() },
        confirmEnabled = canSave,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
        dismissEnabled = !busy,
    ) {
        // Индикатор стоит ПЕРЕД прокручиваемым блоком: у кнопки подтверждения
        // HUD-диалога нет слота контента, а из конца списка полей индикатор
        // уехал бы за пределы видимой части.
        if (busy) {
            AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (requirePassword) R.string.sec_credentials_dialog_desc_add
                    else R.string.sec_credentials_dialog_desc_login,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
            )
            LabeledTextField(
                value = login,
                onValueChange = { login = it; error = null },
                label = stringResource(R.string.account_login_label),
                supporting = stringResource(R.string.account_login_hint),
                enabled = !busy,
            )
            loginError?.let { err ->
                if (normalized.isNotEmpty()) {
                    AuthErrorText(stringResource(err.stringRes()))
                }
            }
            if (requirePassword) {
                PasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = stringResource(R.string.account_password_label),
                    enabled = !busy,
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = stringResource(R.string.account_password_confirm_label),
                    enabled = !busy,
                )
                Text(
                    text = stringResource(R.string.sec_credentials_password_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = AegisTheme.extendedColors.textSecondary,
                )
            }
            error?.let { AuthErrorText(it) }
        }
    }
}
