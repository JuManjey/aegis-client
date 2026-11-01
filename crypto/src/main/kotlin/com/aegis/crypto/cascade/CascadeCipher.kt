package com.aegis.crypto.cascade

import android.util.Base64
import com.aegis.crypto.NativeAegisCore

/**
 * Контракт каскадного контейнера (СЛОЙ A, §4–§5).
 *
 * Каждый шаг — AEAD (шифрование + аутентификация). При провале проверки
 * тега любого слоя расшифровка немедленно прекращается ([CascadeAuthException]).
 *
 * НЕ сжимать данные между слоями (риск атак типа CRIME, §4).
 */
interface CascadeCipher {

    /** Специфика каскада (порядок слоёв, KDF-параметры). */
    val spec: CascadeSpec

    /**
     * Зашифровать контейнер: plaintext → K1..K4 (вложенное AEAD).
     *
     * @param plaintext исходные байты сообщения.
     * @param associatedData аутентифицируемые, но не шифруемые данные (AAD).
     * @return байты контейнера (шифртекст со всеми AEAD-тегами).
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray

    /**
     * Расшифровать контейнер в обратном порядке (Twofish → … → XChaCha20).
     *
     * @throws CascadeAuthException при неуспешной проверке тега любого слоя.
     */
    fun decrypt(container: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray
}

/** Ошибка аутентификации AEAD-слоя каскада (тег не сошёлся) либо порчи контейнера. */
class CascadeAuthException(message: String) : Exception(message)

/** Бросается, если нативное ядро (`.so`) недоступно — фейковый шифр НЕ поставляем (§1). */
class CascadeUnavailableException :
    IllegalStateException(
        "Каскадный контейнер (слой A) требует нативного ядра aegis-core, " +
            "но `.so` не загружена. Собери проект с cargo-ndk (см. README.md).",
    )

/**
 * Реальная реализация каскадного контейнера (P3, §5) поверх нативного
 * Rust-ядра `aegis-core` (модуль `cascade.rs`).
 *
 * Каскад: XChaCha20-Poly1305 → AES-256-GCM → Camellia-256(EAX) →
 * Twofish-256(EAX). Ключи K1..K4 выводятся в нативном коде через HKDF-SHA512 с
 * разделением доменов (метки — [CascadeSpec.hkdfInfoLabels], единый контракт).
 * Вся криптография — в аудированных крейтах RustCrypto; здесь только «клей» JNI.
 *
 * @param containerSecret общий секрет контейнера (PSK, обменянный вручную §6.2,
 *   ЛИБО выход Argon2id из парольной фразы). Кодируется в base64 для передачи
 *   через границу JNI; вызывающий должен затереть свою копию сам.
 */
class NativeCascadeCipher(
    containerSecret: ByteArray,
    override val spec: CascadeSpec = CascadeSpec(),
) : CascadeCipher {

    private val secretB64: String = Base64.encodeToString(containerSecret, Base64.NO_WRAP)

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        if (!NativeAegisCore.isAvailable) throw CascadeUnavailableException()
        val resp = NativeAegisCore.nativeCascadeSealB64(
            secretB64,
            Base64.encodeToString(plaintext, Base64.NO_WRAP),
            Base64.encodeToString(associatedData, Base64.NO_WRAP),
        )
        return unwrap(resp)
    }

    override fun decrypt(container: ByteArray, associatedData: ByteArray): ByteArray {
        if (!NativeAegisCore.isAvailable) throw CascadeUnavailableException()
        val resp = NativeAegisCore.nativeCascadeOpenB64(
            secretB64,
            Base64.encodeToString(container, Base64.NO_WRAP),
            Base64.encodeToString(associatedData, Base64.NO_WRAP),
        )
        return unwrap(resp)
    }

    /**
     * Разбирает ответ нативного слоя: `+<base64>` — успех, `-<текст>` — ошибка.
     * Префикс отличает успешную расшифровку в пустой массив от сбоя.
     */
    private fun unwrap(response: String): ByteArray {
        if (response.isEmpty()) throw CascadeAuthException("пустой ответ нативного каскада")
        val payload = response.substring(1)
        return when (response[0]) {
            '+' -> Base64.decode(payload, Base64.NO_WRAP)
            else -> throw CascadeAuthException(payload.ifEmpty { "сбой каскадного контейнера" })
        }
    }
}
