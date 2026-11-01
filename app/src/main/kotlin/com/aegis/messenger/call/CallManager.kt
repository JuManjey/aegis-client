package com.aegis.messenger.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aegis.core.common.call.CallControl
import com.aegis.core.common.call.CallStage
import com.aegis.core.common.call.CallStageEntry
import com.aegis.core.common.call.CallStageLog
import com.aegis.core.common.call.CallStageState
import com.aegis.core.common.call.CallTimeouts
import com.aegis.core.common.call.CallTrace
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.diag.ProcSelf
import com.aegis.messenger.diag.ProcessDeathReport
import com.aegis.core.common.call.CallDirection
import com.aegis.core.common.call.CallEndCode
import com.aegis.core.common.call.CallInfo
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.call.PendingCallIce
import com.aegis.core.common.security.TorMode
import com.aegis.crypto.CallCrypto
import com.aegis.crypto.CryptoModule
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.messenger.notifications.CallMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Оркестратор звонков (P8, §7.5/§10.10). Машина состояний [CallPhase] + связка
 * сигналинга ([CallSignaling]) и медиадвижка ([MediaEngine], WebRTC/DTLS-SRTP).
 *
 * КЛЮЧИ ЗВОНКА (§7.5/§10.10): инициатор генерирует случайный 256-битный
 * `callSecret` (ОС-ГСЧ) и отправляет его в offer'е ЧЕРЕЗ Signal-сессию поверх
 * каскада на PSK (слой A→B) — то есть корень ключей «установлен через
 * Signal-сессию». Из `callSecret` + ручного PSK (§6.2, подмешивается) + id
 * звонка + «транскрипта» (DTLS-фингерпринты сторон) обе стороны выводят
 * SRTP-ключи и код сверки SAS ([CallCrypto]). Сервер без PSK не воспроизведёт
 * ни ключи, ни SAS → закрыт MITM недоверенного сервера.
 *
 * Метаданные звонка минимизируем (§10.10): состояние держим в памяти, на диск
 * ничего не пишем.
 *
 * ## Маршрут медиа выводится из режима сети
 *
 * Отдельной настройки «медиа через Tor» нет: Tor ВКЛ — медиа только через
 * onion-TURN (ICE `RELAY`), без такого TURN звонок не начинается; Tor ВЫКЛ —
 * ICE `ALL` со STUN и TURN своего сервера ([CallIcePlan]). Ключи звонка, SAS,
 * DTLS-SRTP и кадровый слой от режима не зависят.
 *
 * ## Отзывчивость UI как отдельное требование
 *
 * Сигналинг идёт через релей (через Tor это секунды на один POST). Поэтому
 * пользовательские действия НИКОГДА не ждут сети: «Принять» сразу переводит
 * фазу в [CallPhase.CONNECTING] (это гасит рингтон и вибрацию), «Сбросить»
 * сразу переводит в [CallPhase.ENDED] и закрывает окно, а отправка `hangup` и
 * освобождение движка уходят в фон. Ход установки пользователь видит в ленте
 * этапов ([CallInfo.stages]).
 */
class CallManager(
    private val appContext: Context,
    private val contactProvider: suspend (String) -> StoredContact?,
    private val pskManager: com.aegis.messenger.psk.PskManager,
    private val signaling: CallSignaling,
    private val mediaEngineFactory: (CallIcePlan) -> MediaEngine,
    private val callsEnabledProvider: () -> Boolean,
    /** Режим сети: из него выводится маршрут медиа. */
    private val modeProvider: () -> TorMode,
    private val scope: CoroutineScope,
    private val turnProvisioner: TurnProvisioner? = null,
    private val calls: CallCrypto = CryptoModule.calls,
) {
    /** Медиа через Tor — то же самое, что режим Tor ВКЛ. */
    private fun mediaOverTor(): Boolean = modeProvider() == TorMode.ALWAYS

    private val _state = MutableStateFlow(CallInfo.NONE)
    val state: StateFlow<CallInfo> = _state.asStateFlow()

    /**
     * Локализованная строка причины завершения/ошибки звонка (§14.6). Резолвим
     * через [LocaleManager], т.к. эти строки формируются вне Activity и попадают
     * в UI ([com.aegis.messenger.ui.call.CallScreen]) как `endReason`.
     */
    private fun str(resId: Int, vararg args: Any): String =
        LocaleManager.wrap(appContext).getString(resId, *args)

    /** Доступен ли реальный медиадвижок (WebRTC, не заглушка). */
    val mediaAvailable: Boolean get() = session?.engine?.isReal ?: true

    /**
     * Общий OpenGL-контекст текущего движка для инициализации видеоповерхностей
     * в UI (§7.5). `null`, если нет активного видеозвонка/реального WebRTC.
     */
    val eglBaseContext: org.webrtc.EglBase.Context? get() = session?.engine?.eglBaseContext

    /** Привязывает приёмник кадров локальной камеры (предпросмотр в UI). */
    fun setLocalVideoSink(sink: org.webrtc.VideoSink?) {
        session?.engine?.setLocalVideoSink(sink)
    }

    /** Привязывает приёмник кадров удалённого видео (видео собеседника). */
    fun setRemoteVideoSink(sink: org.webrtc.VideoSink?) {
        session?.engine?.setRemoteVideoSink(sink)
    }

    /** Вкл/выкл локальную камеру во время видеозвонка. */
    fun setCameraEnabled(enabled: Boolean) {
        session?.engine?.setVideoEnabled(enabled)
        _state.value = _state.value.copy(cameraEnabled = enabled)
    }

    /** Переключает фронтальную/тыловую камеру. */
    fun switchCamera() {
        session?.engine?.switchCamera()
    }

    private class Session(
        val callId: String,
        val contact: StoredContact,
        val media: CallMedia,
        val direction: CallDirection,
        val callSecret: ByteArray,
        // Движок и TURN-аренда создаются в момент реального старта медиа (у
        // инициатора — при звонке, у принимающего — при приёме), поэтому nullable.
        var engine: MediaEngine? = null,
        var turnLease: TurnProvisioner.Lease? = null,
        var remoteOfferSdp: String? = null,
        var localSdp: String? = null,
        /** Локальный ICE не уходит, пока offer/answer не доставлен в релей. */
        var localIceReady: Boolean = false,
        val queuedLocalIce: MutableList<CallIce> = mutableListOf(),
        /** Принимающий уже нажал «Принять» — второй раз медиа не поднимаем. */
        val accepting: AtomicBoolean = AtomicBoolean(false),
        /** Звонящий уже применил ответ — повтор того же answer не трогает идущий звонок. */
        val answerApplied: AtomicBoolean = AtomicBoolean(false),
        /** Монотонный момент входа в текущую фазу — для фазовых таймаутов. */
        @Volatile var phaseSinceElapsed: Long = SystemClock.elapsedRealtime(),
    )

    @Volatile
    private var session: Session? = null

    /** Защита от повторного [endCall] (ICE CLOSED + hangup + close). */
    private val ending = AtomicBoolean(false)

    /** Таймаут DIALING/RINGING/CONNECTING, если собеседник упал без hangup. */
    private var setupTimeoutJob: Job? = null

    /** Пульс фазы звонка в журнал — чтобы отличать зависание от native-падения. */
    private var heartbeatJob: Job? = null

    /** Отложенный сброс окна звонка после [CallPhase.ENDED]. */
    private var dismissJob: Job? = null

    /**
     * ICE, пришедший до offer или до PeerConnection. Через Tor меньший ICE-POST
     * часто обгоняет offer; без буфера кандидат теряется и ICE FAILED.
     */
    private val pendingRemoteIce = PendingCallIce()

    /** Сериализация буфера ICE и очереди исходящих кандидатов. */
    private val iceLock = Any()

    /**
     * Лента этапов установки звонка. Пишется из корутин сигналинга и из
     * колбэков нативных потоков WebRTC, поэтому доступ сериализован [stageLock].
     */
    private val stageLog = CallStageLog { SystemClock.elapsedRealtime() }
    private val stageLock = Any()

    // --- Исходящий звонок ---------------------------------------------------

    /** Инициирует исходящий звонок контакту. */
    fun startCall(contactId: String, media: CallMedia) {
        if (!callsEnabledProvider()) {
            DiagnosticLog.w(DIAG_TAG) { "исходящий звонок отменён: звонки выключены" }
            return
        }
        if (session != null) {
            DiagnosticLog.w(DIAG_TAG) { "исходящий звонок отменён: уже есть сессия" }
            return
        }
        scope.launch(Dispatchers.IO) {
            val contact = contactProvider(contactId) ?: run {
                DiagnosticLog.w(DIAG_TAG) { "исходящий звонок: контакта нет ${DiagnosticLog.shortId(contactId)}" }
                return@launch
            }
            val callId = UUID.randomUUID().toString()
            DiagnosticLog.sync(
                DIAG_TAG, "исходящий старт",
                "контакт" to DiagnosticLog.shortId(contactId),
                "callId" to DiagnosticLog.fp(callId),
                "медиа" to media.name,
                "tor" to mediaOverTor(),
                "звонки" to callsEnabledProvider(),
            )
            val secret = ByteArray(CALL_SECRET_BYTES).also { SecureRandom().nextBytes(it) }
            val s = Session(callId, contact, media, CallDirection.OUTGOING, secret)
            session = s
            resetStages()
            stage(CallStage.OUTGOING_START, CallStageState.DONE)
            val mode = modeProvider()
            val mediaOverTor = mode == TorMode.ALWAYS
            emit(s, CallPhase.DIALING, mediaOverTor = mediaOverTor)
            armSetupWatchdog(callId)
            armHeartbeat(callId)
            try {
                DiagnosticLog.sync(DIAG_TAG, "исходящий prepareEngine")
                val engine = prepareEngine(s, mode)
                DiagnosticLog.sync(DIAG_TAG, "исходящий startAsCaller")
                val offer = engine.startAsCaller(media, mediaOverTor)
                s.localSdp = offer
                DiagnosticLog.sync(DIAG_TAG, "исходящий offer готов", *CallTrace.sdpFields(offer))
                stage(CallStage.LOCAL_SDP, CallStageState.DONE)
                stage(CallStage.SIGNAL_OFFER, CallStageState.ACTIVE)
                signaling.send(
                    contact,
                    CallControl(
                        type = CallControl.TYPE_OFFER,
                        callId = callId,
                        media = media,
                        callSecretB64 = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP),
                        sdp = offer,
                    ),
                )
                stage(CallStage.SIGNAL_OFFER, CallStageState.DONE)
                // Offer ушёл первым: теперь можно trickle ICE (иначе ICE обгоняет offer).
                flushRemoteIce(s)
                enableLocalIce(s)
                DiagnosticLog.sync(DIAG_TAG, "исходящий offer отправлен", "callId" to DiagnosticLog.fp(callId))
            } catch (t: Throwable) {
                DiagnosticLog.e(DIAG_TAG, t) { "исходящий звонок не стартовал callId=${DiagnosticLog.fp(callId)}" }
                endCall(setupFailureReason(t, R.string.call_error_start), CallEndCode.ERROR)
            }
        }
    }

    // --- Приём сигналинга (вызывается приёмным циклом релея / тестами) -------

    /** Обрабатывает входящее управляющее сообщение звонка от контакта. */
    fun handleControl(contact: StoredContact, control: CallControl) {
        scope.launch(Dispatchers.IO) {
            if (control.type != CallControl.TYPE_OFFER && spoofsSession(contact, control)) {
                DiagnosticLog.w(DIAG_TAG) {
                    "сигнал текущего звонка от другого контакта отклонён тип=${control.type} " +
                        "контакт=${DiagnosticLog.shortId(contact.contact.id)}"
                }
                return@launch
            }
            when (control.type) {
                CallControl.TYPE_OFFER -> onOffer(contact, control)
                CallControl.TYPE_ANSWER -> onAnswer(control)
                CallControl.TYPE_ICE -> onRemoteIce(control)
                CallControl.TYPE_HANGUP, CallControl.TYPE_BUSY -> {
                    pendingRemoteIce.drop(control.callId)
                    if (session?.callId == control.callId) {
                        endCall(reasonFor(control), endCodeFor(control))
                    }
                }
            }
        }
    }

    /** Сигнал несёт id текущего звонка, но пришёл не от его собеседника. */
    private fun spoofsSession(contact: StoredContact, control: CallControl): Boolean {
        val s = session ?: return false
        return s.callId == control.callId && s.contact.contact.id != contact.contact.id
    }

    private suspend fun onOffer(contact: StoredContact, control: CallControl) {
        // Занято другим звонком → вежливо отклоняем (busy), не раскрывая состояние.
        if (session != null) {
            DiagnosticLog.event(
                DIAG_TAG, "входящий занято",
                "контакт" to DiagnosticLog.shortId(contact.contact.id),
                "callId" to DiagnosticLog.fp(control.callId),
            )
            pendingRemoteIce.drop(control.callId)
            signaling.send(contact, CallControl(CallControl.TYPE_BUSY, control.callId))
            return
        }
        val secret = control.callSecretB64
            ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == CALL_SECRET_BYTES }
            ?: run {
                DiagnosticLog.w(DIAG_TAG) {
                    "offer без 256-битного секрета звонка отклонён callId=${DiagnosticLog.fp(control.callId)}"
                }
                return
            }
        // Движок и TURN-аренду создаём только при реальном приёме (acceptIncoming),
        // чтобы не тратить TURN-креды/форвардер на звонки, которые отклонят.
        val s = Session(
            callId = control.callId,
            contact = contact,
            media = control.media,
            direction = CallDirection.INCOMING,
            callSecret = secret,
            remoteOfferSdp = control.sdp,
        )
        session = s
        DiagnosticLog.sync(
            DIAG_TAG, "входящий звонок",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "callId" to DiagnosticLog.fp(control.callId),
            "медиа" to control.media.name,
            *CallTrace.sdpFields(control.sdp.orEmpty()),
        )
        resetStages()
        stage(CallStage.INCOMING_OFFER, CallStageState.ACTIVE)
        emit(s, CallPhase.RINGING, mediaOverTor = mediaOverTor())
        armSetupWatchdog(control.callId)
        armHeartbeat(control.callId)
    }

    private suspend fun onAnswer(control: CallControl) {
        val s = session ?: return
        if (s.direction != CallDirection.OUTGOING || s.callId != control.callId) return
        val answerSdp = control.sdp ?: return
        val engine = s.engine ?: return
        // Повторный setRemoteDescription того же ответа падает и оборвал бы
        // уже идущий разговор.
        if (!s.answerApplied.compareAndSet(false, true)) {
            DiagnosticLog.event(DIAG_TAG, "повторный ответ отброшен", "callId" to DiagnosticLog.fp(control.callId))
            return
        }
        try {
            DiagnosticLog.sync(DIAG_TAG, "ответ получен", "callId" to DiagnosticLog.fp(control.callId), *CallTrace.sdpFields(answerSdp))
            stage(CallStage.SIGNAL_ANSWER, CallStageState.DONE)
            // Ключи, кадровый слой и фаза — ДО применения ответа. На прямом пути
            // ICE и DTLS сходятся за десятки миллисекунд после него, раньше, чем
            // успел бы закончиться вывод ключей: без этого порядка первые кадры
            // уходят без кадрового слоя, а CONNECTING, опубликованный после
            // ACTIVE, откатывает разговор в «Соединение» — с пингами
            // согласования и обрывом сторожем через CONNECT_TIMEOUT_MS.
            deriveAndApply(s, initiator = true, sdpA = s.localSdp.orEmpty(), sdpB = answerSdp)
            emit(s, CallPhase.CONNECTING)
            stage(CallStage.ICE, CallStageState.ACTIVE)
            engine.onRemoteAnswer(answerSdp)
            DiagnosticLog.sync(DIAG_TAG, "ответ применён", "callId" to DiagnosticLog.fp(control.callId))
        } catch (t: Throwable) {
            DiagnosticLog.e(DIAG_TAG, t) { "ответ не принят callId=${DiagnosticLog.fp(control.callId)}" }
            if (session === s) sendHangup(s, reason = "unreachable")
            endCall(setupFailureReason(t, R.string.call_error_negotiation), CallEndCode.ERROR)
        }
    }

    // --- Действия пользователя ----------------------------------------------

    /**
     * Принять входящий звонок (фаза RINGING).
     *
     * Фаза меняется СИНХРОННО, до любой сетевой работы: именно смена фазы гасит
     * рингтон и вибрацию ([com.aegis.messenger.AppContainer] слушает
     * [state]). Дальше идёт поднятие FGS, движка, TURN и отправка answer в
     * релей через Tor — это единицы секунд, и всё это время телефон звонить уже
     * не должен.
     */
    fun acceptIncoming() {
        val s = session ?: return
        if (s.direction != CallDirection.INCOMING) return
        // Кнопка в оверлее и кнопка в уведомлении ведут в один и тот же метод —
        // без защёлки двойное нажатие поднимало бы второй движок на ту же сессию.
        if (!s.accepting.compareAndSet(false, true)) {
            DiagnosticLog.event(DIAG_TAG, "приём: повторное нажатие проигнорировано")
            return
        }
        val prevPhase = _state.value.phase
        // Фаза публикуется ПЕРВОЙ, до отметки этапа. Иначе первым наблюдатели
        // получили бы состояние, где фаза всё ещё RINGING (изменилась только
        // лента), и подписчик рингтона снова завёл бы звонок, который только
        // что погасили по нажатию.
        //
        // Журналирования здесь нет намеренно: метод зовётся с UI-потока, а
        // `DiagnosticLog.sync` пишет в файл синхронно. Фазу пишет корутина ниже.
        publishPhase(s, CallPhase.CONNECTING, mediaOverTor = mediaOverTor())
        stage(CallStage.INCOMING_OFFER, CallStageState.DONE, str(R.string.call_stage_detail_accepted))
        // Бюджет таймаута считается от входа в фазу: перезапускаем сторожа,
        // чтобы CONNECTING получил свой бюджет, а не остаток от RINGING.
        armSetupWatchdog(s.callId)
        scope.launch(Dispatchers.IO) {
            logPhase(s, prevPhase, CallPhase.CONNECTING)
            val offer = s.remoteOfferSdp ?: run {
                endCall(str(R.string.call_error_no_offer), CallEndCode.ERROR)
                return@launch
            }
            DiagnosticLog.sync(
                DIAG_TAG, "приём: старт медиа",
                "callId" to DiagnosticLog.fp(s.callId),
                "медиа" to s.media.name,
                *CallTrace.sdpFields(offer),
            )
            try {
                val mode = modeProvider()
                val engine = prepareEngine(s, mode)
                DiagnosticLog.sync(DIAG_TAG, "приём startAsCallee")
                val answer = engine.startAsCallee(s.media, offer, mode == TorMode.ALWAYS)
                s.localSdp = answer
                DiagnosticLog.sync(DIAG_TAG, "приём answer готов", *CallTrace.sdpFields(answer))
                stage(CallStage.LOCAL_SDP, CallStageState.DONE)
                deriveAndApply(s, initiator = false, sdpA = offer, sdpB = answer)
                // Кандидаты звонящего, пришедшие в RINGING / раньше offer.
                flushRemoteIce(s)
                stage(CallStage.SIGNAL_ANSWER, CallStageState.ACTIVE)
                signaling.send(
                    s.contact,
                    CallControl(CallControl.TYPE_ANSWER, s.callId, media = s.media, sdp = answer),
                )
                stage(CallStage.SIGNAL_ANSWER, CallStageState.DONE)
                enableLocalIce(s)
                stage(CallStage.ICE, CallStageState.ACTIVE)
                DiagnosticLog.sync(DIAG_TAG, "приём: answer отправлен", "callId" to DiagnosticLog.fp(s.callId))
            } catch (t: Throwable) {
                DiagnosticLog.e(DIAG_TAG, t) { "приём звонка упал callId=${DiagnosticLog.fp(s.callId)}" }
                // Без отбоя звонящий ждал бы до своего таймаута и увидел бы «нет ответа».
                if (session === s) sendHangup(s, reason = "unreachable")
                endCall(setupFailureReason(t, R.string.call_error_accept), CallEndCode.ERROR)
            }
        }
    }

    /** Отклонить входящий звонок. */
    fun declineIncoming() = hangUp(reason = "declined")

    /**
     * Завершить/сбросить текущий звонок.
     *
     * Окно звонка закрывается СРАЗУ ([endCall] выставляет [CallPhase.ENDED] на
     * вызывающем потоке). Уведомление `hangup` уходит собеседнику в фоне: POST в
     * релей через Tor занимает секунды, и держать открытое окно всё это время
     * (как было раньше) пользователь читает как «кнопка не работает».
     */
    fun hangUp(reason: String = "ended") {
        val s = session ?: return
        sendHangup(s, reason)
        endCall(humanReason(reason), endCodeFor(reason), local = true)
    }

    /** Best-effort `hangup` собеседнику в фоне: POST в релей через Tor занимает секунды. */
    private fun sendHangup(s: Session, reason: String) {
        val contact = s.contact
        val callId = s.callId
        scope.launch(Dispatchers.IO) {
            runCatching {
                signaling.send(contact, CallControl(CallControl.TYPE_HANGUP, callId, reason = reason))
            }
        }
    }

    /** Микрофон вкл/выкл. */
    fun setMuted(muted: Boolean) {
        session?.engine?.setMuted(muted)
    }

    // --- Вспомогательное ----------------------------------------------------

    /**
     * Создаёт медиадвижок для сессии.
     *
     * Два предварительных условия поднимаются ПАРАЛЛЕЛЬНО, потому что каждое
     * занимает единицы секунд, а последовательно это заметная задержка приёма:
     *  1. FGS микрофона ([CallMediaService]) — без него AudioRecord на Android 14+
     *     приводит к убийству процесса, поэтому отказ трактуем как фатальный для
     *     звонка (но не для процесса);
     *  2. аренда TURN ([TurnProvisioner]) — в режиме Tor это единственный путь
     *     медиа, и без неё звонок прерывается: движок без ретранслятора собрал бы
     *     host-кандидаты и раскрыл бы IP собеседнику. Без Tor аренда (STUN и TURN
     *     своего сервера) — путь к собеседнику за NAT и РЕЗЕРВ, когда прямого нет.
     */
    private suspend fun prepareEngine(s: Session, mode: TorMode): MediaEngine {
        val startedAt = SystemClock.elapsedRealtime()
        val turnJob = scope.async(Dispatchers.IO) { acquireTurn(mode) }
        val lease = try {
            ensureMediaService(s)
            awaitTurn(turnJob, mode, startedAt)
        } catch (t: Throwable) {
            // Условие провалилось — аренду не бросаем висеть: форвардер и
            // аллокация на coturn должны закрыться вместе со звонком.
            closeTurnWhenReady(turnJob)
            throw t
        }
        // Поднятие условий занимает секунды, и за это время звонок могли
        // сбросить (своей кнопкой, hangup собеседника, таймаут). Продолжать
        // нельзя: движок на мёртвой сессии никто не закроет, а он держит
        // PeerConnection, микрофон и аудиофокус до конца жизни процесса.
        abortIfStale(s) { turnProvisioner?.release(lease) }
        val plan = try {
            CallIcePlan.forMode(mode, lease?.iceServers.orEmpty())
        } catch (_: CallIcePlan.RelayRequiredException) {
            turnProvisioner?.release(lease)
            throw CallSetupException(str(R.string.call_error_relay_required))
        }
        s.turnLease = lease
        DiagnosticLog.sync(
            DIAG_TAG, "движок готовится",
            "callId" to DiagnosticLog.fp(s.callId),
            "серверов" to plan.servers.size,
            "relay" to plan.hasRelay,
            "tor" to plan.mediaOverTor,
            "реальный" to mediaAvailable,
        )
        val engine = mediaEngineFactory(plan)
        abortIfStale(s) {
            engine.close()
            turnProvisioner?.release(lease)
            s.turnLease = null
        }
        s.engine = engine
        engine.listener = EngineListener(s.callId)
        stage(CallStage.ENGINE, CallStageState.DONE)
        return engine
    }

    /**
     * Прерывает установку, если [s] больше не текущая сессия, выполнив перед
     * этим переданную уборку ресурсов, которые [endCall] уже не увидит.
     */
    private fun abortIfStale(s: Session, cleanup: () -> Unit) {
        if (session === s) return
        DiagnosticLog.w(DIAG_TAG) {
            "установка прервана: сессия callId=${DiagnosticLog.fp(s.callId)} уже не активна"
        }
        runCatching(cleanup)
        throw CallSetupException(str(R.string.call_reason_ended))
    }

    /**
     * Поднимает FGS звонка и ждёт его подтверждения.
     *
     * Одна повторная попытка нужна на реальный сценарий «экран был заблокирован»:
     * между нажатием «Принять» в уведомлении и тем моментом, когда система
     * считает приложение видимым, есть окно в сотни миллисекунд, и первый
     * `startForeground(microphone)` в нём получает отказ.
     */
    private suspend fun ensureMediaService(s: Session) {
        stage(CallStage.MEDIA_SERVICE, CallStageState.ACTIVE)
        val video = s.media == CallMedia.VIDEO
        var result = startMediaService(video)
        if (result != CallMediaService.Result.READY) {
            DiagnosticLog.w(DIAG_TAG) { "FGS медиазвонка отказ=$result — повтор" }
            delay(FGS_RETRY_DELAY_MS)
            result = startMediaService(video)
        }
        DiagnosticLog.sync(
            DIAG_TAG, "FGS медиазвонка",
            "итог" to result.name,
            "микрофон" to permissionGranted(Manifest.permission.RECORD_AUDIO),
            "камера" to permissionGranted(Manifest.permission.CAMERA),
            "sdk" to android.os.Build.VERSION.SDK_INT,
        )
        if (result != CallMediaService.Result.READY) {
            stage(CallStage.MEDIA_SERVICE, CallStageState.FAILED)
            // Дальше был бы старт AudioRecord без FGS типа microphone — на
            // Android 14+ это гарантированная смерть процесса без Java-стека.
            throw CallSetupException(str(R.string.call_error_fgs))
        }
        stage(CallStage.MEDIA_SERVICE, CallStageState.DONE)
    }

    private suspend fun startMediaService(video: Boolean): CallMediaService.Result {
        runCatching { CallMediaService.start(appContext, video = video) }
        return CallMediaService.awaitForeground(FGS_WAIT_MS)
    }

    /**
     * Берёт аренду TURN для режима [mode]; `null` — ICE-серверов нет вовсе. В
     * режиме Tor STUN не запрашивается никогда (раскрыл бы IP и через Tor
     * бесполезен), без Tor STUN своего сервера нужен, чтобы абонент узнал адрес
     * снаружи NAT.
     */
    private suspend fun acquireTurn(mode: TorMode): TurnProvisioner.Lease? {
        val provisioner = turnProvisioner ?: return null
        stage(CallStage.TURN, CallStageState.ACTIVE)
        val lease = runCatching { provisioner.acquire(mode) }.getOrNull()
        return lease?.takeIf { it.iceServers.isNotEmpty() }
    }

    /**
     * Ждёт аренду TURN.
     *
     * В режиме Tor relay — единственный путь медиа, поэтому ждём столько,
     * сколько нужно (внутренние таймауты есть у Tor и у HTTP-клиента). Без Tor
     * ждать дольше [TURN_DIRECT_BUDGET_MS] значило бы удлинять КАЖДЫЙ звонок ради
     * резерва, который может и не понадобиться, поэтому по истечении бюджета
     * идём без него, а опоздавшую аренду закрываем.
     *
     * Бюджет считается от [startedAtElapsed] — начала подготовки, а не от конца
     * ожидания FGS. Иначе задержки складывались бы (FGS до 2.5 с + бюджет).
     */
    private suspend fun awaitTurn(
        turnJob: Deferred<TurnProvisioner.Lease?>,
        mode: TorMode,
        startedAtElapsed: Long,
    ): TurnProvisioner.Lease? {
        val mediaOverTor = mode == TorMode.ALWAYS
        val lease = if (mediaOverTor) {
            turnJob.await()
        } else {
            val spent = SystemClock.elapsedRealtime() - startedAtElapsed
            val left = TURN_DIRECT_BUDGET_MS - spent
            val got = if (left <= 0L) {
                // Бюджет уже съеден поднятием FGS: аренду не ждём, но если она
                // подоспеет сама — забираем её, а не выбрасываем.
                if (turnJob.isCompleted) runCatching { turnJob.await() }.getOrNull() else null
            } else {
                withTimeoutOrNull(left) { turnJob.await() }
            }
            if (got == null && turnJob.isActive) closeTurnWhenReady(turnJob)
            got
        }
        // Состояние этапа зависит от РЕЖИМА, а не только от наличия аренды.
        //
        // С Tor relay — единственный путь медиа, и без него звонок не начинается
        // (FAILED, см. prepareEngine). Без Tor звонок идёт и без TURN, но тогда
        // собеседник за симметричным NAT или в режиме Tor (у него только
        // relay-кандидат) недостижим — это честное предупреждение, а не сбой.
        val (state, detail) = when {
            mediaOverTor && lease != null -> CallStageState.DONE to str(R.string.call_stage_detail_turn_ready_tor)
            mediaOverTor -> CallStageState.FAILED to str(R.string.call_stage_detail_turn_absent_tor)
            lease?.hasRelay == true -> CallStageState.DONE to str(R.string.call_stage_detail_turn_ready_direct)
            lease != null -> CallStageState.WARN to str(R.string.call_stage_detail_turn_stun_only)
            else -> CallStageState.WARN to str(R.string.call_stage_detail_turn_absent_direct)
        }
        stage(CallStage.TURN, state, detail)
        return lease
    }

    private fun closeTurnWhenReady(turnJob: Deferred<TurnProvisioner.Lease?>) {
        scope.launch(Dispatchers.IO) {
            turnProvisioner?.release(runCatching { turnJob.await() }.getOrNull())
        }
    }

    /**
     * Выводит ключи звонка и навешивает кадровый слой.
     *
     * Если реальный движок остался без кадрового слоя, бросает
     * [CallSetupException]: вызывающий обязан прервать установку, а не
     * продолжать её на одном DTLS-SRTP.
     */
    private suspend fun deriveAndApply(
        s: Session,
        initiator: Boolean,
        sdpA: String,
        sdpB: String,
    ) {
        val engine = s.engine
        val realEngine = engine?.isReal == true
        if (!calls.isAvailable) {
            if (realEngine) throw CallSetupException(str(R.string.call_error_frame_crypto))
            return
        }
        val psk = runCatching { pskManager.pskBytesFor(s.contact.contact.id) }.getOrNull()
        var keys: CallCrypto.CallKeys? = null
        try {
            val context = fingerprintContext(sdpA, sdpB)
            val derived = calls.deriveKeys(
                callSecret = s.callSecret,
                psk = psk ?: ByteArray(0),
                callId = s.callId,
                initiator = initiator,
                context = context,
            )
            keys = derived
            val attached = engine?.applyDerivedKeys(derived, initiator) ?: false
            if (realEngine && !attached) throw CallSetupException(str(R.string.call_error_frame_crypto))
            _state.value = _state.value.copy(
                sas = derived.sas,
                pskProtected = psk != null && psk.isNotEmpty(),
                frameLayer = attached,
            )
            stage(
                CallStage.KEYS,
                CallStageState.DONE,
                buildString {
                    append(
                        if (psk != null && psk.isNotEmpty()) {
                            str(R.string.call_stage_detail_keys_psk)
                        } else {
                            str(R.string.call_stage_detail_keys_plain)
                        },
                    )
                    if (_state.value.frameLayer) {
                        append(" · ")
                        append(str(R.string.call_stage_detail_frame_layer))
                    }
                },
            )
        } finally {
            psk?.fill(0)
            keys?.wipe()
        }
    }

    private inner class EngineListener(private val callId: String) : MediaEngine.Listener {
        override fun onLocalIce(candidate: CallIce) {
            val s = session ?: return
            if (s.callId != callId) return
            val sendNow: Boolean
            synchronized(iceLock) {
                if (s.localIceReady) {
                    sendNow = true
                } else {
                    s.queuedLocalIce.add(candidate)
                    sendNow = false
                }
            }
            if (sendNow) sendLocalIce(s, candidate)
        }

        override fun onConnected() {
            val s = session ?: return
            if (s.callId == callId) {
                DiagnosticLog.event(DIAG_TAG, "медиа установлено", "callId" to DiagnosticLog.fp(callId))
                stage(CallStage.ICE, CallStageState.DONE)
                stage(CallStage.MEDIA, CallStageState.DONE)
                _state.value = _state.value.copy(reconnecting = false)
                emit(s, CallPhase.ACTIVE)
            }
        }

        override fun onFailed(reason: String) {
            DiagnosticLog.w(DIAG_TAG) { "медиа ошибка callId=${DiagnosticLog.fp(callId)} причина=$reason" }
            if (session?.callId != callId) return
            stage(CallStage.ICE, CallStageState.FAILED, str(R.string.call_stage_detail_no_path))
            endCall(str(R.string.call_media_error, reason), CallEndCode.UNREACHABLE)
        }

        override fun onEnded() {
            // Не завершаем звонок отсюда: ICE CLOSED/dispose приходит при нашем
            // hangup, а повторный close ронял процесс. Удалённый сброс — через hangup.
            DiagnosticLog.event(DIAG_TAG, "движок onEnded", "callId" to DiagnosticLog.fp(callId))
        }

        override fun onIceState(state: String) {
            if (session?.callId != callId) return
            when (state) {
                "CHECKING" -> stage(CallStage.ICE, CallStageState.ACTIVE)
                "DISCONNECTED" -> {
                    // Путь потерян, но ICE ещё пытается восстановиться: звонок
                    // не рвём, только честно показываем это в UI.
                    _state.value = _state.value.copy(reconnecting = true)
                    stage(CallStage.MEDIA, CallStageState.WARN, str(R.string.call_stage_detail_reconnecting))
                }
            }
        }

        override fun onIcePath(localType: String, remoteType: String) {
            if (session?.callId != callId) return
            val relayed = localType == "relay" || remoteType == "relay"
            _state.value = _state.value.copy(relayedPath = relayed)
            stage(
                CallStage.ICE,
                CallStageState.DONE,
                if (relayed) {
                    str(R.string.call_stage_detail_path_relay)
                } else {
                    str(R.string.call_stage_detail_path_direct)
                },
            )
        }
    }

    private fun onRemoteIce(control: CallControl) {
        val encoded = control.ice ?: return
        val ice = CallIce.decode(encoded) ?: return
        val applyNow: MediaEngine?
        synchronized(iceLock) {
            val s = session
            if (s != null && s.callId == control.callId && s.engine != null) {
                applyNow = s.engine
            } else {
                // ICE до offer, во время RINGING, или от другого callId — не в чужой PC.
                pendingRemoteIce.add(control.callId, encoded)
                applyNow = null
            }
        }
        if (applyNow != null) {
            applyNow.onRemoteIce(ice)
        } else {
            DiagnosticLog.event(
                DIAG_TAG, "ICE отложен",
                "callId" to DiagnosticLog.fp(control.callId),
                "вБуфере" to pendingRemoteIce.size(control.callId),
            )
        }
    }

    private fun flushRemoteIce(s: Session) {
        val engine = s.engine ?: return
        val queued = synchronized(iceLock) { pendingRemoteIce.take(s.callId) }
        if (queued.isEmpty()) return
        DiagnosticLog.event(
            DIAG_TAG, "ICE из буфера",
            "callId" to DiagnosticLog.fp(s.callId),
            "штук" to queued.size,
        )
        queued.mapNotNull { CallIce.decode(it) }.forEach { engine.onRemoteIce(it) }
    }

    private fun enableLocalIce(s: Session) {
        val queued: List<CallIce>
        synchronized(iceLock) {
            s.localIceReady = true
            queued = s.queuedLocalIce.toList()
            s.queuedLocalIce.clear()
        }
        queued.forEach { sendLocalIce(s, it) }
    }

    private fun sendLocalIce(s: Session, candidate: CallIce) {
        val callId = s.callId
        scope.launch(Dispatchers.IO) {
            runCatching {
                signaling.send(s.contact, CallControl(CallControl.TYPE_ICE, callId, ice = candidate.encode()))
            }
        }
    }

    /**
     * Завершает звонок.
     *
     * Порядок здесь — требование к отзывчивости, а не вкус. Сначала СИНХРОННО
     * (на потоке вызывающего) гасится сессия и выставляется [CallPhase.ENDED],
     * и только потом в фоне закрывается движок: `MediaEngine.close()` ждёт
     * поток WebRTC до 5 с, а до этой правки окно звонка висело на экране всё
     * это время плюс время POST'а `hangup` в релей.
     *
     * @param code машинно-читаемая причина: по ней выбирается тон завершения
     *   ([CallToneController]); текст [reason] для этого не годится — он
     *   локализован и предназначен человеку.
     * @param local завершение по действию пользователя на ЭТОМ устройстве —
     *   такую причину читать не нужно, окно закрывается почти сразу; чужой
     *   сброс/ошибку показываем чуть дольше.
     */
    private fun endCall(reason: String, code: CallEndCode, local: Boolean = false) {
        if (!ending.compareAndSet(false, true)) return
        val s = session
        if (s == null) {
            ending.set(false)
            return
        }
        // Сессии больше нет: повторные endCall (ICE CLOSED, hangup собеседника)
        // отвалятся на проверке выше, а новый входящий звонок сможет стартовать
        // не дожидаясь, пока догорит окно предыдущего.
        session = null
        s.engine?.listener = null
        cancelSetupWatchdog()
        cancelHeartbeat()
        pendingRemoteIce.drop(s.callId)
        stage(
            CallStage.ENDED,
            if (local) CallStageState.DONE else CallStageState.WARN,
            reason,
        )
        // Через publishPhase, а не copy() поверх текущего значения: если звонок
        // оборвался до первой публикации фазы (hangup собеседника прилетел
        // между созданием сессии и emit), в состоянии ещё лежит CallInfo.NONE,
        // и copy() дал бы карточку «завершено» без имени и без callId.
        //
        // Причина публикуется ТЕМ ЖЕ обновлением, а не следующим: иначе
        // наблюдатели видят промежуточный кадр «завершено, причина пустая» —
        // экран успевал мигнуть без текста, а озвучка выбирала тон по
        // отсутствующему коду.
        publishPhase(s, CallPhase.ENDED, endReason = reason, endCode = code)
        ending.set(false)

        scope.launch(Dispatchers.IO) {
            runCatching { s.engine?.close() }
            runCatching { turnProvisioner?.release(s.turnLease) ?: s.turnLease?.close() }
            runCatching { CallMediaService.stop(appContext) }
            s.callSecret.fill(0)
            ProcessDeathReport.markState(appContext, "звонка нет (завершён)")
            DiagnosticLog.sync(
                DIAG_TAG, "звонок завершён",
                "callId" to DiagnosticLog.fp(s.callId),
                "контакт" to DiagnosticLog.shortId(s.contact.contact.id),
                "причина" to reason,
                "локально" to local,
                *ProcSelf.fields(),
            )
            // Штатное завершение — хорошая точка, чтобы гарантированно положить
            // хвост журнала на диск: дальше пользователь пойдёт его выгружать.
            DiagnosticLog.checkpoint()
        }

        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(if (local) END_HOLD_LOCAL_MS else END_HOLD_REMOTE_MS)
            // Новый звонок мог начаться за время задержки — его состояние не
            // трогаем (публикация его фазы к тому же гасит этот job).
            if (session == null && _state.value.phase == CallPhase.ENDED) {
                _state.value = CallInfo.NONE
                resetStages()
            }
        }
    }

    /**
     * Смена фазы с журналированием. Вызывать только из корутин/нативных потоков:
     * [DiagnosticLog.sync] пишет в файл синхронно, и на UI-потоке это подвисание
     * кадра. Для UI-путей есть [publishPhase] + [logPhase].
     */
    private fun emit(s: Session, phase: CallPhase, mediaOverTor: Boolean? = null) {
        val prev = _state.value.phase
        publishPhase(s, phase, mediaOverTor)
        logPhase(s, prev, phase)
    }

    /** Код завершения по причине из сигналинга/сторожа (см. [humanReason]). */
    private fun endCodeFor(reason: String): CallEndCode = when (reason) {
        "declined" -> CallEndCode.DECLINED
        "busy" -> CallEndCode.BUSY
        "timeout" -> CallEndCode.TIMEOUT
        "unreachable" -> CallEndCode.UNREACHABLE
        else -> CallEndCode.ENDED
    }

    private fun logPhase(s: Session, from: CallPhase, to: CallPhase) {
        ProcessDeathReport.markState(
            appContext,
            "call=${DiagnosticLog.fp(s.callId)} фаза=${to.name} (было ${from.name})",
        )
        DiagnosticLog.sync(
            DIAG_TAG, "фаза",
            "callId" to DiagnosticLog.fp(s.callId),
            "было" to from.name,
            "стало" to to.name,
            *ProcSelf.fields(),
        )
    }

    /**
     * Обновляет наблюдаемое состояние звонка. Дешёвая операция, безопасна везде.
     *
     * @param endReason человекочитаемая причина завершения (только для
     *   [CallPhase.ENDED]).
     * @param endCode та же причина кодом — для логики (озвучка, журнал).
     */
    private fun publishPhase(
        s: Session,
        phase: CallPhase,
        mediaOverTor: Boolean? = null,
        endReason: String = "",
        endCode: CallEndCode = CallEndCode.NONE,
    ) {
        val prev = _state.value
        s.phaseSinceElapsed = SystemClock.elapsedRealtime()
        dismissJob?.cancel()
        val sameCall = prev.callId == s.callId
        _state.value = CallInfo(
            callId = s.callId,
            contactId = s.contact.contact.id,
            displayName = s.contact.contact.displayLabel(),
            direction = s.direction,
            media = s.media,
            phase = phase,
            sas = prev.takeIf { sameCall }?.sas.orEmpty(),
            mediaOverTor = mediaOverTor ?: prev.mediaOverTor,
            pskProtected = prev.takeIf { sameCall }?.pskProtected ?: false,
            frameLayer = prev.takeIf { sameCall }?.frameLayer ?: false,
            cameraEnabled = prev.takeIf { sameCall }?.cameraEnabled ?: true,
            endReason = endReason,
            endCode = endCode,
            startedAtMs = if (phase == CallPhase.ACTIVE && prev.startedAtMs == 0L) {
                System.currentTimeMillis()
            } else {
                prev.takeIf { sameCall }?.startedAtMs ?: 0L
            },
            stages = stageSnapshot(),
            relayedPath = prev.takeIf { sameCall }?.relayedPath ?: false,
            // Завершённый звонок не «восстанавливается»: флаг снимаем здесь, а
            // не отдельным обновлением состояния после publishPhase.
            reconnecting = phase != CallPhase.ENDED &&
                (prev.takeIf { sameCall }?.reconnecting ?: false),
        )
    }

    // --- Лента этапов -------------------------------------------------------

    /** Отмечает этап установки звонка и публикует ленту в [state]. */
    private fun stage(stage: CallStage, state: CallStageState, detail: String = "") {
        val snapshot = synchronized(stageLock) { stageLog.mark(stage, state, detail) }
        DiagnosticLog.event(
            DIAG_TAG, "этап",
            "этап" to stage.name,
            "состояние" to state.name,
        )
        _state.value = _state.value.copy(stages = snapshot)
    }

    private fun stageSnapshot(): List<CallStageEntry> = synchronized(stageLock) { stageLog.snapshot() }

    private fun resetStages() {
        synchronized(stageLock) { stageLog.reset() }
    }

    private fun reasonFor(control: CallControl): String = when (control.type) {
        CallControl.TYPE_BUSY -> str(R.string.call_reason_busy)
        else -> humanReason(control.reason ?: "ended")
    }

    /** Код завершения по сообщению собеседника (парный к [reasonFor]). */
    private fun endCodeFor(control: CallControl): CallEndCode = when (control.type) {
        CallControl.TYPE_BUSY -> CallEndCode.BUSY
        else -> endCodeFor(control.reason ?: "ended")
    }

    private fun humanReason(reason: String): String = when (reason) {
        "declined" -> str(R.string.call_reason_declined)
        "busy" -> str(R.string.call_reason_busy)
        "timeout" -> str(R.string.call_reason_timeout)
        "unreachable" -> str(R.string.call_reason_unreachable)
        else -> str(R.string.call_reason_ended)
    }

    /**
     * Причина отказа на установке. Наши собственные предварительные условия
     * ([CallSetupException]) уже несут понятный пользователю текст — оборачивать
     * его в «Не удалось начать звонок: …» значит прятать суть.
     */
    private fun setupFailureReason(t: Throwable, fallbackResId: Int): String =
        (t as? CallSetupException)?.message
            ?: str(fallbackResId, t.message ?: t.javaClass.simpleName)

    private fun permissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Сторож установки звонка.
     *
     * Бюджет зависит от фазы, потому что фазы ждут разного: в DIALING/RINGING мы
     * ждём ЧЕЛОВЕКА и терпим долго, а в CONNECTING ждём сеть — и 90 с молчания
     * (как было единым таймаутом) пользователь читает как зависание. Теперь
     * неудачное согласование обрывается через [CONNECT_TIMEOUT_MS] с отдельной
     * причиной «не удалось соединиться», а не общим «нет ответа».
     */
    private fun armSetupWatchdog(callId: String) {
        cancelSetupWatchdog()
        setupTimeoutJob = scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                val s = session ?: return@launch
                if (s.callId != callId) return@launch
                val info = _state.value
                val budget = when (info.phase) {
                    CallPhase.DIALING, CallPhase.RINGING -> RING_TIMEOUT_MS
                    // Через Tor каждая проверка ICE идёт по TCP в onion-цепочке,
                    // поэтому анонимному режиму нужен более щедрый бюджет.
                    CallPhase.CONNECTING ->
                        if (info.mediaOverTor) CONNECT_TIMEOUT_TOR_MS else CONNECT_TIMEOUT_MS
                    // ACTIVE/ENDED/IDLE: установка завершилась, сторож не нужен.
                    else -> return@launch
                }
                val phase = info.phase
                val inPhase = SystemClock.elapsedRealtime() - s.phaseSinceElapsed
                if (inPhase < budget) continue
                DiagnosticLog.w(DIAG_TAG) {
                    "таймаут установки callId=${DiagnosticLog.fp(callId)} фаза=$phase вФазеМс=$inPhase"
                }
                if (phase == CallPhase.CONNECTING) {
                    stage(CallStage.ICE, CallStageState.FAILED, str(R.string.call_stage_detail_no_path))
                    hangUp(reason = "unreachable")
                } else {
                    hangUp(reason = "timeout")
                }
                return@launch
            }
        }
    }

    private fun cancelSetupWatchdog() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
    }

    /**
     * Пульс фазы звонка: доказательство, что процесс был жив в момент T.
     *
     * Снимок потоков берётся ДЕШЁВЫЙ ([DiagnosticLog.threadBrief] — только имена
     * и состояния, без остановки виртуальной машины). Полные стеки снимаются раз
     * в [HEARTBEAT_DUMP_EVERY] тиков: они останавливают мир, а во время сборки
     * ICE это само по себе меняет тайминги нативных потоков WebRTC.
     *
     * Дополнительно каждый тик обновляет метку в
     * [ProcessDeathReport.markState]: если систему что-то заставит убить процесс
     * (LMK, ActivityManager), метка переживёт смерть и придёт в следующий запуск
     * вместе с причиной — это единственный способ узнать фазу звонка, когда
     * записать в журнал уже ничего не успели.
     */
    private fun armHeartbeat(callId: String) {
        cancelHeartbeat()
        heartbeatJob = scope.launch {
            var tick = 0
            while (true) {
                delay(HEARTBEAT_PERIOD_MS)
                val s = session ?: break
                if (s.callId != callId) break
                tick++
                val phase = _state.value.phase.name
                ProcessDeathReport.markState(
                    appContext,
                    "call=${DiagnosticLog.fp(callId)} фаза=$phase тик=$tick " +
                        "движок=${s.engine != null} turn=${s.turnLease != null}",
                )
                DiagnosticLog.sync(
                    DIAG_TAG, "пульс",
                    "callId" to DiagnosticLog.fp(callId),
                    "фаза" to phase,
                    "тик" to tick,
                    "движок" to (s.engine != null),
                    "turn" to (s.turnLease != null),
                    "потоки" to DiagnosticLog.threadBrief(),
                    *ProcSelf.fields(),
                )
                if (tick % HEARTBEAT_DUMP_EVERY == 0) {
                    DiagnosticLog.sync(
                        DIAG_TAG, "пульс полный дамп",
                        "тик" to tick,
                        "стек" to DiagnosticLog.threadDump(),
                    )
                }
            }
        }
    }

    private fun cancelHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Извлекает DTLS-фингерпринты из обоих SDP и возвращает канонический
     * «транскрипт» (отсортированный) для привязки ключей к каналу (§10.10).
     */
    private fun fingerprintContext(sdpA: String, sdpB: String): ByteArray {
        val fps = (extractFingerprints(sdpA) + extractFingerprints(sdpB)).sorted()
        return fps.joinToString("|").toByteArray(Charsets.UTF_8)
    }

    private fun extractFingerprints(sdp: String): List<String> =
        sdp.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("a=fingerprint:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }
            .toList()

    /**
     * Предварительное условие звонка не выполнено. Текст уже локализован и
     * предназначен пользователю (в отличие от технических исключений WebRTC).
     */
    private class CallSetupException(message: String) : Exception(message)

    private companion object {
        const val DIAG_TAG = "call"

        /** Корень ключей звонка: 256 бит из ОС-ГСЧ (см. `calls.rs`). */
        const val CALL_SECRET_BYTES = 32

        const val RING_TIMEOUT_MS = CallTimeouts.RING_MS

        /**
         * Ожидание сети на согласовании. Держать здесь прежние 90 с смысла нет:
         * если за это время ICE не собрал ни одной рабочей пары, он её и не
         * соберёт — дальше идёт только тишина на экране «Соединение…».
         */
        const val CONNECT_TIMEOUT_MS = 45_000L

        /** То же, но для медиа через Tor: проверки ICE идут по TCP в onion. */
        const val CONNECT_TIMEOUT_TOR_MS = 75_000L

        /** Шаг сторожа установки. */
        const val WATCHDOG_TICK_MS = 1_000L

        /** Ожидание подтверждения FGS звонка (одна попытка). */
        const val FGS_WAIT_MS = 2_500L

        /** Пауза перед повтором FGS: окно «приложение ещё не видимо». */
        const val FGS_RETRY_DELAY_MS = 400L

        /**
         * Сколько ждём резервный TURN в прямом режиме, прежде чем идти без него.
         * Считается от начала подготовки движка и покрывает `GET /v1/turn`
         * через Tor (2–3 с при готовой цепочке, дольше — на холодной).
         */
        const val TURN_DIRECT_BUDGET_MS = 7_000L

        /** Сброс по своей кнопке: окно уходит почти мгновенно. */
        const val END_HOLD_LOCAL_MS = 220L

        /** Чужой сброс/ошибка: даём прочитать причину. */
        const val END_HOLD_REMOTE_MS = 1_200L

        /** Период пульса фазы звонка. */
        const val HEARTBEAT_PERIOD_MS = 2_000L

        /** Каждый N-й тик — полный дамп потоков (останавливает VM, поэтому редко). */
        const val HEARTBEAT_DUMP_EVERY = 5
    }
}
