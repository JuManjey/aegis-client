package com.aegis.messenger.call

import com.aegis.core.common.call.CallMedia
import com.aegis.crypto.CallCrypto
import org.webrtc.EglBase
import org.webrtc.VideoSink

/**
 * ICE-кандидат в транспорт-независимом виде (строки), чтобы сигналинг ([CallSignaling])
 * и оркестратор ([CallManager]) не зависели от типов `org.webrtc`.
 */
data class CallIce(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
) {
    /** Компактная строка для передачи в [com.aegis.core.common.call.CallControl.ice]. */
    fun encode(): String = "$sdpMid|$sdpMLineIndex|$candidate"

    companion object {
        fun decode(s: String): CallIce? {
            val parts = s.split("|", limit = 3)
            if (parts.size != 3) return null
            val idx = parts[1].toIntOrNull() ?: return null
            return CallIce(parts[0], idx, parts[2])
        }
    }
}

/**
 * ICE-сервер в транспорт-независимом виде (§7.5). В режиме Tor [uri] указывает
 * на локальный форвардер (`turn:127.0.0.2:<port>?transport=tcp`), который
 * туннелирует TCP в onion coturn; без Tor — клирнет `stun:`/`turn:`/`turns:`
 * своего сервера. [username]/[credential] — эфемерные креды от релея
 * (`GET /v1/turn`); у STUN они пустые.
 */
data class IceTurnServer(
    val uri: String,
    val username: String,
    val credential: String,
)

/**
 * Движок медиапотока звонка (P8, §7.5). Абстрагирует WebRTC (DTLS-SRTP) так,
 * чтобы остальной код звонков не зависел от `org.webrtc` напрямую и мог
 * работать даже без нативного WebRTC (тогда используется [PlaceholderMediaEngine]).
 *
 * Порядок для инициатора:  [startAsCaller] → (отправить offer) → [onRemoteAnswer]
 * Порядок для принимающего: [startAsCallee] → (отправить answer)
 * ICE-кандидаты идут в обе стороны через [Listener.onLocalIce] / [onRemoteIce].
 *
 * SDP содержит DTLS-фингерпринт (`a=fingerprint`), который [CallManager]
 * извлекает и подмешивает в вывод ключей звонка (привязка к каналу, §10.10).
 */
interface MediaEngine {
    /** Реальный ли это WebRTC-движок (false для заглушки без медиа). */
    val isReal: Boolean

    /**
     * Общий OpenGL-контекст WebRTC для инициализации `SurfaceViewRenderer` в UI
     * (видеозвонок, §7.5). `null`, если движок без видео ([PlaceholderMediaEngine])
     * или нативный WebRTC недоступен — тогда UI не показывает видеоповерхности.
     */
    val eglBaseContext: EglBase.Context?

    var listener: Listener?

    /**
     * Событийная обратная связь от движка.
     *
     * Методы состояния транспорта объявлены с реализацией по умолчанию: они
     * нужны только ленте этапов в UI, и заглушка [PlaceholderMediaEngine], у
     * которой транспорта нет, не должна их реализовывать.
     */
    interface Listener {
        fun onLocalIce(candidate: CallIce)
        fun onConnected()
        fun onFailed(reason: String)
        fun onEnded()

        /** Смена состояния ICE (`CHECKING`/`DISCONNECTED`/…) для этапов в UI. */
        fun onIceState(state: String) {}

        /**
         * Выбрана пара кандидатов: по типам видно, идёт ли медиа напрямую
         * (`host`/`srflx`) или через ретранслятор (`relay`).
         */
        fun onIcePath(localType: String, remoteType: String) {}
    }

    /** Готовит исходящий звонок; возвращает локальный SDP-offer. */
    suspend fun startAsCaller(media: CallMedia, mediaOverTor: Boolean): String

    /** Принимает входящий звонок по SDP-offer собеседника; возвращает SDP-answer. */
    suspend fun startAsCallee(
        media: CallMedia,
        remoteOfferSdp: String,
        mediaOverTor: Boolean,
    ): String

    /** Инициатор применяет SDP-answer собеседника. */
    suspend fun onRemoteAnswer(sdp: String)

    /** Добавляет удалённый ICE-кандидат. */
    fun onRemoteIce(candidate: CallIce)

    /**
     * Передаёт согласованные ключи звонка (§10.10) в медиадвижок.
     * Реальный движок навешивает кадровый AEAD (AES-GCM на ключах Signal+PSK)
     * поверх DTLS-SRTP через `FrameCryptor` (insertable streams). `true` —
     * слой включён; `false` — заглушка или сбой (тогда реальный движок
     * завершает звонок, а не отдаёт медиа без кадрового слоя).
     * [initiator] задаёт стабильные id направления `aegis-i2r` / `aegis-r2i`.
     */
    suspend fun applyDerivedKeys(keys: CallCrypto.CallKeys, initiator: Boolean): Boolean

    /** Включает/выключает микрофон. */
    fun setMuted(muted: Boolean)

    // --- Видео (§7.5) -------------------------------------------------------

    /**
     * Приёмник кадров ЛОКАЛЬНОЙ камеры (предпросмотр в UI). Передайте
     * `SurfaceViewRenderer` (он реализует [VideoSink]) или `null`, чтобы отвязать.
     * No-op для аудиозвонка/заглушки.
     */
    fun setLocalVideoSink(sink: VideoSink?)

    /**
     * Приёмник кадров УДАЛЁННОГО видео (видео собеседника). Кадры пойдут в него,
     * как только появится входящая видеодорожка. No-op для аудио/заглушки.
     */
    fun setRemoteVideoSink(sink: VideoSink?)

    /** Включает/выключает передачу локального видео (камеры) во время звонка. */
    fun setVideoEnabled(enabled: Boolean)

    /** Переключает фронтальную/тыловую камеру (только для видеозвонка). */
    fun switchCamera()

    /** Завершает звонок и освобождает ресурсы. */
    fun close()
}
