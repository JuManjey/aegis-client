package com.aegis.messenger.messaging

import android.util.Base64
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.privacy.ControlMessage
import com.aegis.data.storage.StoredContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Канал управляющих сообщений переписки (§14.2/§7.3): отчёты о прочтении,
 * «печатает…», онлайн-статус и «удалить у всех». Идёт ТЕМ ЖЕ защищённым
 * конвейером, что текст и сигналинг звонков: слой A (каскад на PSK) → слой B
 * (Signal) → Tor → onion-релей. По сети — только двойной шифртекст, поэтому
 * серверу не видны ни тип события, ни id сообщений (zero-trust, §7.6/§10.9).
 *
 * Мультидевайс (§7.1.1, `docs/multidevice.md` §4.1/§6, follow-up №2.2): control
 * доставляется per-device fan-out'ом — по одному конверту (свой ratchet, MD-1)
 * в per-device подканал v2 КАЖДОГО активного устройства контакта, тем же
 * ([RelayGateway.postToPeerDevices]), что и переписка. Приёмный цикл релея
 * демультиплексирует по полю `channel` уже НА per-device подканале, поэтому
 * общий v1-mailbox (роль 0/1) с гонкой деструктивного ack больше не нужен: у
 * каждого устройства свой ящик и свой ack (§9/§10 док).
 *
 * Отправка ([send]) best-effort: при отсутствии Tor/релея/PSK доставка просто не
 * произойдёт (E2EE от этого не страдает). Приём — [onWire] расшифровывает
 * входящий конверт B→A по ratchet устройства-отправителя (`senderDevice`) и
 * возвращает [ControlMessage] для обработчика ([ChatSignals]); вызывается из
 * приёмного цикла релея ([RelayReceiver]).
 */
class ControlChannel(
    private val sessionManager: SignalSessionManager,
    private val pskManager: com.aegis.messenger.psk.PskManager,
    private val relayGateway: RelayGateway,
    /** device id ЭТОГО устройства — уходит в конверт как `senderDevice` (§6). */
    private val localDeviceIdProvider: () -> Int = { 1 },
    private val json: Json = WireJson,
) {
    /**
     * Шифрует и best-effort отправляет управляющее сообщение контакту. Слой A
     * (каскад на PSK) применяется ОДИН раз, затем для каждого активного устройства
     * контакта — своя Signal-сессия и свой шифртекст (MD-1) в его per-device
     * подканал v2. Пропускает устройства, для которых ещё нет сессии/бандла (они
     * появятся по device-announce).
     */
    suspend fun send(contact: StoredContact, control: ControlMessage) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(ControlMessage.serializer(), control)
            .toByteArray(Charsets.UTF_8)
        val cascade = pskManager.cipherFor(contact.contact.id)
        val layerAUsed = cascade != null
        val inner = cascade?.encrypt(plaintext) ?: plaintext
        val myDevice = localDeviceIdProvider()

        val envelopes = HashMap<Int, ByteArray>()
        for (device in contact.activeDevices()) {
            val envelope = try {
                sessionManager.encryptTo(contact.contact.id, device.deviceId, device.bundleJson, inner, device.identityB64)
            } catch (_: Exception) {
                continue
            }
            val wire = ControlWireEnvelope(
                type = envelope.type,
                layerA = layerAUsed,
                ciphertextB64 = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                senderDevice = myDevice,
            )
            envelopes[device.deviceId] = json.encodeToString(ControlWireEnvelope.serializer(), wire)
                .toByteArray(Charsets.UTF_8)
        }
        if (envelopes.isEmpty()) {
            DiagnosticLog.w(DIAG_TAG) {
                "control не ушёл: нет конвертов тип=${control.type} " +
                    "контакт=${DiagnosticLog.shortId(contact.contact.id)}"
            }
            return@withContext
        }
        DiagnosticLog.event(
            DIAG_TAG, "control исходящий",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "тип" to control.type,
            "слойA" to layerAUsed,
            "устройств" to envelopes.size,
            "idСообщений" to control.messageIds.size,
        )
        relayGateway.postToPeerDevices(contact, envelopes)
    }

    /** Расшифровывает входящий конверт control-канала (B→A) → [ControlMessage]. */
    suspend fun onWire(contact: StoredContact, wireJson: String): ControlMessage =
        withContext(Dispatchers.IO) {
            val wire = json.decodeFromString(ControlWireEnvelope.serializer(), wireJson)
            pskManager.requireLayerA(contact.contact.id, wire.layerA)
            val envelope = Base64.decode(wire.ciphertextB64, Base64.NO_WRAP)
            // Устройство-отправитель берём из конверта (§6): у каждого устройства
            // контакта свой ratchet (MD-1). Откат на первичное для старого формата.
            val senderDevice = wire.senderDevice.takeIf { it in 1..255 } ?: contact.remoteDevice
            val inner = sessionManager.decryptFrom(
                contact.contact.id, senderDevice, envelope, wire.type, contact.identityFor(senderDevice),
            )
            val plaintext = if (wire.layerA) {
                val cascade = pskManager.cipherFor(contact.contact.id)
                    ?: error("Нет PSK для расшифровки слоя A control-канала")
                cascade.decrypt(inner)
            } else {
                inner
            }
            val msg = json.decodeFromString(ControlMessage.serializer(), String(plaintext, Charsets.UTF_8))
            DiagnosticLog.event(
                DIAG_TAG, "control входящий",
                "контакт" to DiagnosticLog.shortId(contact.contact.id),
                "тип" to msg.type,
                "слойA" to wire.layerA,
                "устройство" to senderDevice,
                "idСообщений" to msg.messageIds.size,
            )
            msg
        }

    /** Конверт control-канала: только шифртекст, без секретов (§7.6). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class ControlWireEnvelope(
        val type: String,
        val layerA: Boolean,
        val ciphertextB64: String,
        /**
         * device id УСТРОЙСТВА-отправителя (мультидевайс §6): получатель выбирает
         * по нему нужный ratchet ([SignalSessionManager.decryptFrom]). 0 = старый
         * формат ⇒ откат на первичное устройство контакта.
         */
        val senderDevice: Int = 0,
        /** Маркер канала для демультиплексирования на релее (opaque для сервера). */
        @EncodeDefault
        val channel: String = ControlMessage.CHANNEL,
        val v: Int = 1,
    )

    private companion object {
        const val DIAG_TAG = "control"
    }
}
