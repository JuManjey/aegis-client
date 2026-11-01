package com.aegis.crypto

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Бросается, когда слой B (libsignal) не собран в текущей .so (фича `signal` выкл). */
class SignalUnavailableException :
    IllegalStateException(
        "Слой B (Signal Protocol / libsignal) не скомпилирован в нативном ядре. " +
            "Собери .so с фичей `signal` (cargoNdkBuild включает её по умолчанию).",
    )

/** Бросается при ошибке нативной операции слоя B. */
open class SignalException(message: String) : Exception(message)

/** Результат нативного самотеста слоя B (X3DH/PQXDH + Double Ratchet). */
data class SignalRoundtripReport(
    val ok: Boolean,
    val sessionVersion: Int,
    val aliceToBobType: String,
    val bobToAliceType: String,
    /** Совпал ли PSK-биндинг у обеих сторон (null, если PSK не передавался). */
    val pskBindingMatches: Boolean?,
    /** Safety-code PSK-биндинга для сверки в UI (null, если PSK не передавался). */
    val pskSafetyCode: String?,
    val layerB: String,
)

/**
 * Kotlin-фасад слоя B (Signal Protocol, P2, §6.1) над нативным ядром.
 *
 * На данном этапе выставляет наружу проверенный интеграционный контур:
 *  - [selfTest] — доказательство, что X3DH/PQXDH + Double Ratchet реально
 *    работают внутри собранной `.so` (диагностика на устройстве), с опцией
 *    проверки PSK-биндинга (§6.2);
 *  - [demoPreKeyBundleJson] — эталонный публичный prekey-бандл в формате
 *    обмена с `aegis-server` (§0).
 *
 * Полноценное stateful-управление сессией (персистентные stores поверх
 * SQLCipher, отправка/приём реальных сообщений) подключается вместе с
 * хранилищем (P6): криптоядро уже проверено этим контуром.
 *
 * Вся криптография — в аудированной libsignal (§1). Здесь только «клей» JNI.
 */
object SignalSession {

    /** device id первичного устройства (обратная совместимость, §7.1.1). */
    const val PRIMARY_DEVICE_ID: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    /** Доступен ли слой B (собрана ли .so с фичей `signal`). */
    val isAvailable: Boolean get() = CryptoModule.hasSignalSupport

    private fun requireAvailable() {
        if (!isAvailable) throw SignalUnavailableException()
    }

    /**
     * Прогоняет нативный самотест «два клиента».
     * @param pskB64 опциональный ручной PSK (base64) для проверки §6.2;
     *   `null`/пусто — без PSK.
     */
    fun selfTest(pskB64: String? = null): SignalRoundtripReport {
        requireAvailable()
        val raw = NativeAegisCore.nativeSignalSelfTest(pskB64.orEmpty())
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw SignalException("Некорректный ответ слоя B: $raw") }
        obj["error"]?.let {
            throw SignalException("Самотест слоя B отклонён: ${it.jsonPrimitive.content}")
        }
        fun str(key: String): String = obj[key]?.jsonPrimitive?.content.orEmpty()
        fun boolOrNull(key: String): Boolean? =
            obj[key]?.let { if (it.jsonPrimitive.content == "null") null else it.jsonPrimitive.content.toBooleanStrictOrNull() }
        return SignalRoundtripReport(
            ok = boolOrNull("ok") ?: false,
            sessionVersion = obj["session_version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            aliceToBobType = str("alice_to_bob_type"),
            bobToAliceType = str("bob_to_alice_type"),
            pskBindingMatches = boolOrNull("psk_binding_matches"),
            pskSafetyCode = obj["psk_safety_code"]?.jsonPrimitive?.content?.takeIf { it != "null" },
            layerB = str("layer_b"),
        )
    }

    /** Эталонный публичный prekey-бандл (JSON) — формат обмена с backend (§0). */
    fun demoPreKeyBundleJson(): String {
        requireAvailable()
        val raw = NativeAegisCore.nativeSignalDemoPreKeyBundleJson()
        if (raw.isBlank()) throw SignalException("Пустой ответ при генерации prekey-бандла")
        return raw
    }

    // --- P6: stateful-API (§6.1/§6.3). Состояние — непрозрачный blob (base64),
    // который вызывающий хранит в SQLCipher (SignalStateStore). ---

    /** Создаёт свежий Signal-аккаунт и возвращает начальный blob состояния. */
    fun createAccount(): String {
        requireAvailable()
        val blob = NativeAegisCore.nativeSignalCreateAccount()
        if (blob.isBlank()) throw SignalException("Не удалось создать Signal-аккаунт")
        return blob
    }

    /** Публичный identity-ключ (base64) для PSK-биндинга/отпечатка (§6.2). */
    fun identityKeyB64(state: String): String {
        requireAvailable()
        val key = NativeAegisCore.nativeSignalIdentityKeyB64(state)
        if (key.isBlank()) throw SignalException("Не удалось получить identity-ключ")
        return key
    }

    /**
     * Генерирует комплект публичных prekeys для публикации/ручного обмена.
     * Возвращает обновлённый blob и JSON-бандл (формат §0).
     */
    fun publishPrekeys(state: String, localDevice: Int = PRIMARY_DEVICE_ID): PublishResult {
        requireAvailable()
        val obj = parseObject(NativeAegisCore.nativeSignalPublishPrekeys(state, localDevice))
        checkError(obj)
        val newState = obj["state"]?.jsonPrimitive?.content
            ?: throw SignalException("В ответе нет нового состояния")
        val bundle = obj["bundle"]?.jsonObject
            ?: throw SignalException("В ответе нет prekey-бандла")
        return PublishResult(newState, bundle.toString())
    }

    /**
     * Устанавливает исходящую сессию по публичному бандлу контакта (X3DH/PQXDH).
     * Возвращает обновлённый blob состояния.
     *
     * [expectedIdentityB64] — identity удалённого устройства из доверенного
     * канала (QR/NFC, подписанная заявка или запись каталога, объявление под
     * слоем A). Ядро закрепляет его вместо TOFU, и бандл, PreKey-сообщение или
     * сессия на другом ключе отвергаются. `null` — ключ ещё неизвестен (TOFU).
     * То же значение принимают [hasSession], [encrypt] и [decrypt].
     */
    fun processBundle(
        state: String,
        localName: String,
        remoteName: String,
        remoteDevice: Int,
        bundleJson: String,
        expectedIdentityB64: String?,
        localDevice: Int = PRIMARY_DEVICE_ID,
    ): String {
        requireAvailable()
        val raw = NativeAegisCore.nativeSignalProcessBundle(
            state, localName, localDevice, remoteName, remoteDevice, bundleJson,
            expectedIdentityB64.orEmpty(),
        )
        return unwrapSigned(raw)
    }

    /**
     * Есть ли сессия с устройством. Сессия на identity, отличном от
     * [expectedIdentityB64], считается отсутствующей: вызывающий заведёт новую
     * по сверенному бандлу вместо отказа ядра на каждом шифровании.
     */
    fun hasSession(state: String, remoteName: String, remoteDevice: Int, expectedIdentityB64: String?): Boolean {
        requireAvailable()
        return NativeAegisCore.nativeSignalHasSession(
            state, remoteName, remoteDevice, expectedIdentityB64.orEmpty(),
        )
    }

    /** Шифрует сообщение слоя B. Возвращает новый blob, тип и байты конверта. */
    fun encrypt(
        state: String,
        localName: String,
        remoteName: String,
        remoteDevice: Int,
        plaintext: ByteArray,
        expectedIdentityB64: String?,
        localDevice: Int = PRIMARY_DEVICE_ID,
    ): EncryptResult {
        requireAvailable()
        val obj = parseObject(
            NativeAegisCore.nativeSignalEncrypt(
                state, localName, localDevice, remoteName, remoteDevice, b64(plaintext),
                expectedIdentityB64.orEmpty(),
            ),
        )
        checkError(obj)
        val newState = obj["state"]?.jsonPrimitive?.content
            ?: throw SignalException("В ответе нет нового состояния")
        val type = obj["type"]?.jsonPrimitive?.content
            ?: throw SignalException("В ответе нет типа сообщения")
        val ciphertext = unb64(
            obj["ciphertext"]?.jsonPrimitive?.content
                ?: throw SignalException("В ответе нет шифртекста"),
        )
        return EncryptResult(newState, type, ciphertext)
    }

    /** Расшифровывает конверт слоя B. Возвращает новый blob и plaintext. */
    fun decrypt(
        state: String,
        localName: String,
        remoteName: String,
        remoteDevice: Int,
        ciphertext: ByteArray,
        messageType: String,
        expectedIdentityB64: String?,
        localDevice: Int = PRIMARY_DEVICE_ID,
    ): DecryptResult {
        requireAvailable()
        val obj = parseObject(
            NativeAegisCore.nativeSignalDecrypt(
                state, localName, localDevice, remoteName, remoteDevice, b64(ciphertext), messageType,
                expectedIdentityB64.orEmpty(),
            ),
        )
        checkError(obj)
        val newState = obj["state"]?.jsonPrimitive?.content
            ?: throw SignalException("В ответе нет нового состояния")
        val plaintext = unb64(
            obj["plaintext"]?.jsonPrimitive?.content
                ?: throw SignalException("В ответе нет plaintext"),
        )
        return DecryptResult(newState, plaintext)
    }

    /** PSK-биндинг (§6.2): связывает identity-ключи сторон с ручным PSK. */
    fun pskBinding(localIdentityB64: String, remoteIdentityB64: String, psk: ByteArray): PskBinding {
        requireAvailable()
        val obj = parseObject(
            NativeAegisCore.nativeSignalPskBinding(localIdentityB64, remoteIdentityB64, b64(psk)),
        )
        checkError(obj)
        val binding = unb64(
            obj["binding"]?.jsonPrimitive?.content
                ?: throw SignalException("В ответе нет биндинга"),
        )
        val safety = obj["safety"]?.jsonPrimitive?.content.orEmpty()
        return PskBinding(binding, safety)
    }

    /**
     * Запечатывает plaintext слоя B AEAD на ключе из PSK + identity-ключей.
     * Без PSK / identity не вызывается: это усиление, а не замена каскада слоя A.
     */
    fun pskSessionSeal(
        localIdentityB64: String,
        remoteIdentityB64: String,
        psk: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireAvailable()
        return unwrapSigned(
            NativeAegisCore.nativeSignalPskSealB64(
                localIdentityB64,
                remoteIdentityB64,
                b64(psk),
                b64(plaintext),
            ),
        ).let { unb64(it) }
    }

    /**
     * Снимает сессионную обёртку. Конверт без метки `A1S1` возвращается как есть
     * (сообщения, отправленные до этого слоя).
     */
    fun pskSessionOpen(
        localIdentityB64: String,
        remoteIdentityB64: String,
        psk: ByteArray,
        sealed: ByteArray,
    ): ByteArray {
        requireAvailable()
        return unwrapSigned(
            NativeAegisCore.nativeSignalPskOpenB64(
                localIdentityB64,
                remoteIdentityB64,
                b64(psk),
                b64(sealed),
            ),
        ).let { unb64(it) }
    }

    private fun parseObject(raw: String) =
        runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw SignalException("Некорректный ответ слоя B: $raw") }

    private fun checkError(obj: Map<String, JsonElement>) {
        obj["error"]?.let { throw SignalException(it.jsonPrimitive.content) }
    }

    private fun unwrapSigned(raw: String): String = when {
        raw.startsWith("+") -> raw.substring(1)
        raw.startsWith("-") -> throw SignalException(raw.substring(1))
        else -> throw SignalException("Некорректный ответ слоя B: $raw")
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Результат [publishPrekeys]. */
    data class PublishResult(val state: String, val bundleJson: String)

    /** Результат [encrypt]: тип конверта Signal — "PreKey" или "Whisper". */
    data class EncryptResult(val state: String, val type: String, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is EncryptResult && state == other.state && type == other.type &&
                ciphertext.contentEquals(other.ciphertext)

        override fun hashCode(): Int =
            (state.hashCode() * 31 + type.hashCode()) * 31 + ciphertext.contentHashCode()
    }

    /** Результат [decrypt]. */
    data class DecryptResult(val state: String, val plaintext: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is DecryptResult && state == other.state && plaintext.contentEquals(other.plaintext)

        override fun hashCode(): Int = state.hashCode() * 31 + plaintext.contentHashCode()
    }

    /** Результат [pskBinding]: [safety] — код для устной сверки (§6.2). */
    data class PskBinding(val binding: ByteArray, val safety: String) {
        override fun equals(other: Any?): Boolean =
            other is PskBinding && binding.contentEquals(other.binding) && safety == other.safety

        override fun hashCode(): Int = binding.contentHashCode() * 31 + safety.hashCode()
    }
}
