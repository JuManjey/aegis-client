package com.aegis.messenger.call

import com.aegis.core.common.call.CallMedia
import com.aegis.crypto.CallCrypto

/**
 * Заглушка медиадвижка, когда нативный WebRTC недоступен (не подтянулась
 * библиотека или инициализация не удалась). Позволяет пройти согласование
 * ключей/сигналинг и показать UI звонка, но РЕАЛЬНОГО медиапотока нет.
 *
 * Аналог [com.aegis.network.tor.FakeTorTransport] для Tor: приложение всегда
 * собирается и не падает, а недоступность честно отражается в UI ([isReal]).
 *
 * Генерирует минимальный псевдо-SDP с DTLS-фингерпринтом, чтобы [CallManager]
 * мог отработать привязку ключей к «транскрипту» согласования.
 */
class PlaceholderMediaEngine : MediaEngine {
    override val isReal: Boolean = false
    override val eglBaseContext: org.webrtc.EglBase.Context? = null
    override var listener: MediaEngine.Listener? = null

    private val localFp: String = fakeFingerprint()

    override suspend fun startAsCaller(
        media: CallMedia,
        mediaOverTor: Boolean,
    ): String = stubSdp("offer")

    override suspend fun startAsCallee(
        media: CallMedia,
        remoteOfferSdp: String,
        mediaOverTor: Boolean,
    ): String = stubSdp("answer")

    override suspend fun onRemoteAnswer(sdp: String) {
        // Нет транспорта: не имитируем onConnected(). UI честно остаётся в
        // CONNECTING с предупреждением [isReal]=false; сторож завершит звонок.
    }

    override fun onRemoteIce(candidate: CallIce) { /* нет транспорта */ }

    override suspend fun applyDerivedKeys(keys: CallCrypto.CallKeys, initiator: Boolean): Boolean = false

    override fun setMuted(muted: Boolean) { /* нет медиапотока */ }

    override fun setLocalVideoSink(sink: org.webrtc.VideoSink?) { /* нет камеры */ }

    override fun setRemoteVideoSink(sink: org.webrtc.VideoSink?) { /* нет видео */ }

    override fun setVideoEnabled(enabled: Boolean) { /* нет камеры */ }

    override fun switchCamera() { /* нет камеры */ }

    override fun close() {
        listener = null
    }

    private fun stubSdp(type: String): String =
        "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=aegis-$type-stub\r\n" +
            "a=fingerprint:sha-256 $localFp\r\n"

    private fun fakeFingerprint(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString(":") { "%02X".format(it) }
    }
}
