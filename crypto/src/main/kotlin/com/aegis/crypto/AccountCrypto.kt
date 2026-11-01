package com.aegis.crypto

import android.util.Base64
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.common.account.AccountKdfParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Бросается, когда нативное крипто-ядро недоступно (нет собранной .so). */
class CryptoUnavailableException :
    IllegalStateException(
        "Нативное крипто-ядро AEGIS (libaegis_core.so) не загружено. " +
            "Криптографические операции недоступны без него (свою криптографию не пишем, §1).",
    )

/** Бросается при ошибке нативной криптографической операции. */
class AccountCryptoException(message: String) : Exception(message)

/**
 * Kotlin-фасад над нативным ядром для операций с аккаунтом (P1, §7.1).
 *
 * Инкапсулирует JNI-детали ([NativeAegisCore]): проверяет доступность .so,
 * парсит JSON/base64, отдаёт типобезопасные результаты. Реальная криптография
 * (BIP-39, HKDF, Argon2id, XChaCha20-Poly1305) — в Rust; здесь только «клей».
 */
object AccountCrypto {

    private val json = Json { ignoreUnknownKeys = true }

    /** Готово ли нативное ядро к работе. */
    val isReady: Boolean get() = NativeAegisCore.isAvailable

    private fun requireReady() {
        if (!NativeAegisCore.isAvailable) throw CryptoUnavailableException()
    }

    /** Генерирует новую мнемонику (по умолчанию 24 слова — максимальная энтропия). */
    fun generateMnemonic(wordCount: Int = 24): String {
        requireReady()
        val phrase = NativeAegisCore.nativeGenerateMnemonic(wordCount)
        if (phrase.isBlank()) {
            throw AccountCryptoException("Не удалось сгенерировать мнемонику (wordCount=$wordCount)")
        }
        return phrase
    }

    /** Проверяет корректность мнемонической фразы (контрольная сумма BIP-39). */
    fun validateMnemonic(phrase: String): Boolean {
        requireReady()
        return NativeAegisCore.nativeValidateMnemonic(phrase.trim())
    }

    /**
     * Детерминированно выводит публичную идентичность из мнемоники.
     * @param passphrase необязательная BIP-39-passphrase («25-е слово»).
     */
    fun deriveIdentity(mnemonic: String, passphrase: String = ""): AccountIdentity {
        requireReady()
        val raw = NativeAegisCore.nativeDeriveAccountInfo(mnemonic.trim(), passphrase)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw AccountCryptoException("Некорректный ответ ядра: $raw") }
        obj["error"]?.let {
            throw AccountCryptoException("Вывод идентичности отклонён: ${it.jsonPrimitive.content}")
        }
        return json.decodeFromString(AccountIdentity.serializer(), raw)
    }

    /**
     * Выводит ключ-обёртку из пароля через Argon2id. Результат — base64
     * (передаётся дальше в [sealMnemonic]/[openMnemonic] как есть).
     */
    fun deriveWrappingKeyB64(password: String, params: AccountKdfParams): String {
        requireReady()
        val key = NativeAegisCore.nativeArgon2idDeriveB64(
            password,
            params.saltB64,
            params.memoryKib,
            params.iterations,
            params.parallelism,
            params.keyLengthBytes,
        )
        if (key.isBlank()) throw AccountCryptoException("Argon2id: не удалось вывести ключ")
        return key
    }

    /** Запечатывает мнемонику ключом-обёрткой (base64). Возвращает base64 блоба. */
    fun sealMnemonic(mnemonic: String, wrappingKeyB64: String): String {
        requireReady()
        val blob = NativeAegisCore.nativeSealMnemonicB64(mnemonic.trim(), wrappingKeyB64)
        if (blob.isBlank()) throw AccountCryptoException("Не удалось запечатать мнемонику")
        return blob
    }

    /**
     * Распечатывает мнемонику. Возвращает `null`, если ключ неверен (провал
     * AEAD-тега) — это штатный сигнал «неправильный пароль».
     */
    fun openMnemonic(blobB64: String, wrappingKeyB64: String): String? {
        requireReady()
        val mnemonic = NativeAegisCore.nativeOpenMnemonicB64(blobB64, wrappingKeyB64)
        return mnemonic.ifBlank { null }
    }

    /**
     * Ключ локальных хранилищ (32 байта) из мнемоники: им запечатываются ключ
     * SQLCipher и файл PSK. Вызывающий обязан затереть результат.
     */
    fun deriveStorageKey(mnemonic: String): ByteArray {
        requireReady()
        val b64 = NativeAegisCore.nativeDeriveStorageKeyB64(mnemonic.trim(), "")
        if (b64.isBlank()) throw AccountCryptoException("Не удалось вывести ключ хранилища")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /**
     * Подписывает [message] долгосрочным Ed25519-ключом аккаунта.
     *
     * Приватный ключ выводится из мнемоники ВНУТРИ Rust и наружу не отдаётся —
     * сюда приходит только подпись. Используется для записи каталога и нагрузок
     * рукопожатия RCH (`docs/contact_requests.md`): подпись ключом аккаунта
     * делает секрет слоя A независимым от libsignal.
     */
    fun sign(mnemonic: String, message: ByteArray, passphrase: String = ""): ByteArray {
        requireReady()
        val sig = NativeAegisCore.nativeAccountSignB64(
            mnemonic.trim(),
            passphrase,
            android.util.Base64.encodeToString(message, android.util.Base64.NO_WRAP),
        )
        if (sig.isBlank()) throw AccountCryptoException("Не удалось подписать данные ключом аккаунта")
        return android.util.Base64.decode(sig, android.util.Base64.NO_WRAP)
    }

    /**
     * Проверяет Ed25519-подпись публичным ключом (hex). Fail-closed: `false`,
     * если ядро не собрано, ключ/подпись некорректны или подпись не сходится.
     */
    fun verify(ed25519Hex: String, message: ByteArray, signature: ByteArray): Boolean =
        CryptoModule.verifyEd25519(ed25519Hex, message, signature)

    /**
     * `accountId`, к которому коммитится публичный Ed25519-ключ [ed25519Hex].
     *
     * Ключевая проверка модели доверия удалённого добавления: получив запись
     * каталога, клиент убеждается, что `accountIdFromEd25519(запись.ed25519)`
     * равен accountId из полученного вне сети адреса. Совпало — подменить ключи
     * не мог никто, включая полностью скомпрометированный релей (нужен второй
     * прообраз SHA-256). `null` — ключ некорректен (fail-closed).
     */
    fun accountIdFromEd25519(ed25519Hex: String): String? {
        if (!isReady || ed25519Hex.isBlank()) return null
        return NativeAegisCore.nativeAccountIdFromEd25519(ed25519Hex.trim()).ifBlank { null }
    }
}
