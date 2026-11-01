package com.aegis.messenger.call

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.CallCrypto
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender

/**
 * Кадровый E2EE-слой медиазвонка поверх DTLS-SRTP.
 *
 * Предсобранный `io.github.webrtc-sdk` (та же `libjingle_peerconnection_so.so`,
 * что и PeerConnection) уже содержит `FrameCryptor` / insertable streams.
 * Навешивать собственный C++ `FrameEncryptorInterface` из отдельного `.so`
 * нельзя: prebuilt libjingle вызывает AddRef/Encrypt по чужому vtable и падает
 * SIGSEGV. Здесь криптор живёт В ТОМ ЖЕ native-образе, что и WebRTC, — ABI
 * совпадает.
 *
 * Алгоритм на проводе — AES-128-GCM (AEAD). Корневой материал — 32 байта из
 * HKDF-SHA512 Signal+PSK, но native `KeyProvider` webrtc-sdk M125 прогоняет его
 * через PBKDF2-SHA256 (100 000 итераций) до 128 бит и выбирает AES по длине
 * ключа (`SetKeyFromMaterial` → `DeriveKeys(…, 128)`), поэтому AES-256 этим
 * API не получить. Ключей кадрового слоя нет в DTLS. Rust-AEAD `frame.rs`
 * остаётся эталоном формата и покрыт тестами.
 *
 * Имена участников на проводе стабильны по НАПРАВЛЕНИЮ (`aegis-i2r` /
 * `aegis-r2i`), а не «local/remote»: `FrameCryptorTransformer` берёт ключ
 * по `participant_id` криптора. «local» на обеих сторонах шифрует разными
 * ключами под одной меткой и даёт тишину при живом ICE.
 *
 * Fail-closed: [discardFrameWhenCryptorNotReady] отбрасывает кадр без ключа;
 * сбой навески завершает звонок в [CallManager], а не откатывается на
 * «только DTLS-SRTP».
 */
internal class CallFrameCryptor {

    private var keyProvider: FrameCryptorKeyProvider? = null
    private val cryptors = ArrayList<FrameCryptor>(4)
    private val encryptedSenders = HashSet<String>()
    private val decryptedReceivers = HashSet<String>()
    private var sendParticipant: String = PARTICIPANT_I2R
    private var recvParticipant: String = PARTICIPANT_R2I

    val attached: Boolean
        get() = encryptedSenders.isNotEmpty() && keyProvider != null

    /**
     * Ставит AES-GCM на все текущие sender/receiver. Идемпотентно: повтор
     * с теми же ключами пересоздаёт крипторы (смена ключей звонка не бывает,
     * но ICE restart не должен копить native-объекты).
     *
     * [initiator] выбирает, какой стабильный participant id висит на send
     * (i2r) и recv (r2i). [CallCrypto.CallKeys] уже свапнуты той же ролью.
     */
    fun apply(
        factory: PeerConnectionFactory,
        pc: PeerConnection,
        keys: CallCrypto.CallKeys,
        initiator: Boolean,
    ): Boolean {
        dispose()
        if (keys.sendKey.size != KEY_LEN || keys.recvKey.size != KEY_LEN) {
            DiagnosticLog.e(DIAG_TAG) {
                "кадровый слой: длина ключа send=${keys.sendKey.size} recv=${keys.recvKey.size}"
            }
            return false
        }
        sendParticipant = if (initiator) PARTICIPANT_I2R else PARTICIPANT_R2I
        recvParticipant = if (initiator) PARTICIPANT_R2I else PARTICIPANT_I2R
        val provider = runCatching {
            FrameCryptorFactory.createFrameCryptorKeyProvider(
                /* sharedKey = */ false,
                RATCHET_SALT,
                /* ratchetWindowSize = */ 0,
                ByteArray(0),
                /* failureTolerance = */ -1,
                /* keyRingSize = */ 1,
                /* discardFrameWhenCryptorNotReady = */ true,
            )
        }.getOrElse {
            DiagnosticLog.e(DIAG_TAG, it) { "кадровый слой: KeyProvider не создан" }
            return false
        }
        val sendOk = runCatching { provider.setKey(sendParticipant, 0, keys.sendKey) }
            .getOrDefault(false)
        val recvOk = runCatching { provider.setKey(recvParticipant, 0, keys.recvKey) }
            .getOrDefault(false)
        if (!sendOk || !recvOk) {
            DiagnosticLog.e(DIAG_TAG) { "кадровый слой: setKey send=$sendOk recv=$recvOk" }
            runCatching { provider.dispose() }
            return false
        }
        keyProvider = provider
        var senders = 0
        for (sender in pc.senders) {
            if (attachSender(factory, sender, provider)) senders++
        }
        for (receiver in pc.receivers) {
            attachReceiver(factory, receiver, provider)
        }
        val ok = senders > 0
        DiagnosticLog.event(
            DIAG_TAG, "кадровый слой",
            "доступен" to true,
            "навешен" to ok,
            "sender" to senders,
            "receiver" to decryptedReceivers.size,
            "ключБайт" to keys.sendKey.size,
            "алгоритм" to "AES-128-GCM",
            "sendId" to sendParticipant,
            "recvId" to recvParticipant,
        )
        return ok
    }

    fun attachReceiverIfReady(factory: PeerConnectionFactory, receiver: RtpReceiver?): Boolean {
        val provider = keyProvider ?: return false
        return attachReceiver(factory, receiver, provider)
    }

    fun dispose() {
        for (c in cryptors) {
            runCatching { c.setEnabled(false) }
            runCatching { c.setObserver(null) }
            runCatching { c.dispose() }
        }
        cryptors.clear()
        encryptedSenders.clear()
        decryptedReceivers.clear()
        runCatching { keyProvider?.dispose() }
        keyProvider = null
    }

    private fun attachSender(
        factory: PeerConnectionFactory,
        sender: RtpSender,
        provider: FrameCryptorKeyProvider,
    ): Boolean {
        val id = sender.id().orEmpty()
        if (id.isNotEmpty() && id in encryptedSenders) return true
        val cryptor = runCatching {
            FrameCryptorFactory.createFrameCryptorForRtpSender(
                factory,
                sender,
                sendParticipant,
                FrameCryptorAlgorithm.AES_GCM,
                provider,
            )
        }.getOrElse {
            DiagnosticLog.e(DIAG_TAG, it) { "кадровый слой: encryptor не создан" }
            return false
        }
        cryptor.setKeyIndex(0)
        cryptor.setObserver(stateObserver)
        cryptor.setEnabled(true)
        cryptors.add(cryptor)
        if (id.isNotEmpty()) encryptedSenders.add(id)
        return true
    }

    private fun attachReceiver(
        factory: PeerConnectionFactory,
        receiver: RtpReceiver?,
        provider: FrameCryptorKeyProvider,
    ): Boolean {
        val r = receiver ?: return false
        val id = r.id().orEmpty()
        if (id.isNotEmpty() && id in decryptedReceivers) return true
        val cryptor = runCatching {
            FrameCryptorFactory.createFrameCryptorForRtpReceiver(
                factory,
                r,
                recvParticipant,
                FrameCryptorAlgorithm.AES_GCM,
                provider,
            )
        }.getOrElse {
            DiagnosticLog.e(DIAG_TAG, it) { "кадровый слой: decryptor не создан" }
            return false
        }
        cryptor.setKeyIndex(0)
        cryptor.setObserver(stateObserver)
        cryptor.setEnabled(true)
        cryptors.add(cryptor)
        if (id.isNotEmpty()) decryptedReceivers.add(id)
        return true
    }

    private companion object {
        const val DIAG_TAG = "webrtc"
        const val KEY_LEN = 32
        /** Инициатор → отвечающий: один id на обеих сторонах этого направления. */
        const val PARTICIPANT_I2R = "aegis-i2r"
        /** Отвечающий → инициатор. */
        const val PARTICIPANT_R2I = "aegis-r2i"
        val RATCHET_SALT: ByteArray = "aegis/call/frame-cryptor/v1".toByteArray(Charsets.US_ASCII)

        val stateObserver = FrameCryptor.Observer { participantId, newState ->
            when (newState) {
                FrameCryptor.FrameCryptionState.OK,
                FrameCryptor.FrameCryptionState.NEW,
                -> DiagnosticLog.d(DIAG_TAG) {
                    "кадровый слой состояние id=$participantId state=$newState"
                }
                else -> DiagnosticLog.w(DIAG_TAG) {
                    "кадровый слой состояние id=$participantId state=$newState"
                }
            }
        }
    }
}
