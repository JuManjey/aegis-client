package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.SignalException
import com.aegis.crypto.SignalSession
import com.aegis.data.storage.SignalStateStore
import com.aegis.data.storage.StoredContact

/**
 * Нет ПУБЛИЧНЫХ ключей слоя B для устройства `(contactId, deviceId)`: сессия не
 * установлена и prekey-бандла тоже нет, поэтому шифровать нечем.
 *
 * Отдельный тип (а не общий [SignalException]) нужен, чтобы отправка отличала
 * «ключи ещё не пришли — надо их запросить и повторить» от настоящего сбоя
 * крипты. По этому исключению запускается догоняющий обмен ключами по слою A
 * ([KeyExchangeChannel]).
 */
class MissingPeerKeysException(
    val contactId: String,
    val deviceId: Int,
) : SignalException("Нет prekey-бандла устройства $deviceId — ключи ещё не получены (§6.2).")

/**
 * Stateful-управление Signal-сессиями (слой B, P6 §6.1/§6.3).
 *
 * Всё состояние аккаунта (identity, prekeys, установленные сессии, храповики
 * Double Ratchet) — это непрозрачный blob, который нативное ядро
 * (`signal::stateful`) выдаёт после каждой операции. Здесь blob сохраняется в
 * зашифрованный [SignalStateStore] (SQLCipher) и подаётся обратно на вход.
 * Rust остаётся stateless — единственный источник истины на диске.
 *
 * Адресация Signal: локальное имя — `accountId` владельца, удалённое имя —
 * `contactId`, устройство собеседника — [StoredContact.remoteDevice], СВОЁ
 * устройство — [localDeviceIdProvider] (мультидевайс §7.1.1: каждое устройство —
 * отдельный Signal-эндпоинт со своим ratchet, инвариант MD-1). По умолчанию 1
 * (первичное), что даёт прежнее поведение до линковки.
 *
 * Методы синхронизированы: одновременная мутация одного blob из разных корутин
 * привела бы к гонке (последняя запись затёрла бы храповик). Вызывать с IO-
 * диспетчера.
 */
class SignalSessionManager(
    private val stateStore: SignalStateStore,
    private val accountIdProvider: () -> String?,
    private val localDeviceIdProvider: () -> Int = { SignalSession.PRIMARY_DEVICE_ID },
    private val signal: SignalSession = CryptoModule.signal,
) {
    /** Доступен ли слой B (собрана ли .so с фичей `signal`). */
    val isAvailable: Boolean get() = signal.isAvailable

    /**
     * Исходящая сессия с `(contactId, deviceId)` только что установлена по
     * prekey-бандлу (X3DH/PQXDH завершён). По этому событию хранилище поднимает
     * флаг `session_ready`, иначе он навсегда остался бы `false`. Задаётся из DI.
     */
    var onSessionEstablished: ((String, Int) -> Unit)? = null

    private fun accountId(): String =
        accountIdProvider() ?: throw SignalException("Аккаунт не разблокирован")

    /** device id ЭТОГО устройства (1 = первичное), нормализованный в 1..255. */
    private fun localDevice(): Int = localDeviceIdProvider().coerceIn(1, 255)

    /**
     * Гарантирует наличие Signal-аккаунта (identity + опубликованные prekeys) и
     * возвращает актуальный blob состояния. Идемпотентно.
     */
    @Synchronized
    fun ensureAccount(): String {
        val id = accountId()
        stateStore.load(id)?.let { return it }
        val fresh = signal.createAccount()
        val published = signal.publishPrekeys(fresh, localDevice())
        stateStore.save(id, published.state)
        return published.state
    }

    /** Публичный identity-ключ (base64) владельца для PSK-биндинга/отпечатка (§6.2). */
    @Synchronized
    fun identityKeyB64(): String = signal.identityKeyB64(ensureAccount())

    /**
     * Формирует свежий публичный prekey-бандл владельца (JSON, §0) для передачи
     * контакту вне сети (QR/мнемоника/NFC). Обновлённое состояние сохраняется.
     */
    @Synchronized
    fun publishBundleJson(): String {
        val id = accountId()
        val blob = ensureAccount()
        val published = signal.publishPrekeys(blob, localDevice())
        stateStore.save(id, published.state)
        return published.bundleJson
    }

    /**
     * Шифрует [plaintext] для контакта (слой B). При отсутствии сессии сначала
     * устанавливает её по сохранённому prekey-бандлу контакта (X3DH/PQXDH).
     */
    @Synchronized
    fun encrypt(contact: StoredContact, plaintext: ByteArray): SignalSession.EncryptResult =
        encryptTo(contact.contact.id, contact.remoteDevice, contact.bundleJson, plaintext, contact.remoteIdentityB64)

    /**
     * Шифрует [plaintext] для КОНКРЕТНОГО устройства `(remoteAccountId,
     * remoteDevice)` — единица sender-side fan-out (мультидевайс, Этап 4). Каждое
     * устройство держит отдельную Signal-сессию (свой ratchet, MD-1). При
     * отсутствии сессии устанавливает её по [bundleJson] этого устройства.
     *
     * [expectedIdentityB64] — закреплённый identity этого устройства
     * ([StoredContact.identityFor]). Бандл и сессия на другом ключе отвергаются,
     * а сессия, которую TOFU успел завести на чужом ключе, заменяется новой по
     * [bundleJson]. `null` — ключ ещё неизвестен (TOFU).
     */
    @Synchronized
    fun encryptTo(
        remoteAccountId: String,
        remoteDevice: Int,
        bundleJson: String?,
        plaintext: ByteArray,
        expectedIdentityB64: String?,
    ): SignalSession.EncryptResult {
        val id = accountId()
        val local = localDevice()
        var blob = ensureAccount()
        if (!signal.hasSession(blob, remoteAccountId, remoteDevice, expectedIdentityB64)) {
            val bundle = bundleJson?.takeIf { it.isNotBlank() }
                ?: run {
                    DiagnosticLog.w(DIAG_TAG) {
                        "нет сессии И нет бандла: шифровать нечем, контакт=" +
                            "${DiagnosticLog.shortId(remoteAccountId)} устройство=$remoteDevice"
                    }
                    throw MissingPeerKeysException(remoteAccountId, remoteDevice)
                }
            blob = try {
                signal.processBundle(blob, id, remoteAccountId, remoteDevice, bundle, expectedIdentityB64, local)
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) {
                    "X3DH/PQXDH по бандлу НЕ прошёл, контакт=" +
                        "${DiagnosticLog.shortId(remoteAccountId)} устройство=$remoteDevice " +
                        "бандлБайт=${bundle.length}"
                }
                throw e
            }
            stateStore.save(id, blob)
            DiagnosticLog.event(
                DIAG_TAG, "СЕССИЯ СЛОЯ B УСТАНОВЛЕНА",
                "контакт" to DiagnosticLog.shortId(remoteAccountId),
                "устройство" to remoteDevice,
                "своёУстройство" to local,
                "identityЗакреплён" to !expectedIdentityB64.isNullOrBlank(),
            )
            runCatching { onSessionEstablished?.invoke(remoteAccountId, remoteDevice) }
        }
        val result = signal.encrypt(blob, id, remoteAccountId, remoteDevice, plaintext, expectedIdentityB64, local)
        stateStore.save(id, result.state)
        DiagnosticLog.event(
            DIAG_TAG, "зашифровано слоем B",
            "контакт" to DiagnosticLog.shortId(remoteAccountId),
            "устройство" to remoteDevice,
            "тип" to result.type,
            "внутрьБайт" to plaintext.size,
            "наружуБайт" to result.ciphertext.size,
        )
        return result
    }

    /** Расшифровывает конверт слоя B от контакта. Обновляет состояние храповика. */
    @Synchronized
    fun decrypt(
        contact: StoredContact,
        ciphertext: ByteArray,
        messageType: String,
    ): ByteArray = decryptFrom(
        contact.contact.id, contact.remoteDevice, ciphertext, messageType, contact.remoteIdentityB64,
    )

    /**
     * Расшифровывает конверт слоя B от КОНКРЕТНОГО устройства отправителя
     * `(remoteAccountId, senderDevice)` (мультидевайс, Этап 4: device id берётся
     * из wire-конверта). Обновляет состояние соответствующего храповика.
     *
     * [expectedIdentityB64] — закреплённый identity устройства-отправителя: без
     * него PreKey-сообщение сервера, пришедшее раньше настоящего, закрепило бы
     * чужой ключ (TOFU) ещё до проверки слоя A. `null` — ключ неизвестен.
     */
    @Synchronized
    fun decryptFrom(
        remoteAccountId: String,
        senderDevice: Int,
        ciphertext: ByteArray,
        messageType: String,
        expectedIdentityB64: String?,
    ): ByteArray {
        val id = accountId()
        val blob = ensureAccount()
        val result = try {
            signal.decrypt(
                blob, id, remoteAccountId, senderDevice, ciphertext, messageType, expectedIdentityB64,
                localDevice(),
            )
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) {
                "слой B НЕ расшифрован, контакт=${DiagnosticLog.shortId(remoteAccountId)} " +
                    "устройство=$senderDevice тип=$messageType байт=${ciphertext.size}"
            }
            throw e
        }
        stateStore.save(id, result.state)
        DiagnosticLog.event(
            DIAG_TAG, "расшифровано слоем B",
            "контакт" to DiagnosticLog.shortId(remoteAccountId),
            "устройство" to senderDevice,
            "тип" to messageType,
            "наружуБайт" to ciphertext.size,
            "внутрьБайт" to result.plaintext.size,
        )
        return result.plaintext
    }

    /** Есть ли установленная сессия с контактом. */
    @Synchronized
    fun hasSession(contact: StoredContact): Boolean =
        signal.hasSession(ensureAccount(), contact.contact.id, contact.remoteDevice, contact.remoteIdentityB64)

    /**
     * PSK-биндинг (§6.2) между владельцем и контактом на ручном PSK. Возвращает
     * safety-код для устной сверки и сам биндинг.
     */
    @Synchronized
    fun pskBinding(remoteIdentityB64: String, psk: ByteArray): SignalSession.PskBinding {
        val local = signal.identityKeyB64(ensureAccount())
        return signal.pskBinding(local, remoteIdentityB64, psk)
    }

    /**
     * Сессионная обёртка PSK поверх plaintext слоя B: AEAD на ключе из PSK и
     * фактически увиденных identity. Fail-closed при порче тега.
     */
    @Synchronized
    fun pskSessionSeal(remoteIdentityB64: String, psk: ByteArray, plaintext: ByteArray): ByteArray {
        val local = signal.identityKeyB64(ensureAccount())
        return signal.pskSessionSeal(local, remoteIdentityB64, psk, plaintext)
    }

    @Synchronized
    fun pskSessionOpen(remoteIdentityB64: String, psk: ByteArray, sealed: ByteArray): ByteArray {
        val local = signal.identityKeyB64(ensureAccount())
        return signal.pskSessionOpen(local, remoteIdentityB64, psk, sealed)
    }

    private companion object {
        /** Тег записей слоя B в журнале диагностики. */
        const val DIAG_TAG = "signal"
    }
}
