package com.aegis.messenger.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Restore
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
import com.aegis.core.common.account.AccountCreationMode
import com.aegis.core.common.account.AccountLogin
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountCreationResult
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.AccountTransfer
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.DebugBuildBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.device.DeviceLinkAcceptScreen
import com.aegis.messenger.ui.transfer.TransferImportScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data class Create(val auto: Boolean) : OnboardingStep
    data object Restore : OnboardingStep
    data object Import : OnboardingStep
    data object AcceptDevice : OnboardingStep
}

/** Статические slug'и экранов онбординга для декоративных HUD-кодов. */
private const val WELCOME_SLUG = "onboarding.welcome"
private const val CREATE_SLUG = "onboarding.create"
private const val RESTORE_SLUG = "onboarding.restore"

/**
 * Поток онбординга (состояние аккаунта = None): выбор способа входа, создание,
 * восстановление и перенос с другого устройства (§7.1.2). Успех меняет
 * [AccountManager.state] → корневой auth-gate сам переключит экран (на показ
 * мнемоники или главный интерфейс).
 *
 * [accountTransfer]/[appScope]/[onImported] задаются только когда доступен
 * перенос (свежее устройство); при `null` [accountTransfer] пункт импорта скрыт.
 *
 * [replacesExistingAccount] — поток открыт с экрана входа («Забыли пароль?»), и
 * на устройстве уже хранится аккаунт: создание нового или восстановление чужой
 * фразой сотрёт его данные. Экраны предупреждают об этом заранее, а сама
 * очистка выполняется только после подтверждения в диалоге
 * ([AccountCreationResult.ReplaceConfirmationRequired]). [onExit] — возврат на
 * экран входа.
 */
@Composable
fun OnboardingFlow(
    accountManager: AccountManager,
    nativeReady: Boolean,
    accountTransfer: AccountTransfer? = null,
    deviceLinking: DeviceLinking? = null,
    appScope: CoroutineScope? = null,
    onImported: suspend () -> Unit = {},
    replacesExistingAccount: Boolean = false,
    onExit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf<OnboardingStep>(OnboardingStep.Welcome) }
    val transferAvailable = accountTransfer != null && appScope != null
    val linkAvailable = deviceLinking != null && appScope != null

    when (val current = step) {
        OnboardingStep.Welcome -> WelcomeScreen(
            nativeReady = nativeReady,
            transferAvailable = transferAvailable,
            linkAvailable = linkAvailable,
            onCreate = { step = OnboardingStep.Create(auto = false) },
            onCreateAuto = { step = OnboardingStep.Create(auto = true) },
            onRestore = { step = OnboardingStep.Restore },
            onImport = { step = OnboardingStep.Import },
            onAcceptDevice = { step = OnboardingStep.AcceptDevice },
            onExit = onExit,
            modifier = modifier,
        )

        is OnboardingStep.Create -> CreateAccountScreen(
            accountManager = accountManager,
            automatic = current.auto,
            replacesExistingAccount = replacesExistingAccount,
            onBack = { step = OnboardingStep.Welcome },
            modifier = modifier,
        )

        OnboardingStep.Restore -> RestoreAccountScreen(
            accountManager = accountManager,
            replacesExistingAccount = replacesExistingAccount,
            onBack = { step = OnboardingStep.Welcome },
            modifier = modifier,
        )

        OnboardingStep.Import -> if (accountTransfer != null && appScope != null) {
            TransferImportScreen(
                accountTransfer = accountTransfer,
                appScope = appScope,
                onImported = onImported,
                onBack = { step = OnboardingStep.Welcome },
                modifier = modifier,
            )
        } else {
            step = OnboardingStep.Welcome
        }

        OnboardingStep.AcceptDevice -> if (deviceLinking != null && appScope != null) {
            DeviceLinkAcceptScreen(
                deviceLinking = deviceLinking,
                appScope = appScope,
                onLinked = onImported,
                onBack = { step = OnboardingStep.Welcome },
                modifier = modifier,
            )
        } else {
            step = OnboardingStep.Welcome
        }
    }
}

/**
 * Первый экран приложения: способы входа списком-меню в HUD-панели. Главный
 * путь (создание аккаунта) — залитая кнопка, остальные — рамкой, чтобы выбор
 * читался с первого взгляда.
 *
 * [onExit] задан, когда экран открыт с экрана входа: тогда к нему можно
 * вернуться и кнопкой, и системным «Назад» — иначе случайное «Забыли пароль?»
 * запирало пользователя в онбординге до перезапуска приложения.
 */
@Composable
private fun WelcomeScreen(
    nativeReady: Boolean,
    transferAvailable: Boolean,
    linkAvailable: Boolean,
    onCreate: () -> Unit,
    onCreateAuto: () -> Unit,
    onRestore: () -> Unit,
    onImport: () -> Unit,
    onAcceptDevice: () -> Unit,
    onExit: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = onExit != null) { onExit?.invoke() }

    AuthColumn(slug = WELCOME_SLUG, modifier = modifier) {
        AuthHeader(
            title = stringResource(R.string.onboarding_welcome_title),
            subtitle = stringResource(R.string.onboarding_welcome_subtitle),
        )

        DebugBuildBanner()
        if (!nativeReady) {
            AegisHudBanner(
                text = stringResource(R.string.onboarding_native_missing),
                tone = HudBannerTone.DANGER,
            )
        }

        AegisHudCard {
            AegisHudButton(
                text = stringResource(R.string.onboarding_create),
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
                enabled = nativeReady,
                icon = Icons.Outlined.PersonAdd,
            )
            AegisHudOutlinedButton(
                text = stringResource(R.string.onboarding_create_auto),
                onClick = onCreateAuto,
                modifier = Modifier.fillMaxWidth(),
                enabled = nativeReady,
                icon = Icons.Outlined.FlashOn,
            )
            AegisHudOutlinedButton(
                text = stringResource(R.string.onboarding_restore),
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                enabled = nativeReady,
                icon = Icons.Outlined.Restore,
            )

            if (transferAvailable) {
                AegisHudOutlinedButton(
                    text = stringResource(R.string.onboarding_transfer_from_device),
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nativeReady,
                    icon = Icons.Outlined.ImportExport,
                )
            }

            if (linkAvailable) {
                AegisHudOutlinedButton(
                    text = stringResource(R.string.onboarding_link_device),
                    onClick = onAcceptDevice,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nativeReady,
                    icon = Icons.Outlined.Devices,
                )
            }
        }

        if (onExit != null) {
            AegisHudOutlinedButton(
                text = stringResource(R.string.onboarding_back_to_unlock),
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
            )
        }
    }
}

@Composable
private fun CreateAccountScreen(
    accountManager: AccountManager,
    automatic: Boolean,
    replacesExistingAccount: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var multiDevice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    val shortMsg = stringResource(R.string.account_password_too_short)
    val mismatchMsg = stringResource(R.string.account_password_mismatch)
    val loginEmptyMsg = stringResource(R.string.account_login_empty)
    val loginTooShortMsg = stringResource(R.string.account_login_too_short)
    val loginTooLongMsg = stringResource(R.string.account_login_too_long)
    val loginInvalidMsg = stringResource(R.string.account_login_invalid)
    val loginEmailMsg = stringResource(R.string.account_login_looks_email)
    val loginPhoneMsg = stringResource(R.string.account_login_looks_phone)

    fun loginMessage(err: AccountLogin.Error): String = when (err) {
        AccountLogin.Error.Empty -> loginEmptyMsg
        AccountLogin.Error.TooShort -> loginTooShortMsg
        AccountLogin.Error.TooLong -> loginTooLongMsg
        AccountLogin.Error.InvalidChars -> loginInvalidMsg
        AccountLogin.Error.LooksLikeEmail -> loginEmailMsg
        AccountLogin.Error.LooksLikePhone -> loginPhoneMsg
    }

    fun submit(replaceExisting: Boolean) {
        error = null
        val normalizedLogin = AccountLogin.normalize(login)
        val loginErr = AccountLogin.validateForPersist(normalizedLogin, allowEmpty = automatic)
        if (loginErr != null) {
            error = loginMessage(loginErr)
            return
        }
        if (!automatic) {
            if (password.length < AccountLogin.PASSWORD_MIN_CHARS) { error = shortMsg; return }
            if (password != confirm) { error = mismatchMsg; return }
        }
        submitting = true
        scope.launch {
            val result = accountManager.createAccount(
                mode = if (automatic) AccountCreationMode.AUTOMATIC else AccountCreationMode.MANUAL,
                login = normalizedLogin,
                password = if (automatic) "" else password,
                multiDeviceAllowed = multiDevice,
                replaceExisting = replaceExisting,
            )
            submitting = false
            when (result) {
                is AccountCreationResult.Failure -> error = result.reason
                AccountCreationResult.ReplaceConfirmationRequired -> confirmReplace = true
                // Success → AccountManager.state = Unlocked, корневой gate покажет мнемонику.
                is AccountCreationResult.Success -> Unit
            }
        }
    }

    AuthColumn(slug = CREATE_SLUG, modifier = modifier) {
        AuthHeader(
            title = stringResource(R.string.onboarding_create),
            subtitle = stringResource(R.string.account_password_hint),
        )

        if (replacesExistingAccount) {
            AegisHudBanner(
                text = stringResource(R.string.account_create_replace_warning),
                tone = HudBannerTone.DANGER,
            )
        }
        if (automatic) {
            AegisHudBanner(
                text = stringResource(R.string.account_no_password_warning),
                tone = HudBannerTone.DANGER,
            )
        }

        AegisHudCard {
            LabeledTextField(
                value = login,
                onValueChange = { login = it },
                label = stringResource(R.string.account_login_label),
                supporting = stringResource(
                    if (automatic) R.string.account_login_optional_hint else R.string.account_login_hint,
                ),
                enabled = !submitting,
            )
            if (!automatic) {
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.account_password_label),
                    enabled = !submitting,
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.account_password_confirm_label),
                    enabled = !submitting,
                )
            }

            AegisHudDivider()

            SwitchRow(
                checked = multiDevice,
                onCheckedChange = { multiDevice = it },
                title = stringResource(R.string.account_multidevice_label),
                subtitle = stringResource(R.string.account_multidevice_hint),
                enabled = !submitting,
            )
        }

        error?.let { AuthErrorText(it) }

        AuthSubmitButton(
            text = stringResource(R.string.account_create_submit),
            onClick = { submit(replaceExisting = false) },
            busy = submitting,
            enabled = !submitting,
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.common_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
        )
    }

    if (confirmReplace) {
        ReplaceAccountDialog(
            text = stringResource(R.string.account_replace_create_text),
            confirmLabel = stringResource(R.string.account_replace_create_confirm),
            onConfirm = {
                confirmReplace = false
                submit(replaceExisting = true)
            },
            onDismiss = { confirmReplace = false },
        )
    }
}

@Composable
private fun RestoreAccountScreen(
    accountManager: AccountManager,
    replacesExistingAccount: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    val loginTooShortMsg = stringResource(R.string.account_login_too_short)
    val loginTooLongMsg = stringResource(R.string.account_login_too_long)
    val loginInvalidMsg = stringResource(R.string.account_login_invalid)
    val loginEmailMsg = stringResource(R.string.account_login_looks_email)
    val loginPhoneMsg = stringResource(R.string.account_login_looks_phone)

    fun submit(replaceExisting: Boolean) {
        error = null
        val normalizedLogin = AccountLogin.normalize(login)
        if (normalizedLogin.isNotEmpty()) {
            AccountLogin.validate(normalizedLogin)?.let { err ->
                error = when (err) {
                    AccountLogin.Error.Empty -> loginTooShortMsg
                    AccountLogin.Error.TooShort -> loginTooShortMsg
                    AccountLogin.Error.TooLong -> loginTooLongMsg
                    AccountLogin.Error.InvalidChars -> loginInvalidMsg
                    AccountLogin.Error.LooksLikeEmail -> loginEmailMsg
                    AccountLogin.Error.LooksLikePhone -> loginPhoneMsg
                }
                return
            }
        }
        submitting = true
        scope.launch {
            val result = accountManager.recoverAccount(
                mnemonic = phrase,
                password = password,
                login = normalizedLogin,
                replaceExisting = replaceExisting,
            )
            submitting = false
            when (result) {
                is AccountCreationResult.Failure -> error = result.reason
                AccountCreationResult.ReplaceConfirmationRequired -> confirmReplace = true
                is AccountCreationResult.Success -> Unit
            }
        }
    }

    AuthColumn(slug = RESTORE_SLUG, modifier = modifier) {
        AuthHeader(
            title = stringResource(R.string.account_restore_title),
            subtitle = stringResource(R.string.account_restore_subtitle),
        )

        if (replacesExistingAccount) {
            AegisHudBanner(
                text = stringResource(R.string.account_restore_replace_hint),
                tone = HudBannerTone.SECURITY,
            )
        }

        AegisHudCard {
            LabeledTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = stringResource(R.string.account_restore_phrase_label),
                supporting = null,
                enabled = !submitting,
                singleLine = false,
            )
            LabeledTextField(
                value = login,
                onValueChange = { login = it },
                label = stringResource(R.string.account_login_label),
                supporting = stringResource(R.string.account_login_hint),
                enabled = !submitting,
            )
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.account_password_label),
                enabled = !submitting,
            )
        }

        error?.let { AuthErrorText(it) }

        AuthSubmitButton(
            text = stringResource(R.string.account_restore_submit),
            onClick = { submit(replaceExisting = false) },
            busy = submitting,
            enabled = !submitting && phrase.isNotBlank(),
        )
        AegisHudOutlinedButton(
            text = stringResource(R.string.common_back),
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
        )
    }

    if (confirmReplace) {
        ReplaceAccountDialog(
            text = stringResource(R.string.account_replace_restore_text),
            confirmLabel = stringResource(R.string.account_replace_restore_confirm),
            onConfirm = {
                confirmReplace = false
                submit(replaceExisting = true)
            },
            onDismiss = { confirmReplace = false },
        )
    }
}

/**
 * Подтверждение замены аккаунта, хранящегося на устройстве: очистка
 * необратима, поэтому подтверждается отдельным действием, а не нажатием
 * «Создать»/«Восстановить» в форме.
 */
@Composable
private fun ReplaceAccountDialog(
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AegisHudDialog(
        title = stringResource(R.string.account_replace_title),
        onDismiss = onDismiss,
        icon = Icons.Outlined.DeleteForever,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
        danger = true,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
