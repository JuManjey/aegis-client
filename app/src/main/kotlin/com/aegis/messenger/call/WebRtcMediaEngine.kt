package com.aegis.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.diag.ProcSelf
import com.aegis.crypto.CallCrypto
import com.aegis.messenger.diag.DiagnosticNative
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AddIceObserver
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CryptoOptions
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtcCertificatePem
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Реальный медиадвижок звонка поверх WebRTC (DTLS-SRTP), P8 §7.5/§10.11.
 *
 * Мы НЕ пишем свой медиа-стек (§1) — используем аудированный WebRTC
 * (`io.github.webrtc-sdk`). Звук передаётся всегда; для видеозвонка поднимается
 * захват с камеры (Camera2, 720p) и добавляется видеодорожка.
 *
 * Потоки: все вызовы в native PeerConnection идут через выделенный HandlerThread
 * с Looper. Создание/dispose с Dispatchers.IO без Looper и повторный dispose по
 * ICE DISCONNECTED на Samsung/Android 14+ роняли процесс (SIGSEGV, без Java
 * stack в журнале).
 */
class WebRtcMediaEngine(
    appContext: Context,
    /** Маршрут медиа для режима сети ([CallIcePlan.forMode]). */
    private val plan: CallIcePlan,
) : MediaEngine {

    private val appContext = appContext.applicationContext

    /**
     * ICE-серверы для WebRTC из [plan]: в режиме Tor — только локальный
     * форвардер `turn:127.0.0.2:<port>?transport=tcp`
     * ([com.aegis.messenger.call.TurnProvisioner]); без Tor — STUN и TURN своего
     * сервера напрямую.
     */
    private val turnIceServers: List<PeerConnection.IceServer> = plan.servers.map { s ->
        val host = CallTrace.iceUriHost(s.uri)
        PeerConnection.IceServer.builder(s.uri)
            .setUsername(s.username)
            .setPassword(s.credential)
            .apply {
                // Hostname фиксирует семейство адреса для ЛОКАЛЬНОГО форвардера:
                // иначе native резолвит IPv6 и TurnPort падает, когда форвардер
                // слушает только IPv4. Клирнет-серверам это не нужно — пусть DNS
                // решает сам, какая запись доступна.
                if (host.startsWith("127.")) setHostname(host)
            }
            .createIceServer()
    }

    /**
     * Только TURN-серверы (без STUN). Нужен там, где решение зависит именно от
     * наличия РЕТРАНСЛЯТОРА: режим RELAY, ожидание relay-кандидата, поля
     * диагностики. Смешивать нельзя — при STUN без TURN список серверов не пуст,
     * а ретранслятора нет.
     */
    private val turnOnlyUris: List<String> = plan.servers.map { it.uri }.filterNot { isStunUri(it) }

    /** Список STUN-адресов (для диагностики; в ICE они уже попали выше). */
    private val stunUris: List<String> = plan.servers.map { it.uri }.filter { isStunUri(it) }
    override val isReal: Boolean = true
    override var listener: MediaEngine.Listener? = null

    // Читаются не только с потока движка: PcObserver приходит с signaling_thread,
    // close() может прийти с любого. Без @Volatile это гонка за публикацию ссылки.
    @Volatile private var peerConnection: PeerConnection? = null
    /** Держим observer живым: native ICE-колбэки идут по JNI-указателю. */
    @Volatile private var pcObserver: PeerConnection.Observer? = null
    private var audioSource: AudioSource? = null
    @Volatile private var audioTrack: AudioTrack? = null

    // --- Видео (§7.5) -------------------------------------------------------
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    /** Приёмники кадров, заданные UI. Хранятся, чтобы привязать к дорожкам, как
     *  только те появятся (локальная — при createPeerConnection, удалённая — в
     *  onAddTrack). */
    @Volatile private var localSink: VideoSink? = null
    @Volatile private var remoteSink: VideoSink? = null

    @Volatile private var closed = false

    /**
     * Держит ли движок аудиосессию ([CallAudioSession]). Нужен именно флаг, а не
     * «просто release в close()»: если звонок упал до [startCallAudio] (отказ
     * FGS, сбой TURN), сессия не захватывалась, и лишний release сбил бы счётчик
     * ссылок — вместе с ним смолкли бы «гудки» другого владельца.
     */
    @Volatile private var audioSessionHeld = false

    /** Удалённый ICE, пришедший до createPeerConnection. */
    private val earlyRemoteIce = ArrayList<CallIce>()

    /**
     * Сигнал «есть relay-кандидат или gathering COMPLETE».
     * Пишется с потока движка, countDown() зовут колбэки с signaling_thread —
     * без @Volatile сигнал мог потеряться и звонок висел до таймаута.
     */
    @Volatile private var iceReadyLatch: CountDownLatch? = null

    @Volatile private var gatheredRelay = false

    /** Кадровый AEAD поверх DTLS-SRTP (FrameCryptor той же .so, что WebRTC). */
    private val frameCryptor = CallFrameCryptor()

    /**
     * Сколько раз пересобирали ICE в этом звонке. Обнуляется при возврате в
     * CONNECTED: длинный разговор с двумя переключениями сети — это две
     * ОТДЕЛЬНЫЕ починки, а не приближение к лимиту.
     */
    private val iceRestarts = java.util.concurrent.atomic.AtomicInteger(0)

    override val eglBaseContext: EglBase.Context?
        get() = synchronized(WebRtcMediaEngine::class.java) { eglBase?.eglBaseContext }

    // --- Публичный API ------------------------------------------------------

    override suspend fun startAsCaller(
        media: CallMedia,
        mediaOverTor: Boolean,
    ): String {
        nativeVerboseWindow = true
        nativeLines.set(0)
        try {
            DiagnosticLog.step(
                DIAG_TAG, "движок старт",
                "роль" to "caller",
                "медиа" to media.name,
                "tor" to mediaOverTor,
                "turn" to turnOnlyUris.size,
                "stun" to stunUris.size,
                *turnShapeFields(),
                *processSnapshotFields(),
            )
            onEngineThread {
                createPeerConnection(media, mediaOverTor)
                addLocalMedia(media)
            }
            val offer = createSdp(isOffer = true, video = media == CallMedia.VIDEO, receiveAudio = true)
            setLocalDescriptionLogged(offer, "offer")
            onEngineThread { startCallAudio() }
            val sdp = awaitLocalDescription(mediaOverTor)
            DiagnosticLog.sync(DIAG_TAG, "движок offer готов", *CallTrace.sdpFields(sdp), "relay" to gatheredRelay)
            return sdp
        } finally {
            nativeVerboseWindow = false
        }
    }

    override suspend fun startAsCallee(
        media: CallMedia,
        remoteOfferSdp: String,
        mediaOverTor: Boolean,
    ): String {
        nativeVerboseWindow = true
        nativeLines.set(0)
        try {
            DiagnosticLog.step(
                DIAG_TAG, "движок старт",
                "роль" to "callee",
                "медиа" to media.name,
                "tor" to mediaOverTor,
                "turn" to turnOnlyUris.size,
                "stun" to stunUris.size,
                *turnShapeFields(),
                *CallTrace.sdpFields(remoteOfferSdp),
                *processSnapshotFields(),
            )
            onEngineThread {
                createPeerConnection(media, mediaOverTor)
            }
            onEngineThread { addLocalMedia(media) }
            setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp))
            val answer = createSdp(isOffer = false, video = media == CallMedia.VIDEO, receiveAudio = true)
            setLocalDescriptionLogged(answer, "answer")
            onEngineThread { startCallAudio() }
            val sdp = awaitLocalDescription(mediaOverTor)
            DiagnosticLog.sync(DIAG_TAG, "движок answer готов", *CallTrace.sdpFields(sdp), "relay" to gatheredRelay)
            return sdp
        } finally {
            nativeVerboseWindow = false
        }
    }

    override suspend fun onRemoteAnswer(sdp: String) {
        setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun onRemoteIce(candidate: CallIce) {
        postOnEngineThread {
            if (closed) return@postOnEngineThread
            val pc = peerConnection
            if (pc == null) {
                earlyRemoteIce.add(candidate)
                DiagnosticLog.event(
                    DIAG_TAG, "удалённый ICE отложен",
                    "тип" to iceCandidateType(candidate.candidate),
                )
                return@postOnEngineThread
            }
            addRemoteIce(pc, candidate)
        }
    }

    override suspend fun applyDerivedKeys(keys: CallCrypto.CallKeys, initiator: Boolean): Boolean {
        // Ключи нужны UI для SAS и кадровому слою. C++ FrameEncryptor из
        // libaegis_frame.so навешивать нельзя (чужой vtable → SIGSEGV). Здесь
        // используется FrameCryptor, скомпилированный в ту же
        // libjingle_peerconnection_so.so, что и PeerConnection: AES-GCM на
        // ключах Signal+PSK, которых нет в DTLS.
        return onEngineThread {
            val pc = peerConnection
            val factory = sharedFactory
            if (pc == null || factory == null) {
                DiagnosticLog.w(DIAG_TAG) { "кадровый слой: нет PeerConnection/factory" }
                false
            } else {
                frameCryptor.apply(factory, pc, keys, initiator)
            }
        }
    }

    override fun setMuted(muted: Boolean) {
        postOnEngineThread { audioTrack?.setEnabled(!muted) }
    }

    override fun setLocalVideoSink(sink: VideoSink?) {
        postOnEngineThread {
            val prev = localSink
            localSink = sink
            localVideoTrack?.let { track ->
                if (prev != null && prev !== sink) runCatching { track.removeSink(prev) }
                if (sink != null) runCatching { track.addSink(sink) }
            }
        }
    }

    override fun setRemoteVideoSink(sink: VideoSink?) {
        postOnEngineThread {
            val prev = remoteSink
            remoteSink = sink
            remoteVideoTrack?.let { track ->
                if (prev != null && prev !== sink) runCatching { track.removeSink(prev) }
                if (sink != null) runCatching { track.addSink(sink) }
            }
        }
    }

    override fun setVideoEnabled(enabled: Boolean) {
        postOnEngineThread {
            localVideoTrack?.setEnabled(enabled)
            runCatching {
                if (enabled) {
                    videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
                } else {
                    videoCapturer?.stopCapture()
                }
            }
        }
    }

    override fun switchCamera() {
        postOnEngineThread { (videoCapturer as? CameraVideoCapturer)?.switchCamera(null) }
    }

    override fun close() {
        listener = null
        if (Looper.myLooper() == webrtcHandler().looper) {
            closeInternal()
            return
        }
        val done = CountDownLatch(1)
        val posted = webrtcHandler().post {
            try {
                closeInternal()
            } finally {
                done.countDown()
            }
        }
        if (!posted) {
            closeInternal()
            return
        }
        if (!done.await(5, TimeUnit.SECONDS)) {
            Log.w(TAG, "close: таймаут ожидания потока WebRTC")
            // Важный для расследования случай: поток движка застрял в нативном
            // вызове, PeerConnection остался живым, а вызывающий пойдёт закрывать
            // TURN-форвардер. Нативный TurnPort в этот момент ещё держит сокеты —
            // ровно та ситуация, из которой рождается use-after-free.
            DiagnosticLog.sync(
                DIAG_TAG, "close: таймаут ожидания потока WebRTC",
                "pcЖив" to (peerConnection != null),
                "стек" to DiagnosticLog.threadDump(),
                *processSnapshotFields(),
            )
        }
    }

    /**
     * Освобождает PeerConnection. Сначала PC, потом capturer/helper.
     * Дорожки, добавленные через addTrack, принадлежат PC — отдельный dispose
     * дорожки до dispose PC даёт native use-after-free.
     */
    private fun closeInternal() {
        if (closed) return
        closed = true
        listener = null
        DiagnosticLog.step(DIAG_TAG, "движок close", *processSnapshotFields())
        iceReadyLatch?.countDown()
        iceReadyLatch = null
        earlyRemoteIce.clear()
        frameCryptor.dispose()
        runCatching { videoCapturer?.stopCapture() }
        DiagnosticLog.step(DIAG_TAG, "pc.close")
        runCatching { peerConnection?.close() }
        DiagnosticLog.step(DIAG_TAG, "pc.dispose")
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        pcObserver = null
        DiagnosticLog.step(DIAG_TAG, "dispose capturer/источников")
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        remoteVideoTrack = null
        videoSource = null
        audioTrack = null
        audioSource = null
        localSink = null
        remoteSink = null
        leaveCallAudio()
        DiagnosticLog.step(DIAG_TAG, "движок закрыт", *processSnapshotFields())
    }

    // --- Внутреннее ---------------------------------------------------------

    private fun createPeerConnection(media: CallMedia, mediaOverTor: Boolean) {
        // RELAY с пустым списком серверов роняет network_thread (см. ниже), а ALL
        // собрал бы host-кандидаты и раскрыл бы IP собеседнику мимо Tor.
        check(!mediaOverTor || turnOnlyUris.isNotEmpty()) { "анонимный режим без TURN: медиа не поднимаем" }
        // Страховка к CallIcePlan: в режиме Tor любой сервер вне loopback-форвардера
        // (клирнет-TURN, STUN) означал бы прямое соединение с реального адреса.
        check(mediaOverTor == plan.mediaOverTor) { "режим медиа не совпадает с планом ICE" }
        check(!mediaOverTor || plan.servers.all(CallIcePlan::isTorForwarderTurn)) {
            "анонимный режим: ICE-сервер вне Tor-форвардера"
        }
        ensureFactoryInitialized(appContext, needVideo = media == CallMedia.VIDEO)
        val factory = sharedFactory ?: error("PeerConnectionFactory не инициализирован")
        gatheredRelay = false
        iceRestarts.set(0)
        iceReadyLatch = CountDownLatch(1)
        val observer = PcObserver()
        pcObserver = observer

        // Именно ретранслятор, а не «есть хоть какой-то ICE-сервер»: со включённым
        // прямым путём список может содержать только STUN.
        val hasTurn = turnOnlyUris.isNotEmpty()
        // RELAY + пустой iceServers даёт SIGSEGV (addr=0) на network_thread:
        // native Network* остаётся null. TURN должен быть уже здесь.
        DiagnosticLog.step(
            DIAG_TAG, "PeerConnection создание",
            "видео" to (media == CallMedia.VIDEO),
            "tor" to mediaOverTor,
            "turn" to turnOnlyUris.size,
                "stun" to stunUris.size,
            *turnShapeFields(),
            *processSnapshotFields(),
        )
        // Тайминги под RTT Tor — только для TURN через Tor; клирнет-TURN режима
        // без Tor работает с обычными таймингами WebRTC.
        var usedTorTimings = plan.torIceTimings && hasTurn
        var pc = factory.createPeerConnection(
            rtcConfiguration(mediaOverTor, torTimings = usedTorTimings),
            observer,
        )
        if (pc == null && usedTorTimings) {
            DiagnosticLog.w(DIAG_TAG) {
                "PeerConnection отклонён с ICE-таймингами Tor, повтор без них"
            }
            usedTorTimings = false
            pc = factory.createPeerConnection(
                rtcConfiguration(mediaOverTor, torTimings = false),
                observer,
            )
        }
        if (pc == null) {
            DiagnosticLog.e(DIAG_TAG) {
                "PeerConnection не создан turn=${turnIceServers.size} uri=${turnIceUris()}"
            }
            error("Не удалось создать PeerConnection")
        }
        runCatching { pc.setAudioRecording(false) }
        runCatching { pc.setAudioPlayout(false) }
        peerConnection = pc
        DiagnosticLog.sync(
            DIAG_TAG, "PeerConnection создан",
            "видео" to (media == CallMedia.VIDEO),
            "relay" to (mediaOverTor && hasTurn),
            "таймингиTor" to usedTorTimings,
            "turn" to turnOnlyUris.size,
                "stun" to stunUris.size,
            "turnLoopback" to turnUriIsLoopback(),
            "серт" to (sharedCertificate != null),
            "anyAddr" to true,
            "tcpHost" to (!mediaOverTor),
            // Раньше здесь стояло «synthetic-loopbackAlt», хотя синтетический
            // детектор при disableNetworkMonitor=true не используется вовсе —
            // строка отправляла разбор журнала по ложному следу. Пишем то, что
            // происходит на самом деле.
            "сети" to "getifaddrs+anyAddr, loopback исключён",
            "turnPrune" to "KEEP_FIRST_READY",
            // Явно фиксируем, что STUN-keepalive оставлен дефолтным: именно его
            // переопределение роняло setLocalDescription (см. applyTorIceTimings).
            "stunKeepalive" to "дефолт",
            *turnShapeFields(),
        )
    }

    private fun addLocalMedia(media: CallMedia) {
        val pc = peerConnection ?: return
        val factory = sharedFactory ?: return
        if (audioTrack != null) return
        DiagnosticLog.sync(DIAG_TAG, "аудиодорожка старт")
        // ПРОГРАММНАЯ обработка звука включена.
        //
        // Раньше здесь всё было выключено, и получался звонок без эхоподавления
        // вообще: на громкой связи собеседник слышал собственный голос обратно,
        // тихую речь никто не выравнивал, шум не подавлялся. По качеству это
        // заметно хуже любого нормального мессенджера.
        //
        // Причиной отключения были падения на Samsung, но виноваты там были
        // АППАРАТНЫЕ AEC/NS и источник VOICE_COMMUNICATION — они и остаются
        // выключенными (см. ADM ниже, там `setUseHardwareAcousticEchoCanceler(false)`).
        // Программная обработка живёт в audio processing module самого WebRTC,
        // к драйверам устройства отношения не имеет и работает на всех
        // платформах — это стандартный путь, которым идут все клиенты WebRTC.
        //
        // Детекция стука по клавиатуре осталась выключенной: она вырезает
        // фрагменты речи по эвристике, а для голосового звонка это хуже, чем
        // редкий щелчок.
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        val src = factory.createAudioSource(audioConstraints)
        DiagnosticLog.step(DIAG_TAG, "AudioSource создан")
        val track = factory.createAudioTrack("aegis_audio", src)
        track.setEnabled(false)
        DiagnosticLog.step(DIAG_TAG, "addTrack аудио")
        pc.addTrack(track, listOf(STREAM_ID))
        audioSource = src
        audioTrack = track
        DiagnosticLog.step(DIAG_TAG, "аудиодорожка добавлена", "включена" to track.enabled())
        if (media == CallMedia.VIDEO) {
            setupVideoCapture(factory)
        }
        val early = ArrayList(earlyRemoteIce)
        earlyRemoteIce.clear()
        early.forEach { addRemoteIce(pc, it) }
        DiagnosticLog.sync(
            DIAG_TAG, "медиадорожки добавлены",
            "видео" to (media == CallMedia.VIDEO),
            "раннийICE" to early.size,
        )
    }

    /**
     * Поднимает захват с камеры и добавляет локальную видеодорожку в соединение.
     * Best-effort: если камера недоступна/нет разрешения — звонок продолжается как
     * аудио (видео просто не появится), без падения.
     */
    private fun setupVideoCapture(factory: PeerConnectionFactory) {
        val egl = synchronized(WebRtcMediaEngine::class.java) { eglBase }
            ?: run { Log.w(TAG, "нет EglBase — видео пропущено"); return }
        val capturer = createCameraCapturer()
            ?: run { Log.w(TAG, "камера не найдена — видео пропущено"); return }
        runCatching {
            val helper = SurfaceTextureHelper.create("AegisCaptureThread", egl.eglBaseContext)
            val source = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            val vTrack = factory.createVideoTrack("aegis_video", source)
            vTrack.setEnabled(true)
            localSink?.let { runCatching { vTrack.addSink(it) } }
            peerConnection?.addTrack(vTrack, listOf(STREAM_ID))
            surfaceTextureHelper = helper
            videoSource = source
            videoCapturer = capturer
            localVideoTrack = vTrack
            Log.i(TAG, "локальное видео подключено")
        }.onFailure {
            Log.w(TAG, "не удалось поднять камеру: ${it.message}")
            runCatching { capturer.dispose() }
        }
    }

    /**
     * Базовый RTCConfiguration. Тайминги Tor должны проходить native
     * `P2PTransportChannel::ValidateIceConfig`, иначе
     * `createPeerConnection` возвращает null без Java-причины.
     */
    private fun rtcConfiguration(
        mediaOverTor: Boolean,
        torTimings: Boolean,
    ): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(turnIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (mediaOverTor) {
                PeerConnection.IceTransportsType.RELAY
            } else {
                PeerConnection.IceTransportsType.ALL
            }
            // TCP host-кандидаты не нужны: TURN уже TCP.
            tcpCandidatePolicy = if (mediaOverTor) {
                PeerConnection.TcpCandidatePolicy.DISABLED
            } else {
                PeerConnection.TcpCandidatePolicy.ENABLED
            }
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceCandidatePoolSize = 0
            // Запас буфера джиттера под путь через ретранслятор в Tor.
            //
            // Дефолт (50 пакетов ≈ 1 с) рассчитан на обычную сеть. Tor даёт
            // разброс задержек в сотни миллисекунд и пачки пакетов после
            // перестроения цепи: буфер переполняется, NetEq вынужденно
            // выбрасывает пакеты, и на слух это «металлические» щелчки и
            // проглоченные слоги. Это ПОТОЛОК, а не постоянная задержка: NetEq
            // адаптивен и на хорошем канале держит буфер минимальным, поэтому
            // прямому звонку запас ничего не стоит.
            if (turnOnlyUris.isNotEmpty()) {
                audioJitterBufferMaxPackets = TOR_JITTER_BUFFER_PACKETS
                // Быстрое «схлопывание» разросшегося буфера: после всплеска
                // задержки разговор возвращается к нормальной задержке за
                // секунды, а не тянет накопленное отставание до конца звонка.
                audioJitterBufferFastAccelerate = true
            }
            surfaceIceCandidatesOnIceTransportTypeChanged = false
            // Одна TURN-аллокация вместо одной на каждую сеть устройства.
            //
            // Без этого WebRTC поднимает TurnPort на КАЖДОЙ сети (в журнале с
            // мобильным интернетом и активным VPN их было четыре: lo, any, tun0,
            // rmnet_data0). Все четыре идут в один и тот же coturn через один и
            // тот же локальный форвардер, то есть дают четыре TCP-соединения
            // через Tor и восемь запросов Allocate (первый всегда 401 — так
            // устроена аутентификация TURN). Через Tor это единицы секунд на
            // каждый: именно отсюда бралась основная задержка установки звонка.
            // Смысла в четырёх аллокациях нет — они ведут в одну точку.
            turnPortPrunePolicy = PeerConnection.PortPrunePolicy.KEEP_FIRST_READY
            // Без Tor TURN — резерв, а не основной путь: политика остаётся ALL,
            // поэтому host/srflx-пары проверяются первыми и выигрывают по
            // приоритету, а relay подхватывается только когда прямого пути нет
            // (NAT, VPN, изоляция клиентов) или собеседник в режиме Tor (у него
            // только relay-кандидат, и пара получается relay↔relay внутри coturn).
            // Без ANY-address GetNetworks()=∅ оставляет Network*=null
            // и network_thread падает SIGSEGV addr=0 (журнал 20-02-53).
            enableIceGatheringOnAnyAddressPorts = true
            disableIPv6OnWifi = true
            maxIPv6Networks = 0
            sharedCertificate?.let { certificate = it }
            continualGatheringPolicy =
                if (mediaOverTor) PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                else PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            presumeWritableWhenFullyRelayed = mediaOverTor && turnOnlyUris.isNotEmpty()
            // Тайминги под RTT Tor — когда TURN идёт через Tor ([CallIcePlan.torIceTimings]).
            // С дефолтными таймингами WebRTC (iceUnwritableTimeMs ≈ 5 с) одна
            // заминка цепи Tor длиннее пяти секунд помечала соединение неписуемым,
            // и разговор рвался на ICE DISCONNECTED. Клирнет-TURN режима без Tor
            // так не задерживается, и тайминги там остаются стандартными.
            if (torTimings) applyTorIceTimings()
            cryptoOptions = CryptoOptions.builder()
                // GCM-наборы только В ДОПОЛНЕНИЕ к AES_CM_128_HMAC_SHA1_80: в WebRTC
                // M125 он стоит в списке первым, обе стороны предлагают одинаковый
                // список, и DTLS выбирает именно его. Java-API не даёт его выключить,
                // поэтому на проводе SRTP — AES-128-CM + HMAC-SHA1-80, а не GCM.
                .setEnableGcmCryptoSuites(true)
                // Слабый 32-битный тег HMAC-SHA1 не предлагаем.
                .setEnableAes128Sha1_32CryptoCipher(false)
                .setEnableEncryptedRtpHeaderExtensions(true)
                // FrameCryptor — это FrameTransformer (insertable streams), а не
                // FrameEncryptorInterface. requireFrameEncryption смотрит ТОЛЬКО
                // на FrameEncryptor: при true WebRTC молча дропает RTP, хотя
                // кадровый AEAD уже навешен. Fail-closed — сбой навески
                // FrameCryptor завершает звонок; discardFrameWhenCryptorNotReady
                // отбрасывает кадр без ключа.
                .setRequireFrameEncryption(false)
                .createCryptoOptions()
        }

    /**
     * ICE-тайминги под RTT Tor.
     *
     * ⚠️ `stunCandidateKeepaliveIntervalMs` ЗДЕСЬ ЗАДАВАТЬ НЕЛЬЗЯ — это гарантированный
     * SIGSEGV на первом же `setLocalDescription`. В `P2PTransportChannel::SetIceConfig`
     * (`p2p/base/p2p_transport_channel.cc`) соответствующая ветка выглядит так:
     *
     * ```cpp
     * if (config_.stun_keepalive_interval != config.stun_keepalive_interval) {
     *   config_.stun_keepalive_interval = config.stun_keepalive_interval;
     *   allocator_sessions_.back()->SetStunKeepaliveIntervalForReadyPorts(...);  // ← .back() без проверки
     *   RTC_LOG(LS_INFO) << "Set STUN keepalive interval to " << ...;
     * }
     * ```
     *
     * Первый `SetIceConfig` выполняется при создании ICE-транспорта ВНУТРИ
     * `setLocalDescription`, то есть ДО `MaybeStartGathering()`, когда
     * `allocator_sessions_` ещё пуст. `.back()` на пустом векторе даёт нулевой
     * указатель, дальше идёт загрузка vtable из него — `SIGSEGV addr=0` на
     * `network_thread`. Все остальные поля в этой функции либо обновляют
     * одиночный `ice_controller_`, либо проходят циклом по контейнеру (цикл по
     * пустому безопасен), поэтому падало именно на этом присваивании.
     *
     * Проверено на `libjingle_peerconnection_so.so` из `io.github.webrtc-sdk:android:125.6422.07`:
     * `P2PTransportChannel::SetIceConfig` = `+0x6b863c`, сбой на `+0x6b8e34`
     * (`ldr x8, [x0]` при `x0 = 0`), единственный незащищённый `.back()` во всей функции.
     * В журнале это видно как «есть все строки `Set …`, кроме
     * `Set STUN keepalive interval to`»: лог в этой ветке стоит ПОСЛЕ падающего вызова.
     *
     * Потери от удаления нет: 10 000 мс и так дефолт WebRTC для keepalive готовых
     * портов (`STUN_KEEPALIVE_INTERVAL`), то есть присваивание было
     * семантически пустым и при этом фатальным.
     */
    private fun PeerConnection.RTCConfiguration.applyTorIceTimings() {
        iceUnwritableTimeMs = TOR_ICE_UNWRITABLE_MS
        iceUnwritableMinChecks = TOR_ICE_UNWRITABLE_CHECKS
        iceCheckIntervalStrongConnectivityMs = TOR_ICE_CHECK_STRONG_MS
        iceCheckIntervalWeakConnectivityMs = TOR_ICE_CHECK_WEAK_MS
        iceCheckMinInterval = TOR_ICE_CHECK_MIN_MS
        iceConnectionReceivingTimeout = TOR_ICE_RECEIVING_MS
        stableWritableConnectionPingIntervalMs = TOR_ICE_KEEPALIVE_MS
    }

    private fun turnIceUris(): String = turnOnlyUris.joinToString()

    /**
     * STUN-ли это URI. Различие принципиально: STUN лишь сообщает абоненту его
     * адрес снаружи NAT и НЕ ретранслирует медиа, поэтому от него не зависят ни
     * режим RELAY, ни тайминги под задержки Tor, ни ожидание relay-кандидата.
     */
    private fun isStunUri(uri: String): Boolean = uri.startsWith("stun:", ignoreCase = true)

    private fun turnUriIsLoopback(): Boolean {
        val uri = turnOnlyUris.joinToString()
        return uri.contains("127.0.0.") || uri.contains("localhost")
    }

    /**
     * Поля диагностики по TURN-серверу. Берётся именно TURN, а не первый элемент
     * списка: с включённым прямым путём первым идёт STUN, и «форма URI» тогда
     * описывала бы не тот сервер, из-за которого разбирают журнал.
     */
    private fun turnShapeFields(): Array<Pair<String, Any?>> {
        val uri = turnOnlyUris.firstOrNull() ?: return arrayOf("turnUri" to "нет")
        return CallTrace.iceUriFields(uri)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator: CameraEnumerator = Camera2Enumerator(appContext)
        val names = enumerator.deviceNames
        (names.firstOrNull { enumerator.isFrontFacing(it) }
            ?: names.firstOrNull { enumerator.isBackFacing(it) }
            ?: names.firstOrNull())
            ?.let { return enumerator.createCapturer(it, null) }
        return null
    }

    private suspend fun createSdp(
        isOffer: Boolean,
        video: Boolean,
        receiveAudio: Boolean = true,
    ): SessionDescription =
        suspendCancellableCoroutine { cont ->
            postOnEngineThread {
                val kind = if (isOffer) "offer" else "answer"
                DiagnosticLog.step(
                    DIAG_TAG, "SDP create старт",
                    "вид" to kind,
                    "recvAudio" to receiveAudio,
                    "видео" to video,
                )
                val pc = peerConnection
                if (pc == null) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("нет PeerConnection"))
                    return@postOnEngineThread
                }
                val observer = object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        DiagnosticLog.sync(DIAG_TAG, "SDP create ок", "вид" to kind, *CallTrace.sdpFields(desc.description))
                        if (cont.isActive) cont.resume(desc)
                    }
                    override fun onCreateFailure(error: String?) {
                        DiagnosticLog.sync(DIAG_TAG, "SDP create ошибка", "вид" to kind, "ошибка" to (error ?: "null"))
                        if (cont.isActive) {
                            cont.resumeWithException(RuntimeException("SDP create: $error"))
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }
                val constraints = MediaConstraints().apply {
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveAudio", if (receiveAudio) "true" else "false"),
                    )
                    mandatory.add(
                        MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"),
                    )
                }
                if (isOffer) pc.createOffer(observer, constraints)
                else pc.createAnswer(observer, constraints)
            }
        }

    private suspend fun setLocalDescriptionLogged(desc: SessionDescription, label: String) {
        DiagnosticLog.sync(
            DIAG_TAG, "setLocalDescription старт",
            "этап" to label,
            "хукСигналов" to DiagnosticNative.rebind(),
            "sidecarFd" to DiagnosticNative.sidecarFd(),
            *CallTrace.sdpFields(desc.description),
            *processSnapshotFields(),
        )
        val watchdog = startWatchdog("setLocal", label)
        try {
            setLocalDescription(desc)
        } finally {
            watchdog.interrupt()
        }
        DiagnosticLog.sync(
            DIAG_TAG, "setLocalDescription ок",
            "этап" to label,
            *processSnapshotFields(),
        )
    }

    /**
     * Сторож нативного вызова: раз в [WATCHDOG_STEP_MS] пишет, что операция ещё
     * не вернулась, и чем занят процесс.
     *
     * Дешёвые снимки ([DiagnosticLog.threadBrief] — только имена и состояния)
     * идут на каждом шаге. Полный дамп со стеками останавливает виртуальную
     * машину, поэтому снимается только на [DUMP_AT_MS] — когда операция уже
     * очевидно зависла и искажать тайминги нечем. Раньше полный дамп снимался
     * каждые 100 мс прямо во время сборки ICE и сам по себе тормозил нативные
     * потоки WebRTC, которые мы пытались отладить.
     */
    private fun startWatchdog(operation: String, label: String): Thread =
        Thread({
            try {
                var elapsed = 0
                while (elapsed < WATCHDOG_LIMIT_MS) {
                    Thread.sleep(WATCHDOG_STEP_MS.toLong())
                    elapsed += WATCHDOG_STEP_MS
                    val deepDump = elapsed in DUMP_AT_MS
                    DiagnosticLog.sync(
                        DIAG_TAG, "$operation ждёт",
                        "этап" to label,
                        "мс" to elapsed,
                        "закрыт" to closed,
                        "relay" to gatheredRelay,
                        "потоки" to DiagnosticLog.threadBrief(),
                        *processSnapshotFields(),
                    )
                    if (deepDump) {
                        DiagnosticLog.sync(
                            DIAG_TAG, "$operation полный дамп",
                            "мс" to elapsed,
                            "стек" to DiagnosticLog.threadDump(),
                        )
                    }
                }
                DiagnosticLog.sync(
                    DIAG_TAG, "$operation не вернулся за отведённое время",
                    "этап" to label,
                    "мс" to WATCHDOG_LIMIT_MS,
                    "стек" to DiagnosticLog.threadDump(),
                )
            } catch (_: InterruptedException) {
            }
        }, "aegis-$operation-watch").apply {
            isDaemon = true
            start()
        }

    private suspend fun setLocalDescription(desc: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            postOnEngineThread {
                val pc = peerConnection
                if (pc == null) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("нет PeerConnection"))
                    return@postOnEngineThread
                }
                DiagnosticLog.step(
                    DIAG_TAG, "setLocal native вызов",
                    "поток" to Thread.currentThread().name,
                    "sdpБайт" to desc.description.length,
                )
                pc.setLocalDescription(setObserver(cont, "local"), desc)
                DiagnosticLog.step(DIAG_TAG, "setLocal native вернулся")
            }
        }

    private suspend fun setRemoteDescription(desc: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            postOnEngineThread {
                val pc = peerConnection
                if (pc == null) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("нет PeerConnection"))
                    return@postOnEngineThread
                }
                DiagnosticLog.step(
                    DIAG_TAG, "setRemote native вызов",
                    *CallTrace.sdpFields(desc.description),
                )
                pc.setRemoteDescription(setObserver(cont, "remote"), desc)
                DiagnosticLog.step(DIAG_TAG, "setRemote native вернулся")
            }
        }

    private fun setObserver(
        cont: kotlinx.coroutines.CancellableContinuation<Unit>,
        side: String,
    ): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {
                DiagnosticLog.sync(DIAG_TAG, "setSDP колбэк ок", "сторона" to side)
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                DiagnosticLog.sync(DIAG_TAG, "setSDP колбэк ошибка", "сторона" to side, "ошибка" to (error ?: "null"))
                if (cont.isActive) cont.resumeWithException(RuntimeException("setSDP: $error"))
            }
        }

    /**
     * Ждёт relay-кандидат (Tor) или первый кандидат / COMPLETE, затем отдаёт
     * актуальный local SDP — в нём уже есть `a=candidate`, и trickle не обгонит offer.
     *
     * Бюджет ожидания зависит от того, есть ли смысл ждать relay:
     *  * анонимный режим — relay единственный путь, ждём долго ([TOR_RELAY_WAIT_MS]);
     *  * прямой режим с TURN-резервом — ждём relay ограниченно
     *    ([DIRECT_RELAY_WAIT_MS]), чтобы собеседник получил резервный путь уже
     *    в offer/answer, а не только trickle'ом;
     *  * прямой режим без TURN — ждать нечего, латч отпускает первый же
     *    host-кандидат (см. [PcObserver.onIceCandidate]).
     */
    private suspend fun awaitLocalDescription(mediaOverTor: Boolean): String {
        val timeoutMs = when {
            mediaOverTor -> TOR_RELAY_WAIT_MS
            turnOnlyUris.isNotEmpty() -> DIRECT_RELAY_WAIT_MS
            else -> DIRECT_ICE_WAIT_MS
        }
        val latch = iceReadyLatch
        if (latch != null) {
            val ok = withContext(Dispatchers.IO) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!ok) {
                DiagnosticLog.w(DIAG_TAG) {
                    "ICE wait timeout relay=$gatheredRelay tor=$mediaOverTor ${timeoutMs}мс"
                }
            }
        }
        return onEngineThread {
            val sdp = peerConnection?.localDescription?.description
                ?: error("нет local SDP")
            DiagnosticLog.sync(
                DIAG_TAG, "SDP после gathering",
                *CallTrace.sdpFields(sdp),
                "relay" to gatheredRelay,
                "tor" to mediaOverTor,
            )
            sdp
        }
    }

    private fun addRemoteIce(pc: PeerConnection, candidate: CallIce) {
        val ice = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        val typ = iceCandidateType(candidate.candidate)
        pc.addIceCandidate(ice, object : AddIceObserver {
            override fun onAddSuccess() {
                DiagnosticLog.event(DIAG_TAG, "удалённый ICE принят", "тип" to typ)
            }
            override fun onAddFailure(error: String?) {
                DiagnosticLog.w(DIAG_TAG) { "удалённый ICE отклонён тип=$typ ошибка=$error" }
            }
        })
    }

    private fun iceCandidateType(sdp: String): String = CallTrace.candidateType(sdp)

    /**
     * Просит WebRTC пересобрать ICE, если путь потерян и сам не восстановился.
     *
     * ЗАЧЕМ. `DISCONNECTED` означает «выбранная пара перестала отвечать». WebRTC
     * продолжает пинговать УЖЕ ИЗВЕСТНЫЕ пары, но новых кандидатов не собирает.
     * Если причина — смена сети или перестроенная цепь Tor (наш обычный случай),
     * прежние кандидаты мертвы навсегда, и ждать от них ответа бессмысленно:
     * ровно так разговор и умирал молча, оставляя в UI «восстанавливаем
     * соединение…» до самого таймаута.
     *
     * `restartIce()` меняет ufrag/pwd и запускает новую сборку кандидатов; новые
     * кандидаты уходят собеседнику обычным trickle-путём через сигналинг, то есть
     * никакой отдельной поддержки на другой стороне не требуется.
     *
     * Пауза перед рестартом обязательна: `DISCONNECTED` часто длится доли секунды
     * (переключение вышки, всплеск задержки), и рестарт в этот момент только
     * оборвал бы уже работающий путь. Проверяем состояние ПОВТОРНО перед
     * вызовом.
     *
     * @param immediate не выжидать паузу (для терминального `FAILED`).
     * @return была ли попытка запланирована; `false` — лимит [MAX_ICE_RESTARTS]
     *   исчерпан, и вызывающему пора завершать звонок.
     */
    private fun scheduleIceRestart(immediate: Boolean = false): Boolean {
        if (closed) return false
        val attempt = iceRestarts.incrementAndGet()
        if (attempt > MAX_ICE_RESTARTS) {
            DiagnosticLog.w(DIAG_TAG) { "ICE рестарт не помог за $MAX_ICE_RESTARTS попыток — сдаёмся" }
            return false
        }
        val delayMs = if (immediate) 0L else ICE_RESTART_GRACE_MS
        DiagnosticLog.sync(
            DIAG_TAG, "ICE рестарт запланирован",
            "попытка" to attempt,
            "черезМс" to delayMs,
        )
        webrtcHandler().postDelayed({
            if (closed) return@postDelayed
            val pc = peerConnection ?: return@postDelayed
            val state = runCatching { pc.iceConnectionState() }.getOrNull()
            val stillBroken = state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.FAILED
            if (!stillBroken) {
                DiagnosticLog.event(DIAG_TAG, "ICE рестарт не нужен", "состояние" to (state?.name ?: "null"))
                return@postDelayed
            }
            DiagnosticLog.sync(
                DIAG_TAG, "ICE рестарт",
                "попытка" to attempt,
                "состояние" to (state?.name ?: "null"),
            )
            runCatching { pc.restartIce() }
                .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "ICE рестарт не удался" } }
        }, delayMs)
        return true
    }

    /**
     * Включает микрофон/динамик только после local SDP. Раньше AudioRecord
     * стартовал внутри setLocalDescription и убивал процесс на Samsung.
     */
    private fun startCallAudio() {
        DiagnosticLog.step(DIAG_TAG, "аудио включение старт")
        enterCallAudio()
        val pc = peerConnection
        // setAudioRecording(true) поднимает AudioRecord — исторически самое
        // «падучее» место на Samsung. Каждый шаг помечаем крошкой, чтобы в
        // sidecar было видно, на каком именно из них умер процесс.
        runCatching { pc?.setAudioPlayout(true) }
        DiagnosticLog.step(DIAG_TAG, "аудио playout")
        runCatching { pc?.setAudioRecording(true) }
        DiagnosticLog.step(DIAG_TAG, "аудио recording")
        audioTrack?.setEnabled(true)
        DiagnosticLog.step(DIAG_TAG, "аудио включено", "дорожка" to (audioTrack?.enabled() == true))
    }

    /**
     * Забирает аудиосессию звонка (режим разговора + фокус).
     *
     * Владение отдано [CallAudioSession] со счётчиком ссылок: режим аудио — это
     * ресурс процесса, и когда им управляли ЗДЕСЬ, наложение двух движков
     * (новый звонок начинался раньше, чем догорал `close()` предыдущего)
     * оставляло систему в `MODE_IN_COMMUNICATION` навсегда. Кроме движка ту же
     * сессию держит проигрыватель «гудков» ([CallTonePlayer]) — поэтому переход
     * «гудки → разговор» проходит без смены режима и без скачка маршрута.
     *
     * Захват однократный: [audioSessionHeld] держит баланс с [leaveCallAudio],
     * потому что звонок может не дойти до `startCallAudio` вовсе.
     */
    private fun enterCallAudio() {
        if (audioSessionHeld) return
        audioSessionHeld = true
        CallAudioSession.acquire(appContext)
    }

    private fun leaveCallAudio() {
        if (!audioSessionHeld) return
        audioSessionHeld = false
        CallAudioSession.release(appContext)
    }

    private inner class PcObserver : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            DiagnosticLog.sync(DIAG_TAG, "signaling", "состояние" to (state?.name ?: "null"))
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            DiagnosticLog.sync(DIAG_TAG, "ICE", "состояние" to (state?.name ?: "null"))
            state?.let { listener?.onIceState(it.name) }
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    iceRestarts.set(0)
                    listener?.onConnected()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> scheduleIceRestart()
                PeerConnection.IceConnectionState.FAILED -> {
                    // FAILED — терминальное состояние ICE: сам он оттуда не
                    // выйдет. Пробуем ещё один рестарт (пути могли появиться
                    // после смены сети) и только потом сдаёмся.
                    if (!scheduleIceRestart(immediate = true)) listener?.onFailed("ICE FAILED")
                }
                else -> {}
            }
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            DiagnosticLog.sync(DIAG_TAG, "PC", "состояние" to (state?.name ?: "null"))
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            DiagnosticLog.event(DIAG_TAG, "ICE receiving", "есть" to receiving)
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            DiagnosticLog.sync(DIAG_TAG, "ICE gathering", "состояние" to (state?.name ?: "null"))
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                iceReadyLatch?.countDown()
            }
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            val ice = candidate ?: return
            val typ = iceCandidateType(ice.sdp)
            DiagnosticLog.sync(
                DIAG_TAG, "локальный ICE",
                "тип" to typ,
                "байт" to ice.sdp.length,
                "mid" to (ice.sdpMid ?: ""),
                "mLine" to ice.sdpMLineIndex,
            )
            if (typ == "relay") {
                gatheredRelay = true
                iceReadyLatch?.countDown()
            } else if (turnIceServers.isEmpty()) {
                // Без TURN relay-кандидата не будет никогда, а GATHER_CONTINUALLY
                // не даёт COMPLETE — держать латч до таймаута значит дарить
                // каждому прямому звонку лишние секунды на пустом ожидании.
                iceReadyLatch?.countDown()
            }
            listener?.onLocalIce(CallIce(ice.sdpMid ?: "", ice.sdpMLineIndex, ice.sdp))
        }
        override fun onIceCandidateError(event: org.webrtc.IceCandidateErrorEvent?) {
            DiagnosticLog.sync(
                DIAG_TAG, "ICE ошибка кандидата",
                "код" to (event?.errorCode ?: -1),
                "текст" to CallTrace.redactNet(event?.errorText.orEmpty()),
                "url" to CallTrace.redactNet(event?.url.orEmpty()),
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            DiagnosticLog.event(DIAG_TAG, "ICE сняты", "штук" to (candidates?.size ?: 0))
        }
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) {
            val local = iceCandidateType(event?.local?.sdp.orEmpty())
            val remote = iceCandidateType(event?.remote?.sdp.orEmpty())
            DiagnosticLog.sync(
                DIAG_TAG, "ICE пара",
                "локальный" to local,
                "удалённый" to remote,
                "причина" to CallTrace.redactNet(event?.reason.orEmpty()),
            )
            listener?.onIcePath(local, remote)
        }
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: org.webrtc.DataChannel?) {
            DiagnosticLog.event(DIAG_TAG, "datachannel", "есть" to (channel != null))
        }
        override fun onRenegotiationNeeded() {
            DiagnosticLog.sync(DIAG_TAG, "renegotiation")
        }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val kind = receiver?.track()?.kind() ?: "null"
            DiagnosticLog.sync(DIAG_TAG, "onAddTrack", "вид" to kind)
            postOnEngineThread {
                if (closed) return@postOnEngineThread
                val track = receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    remoteVideoTrack = track
                    remoteSink?.let { runCatching { track.addSink(it) } }
                }
                sharedFactory?.let { factory ->
                    frameCryptor.attachReceiverIfReady(factory, receiver)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AegisWebRtc"
        private const val DIAG_TAG = "webrtc"
        private const val STREAM_ID = "aegis_stream"

        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        /** Ожидание relay-кандидата через TCP-TURN/Tor, прежде чем отдать SDP. */
        private const val TOR_RELAY_WAIT_MS = 12_000L

        /**
         * Прямой режим с TURN-резервом: ждём relay ограниченно. Аллокация идёт
         * по TCP через Tor и занимает секунды, но резервный путь полезнее в
         * самом offer/answer, чем позже trickle'ом — собеседник начинает его
         * проверять сразу.
         */
        private const val DIRECT_RELAY_WAIT_MS = 5_000L

        /** Прямой режим без TURN: страховка, если кандидатов не будет вообще. */
        private const val DIRECT_ICE_WAIT_MS = 2_000L

        /**
         * Потолок буфера джиттера, когда в пути есть ретранслятор через Tor:
         * 200 пакетов по 20 мс ≈ 4 с. Не задержка, а разрешённый максимум —
         * NetEq держит ровно столько, сколько требует канал.
         */
        private const val TOR_JITTER_BUFFER_PACKETS = 200

        /**
         * Пауза между `DISCONNECTED` и пересборкой ICE. Короткие провалы связи
         * (переключение вышки, всплеск задержки Tor) проходят сами за доли
         * секунды — рестарт в этот момент оборвал бы рабочий путь.
         */
        private const val ICE_RESTART_GRACE_MS = 4_000L

        /**
         * Сколько раз пересобирать ICE за один разговор, прежде чем признать
         * путь безнадёжным. Каждая попытка стоит новой аллокации TURN через Tor,
         * поэтому бесконечно повторять нельзя: лучше честно завершить звонок,
         * чем держать «восстанавливаем соединение…» минутами.
         */
        private const val MAX_ICE_RESTARTS = 3

        /** Шаг сторожа нативных вызовов: дешёвый снимок без остановки VM. */
        private const val WATCHDOG_STEP_MS = 250

        /** Докуда сторож следит за одним нативным вызовом. */
        private const val WATCHDOG_LIMIT_MS = 15_000

        /**
         * Отметки, на которых снимается ПОЛНЫЙ дамп потоков (останавливает VM).
         * К этому моменту вызов уже завис, и точность таймингов не важна.
         */
        private val DUMP_AT_MS = setOf(1_000, 3_000, 6_000, 12_000)

        @Volatile
        private var nativeVerboseWindow = false

        /** Сколько строк нативного лога записано за текущее окно (видно объём). */
        private val nativeLines = java.util.concurrent.atomic.AtomicLong(0)
        /**
         * ICE-тайминги под RTT Tor. Должны удовлетворять ValidateIceConfig:
         * unwritable ≤ inactive (в Java нет iceInactiveTimeout, дефолт 15 с);
         * strong/weak интервалы равны — инвариант «strong ≥ weak» и наоборот;
         * receiving ≥ max(strong, min); stable writable ≥ strong.
         * 30 с unwritable / weak 5 с при strong 2 с роняли createPeerConnection.
         */
        private const val TOR_ICE_UNWRITABLE_MS = 15_000
        private const val TOR_ICE_UNWRITABLE_CHECKS = 8
        private const val TOR_ICE_CHECK_STRONG_MS = 2_000
        private const val TOR_ICE_CHECK_WEAK_MS = 2_000
        private const val TOR_ICE_CHECK_MIN_MS = 2_000
        private const val TOR_ICE_RECEIVING_MS = 15_000

        /**
         * Только для `stableWritableConnectionPingIntervalMs`.
         * НЕ применять к `stunCandidateKeepaliveIntervalMs` — см.
         * [applyTorIceTimings]: там это null-разыменование на `network_thread`.
         */
        private const val TOR_ICE_KEEPALIVE_MS = 10_000

        /** Ненулевой handle: 0 native трактует как «сети нет». */
        private const val SYNTHETIC_NET_HANDLE = 0xAE61502L

        @Volatile
        private var factoryInitialized = false

        @Volatile
        private var sharedFactory: PeerConnectionFactory? = null

        private var eglBase: EglBase? = null

        private var webrtcThread: HandlerThread? = null
        private var webrtcHandler: Handler? = null

        /**
         * Живая ссылка на ADM на всё время процесса. Native factory хранит только
         * указатель; без Java-корня обёртка финализируется и рвёт AudioRecord.
         */
        private var sharedAdm: JavaAudioDeviceModule? = null

        /** Общий DTLS-серт: генерация внутри setLocal на Samsung иногда виснет/падает. */
        private var sharedCertificate: RtcCertificatePem? = null

        private val audioRecordErrors = object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                DiagnosticLog.sync(DIAG_TAG, "AudioRecord init ошибка", "текст" to errorMessage)
            }
            override fun onWebRtcAudioRecordStartError(
                errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                errorMessage: String,
            ) {
                DiagnosticLog.sync(DIAG_TAG, "AudioRecord start ошибка", "код" to errorCode.name, "текст" to errorMessage)
            }
            override fun onWebRtcAudioRecordError(errorMessage: String) {
                DiagnosticLog.e(DIAG_TAG) { "AudioRecord: $errorMessage" }
            }
        }

        private val audioTrackErrors = object : JavaAudioDeviceModule.AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                DiagnosticLog.e(DIAG_TAG) { "AudioTrack init: $errorMessage" }
            }
            override fun onWebRtcAudioTrackStartError(
                errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                errorMessage: String,
            ) {
                DiagnosticLog.e(DIAG_TAG) { "AudioTrack start: $errorCode $errorMessage" }
            }
            override fun onWebRtcAudioTrackError(errorMessage: String) {
                DiagnosticLog.e(DIAG_TAG) { "AudioTrack: $errorMessage" }
            }
        }

        @Synchronized
        private fun webrtcHandler(): Handler {
            val existing = webrtcHandler
            if (existing != null) return existing
            val thread = HandlerThread("aegis-webrtc").also { it.start() }
            webrtcThread = thread
            return Handler(thread.looper).also { webrtcHandler = it }
        }

        private fun postOnEngineThread(block: () -> Unit) {
            val handler = webrtcHandler()
            if (Looper.myLooper() == handler.looper) block()
            else handler.post(block)
        }

        /** Идемпотентная инициализация WebRTC. Бросает при неудаче — вызывающий ловит. */
        @Synchronized
        private fun ensureFactoryInitialized(context: Context, needVideo: Boolean) {
            if (factoryInitialized && sharedFactory != null) {
                if (needVideo && eglBase == null) {
                    DiagnosticLog.w(DIAG_TAG) {
                        "factory без EGL: видео в этом процессе недоступно (первый звонок был аудио)"
                    }
                }
                return
            }
            val app = context.applicationContext
            DiagnosticLog.step(
                DIAG_TAG, "factory initialize старт",
                "видео" to needVideo,
                *processSnapshotFields(),
            )
            installSafeNetworkMonitor()
            val minSeverity = if (DiagnosticLog.isEnabled) {
                org.webrtc.Logging.Severity.LS_VERBOSE
            } else {
                org.webrtc.Logging.Severity.LS_INFO
            }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    // IPv6: loopback TURN иначе резолвится в [::1], а форвардер
                    // слушает только IPv4 — TurnPort на Samsung падает.
                    //
                    // `WebRTC-Aec3/Disabled` УБРАН сознательно. Он выключал
                    // современный эхоподавитель, и вместе с выключенными
                    // ограничениями аудиодорожки звонок шёл вообще без AEC —
                    // громкая связь давала эхо собеседнику. AEC3 — программный
                    // модуль WebRTC, к аппаратным AEC/NS (которые и роняли
                    // Samsung) отношения не имеет.
                    .setFieldTrials("WebRTC-IPv6/Disabled/")
                    .setInjectableLogger(
                        { message, severity, tag ->
                            logNative(severity, CallTrace.redactNet("$tag $message"))
                        },
                        minSeverity,
                    )
                    .createInitializationOptions(),
            )
            // EGL/HW-кодеки только для видео: на Samsung аудиозвонок с
            // DefaultVideoEncoderFactory иногда умирает native через пару секунд.
            val egl = if (needVideo) runCatching { EglBase.create() }.getOrNull() else null
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            // HW AEC/NS и VOICE_COMMUNICATION source на Samsung (S24 / Android 16)
            // дают native crash при старте AudioRecord. MIC + софт-обработка стабильнее.
            val adm = JavaAudioDeviceModule.builder(app)
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioAttributes(audioAttrs)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setUseLowLatency(false)
                .setUseStereoInput(false)
                .setUseStereoOutput(false)
                .setEnableVolumeLogger(false)
                .setAudioRecordErrorCallback(audioRecordErrors)
                .setAudioTrackErrorCallback(audioTrackErrors)
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() {
                        DiagnosticLog.sync(DIAG_TAG, "AudioRecord старт")
                    }
                    override fun onWebRtcAudioRecordStop() {
                        DiagnosticLog.sync(DIAG_TAG, "AudioRecord стоп")
                    }
                })
                .createAudioDeviceModule()
            // ADM нельзя отпускать GC: native factory держит только указатель,
            // финализатор Java-обёртки рвёт AudioRecord → SIGSEGV на Samsung.
            sharedAdm = adm
            DiagnosticLog.step(DIAG_TAG, "ADM создан")
            // Android 15/16 NetworkMonitor делает BindSocketToNetwork(127.0.0.1, lo)
            // и не находит handle — на Samsung это SIGSEGV внутри setLocal/TURN.
            val options = PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
                // Исключаем loopback из перечисления сетей для сборки ICE.
                //
                // ЗАЧЕМ. В Java-обёртке networkIgnoreMask по умолчанию 0, тогда
                // как в самом WebRTC (C++ kDefaultNetworkIgnoreMask) loopback
                // исключён. Из-за этого расхождения интерфейс `lo` попадал в
                // список сетей, и WebRTC поднимал на нём host-порты. Дальше
                // происходило ровно то, чего и следует ожидать: он пытался
                // отправить STUN с сокета, привязанного к 127.0.0.x, на
                // LAN/CGNAT-адрес собеседника, ядро отвечало EINVAL, и журнал
                // заливало сотнями строк
                //   `UDP send ... failed with error 22`
                //   `TCP connect failed with error 22`
                // Такие пары не могут заработать НИКОГДА: 127.0.0.0/8 не
                // маршрутизируется за пределы устройства. Они только съедали
                // бюджет проверок ICE и задерживали выбор рабочего пути.
                //
                // Вторая причина — приватность (§10.10): loopback-кандидаты
                // уходили собеседнику в SDP (в журнале candHost=7 при одном
                // осмысленном адресе), раскрывая устройство и число его
                // интерфейсов без всякой пользы.
                //
                // TURN от этого не страдает: локальный форвардер слушает
                // 127.0.0.2, но TurnPort'ы поднимаются на обычных сетях и на
                // ANY-адресе (enableIceGatheringOnAnyAddressPorts), а сокет,
                // привязанный к 0.0.0.0, к loopback подключается свободно.
                networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
            }
            val builder = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
            if (egl != null) {
                builder.setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(egl.eglBaseContext, true, true),
                )
                builder.setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            }
            DiagnosticLog.step(DIAG_TAG, "createPeerConnectionFactory")
            val factory = builder.createPeerConnectionFactory()
            eglBase = egl
            sharedFactory = factory
            sharedCertificate = runCatching { RtcCertificatePem.generateCertificate() }.getOrNull()
            factoryInitialized = true
            // initialize/createFactory могут перезаписать factory детектора.
            installSafeNetworkMonitor()
            // WebRTC при initialize перехватывает SIGSEGV — возвращаем свой хук
            // поверх, иначе нативное падение уйдёт мимо sidecar.
            val rebound = DiagnosticNative.rebind()
            Log.i(TAG, "WebRTC PeerConnectionFactory инициализирован egl=${egl != null}")
            DiagnosticLog.sync(
                DIAG_TAG, "factory готов",
                "egl" to (egl != null),
                "видео" to needVideo,
                "networkMonitor" to "native-disabled+synthetic-fallback",
                "серт" to (sharedCertificate != null),
                "verboseNative" to DiagnosticLog.isEnabled,
                "хукСигналов" to rebound,
                "sidecarFd" to DiagnosticNative.sidecarFd(),
                *processSnapshotFields(),
            )
        }

        /**
         * Все native-логи WebRTC, кроме строк с байтами ключей
         * ([CallTrace.carriesKeyMaterial]), — через sync: при SIGSEGV асинхронная
         * очередь умирает вместе с процессом, и как раз TurnPort-строки пропадают.
         * VERBOSE только в окне createPC/setLocal, плюс любые строки про
         * TURN/ICE/socket — иначе RTP зальёт журнал.
         *
         * Строки про сборку ICE дополнительно кладём «хлебной крошкой» в
         * нативное кольцо: если процесс умрёт внутри TurnPort, последняя
         * нативная строка окажется прямо в sidecar рядом с сигналом.
         *
         * Начиная с [DiagnosticLog] без `fsync` на строку такая запись стоит
         * микросекунды. Раньше каждая строка стоила отдельного `fsync`, а зовут
         * логгер `network_thread`/`signaling_thread`/`worker_thread` под
         * глобальной блокировкой WebRTC — из-за этого сборка ICE замедлялась в
         * разы и диагностика меняла поведение, которое измеряла.
         */
        private fun logNative(severity: org.webrtc.Logging.Severity, text: String) {
            if (CallTrace.carriesKeyMaterial(text)) return
            nativeLines.incrementAndGet()
            val turnish = text.contains("Turn", ignoreCase = true) ||
                text.contains("ICE", ignoreCase = true) ||
                text.contains("TCP", ignoreCase = true) ||
                text.contains("socket", ignoreCase = true) ||
                text.contains("fail", ignoreCase = true) ||
                text.contains("error", ignoreCase = true) ||
                text.contains("port", ignoreCase = true) ||
                text.contains("network", ignoreCase = true) ||
                text.contains("candidate", ignoreCase = true) ||
                text.contains("dtls", ignoreCase = true)
            when (severity) {
                org.webrtc.Logging.Severity.LS_ERROR -> {
                    DiagnosticNative.breadcrumb("native E $text")
                    DiagnosticLog.sync(DIAG_TAG, "native E", "текст" to text)
                }
                org.webrtc.Logging.Severity.LS_WARNING -> {
                    DiagnosticNative.breadcrumb("native W $text")
                    DiagnosticLog.sync(DIAG_TAG, "native W", "текст" to text)
                }
                org.webrtc.Logging.Severity.LS_VERBOSE ->
                    if (nativeVerboseWindow || turnish) {
                        if (turnish) DiagnosticNative.breadcrumb("native V $text")
                        DiagnosticLog.sync(DIAG_TAG, "native V", "текст" to text)
                    }
                else -> {
                    if (turnish) DiagnosticNative.breadcrumb("native I $text")
                    DiagnosticLog.sync(DIAG_TAG, "native I", "текст" to text)
                }
            }
        }

        /**
         * Синтетический детектор сетей — СТРАХОВКА, а не основной механизм.
         *
         * ВАЖНО ПОНИМАТЬ ОБЛАСТЬ ДЕЙСТВИЯ. Пока в [PeerConnectionFactory.Options]
         * стоит `disableNetworkMonitor = true`, нативная сторона вообще не
         * создаёт `AndroidNetworkMonitor` и Java-детектор НИКОГДА не
         * опрашивается: перечисление интерфейсов идёт через `getifaddrs()` в
         * `BasicNetworkManager`, а `BindSocketToNetwork` (та самая операция,
         * которая роняла Samsung на `lo`) не выполняется совсем. То есть
         * «синтетика» здесь не чинит `Network* == null` — от пустого списка
         * сетей защищает `enableIceGatheringOnAnyAddressPorts`, который
         * подставляет ANY-address порт.
         *
         * Детектор всё равно ставим: он глобален на процесс и делает поведение
         * предсказуемым, если `disableNetworkMonitor` когда-нибудь выключат или
         * монитор создаст сторонний код. Тогда native получит одну валидную
         * IPv4-сеть `127.0.0.2` (адрес форвардера) с ненулевым handle вместо
         * пустого списка и `CONNECTION_NONE`.
         */
        private fun installSafeNetworkMonitor() {
            val ok = runCatching {
                val ipv4 = org.webrtc.NetworkChangeDetector.IPAddress(byteArrayOf(127, 0, 0, 2))
                val ethernet = org.webrtc.NetworkChangeDetector.ConnectionType.CONNECTION_ETHERNET
                val none = org.webrtc.NetworkChangeDetector.ConnectionType.CONNECTION_NONE
                val info = org.webrtc.NetworkChangeDetector.NetworkInformation(
                    "aegis-lo",
                    ethernet,
                    none,
                    SYNTHETIC_NET_HANDLE,
                    arrayOf(ipv4),
                )
                val factory = org.webrtc.NetworkChangeDetectorFactory { _, _ ->
                    object : org.webrtc.NetworkChangeDetector {
                        override fun getCurrentConnectionType() = ethernet
                        override fun supportNetworkCallback() = false
                        override fun getActiveNetworkList() = listOf(info)
                        override fun destroy() {}
                    }
                }
                org.webrtc.NetworkMonitor.getInstance().setNetworkChangeDetectorFactory(factory)
            }.isSuccess
            DiagnosticLog.sync(
                DIAG_TAG, "NetworkMonitor синтетика",
                "ок" to ok,
                "hostKind" to "loopbackAlt",
                // Честно фиксируем, что при disableNetworkMonitor=true эта
                // заглушка нативной стороной не используется: иначе следующий
                // разбор журнала снова уйдёт по ложному следу.
                "активна" to false,
                "перечислениеСетей" to "getifaddrs+anyAddressPorts",
            )
        }

        /**
         * Состояние процесса одной строкой. Кроме памяти включает дескрипторы и
         * `oom_score_adj`: если процесс убьют снаружи (LMK / ActivityManager),
         * нативного сигнала не будет вовсе, и ответ дадут именно эти числа.
         */
        private fun processSnapshotFields(): Array<Pair<String, Any?>> = arrayOf(
            "pid" to android.os.Process.myPid(),
            "потоки" to Thread.activeCount(),
            "heapМб" to (Runtime.getRuntime().totalMemory() / (1024 * 1024)),
            "heapСвобМб" to (Runtime.getRuntime().freeMemory() / (1024 * 1024)),
            "nativeКб" to (android.os.Debug.getNativeHeapAllocatedSize() / 1024),
            "нативСтрок" to nativeLines.get(),
            *ProcSelf.fields(),
        )
    }

    private suspend fun <T> onEngineThread(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            val handler = webrtcHandler()
            val run = Runnable {
                try {
                    if (cont.isActive) cont.resume(block())
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
            if (Looper.myLooper() == handler.looper) {
                run.run()
            } else if (!handler.post(run) && cont.isActive) {
                cont.resumeWithException(IllegalStateException("поток WebRTC недоступен"))
            }
        }

    private fun postOnEngineThread(block: () -> Unit) {
        Companion.postOnEngineThread(block)
    }
}
