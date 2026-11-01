package com.aegis.crypto.cascade

/**
 * Порядок AEAD-шифров в каскадном контейнере (СЛОЙ A, §4–§5 технического задания).
 *
 * Шифрование выполняется СВЕРХУ ВНИЗ (вложенное шифрование: выход предыдущего
 * шифра — вход следующего). Расшифровка — строго в обратном порядке.
 */
enum class CascadeLayer(val displayName: String) {
    /** 1) Внутренний слой. XChaCha20-Poly1305 (192-бит nonce). */
    XCHACHA20_POLY1305("XChaCha20-Poly1305"),

    /** 2) AES-256-GCM (AEAD). */
    AES_256("AES-256-GCM"),

    /**
     * 3) Camellia-256 в AEAD-конструкции EAX.
     *
     * ЗАМЕНА Serpent-256 (§5 «или обоснуй замену»): аудированный крейт RustCrypto
     * `serpent` во всех версиях принимает лишь 128-битный ключ (`KeySize = U16`),
     * поэтому настоящий Serpent-256 недостижим. Camellia-256 (RFC 3713 / ISO-IEC
     * 18033-3 / NESSIE / CRYPTREC) — 256-бит ключ, отдельное семейство от
     * AES/Twofish; каскад остаётся «4×256 бит независимых слоёв».
     */
    CAMELLIA_256("Camellia-256-EAX"),

    /** 4) Внешний слой. Twofish-256 в AEAD-конструкции EAX. */
    TWOFISH_256("Twofish-256-EAX");

    companion object {
        /** Канонический порядок шифрования каскада (§5). */
        val ENCRYPT_ORDER: List<CascadeLayer> = listOf(
            XCHACHA20_POLY1305, AES_256, CAMELLIA_256, TWOFISH_256,
        )

        /** Порядок расшифровки — обратный порядку шифрования. */
        val DECRYPT_ORDER: List<CascadeLayer> = ENCRYPT_ORDER.reversed()
    }
}

/**
 * Параметры вывода ключей и KDF для каскада (§5).
 *
 * Все значения — требования ТЗ:
 *  - Ключи K1..K4 независимы, по 256 бит, выводятся HKDF-SHA512 из общего
 *    секрета контейнера с РАЗДЕЛЕНИЕМ ДОМЕНОВ (разные info-метки на каждый ключ).
 *  - Ключ контейнера из парольной/мнемонической фразы — Argon2id с высокими
 *    параметрами.
 *
 * ВАЖНО: это ДЕКЛАРАЦИЯ параметров, ЕДИНЫЙ КОНТРАКТ вывода ключей с нативным
 * ядром. Метки [hkdfInfoLabels] обязаны совпадать с константами `INFO_K*` в
 * `rust/aegis-core/src/cascade.rs`. Реальная крипта каскада живёт в нативном
 * Rust-модуле (RustCrypto), а не в Kotlin (свою криптографию не пишем, §1).
 */
data class CascadeSpec(
    val keyLengthBits: Int = 256,
    /** Метки разделения доменов для HKDF-SHA512 (по одной на слой). */
    val hkdfInfoLabels: Map<CascadeLayer, String> = mapOf(
        CascadeLayer.XCHACHA20_POLY1305 to "aegis/cascade/v1/k1-xchacha20",
        CascadeLayer.AES_256 to "aegis/cascade/v1/k2-aes256",
        CascadeLayer.CAMELLIA_256 to "aegis/cascade/v1/k3-camellia256",
        CascadeLayer.TWOFISH_256 to "aegis/cascade/v1/k4-twofish256",
    ),
    /** Параметры Argon2id для вывода ключа контейнера из фразы (высокие). */
    val argon2MemoryKib: Int = 256 * 1024, // 256 MiB
    val argon2Iterations: Int = 4,
    val argon2Parallelism: Int = 2,
) {
    init {
        require(keyLengthBits == 256) { "Каскад работает только с 256-битными ключами (§5)." }
        require(hkdfInfoLabels.keys.containsAll(CascadeLayer.ENCRYPT_ORDER)) {
            "Должна быть info-метка для КАЖДОГО слоя каскада (разделение доменов)."
        }
    }
}
