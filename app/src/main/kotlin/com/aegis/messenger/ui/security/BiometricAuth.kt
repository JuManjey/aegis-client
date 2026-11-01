package com.aegis.messenger.ui.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

/** Результат биометрической аутентификации (§14.3). */
sealed interface BiometricAuthResult {
    /** Успех: [cipher] подтверждён аппаратной аутентификацией и готов к doFinal. */
    data class Success(val cipher: Cipher?) : BiometricAuthResult
    /** Пользователь отменил/навигация — без ошибки для UI. */
    data object Cancelled : BiometricAuthResult
    /** Ошибка/недоступность биометрии. */
    data class Error(val message: String) : BiometricAuthResult
}

/**
 * Настроена ли на устройстве сильная биометрия (§14.3). PIN устройства не
 * подходит: ключ входа по отпечатку принимает только биометрию.
 */
fun isBiometricAvailable(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS

/**
 * Показывает `BiometricPrompt` и возвращает подтверждённый [Cipher].
 *
 * @param cipher криптообъект (encrypt/decrypt Cipher на биометрическом ключе);
 *   `null` для аутентификации без CryptoObject.
 */
suspend fun authenticateBiometric(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeText: String,
    cipher: Cipher?,
): BiometricAuthResult = suspendCancellableCoroutine { cont ->
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!cont.isActive) return
            val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_CANCELED
            cont.resume(
                if (cancelled) BiometricAuthResult.Cancelled
                else BiometricAuthResult.Error(errString.toString()),
            )
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (!cont.isActive) return
            cont.resume(BiometricAuthResult.Success(result.cryptoObject?.cipher))
        }

        // onAuthenticationFailed — единичная неудачная попытка; промпт остаётся
        // открытым, ждём следующую. Финализируем только по error/success.
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        // Только сильная биометрия — иначе CryptoObject недопустим.
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText(negativeText)
        .build()

    try {
        if (cipher != null) {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info)
        }
    } catch (e: Exception) {
        if (cont.isActive) cont.resume(BiometricAuthResult.Error(e.message ?: "Ошибка биометрии"))
    }

    cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
}
