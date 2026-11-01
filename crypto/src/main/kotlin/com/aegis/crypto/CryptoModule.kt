package com.aegis.crypto

import android.util.Base64
import com.aegis.crypto.cascade.CascadeCipher
import com.aegis.crypto.cascade.CascadeSpec
import com.aegis.crypto.cascade.NativeCascadeCipher

/**
 * Единая точка входа в крипто-подсистему клиента (слой A + мост к нативному ядру).
 *
 * Предоставляет:
 *  - статус нативного модуля ([nativeVersion], [isNativeReady]);
 *  - фабрику реального каскадного контейнера ([NativeCascadeCipher], слой A, P3).
 */
object CryptoModule {

    /** Готово ли нативное Rust-крипто-ядро (собран ли .so). */
    val isNativeReady: Boolean
        get() = NativeAegisCore.isAvailable

    /** Версия нативного крипто-ядра или `null`, если оно не собрано (каркас). */
    fun nativeVersion(): String? = NativeAegisCore.versionOrNull()

    /** Доступна ли интеграция libsignal (слой B, P2) в собранной .so. */
    val hasSignalSupport: Boolean
        get() = NativeAegisCore.hasSignalSupport()

    /** Доступен ли встроенный Tor (Arti, слой транспорта, P5) в собранной .so. */
    val hasTorSupport: Boolean
        get() = NativeAegisCore.hasTorSupport()

    /** Встроенный Tor-транспорт (Arti, P5, §7.2): запуск/статус/диагностика. */
    val tor: TorNative
        get() = TorNative

    /** Операции с аккаунтом (P1, §7.1): мнемоника, Argon2id, запечатывание мнемоники. */
    val account: AccountCrypto
        get() = AccountCrypto

    /** Слой B (P2, §6.1): Signal Protocol (X3DH/PQXDH + Double Ratchet). */
    val signal: SignalSession
        get() = SignalSession

    /** Ручной обмен ключом контейнера (PSK, P4, §6.2): генерация/мнемоника/отпечаток. */
    val psk: PskCrypto
        get() = PskCrypto

    /**
     * Удалённое добавление контакта (RCH v1, §6.2, `docs/contact_requests.md`):
     * тег обнаружения, запечатанная запись каталога, вывод секрета слоя A из
     * подписанного рукопожатия. Требует слоя B (см. [DiscoveryCrypto.isAvailable]).
     */
    val discovery: DiscoveryCrypto
        get() = DiscoveryCrypto

    /** Согласование ключей звонка (P8, §7.5/§10.10): корень из Signal + PSK, SAS. */
    val calls: CallCrypto
        get() = CallCrypto

    /**
     * Создать каскадный контейнер (слой A, §5) на общем секрете контейнера.
     *
     * @param containerSecret PSK (обменянный вручную §6.2) ЛИБО выход Argon2id
     *   из парольной/мнемонической фразы. Из него нативное ядро выводит K1..K4
     *   (HKDF-SHA512, разделение доменов).
     */
    fun createCascadeCipher(
        containerSecret: ByteArray,
        spec: CascadeSpec = CascadeSpec(),
    ): CascadeCipher = NativeCascadeCipher(containerSecret, spec)

    /**
     * Проверяет Ed25519-подпись [signature] над [message] публичным ключом
     * [publicKeyHex] (64 hex). Используется для авто-обнаружения адреса релея
     * (§12.2): подпись конфиг-документа делается офлайн-ключом, публичная часть
     * которого зашита в сборку. Возвращает false, если ядро не собрано или
     * подпись некорректна (fail-closed — тогда клиент останется на дефолте).
     */
    fun verifyEd25519(publicKeyHex: String, message: ByteArray, signature: ByteArray): Boolean {
        if (!isNativeReady || publicKeyHex.isBlank()) return false
        return runCatching {
            NativeAegisCore.nativeVerifyEd25519(
                publicKeyHex.trim(),
                Base64.encodeToString(message, Base64.NO_WRAP),
                Base64.encodeToString(signature, Base64.NO_WRAP),
            )
        }.getOrDefault(false)
    }
}
