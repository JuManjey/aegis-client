package com.aegis.messenger.messaging

import android.content.Context
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.data.storage.PeerDeviceStore
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.messenger.psk.PskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Верификация отпечатка / safety number контакта (§14.3, §6.2 анти-MITM).
 *
 * Код сверки выводится из ОБОИХ публичных identity-ключей сторон И ручного PSK
 * (тот же примитив, что PSK-биндинг слоя B, `signal::psk_session_binding`):
 * `HKDF(salt=PSK, ikm=sort(idLocal‖idRemote))`. Свойства:
 *  - обе стороны получают ОДИН И ТОТ ЖЕ код (симметрия ролей) → его можно
 *    сверить вслух/лично;
 *  - без знания ручного PSK код невоспроизводим, поэтому недоверенный сервер,
 *    подменивший публичные ключи в X3DH, не сможет предъявить совпадающий код
 *    (это и закрывает главную теоретическую слабость Signal, §10.7);
 *  - PSK в код не входит и наружу не уходит — только производный тег.
 *
 * Требует разблокированного аккаунта (нужен наш identity-ключ), доступного слоя
 * B (libsignal) и установленного PSK + известного identity собеседника. Если
 * чего-то нет — [Unavailable] с честной причиной.
 */
class ContactVerification(
    private val appContext: Context,
    private val repository: MessagingRepository,
    private val sessionManager: SignalSessionManager,
    private val pskManager: PskManager,
    private val peerDeviceStore: PeerDeviceStore,
) {
    private fun str(resId: Int): String = LocaleManager.wrap(appContext).getString(resId)

    /** Результат запроса кода сверки для контакта. */
    sealed interface Result {
        /** Код готов к сверке. [alreadyVerified] — отмечен ли контакт ранее. */
        data class Ready(
            val code: String,
            val alreadyVerified: Boolean,
        ) : Result

        /** Сверка сейчас невозможна — [reason] для показа пользователю. */
        data class Unavailable(val reason: String, val alreadyVerified: Boolean) : Result
    }

    /**
     * Вычисляет код сверки контакта. Все секреты (PSK) живут только на время
     * вызова и затираются.
     */
    suspend fun safetyCode(contactId: String): Result = withContext(Dispatchers.IO) {
        val stored = repository.contact(contactId)
        val verified = stored?.contact?.verified == true
        if (!sessionManager.isAvailable) {
            DiagnosticLog.w(DIAG_TAG) { "сверка недоступна: нет слоя B контакт=${DiagnosticLog.shortId(contactId)}" }
            return@withContext Result.Unavailable(str(R.string.verify_err_layer_b), verified)
        }
        val remoteIdentity = stored?.remoteIdentityB64
        if (remoteIdentity.isNullOrBlank()) {
            return@withContext Result.Unavailable(str(R.string.verify_err_no_remote_key), verified)
        }
        val psk = pskManager.pskBytesFor(contactId)
            ?: return@withContext Result.Unavailable(str(R.string.verify_err_no_psk), verified)
        try {
            val binding = sessionManager.pskBinding(remoteIdentity, psk)
            DiagnosticLog.event(
                DIAG_TAG, "код сверки готов",
                "контакт" to DiagnosticLog.shortId(contactId),
                "ужеПроверен" to verified,
            )
            Result.Ready(code = formatCode(binding.safety), alreadyVerified = verified)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "код сверки не вычислен контакт=${DiagnosticLog.shortId(contactId)}" }
            Result.Unavailable(e.message ?: str(R.string.verify_err_compute), verified)
        } finally {
            psk.fill(0)
        }
    }

    /** Отметить/снять верификацию (после успешной сверки вне сети). */
    suspend fun setVerified(contactId: String, verified: Boolean) {
        DiagnosticLog.event(
            DIAG_TAG, "верификация",
            "контакт" to DiagnosticLog.shortId(contactId),
            "проверен" to verified,
        )
        repository.setContactVerified(contactId, verified)
    }

    /**
     * Код сверки ОДНОГО дополнительного устройства контакта (мультидевайс §7.1.1,
     * `docs/multidevice.md` §8). Каждое устройство контакта — отдельный Signal
     * identity (инвариант MD-1), поэтому у него СВОЙ safety-код, выведенный из ЕГО
     * identity + ручного PSK контакта (PSK общий на контакт). [verified] — сверено
     * ли устройство ранее (`peer_devices.verified`).
     */
    data class DeviceCode(
        val deviceId: Int,
        val code: String?,
        val reason: String?,
        val verified: Boolean,
    )

    /**
     * Коды сверки ДОПОЛНИТЕЛЬНЫХ устройств контакта (`deviceId` ≠ первичного).
     * Первичное устройство сверяется через [safetyCode]/[setVerified] (флаг
     * контакта). Возвращает по одному [DeviceCode] на каждое неотозванное доп.
     * устройство; при нехватке данных (нет identity/PSK, недоступен слой B) —
     * [DeviceCode.code]=null с честной [DeviceCode.reason].
     */
    suspend fun peerDeviceCodes(contactId: String): List<DeviceCode> = withContext(Dispatchers.IO) {
        val stored = repository.contact(contactId) ?: return@withContext emptyList()
        val extras = stored.devices.filter { it.deviceId != stored.remoteDevice && !it.revoked }
        if (extras.isEmpty()) return@withContext emptyList()
        if (!sessionManager.isAvailable) {
            return@withContext extras.map { DeviceCode(it.deviceId, null, str(R.string.verify_err_layer_b), it.verified) }
        }
        val psk = pskManager.pskBytesFor(contactId)
            ?: return@withContext extras.map {
                DeviceCode(it.deviceId, null, str(R.string.verify_err_no_psk), it.verified)
            }
        try {
            extras.map { device ->
                val identity = device.identityB64
                if (identity.isNullOrBlank()) {
                    DeviceCode(device.deviceId, null, str(R.string.verify_err_no_remote_key), device.verified)
                } else {
                    runCatching {
                        val binding = sessionManager.pskBinding(identity, psk)
                        DeviceCode(device.deviceId, formatCode(binding.safety), null, device.verified)
                    }.getOrElse { e ->
                        DeviceCode(device.deviceId, null, e.message ?: str(R.string.verify_err_compute), device.verified)
                    }
                }
            }
        } finally {
            psk.fill(0)
        }
    }

    /**
     * Ручная верификация отпечатка ДОПОЛНИТЕЛЬНОГО устройства контакта (§14.3,
     * `PeerDeviceStore.setVerified`). До сверки устройство регистрируется как
     * `verified=false` (§5, шаг 4) и подсвечивает баннер «новое устройство».
     */
    suspend fun setDeviceVerified(contactId: String, deviceId: Int, verified: Boolean) =
        withContext(Dispatchers.IO) {
            peerDeviceStore.setVerified(contactId, deviceId, verified)
        }

    /**
     * Приводит safety-код к читаемому виду: только цифры, разбитые на группы по
     * 5 для удобной сверки вслух (стиль safety number Signal).
     */
    private fun formatCode(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        val normalized = if (digits.length >= GROUP_SIZE * GROUPS) {
            digits.take(GROUP_SIZE * GROUPS)
        } else {
            // Фолбэк: если по какой-то причине цифр мало — берём весь исходный код.
            raw.trim()
        }
        return normalized.chunked(GROUP_SIZE).joinToString(" ")
    }

    private companion object {
        const val DIAG_TAG = "verify"
        const val GROUP_SIZE = 5
        const val GROUPS = 6
    }
}
