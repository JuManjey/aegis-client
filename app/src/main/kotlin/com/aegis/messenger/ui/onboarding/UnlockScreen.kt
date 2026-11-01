package com.aegis.messenger.ui.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.core.common.account.AccountProfile
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.data.storage.KeyStoreManager
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountManager
import com.aegis.messenger.account.UnlockResult
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.DebugBuildBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.security.BiometricAuthResult
import com.aegis.messenger.ui.security.authenticateBiometric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Статический slug экрана входа для декоративных HUD-кодов. */
private const val UNLOCK_SLUG = "auth.unlock"

/**
 * Экран входа (состояние аккаунта = Locked): разблокировка паролем или биометрией
 * (§14.3). Для авто-аккаунта без пароля разблокировка происходит по нажатию.
 *
 * ВАЖНО (§7.4): если введён duress-пароль, [AccountManager.unlock] вместо
 * разблокировки выполняет тихую очистку и сбрасывает состояние — экран без всяких
 * особых сообщений исчезает (auth-gate уводит на онбординг «пустого» устройства).
 */
@Composable
fun UnlockScreen(
    profile: AccountProfile,
    accountManager: AccountManager,
    keyStoreManager: KeyStoreManager,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    /** Звонит вызов, пришедший при закрытом аккаунте: после входа он примется или зазвонит с именем. */
    incomingCall: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val biometricReset by accountManager.biometricReset.collectAsStateWithLifecycle()

    // Задержка после неверных паролей: момент окончания по монотонным часам и
    // остаток для отсчёта на экране.
    var lockoutDeadline by remember { mutableLongStateOf(0L) }
    var lockoutLeft by remember { mutableLongStateOf(0L) }
    fun startLockout(ms: Long) {
        lockoutLeft = ms.coerceAtLeast(0L)
        lockoutDeadline = if (ms > 0) SystemClock.elapsedRealtime() + ms else 0L
    }
    LaunchedEffect(lockoutDeadline) {
        while (lockoutDeadline > 0) {
            val left = lockoutDeadline - SystemClock.elapsedRealtime()
            if (left <= 0) {
                lockoutLeft = 0L
                lockoutDeadline = 0L
                break
            }
            lockoutLeft = left
            delay(minOf(left, 1_000L))
        }
    }

    var biometricAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        biometricAvailable = accountManager.isBiometricUnlockConfigured()
        startLockout(accountManager.unlockLockoutRemainingMs())
    }

    val wrongMsg = stringResource(R.string.unlock_wrong_password)
    val biometricErrMsg = stringResource(R.string.unlock_biometric_error)
    val biometricResetMsg = stringResource(R.string.unlock_biometric_reset)

    fun handleResult(r: UnlockResult) {
        when (r) {
            // Дальше gate сам переключит на главный экран.
            is UnlockResult.Success -> accountManager.acknowledgeBiometricReset()
            // Очистка уже выполнена, состояние сброшено — ничего не показываем.
            UnlockResult.DuressWiped, UnlockResult.WipedAfterFailedAttempts -> Unit
            is UnlockResult.WrongPassword -> {
                error = r.attemptsBeforeWipe?.let {
                    context.getString(R.string.unlock_wrong_password_wipe, it)
                } ?: wrongMsg
                startLockout(r.lockoutMs)
            }
            is UnlockResult.LockedOut -> startLockout(r.remainingMs)
            UnlockResult.NoAccount -> error = wrongMsg
            is UnlockResult.Error -> error = r.reason
        }
    }

    fun submit() {
        error = null
        submitting = true
        scope.launch {
            handleResult(accountManager.unlock(password))
            submitting = false
        }
    }

    fun biometricUnlock() {
        val act = activity ?: return
        error = null
        submitting = true
        scope.launch {
            val ivB64 = accountManager.biometricIvB64()
            val cipher = ivB64?.let { keyStoreManager.biometricDecryptCipher(it) }
            if (cipher == null) {
                // Аннулированный ключ объясняет баннер biometricReset над полем пароля.
                if (accountManager.dropBiometricIfInvalidated()) {
                    biometricAvailable = false
                } else {
                    error = biometricErrMsg
                }
                submitting = false
                return@launch
            }
            when (
                val res = authenticateBiometric(
                    activity = act,
                    title = act.getString(R.string.unlock_biometric_title),
                    subtitle = act.getString(R.string.unlock_biometric_subtitle),
                    negativeText = act.getString(R.string.unlock_biometric_use_password),
                    cipher = cipher,
                )
            ) {
                is BiometricAuthResult.Success -> {
                    val c = res.cipher
                    if (c != null) handleResult(accountManager.unlockWithBiometric(c))
                    else error = biometricErrMsg
                }
                BiometricAuthResult.Cancelled -> Unit
                is BiometricAuthResult.Error -> error = res.message
            }
            submitting = false
        }
    }

    AuthColumn(slug = UNLOCK_SLUG, modifier = modifier) {
        AuthHeader(
            title = stringResource(R.string.unlock_title),
            subtitle = stringResource(
                R.string.unlock_subtitle,
                profile.login.ifBlank { profile.identity.shortId() },
            ),
        )

        DebugBuildBanner()
        if (incomingCall) {
            AegisHudBanner(text = stringResource(R.string.unlock_incoming_call), tone = HudBannerTone.SECURITY)
        }
        if (biometricReset) {
            AegisHudBanner(text = biometricResetMsg, tone = HudBannerTone.SECURITY)
        }

        if (profile.hasPassword) {
            AegisHudCard {
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.account_password_label),
                    enabled = !submitting,
                )
            }
        }

        error?.let { AuthErrorText(it) }
        if (lockoutLeft > 0) {
            AuthErrorText(stringResource(R.string.unlock_locked_out, formatLockout(lockoutLeft)))
        }

        AuthSubmitButton(
            text = stringResource(R.string.unlock_submit),
            onClick = { submit() },
            busy = submitting,
            enabled = !submitting && lockoutLeft == 0L,
        )

        if (biometricAvailable && activity != null) {
            AegisHudOutlinedButton(
                text = stringResource(R.string.unlock_biometric_button),
                onClick = { biometricUnlock() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                icon = Icons.Outlined.Fingerprint,
            )
        }

        // Уход в восстановление — целой полосой, а не кнопкой: подсказка длинная,
        // и в прописном моноширинном лейбле кнопки она бы не читалась. Во время
        // запроса полоса гаснет — как и раньше, уйти нельзя.
        AegisHudBanner(
            text = stringResource(R.string.unlock_forgot),
            tone = HudBannerTone.SECURITY,
            onClick = onRestore,
            enabled = !submitting,
        )
    }
}

/** Остаток задержки как `м:сс`, с округлением вверх до секунды. */
private fun formatLockout(ms: Long): String {
    val totalSeconds = (ms + 999) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Достаёт [FragmentActivity] из Compose-контекста (для BiometricPrompt). */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
