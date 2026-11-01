package com.aegis.crypto

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Бросается при ошибке нативной операции удалённого добавления контакта (RCH v1). */
class DiscoveryException(message: String) : Exception(message)

/**
 * Kotlin-фасад удалённого добавления контакта (RCH v1, §6.2,
 * `docs/contact_requests.md`) над нативным ядром ([NativeAegisCore]).
 *
 * ЗАЧЕМ ФАСАД ТОНКИЙ. Вся содержательная криптография — вывод индекса каталога и
 * ключа записи из тега, transcript рукопожатия, вывод секрета слоя A, Kyber1024 —
 * живёт в Rust (`discovery.rs`) и покрыта юнит-тестами. Здесь только сборка
 * JSON-запроса и разбор ответа: канонический порядок полей через границу JNI НЕ
 * передаётся, поэтому перепутать его на стороне Kotlin невозможно.
 *
 * ГРАНИЦЫ. Тег обнаружения и эфемерные секреты раунда — СЕКРЕТЫ: они хранятся
 * только в SQLCipher (`contact_requests`, sealed-настройки) и никогда не уходят
 * в сеть. Наружу едут лишь производные значения: опаковый индекс каталога,
 * адрес ящика приглашений и ПУБЛИЧНЫЕ части рукопожатия.
 */
object DiscoveryCrypto {

    /** Длина тега обнаружения в байтах (256 бит), синхронно с `discovery::TAG_LEN`. */
    const val TAG_LENGTH: Int = 32

    /** Минимальная/максимальная длина публичного алиаса, синхронно с Rust. */
    const val ALIAS_MIN_LENGTH: Int = 3
    const val ALIAS_MAX_LENGTH: Int = 32

    private val json = Json { ignoreUnknownKeys = true }

    /** Готово ли нативное ядро. */
    val isReady: Boolean get() = NativeAegisCore.isAvailable

    /**
     * Доступно ли удалённое добавление целиком. Требует слоя B: приглашение едет
     * внутри Signal PreKeyMessage, а PQ-составляющая секрета — Kyber1024 из
     * libsignal. Без него нативное ядро честно вернёт ошибку.
     */
    val isAvailable: Boolean get() = isReady && CryptoModule.hasSignalSupport

    private fun requireReady() {
        if (!NativeAegisCore.isAvailable) throw CryptoUnavailableException()
    }

    /** Производные тега обнаружения: адрес записи в каталоге и ключ записи. */
    data class DiscoveryIds(
        /** Опаковый индекс записи (URL-safe base64 без паддинга — сегмент URL). */
        val index: String,
        /** Ключ AEAD записи каталога (base64). СЕКРЕТ: только в SQLCipher. */
        val recordKeyB64: String,
        /** Коммитмент к тегу для вложения в запись (base64 SHA-256). */
        val tagCommitB64: String,
    )

    /** Материал одного раунда рукопожатия RCH. */
    data class RoundMaterial(
        /** Эфемерный секрет X25519. СЕКРЕТ: хранить до получения ответа, затем затереть. */
        val epkSecretB64: String,
        /** Публичный эфемерный X25519 — едет в нагрузке. */
        val epkPublicB64: String,
        /** Случайный вклад стороны в секрет — едет в нагрузке. */
        val contribB64: String,
        /** Nonce нагрузки — едет в нагрузке. */
        val nonceB64: String,
        /** Публичный ключ Kyber1024 инициатора — едет в `INVITE`. */
        val kemPublicB64: String,
        /** Секретный ключ Kyber1024. СЕКРЕТ: нужен инициатору для декапсуляции. */
        val kemSecretB64: String,
    )

    /**
     * Публичная часть нагрузки одной стороны рукопожатия.
     *
     * Для СВОЕЙ стороны поля [accountId], [ed25519Hex] и [x25519Hex] нативный
     * слой игнорирует: он выводит их из мнемоники сам, чтобы «переданная»
     * личность не могла разойтись с реальным ключом.
     */
    data class Party(
        val accountId: String,
        val ed25519Hex: String,
        val x25519Hex: String,
        val epkPublicB64: String,
        val contribB64: String,
        val nonceB64: String,
    )

    /** Итог рукопожатия: секрет слоя A и код для сверки. */
    data class Derived(
        /** Секрет контейнера слоя A (base64). Кладётся в `PskStore` как PSK. */
        val pskB64: String,
        /** Хеш transcript (base64) — для журнала/отладки, не секрет. */
        val transcriptB64: String,
        /** Шесть групп по пять цифр для устной сверки сразу после активации. */
        val handshakeCode: String,
        /**
         * Шифртекст Kyber1024. Заполняется у ОТВЕТЧИКА — его нужно положить в
         * `ACCEPT`; у инициатора совпадает с присланным.
         */
        val kemCiphertextB64: String,
        /** Свой `accountId`, выведенный нативным слоем из мнемоники (сверка). */
        val localAccountId: String,
    )

    /** Генерирует новый тег обнаружения. СЕКРЕТ: только в sealed-хранилище. */
    fun generateTag(): ByteArray {
        requireReady()
        val b64 = NativeAegisCore.nativeDiscoveryGenerateTagB64()
        if (b64.isBlank()) throw DiscoveryException("Не удалось сгенерировать тег обнаружения")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /**
     * Выводит из тега индекс каталога (пригодный как сегмент URL) и ключ записи.
     * Разные HKDF-домены: по индексу восстановить ключ записи невозможно.
     */
    fun deriveIds(tagB64: String): DiscoveryIds {
        requireReady()
        val obj = parse(NativeAegisCore.nativeDiscoveryDeriveJson(tagB64.trim()))
        return DiscoveryIds(
            index = urlSafe(str(obj, "indexB64")),
            recordKeyB64 = str(obj, "recordKeyB64"),
            tagCommitB64 = str(obj, "tagCommitB64"),
        )
    }

    /** Нормализованный алиас (`@Ник` → `ник`) и его индекс в публичном каталоге. */
    data class AliasIndex(val normalized: String, val index: String)

    /**
     * Нормализует публичный алиас и выводит его индекс. Правила жёсткие: только
     * ASCII `[a-z0-9_.]`, 3..32 символа, без краевых и сдвоенных разделителей.
     * Не-ASCII запрещён специально — в публичном пространстве имён схожие
     * символы Unicode это готовый фишинг. `null`, если алиас недопустим.
     */
    fun aliasIndexOrNull(alias: String): AliasIndex? {
        if (!isReady) return null
        val raw = NativeAegisCore.nativeAliasIndexJson(alias)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (obj["error"] != null) return null
        val normalized = obj["aliasNormalized"]?.jsonPrimitive?.content ?: return null
        val index = obj["indexB64"]?.jsonPrimitive?.content ?: return null
        if (normalized.isBlank() || index.isBlank()) return null
        return AliasIndex(normalized, urlSafe(index))
    }

    /** Причина, по которой алиас недопустим (для показа в UI), или `null`. */
    fun aliasErrorOrNull(alias: String): String? {
        if (!isReady) return null
        val raw = NativeAegisCore.nativeAliasIndexJson(alias)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return obj["error"]?.jsonPrimitive?.content
    }

    /**
     * Запечатывает запись каталога: AAD привязан к индексу, поэтому переставить
     * запись на другой индекс нельзя. Возвращает сырые байты для `PUT`.
     */
    fun sealRecord(recordKeyB64: String, index: String, plaintext: String): ByteArray {
        requireReady()
        val b64 = NativeAegisCore.nativeDiscoverySealRecordB64(
            recordKeyB64,
            standardFromUrlSafe(index),
            plaintext,
        )
        if (b64.isBlank()) throw DiscoveryException("Не удалось запечатать запись каталога")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /**
     * Распечатывает запись каталога. `null` при провале AEAD-тега — то есть при
     * неверном теге, битой или переставленной записи (fail-closed: вызывающий
     * ОБЯЗАН трактовать `null` как «записи нет», а не как «пустая запись»).
     */
    fun openRecordOrNull(recordKeyB64: String, index: String, sealed: ByteArray): String? {
        requireReady()
        val plain = NativeAegisCore.nativeDiscoveryOpenRecord(
            recordKeyB64,
            standardFromUrlSafe(index),
            Base64.encodeToString(sealed, Base64.NO_WRAP),
        )
        return plain.takeIf { it.isNotEmpty() }
    }

    /** Генерирует эфемерный материал раунда рукопожатия. */
    fun generateRound(): RoundMaterial {
        requireReady()
        val obj = parse(NativeAegisCore.nativeRchGenerateRoundJson())
        return RoundMaterial(
            epkSecretB64 = str(obj, "epkSecretB64"),
            epkPublicB64 = str(obj, "epkPublicB64"),
            contribB64 = str(obj, "contribB64"),
            nonceB64 = str(obj, "nonceB64"),
            kemPublicB64 = str(obj, "kemPublicB64"),
            kemSecretB64 = str(obj, "kemSecretB64"),
        )
    }

    /**
     * Выводит секрет слоя A из рукопожатия RCH.
     *
     * [initiator] — эта сторона отправляла `INVITE` (тогда обязательны
     * [kemSecretB64] и [kemCiphertextB64] из полученного `ACCEPT`). Иначе это
     * ответчик: он сам инкапсулирует Kyber в [kemPublicB64] инициатора, и
     * получившийся шифртекст возвращается в [Derived.kemCiphertextB64] — его
     * нужно положить в `ACCEPT`.
     *
     * [mnemonic] нужна, чтобы вывести статический X25519-секрет и свою личность
     * ВНУТРИ Rust: приватные ключи не покидают нативный слой.
     *
     * Обе стороны получают ОДИН секрет; расхождение означает подмену поля
     * (transcript связывает все поля) и приводит к разным адресам ящиков, то
     * есть к отсутствию связи, а не к незащищённой переписке.
     */
    @Suppress("LongParameterList")
    fun derive(
        initiator: Boolean,
        mnemonic: String,
        local: Party,
        remote: Party,
        localEpkSecretB64: String,
        kemPublicB64: String,
        kemSecretB64: String = "",
        kemCiphertextB64: String = "",
        passphrase: String = "",
    ): Derived {
        requireReady()
        val request = buildJsonObject {
            put("role", if (initiator) "initiator" else "responder")
            put("mnemonic", mnemonic.trim())
            put("passphrase", passphrase)
            put("local", party(local))
            put("remote", party(remote))
            put("localEpkSecretB64", localEpkSecretB64)
            put("kemPublicB64", kemPublicB64)
            if (initiator) {
                put("kemSecretB64", kemSecretB64)
                put("kemCiphertextB64", kemCiphertextB64)
            }
        }
        val obj = parse(NativeAegisCore.nativeRchDeriveJson(request.toString()))
        return Derived(
            pskB64 = str(obj, "pskB64"),
            transcriptB64 = str(obj, "transcriptB64"),
            handshakeCode = str(obj, "handshakeCode"),
            kemCiphertextB64 = str(obj, "kemCiphertextB64"),
            localAccountId = str(obj, "localAccountId"),
        )
    }

    private fun party(p: Party): JsonObject = buildJsonObject {
        put("accountId", p.accountId)
        put("ed25519Hex", p.ed25519Hex)
        put("x25519Hex", p.x25519Hex)
        put("epkPublicB64", p.epkPublicB64)
        put("contribB64", p.contribB64)
        put("nonceB64", p.nonceB64)
    }

    private fun parse(raw: String): JsonObject {
        if (raw.isBlank()) throw DiscoveryException("Пустой ответ нативного ядра")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw DiscoveryException("Некорректный ответ нативного ядра: $raw") }
        obj["error"]?.let { throw DiscoveryException(it.jsonPrimitive.content) }
        return obj
    }

    private fun str(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.content
            ?: throw DiscoveryException("В ответе нативного ядра нет поля $key")

    /**
     * Стандартный base64 → URL-safe без паддинга: индексы каталога и адреса
     * ящиков едут в URL, а релей принимает алфавит `[A-Za-z0-9_-]`.
     */
    private fun urlSafe(standardB64: String): String {
        val raw = Base64.decode(standardB64, Base64.NO_WRAP)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Обратное преобразование для передачи индекса в нативный слой. */
    private fun standardFromUrlSafe(urlSafeB64: String): String {
        val raw = Base64.decode(urlSafeB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
