package com.aegis.core.common.wire

import com.aegis.core.common.call.CallControl
import com.aegis.core.common.privacy.ControlMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Маркеры канала во внешнем конверте mailbox и разбор служебного plaintext.
 *
 * Переписка, control (§14.2), сигналинг звонка (§7.5) и keyx (§6.2) едут в ОДИН
 * per-device ящик. Приёмник ([com.aegis.messenger.messaging.RelayReceiver])
 * выбирает обработчик по полю `channel`. Если маркер отсутствует (старый баг:
 * kotlinx.serialization по умолчанию не кодирует поля с дефолтом) — эвристика
 * по форме JSON, а после расшифровки — [classifyPlaintext], чтобы служебные
 * события не попадали в историю чата.
 */
object WireChannel {
    const val MESSAGE = "message"
    const val CONTROL = ControlMessage.CHANNEL
    const val CALL = CallControl.CHANNEL
    const val KEYX = "keyx"
    const val SELF_SYNC = "self-sync"

    /**
     * Рукопожатие удалённого добавления контакта (RCH v1, §6.2,
     * `docs/contact_requests.md`). Приходит в ящик приглашений/ответа, то есть
     * от того, с кем контакта ЕЩЁ НЕТ, — обрабатывается отдельным путём
     * (`ContactRequestManager`), а не конвейером переписки.
     */
    const val INVITE = "invite"

    /**
     * Канал внешнего конверта. Явный `channel` побеждает; иначе keyx-конверт
     * (есть `ciphertextB64`, нет `type`/`layerA`) → [KEYX]; всё прочее → [MESSAGE].
     */
    fun resolve(outerJson: String): String {
        val obj = parseObject(outerJson) ?: return MESSAGE
        val explicit = (obj["channel"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        val hasCipher = obj.containsKey("ciphertextB64")
        val hasType = obj.containsKey("type")
        val hasLayerA = obj.containsKey("layerA")
        if (hasCipher && !hasType && !hasLayerA) return KEYX
        return MESSAGE
    }

    /**
     * Служебный plaintext (control/звонок) без кадрового байта переписки.
     * `null` — обычный текст (в т.ч. legacy unframed) или не-JSON.
     */
    fun classifyPlaintext(text: String): Kind? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = parseObject(trimmed) ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return when (type) {
            in CONTROL_TYPES -> Kind.CONTROL
            in CALL_TYPES -> Kind.CALL
            else -> null
        }
    }

    /** Уже сохранённое тело сообщения, которое не должно быть в ленте. */
    fun isStoredLeak(body: String, hasAttachment: Boolean = false): Boolean {
        if (hasAttachment) return false
        return classifyPlaintext(body) != null
    }

    enum class Kind { CONTROL, CALL }

    private val CONTROL_TYPES = setOf(
        ControlMessage.TYPE_READ,
        ControlMessage.TYPE_TYPING,
        ControlMessage.TYPE_PRESENCE,
        ControlMessage.TYPE_DELETE,
        ControlMessage.TYPE_DEVICE_ANNOUNCE,
        ControlMessage.TYPE_DEVICE_REVOKE,
    )

    private val CALL_TYPES = setOf(
        CallControl.TYPE_OFFER,
        CallControl.TYPE_ANSWER,
        CallControl.TYPE_ICE,
        CallControl.TYPE_HANGUP,
        CallControl.TYPE_BUSY,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
