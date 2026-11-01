package com.aegis.crypto

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin-фасад согласования ключей звонка (слой звонков, P8, §7.5/§10.10) над
 * нативным ядром.
 *
 * Медиа звонка защищает WebRTC (DTLS-SRTP) плюс кадровый AES-128-GCM
 * (`org.webrtc.FrameCryptor`) на ключах, которых нет в DTLS. AEGIS добавляет
 * два независимых усиления, которые считает это ядро:
 *  - корень ключей звонка (`callSecret`) генерируется случайно и доставляется
 *    собеседнику ЧЕРЕЗ Signal-сессию поверх каскада на PSK (слой A→B), то есть
 *    «установлен через Signal-сессию» (§7.5);
 *  - ручной PSK (§6.2) ПОДМЕШИВАЕТСЯ в вывод ключей и кода сверки (SAS), закрывая
 *    MITM недоверенного сервера, который не знает out-of-band PSK (§10.10).
 *
 * Все ключи — в аудированном HKDF-SHA512 внутри Rust; здесь только «клей» JNI.
 */
object CallCrypto {

    private val json = Json { ignoreUnknownKeys = true }

    /** Доступен ли слой звонков (собрана ли .so с фичей `signal`). */
    val isAvailable: Boolean get() = CryptoModule.hasSignalSupport

    /**
     * Согласует ключи звонка для одной стороны.
     *
     * @param callSecret общий корень звонка (сгенерирован инициатором, доставлен
     *   через Signal-сессию поверх каскада на PSK).
     * @param psk ручной PSK контакта (§6.2). Может быть пустым — тогда без
     *   out-of-band усиления (UI честно предупреждает).
     * @param callId идентификатор звонка (привязка вывода к сеансу).
     * @param initiator эта сторона инициировала звонок.
     * @param context «транскрипт» согласования (напр. отсортированные
     *   DTLS-фингерпринты сторон) — привязка ключей к конкретному каналу.
     */
    fun deriveKeys(
        callSecret: ByteArray,
        psk: ByteArray,
        callId: String,
        initiator: Boolean,
        context: ByteArray = ByteArray(0),
    ): CallKeys {
        if (!isAvailable) throw SignalUnavailableException()
        val raw = NativeAegisCore.nativeCallDeriveKeys(
            b64(callSecret), b64(psk), callId, initiator, b64(context),
        )
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw SignalException("Некорректный ответ слоя звонков: $raw") }
        obj["error"]?.let { throw SignalException(it.jsonPrimitive.content) }
        fun key(name: String): ByteArray = unb64(
            obj[name]?.jsonPrimitive?.content
                ?: throw SignalException("В ответе нет поля $name"),
        )
        return CallKeys(
            sendKey = key("sendKey"),
            sendSalt = key("sendSalt"),
            recvKey = key("recvKey"),
            recvSalt = key("recvSalt"),
            sas = obj["sas"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /**
     * Ключевой материал звонка для этой стороны. `send*` — исходящий SRTP-поток,
     * `recv*` — входящий; [sas] — короткий код для устной сверки (совпадает у
     * обеих честных сторон, ломается при MITM).
     */
    data class CallKeys(
        val sendKey: ByteArray,
        val sendSalt: ByteArray,
        val recvKey: ByteArray,
        val recvSalt: ByteArray,
        val sas: String,
    ) {
        /** Обнуляет ключевой материал в куче после копирования в native-криптор. */
        fun wipe() {
            sendKey.fill(0)
            sendSalt.fill(0)
            recvKey.fill(0)
            recvSalt.fill(0)
        }
        override fun equals(other: Any?): Boolean =
            other is CallKeys && sendKey.contentEquals(other.sendKey) &&
                sendSalt.contentEquals(other.sendSalt) &&
                recvKey.contentEquals(other.recvKey) &&
                recvSalt.contentEquals(other.recvSalt) && sas == other.sas

        override fun hashCode(): Int {
            var result = sendKey.contentHashCode()
            result = 31 * result + sendSalt.contentHashCode()
            result = 31 * result + recvKey.contentHashCode()
            result = 31 * result + recvSalt.contentHashCode()
            result = 31 * result + sas.hashCode()
            return result
        }
    }
}
