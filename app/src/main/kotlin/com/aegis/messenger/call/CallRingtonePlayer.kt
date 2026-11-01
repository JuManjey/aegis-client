package com.aegis.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aegis.core.common.call.tone.CallTone
import com.aegis.core.common.call.tone.CallToneRoute
import com.aegis.core.common.diag.DiagnosticLog

/**
 * Непрерывный «рингтон» входящего звонка (§14.5/§7.5).
 *
 * Само уведомление о звонке (см. [com.aegis.messenger.notifications.AegisNotifier])
 * делается ТИХИМ, а звук и вибрацию ведёт этот проигрыватель. Причина: звук
 * канала уведомления играет ОДИН раз, а для звонка нужен зацикленный рингтон на
 * всё время фазы RINGING. Кроме того, проигрыватель звучит и когда приложение на
 * переднем плане (оверлей CallScreen), где уведомление не показывается.
 *
 * Уважает режим звонка устройства ([AudioManager.getRingerMode]): «без звука» —
 * тишина, «вибро» — только вибрация.
 *
 * ## Два источника звука
 *
 * Мелодия берётся либо из URI (персональный рингтон контакта §14.5 или
 * системный дефолт — [MediaPlayer]), либо синтезируется в стиле приложения
 * ([CallTone.INCOMING_RING] через [CallTonePlayer]). Выбирает источник
 * `AppContainer`, здесь только воспроизведение: этому классу принадлежит
 * ИНВАРИАНТ «звук и вибрация входящего живут и гаснут вместе», и разносить его
 * по двум объектам значило бы получить звонящий телефон после нажатия
 * «Принять».
 *
 * Ничего не пишет на диск — состояние живёт в памяти (минимизация метаданных
 * §10.10). Вызовы [start]/[stop] идемпотентны и потокобезопасны.
 */
class CallRingtonePlayer(
    context: Context,
    private val tonePlayer: CallTonePlayer,
) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val vibrator: Vibrator? = resolveVibrator()

    private var player: MediaPlayer? = null
    private var vibrating = false

    /** Играет ли сейчас синтезированный рингтон (чтобы гасить только его). */
    private var synthRinging = false

    /**
     * Запускает рингтон и/или вибрацию. Повторный вызов, пока уже играет, —
     * no-op (не перезапускает, чтобы дозвон не «дёргался»).
     *
     * @param soundUri рингтон из URI (персональный или системный); `null` —
     *   либо звук выключен, либо используется [synth].
     * @param vibrate  включить непрерывную вибрацию.
     * @param synth    играть встроенный рингтон приложения. Учитывается только
     *   когда [soundUri] равен `null`: явно выбранная мелодия контакта важнее
     *   глобального стиля.
     */
    @Synchronized
    fun start(soundUri: Uri?, vibrate: Boolean, synth: Boolean = false) {
        if (soundPlaying || vibrating) return

        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val soundAllowed = ringerMode == AudioManager.RINGER_MODE_NORMAL
        val vibeAllowed = ringerMode != AudioManager.RINGER_MODE_SILENT

        if (soundAllowed) {
            // Два источника страхуют друг друга. Сбой не гипотетический: URI
            // может указывать на удалённый файл или на провайдера без прав, а
            // созданию `AudioTrack` может не хватить свободной дорожки. Молчащий
            // входящий звонок — это пропущенный звонок, поэтому вторая попытка
            // обязательна. Синтез идёт последним резервом осознанно: ему не
            // нужны ни файл, ни разрешения, ни сторонний провайдер.
            val started = when {
                soundUri != null -> startSound(soundUri) || startSynth()
                synth -> startSynth() || startSound(defaultRingtoneUri())
                else -> false
            }
            if (!started) {
                DiagnosticLog.w(DIAG_TAG) { "рингтон не запустился ни из URI, ни синтезом" }
            }
        }
        if (vibrate && vibeAllowed) startVibration()
    }

    /** Звучит ли сейчас мелодия (любым из двух источников). */
    private val soundPlaying: Boolean get() = player != null || synthRinging

    /** Останавливает звук и вибрацию (идемпотентно). */
    @Synchronized
    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        if (synthRinging) {
            // Только петля своего маршрута: тоны хода звонка (канал разговора)
            // ведёт CallToneController, и глушить их отсюда — значит съесть
            // «соединение установлено» ровно в момент нажатия «Принять».
            runCatching { tonePlayer.stopLoop(CallToneRoute.RINGER) }
            synthRinging = false
        }
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
    }

    /** @return удалось ли завести встроенный рингтон. */
    private fun startSynth(): Boolean {
        val started = runCatching { tonePlayer.startLoop(CallTone.INCOMING_RING) }
            .onFailure { error ->
                DiagnosticLog.e(DIAG_TAG, error) { "встроенный рингтон не запустился" }
            }
            .getOrDefault(false)
        synthRinging = started
        return started
    }

    private fun defaultRingtoneUri(): Uri? =
        runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) }.getOrNull()

    /** @return удалось ли завести мелодию из [soundUri]. */
    private fun startSound(soundUri: Uri?): Boolean {
        if (soundUri == null) return false
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val mp = MediaPlayer()
        return runCatching {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(appContext, soundUri)
            mp.isLooping = true
            // Проглатываем ошибки движка, чтобы не ронять приём звонка из-за звука.
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepare()
            mp.start()
            player = mp
            true
        }.onFailure { error ->
            runCatching { mp.release() }
            player = null
            DiagnosticLog.w(DIAG_TAG) {
                "мелодия из URI не запустилась: ${error.javaClass.simpleName}"
            }
        }.getOrDefault(false)
    }

    private fun startVibration() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        // Пауза 0 → вибро 700 мс → пауза 800 мс, повтор с начала (индекс 0).
        val timings = longArrayOf(0L, 700L, 800L)
        runCatching {
            val effect = VibrationEffect.createWaveform(timings, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Вибрацию без атрибутов Android 12+ считает «неизвестной» и молча
                // отбрасывает у процесса без экрана и без foreground-сервиса, а
                // также при включённом энергосбережении. Запрос связи система
                // пропускает в обоих случаях и, в отличие от RINGTONE, не гасит
                // системным «вибрировать при звонке» — решает настройка приложения.
                v.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST),
                )
            } else {
                v.vibrate(effect)
            }
            vibrating = true
        }.onFailure { error ->
            DiagnosticLog.w(DIAG_TAG) { "вибрация не запустилась: ${error.javaClass.simpleName}" }
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        const val DIAG_TAG = "call-ring"
    }
}
