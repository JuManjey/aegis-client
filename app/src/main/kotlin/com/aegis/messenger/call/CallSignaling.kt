package com.aegis.messenger.call

import android.util.Base64
import com.aegis.core.common.call.CallControl
import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.wire.WireChannel
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.messaging.RelayGateway
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.messenger.messaging.WireJson
import com.aegis.messenger.psk.PskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Сигналинг звонка (P8, §7.5). Управляющие сообщения ([CallControl]) идут ТЕМ ЖЕ
 * защищённым конвейером, что и текст: слой A (каскад на PSK) → слой B (Signal) →
 * Tor → onion-релей. По сети уходит только двойной шифртекст — SDP, ICE и корень
 * ключей звонка (`callSecret`) серверу/сети не видны (§7.6, zero-trust).
 *
 * Мультидевайс (§7.1.1, `docs/multidevice.md` §4.1/§6, follow-up №2.2): сигналинг
 * доставляется per-device fan-out'ом — свой конверт (свой ratchet, MD-1) в
 * per-device подканал v2 КАЖДОГО активного устройства контакта (звонок «поднимает»
 * все устройства собеседника), тем же [RelayGateway.postToPeerDevices], что и
 * переписка. У каждого устройства свой ящик и свой ack ⇒ нет гонки деструктивного
 * ack на общем v1-mailbox (§9/§10 док).
 *
 * Отправка ([send]) работает локально (E2EE) и best-effort доставляет конверты в
 * релей через Tor SOCKS. Метод [onWire] расшифровывает входящий конверт B→A по
 * ratchet устройства-отправителя (`senderDevice`) и возвращает [CallControl] для
 * [CallManager] (вызывается из приёмного цикла релея, §12.2, либо в тестах).
 */
class CallSignaling(
    private val sessionManager: SignalSessionManager,
    private val pskManager: PskManager,
    private val relayGateway: RelayGateway,
    /** device id ЭТОГО устройства — уходит в конверт как `senderDevice` (§6). */
    private val localDeviceIdProvider: () -> Int = { 1 },
    private val json: Json = WireJson,
) {
    /**
     * Шифрует и best-effort отправляет управляющее сообщение звонка контакту.
     * Слой A применяется один раз, затем per-device fan-out (§6): своя Signal-
     * сессия и свой шифртекст в per-device подканал v2 каждого активного
     * устройства контакта. Устройства без сессии/бандла пропускаются.
     */
    suspend fun send(contact: StoredContact, control: CallControl) = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(CallControl.serializer(), control)
            .toByteArray(Charsets.UTF_8)
        // Слой A (§5): каскад на PSK контакта, если он установлен.
        val cascade = pskManager.cipherFor(contact.contact.id)
        val layerAUsed = cascade != null
        val inner = cascade?.encrypt(plaintext) ?: plaintext
        val myDevice = localDeviceIdProvider()

        val envelopes = HashMap<Int, ByteArray>()
        for (device in contact.activeDevices()) {
            // Слой B (§6.1): свой конверт Signal на устройство (MD-1).
            val envelope = try {
                sessionManager.encryptTo(contact.contact.id, device.deviceId, device.bundleJson, inner, device.identityB64)
            } catch (_: Exception) {
                continue
            }
            val wire = CallWireEnvelope(
                type = envelope.type,
                layerA = layerAUsed,
                ciphertextB64 = Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP),
                senderDevice = myDevice,
            )
            envelopes[device.deviceId] = json.encodeToString(CallWireEnvelope.serializer(), wire)
                .toByteArray(Charsets.UTF_8)
        }
        if (envelopes.isEmpty()) {
            DiagnosticLog.w(DIAG_TAG) {
                "сигналинг не ушёл тип=${control.type} контакт=${DiagnosticLog.shortId(contact.contact.id)}"
            }
            return@withContext
        }
        DiagnosticLog.sync(
            DIAG_TAG, "сигналинг исходящий",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "тип" to control.type,
            "callId" to DiagnosticLog.fp(control.callId),
            "слойA" to layerAUsed,
            "устройств" to envelopes.size,
            "естьSdp" to !control.sdp.isNullOrEmpty(),
            "естьIce" to !control.ice.isNullOrEmpty(),
            *CallTrace.sdpFields(control.sdp.orEmpty()),
        )
        // Тот же per-device конвейер, что и текст: mailbox устройства из PSK (§12.2).
        relayGateway.postToPeerDevices(contact, envelopes)
    }

    /**
     * Расшифровывает входящий конверт сигналинга (B→A) и возвращает [CallControl].
     * Вызывается приёмным циклом релея (backend, §12.2) либо в тестах.
     */
    suspend fun onWire(contact: StoredContact, wireJson: String): CallControl =
        withContext(Dispatchers.IO) {
            val wire = json.decodeFromString(CallWireEnvelope.serializer(), wireJson)
            pskManager.requireLayerA(contact.contact.id, wire.layerA)
            val envelope = Base64.decode(wire.ciphertextB64, Base64.NO_WRAP)
            // Устройство-отправитель — из конверта (§6): свой ratchet на устройство
            // (MD-1). Откат на первичное для старого формата (senderDevice=0).
            val senderDevice = wire.senderDevice.takeIf { it in 1..255 } ?: contact.remoteDevice
            val inner = sessionManager.decryptFrom(
                contact.contact.id, senderDevice, envelope, wire.type, contact.identityFor(senderDevice),
            )
            val plaintext = if (wire.layerA) {
                val cascade = pskManager.cipherFor(contact.contact.id)
                    ?: error("Нет PSK для расшифровки слоя A сигналинга")
                cascade.decrypt(inner)
            } else {
                inner
            }
            val control = json.decodeFromString(CallControl.serializer(), String(plaintext, Charsets.UTF_8))
            DiagnosticLog.sync(
                DIAG_TAG, "сигналинг входящий",
                "контакт" to DiagnosticLog.shortId(contact.contact.id),
                "тип" to control.type,
                "callId" to DiagnosticLog.fp(control.callId),
                "слойA" to wire.layerA,
                "устройство" to senderDevice,
                "естьSdp" to !control.sdp.isNullOrEmpty(),
                "естьIce" to !control.ice.isNullOrEmpty(),
                *CallTrace.sdpFields(control.sdp.orEmpty()),
            )
            control
        }

    /** Конверт сигналинга звонка: только шифртекст, без секретов (§7.6). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class CallWireEnvelope(
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
        val channel: String = WireChannel.CALL,
        val v: Int = 1,
    )

    private companion object {
        const val DIAG_TAG = "call-sig"
    }
}
