package com.aegis.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.aegis.core.common.diag.DiagnosticLog

/**
 * Единственный владелец аудиосессии звонка: режим [AudioManager.MODE_IN_COMMUNICATION]
 * и аудиофокус.
 *
 * ## Зачем синглтон
 *
 * Режим аудио и фокус — ресурсы ПРОЦЕССА, а не объекта: их в системе ровно по
 * одному. Раньше ими владел [WebRtcMediaEngine] в своих `enterCallAudio`/
 * `leaveCallAudio`, и это давало два дефекта:
 *
 *  1. **Потеря исходного режима.** Каждый движок запоминал `previousAudioMode`
 *     у себя. Когда новый звонок начинался до того, как закрылся предыдущий
 *     движок (а `close()` ждёт поток WebRTC до 5 с — см. `closeInternal`),
 *     второй движок запоминал уже `MODE_IN_COMMUNICATION` как «прежний» и
 *     навсегда оставлял систему в режиме разговора. Симптом: после звонка у
 *     музыки и уведомлений «пропадает» громкость.
 *  2. **Невозможность звучать до движка.** «Гудкам» ([CallTonePlayer]) нужен
 *     режим разговора ЗАРАНЕЕ, иначе первый `AudioTrack` с
 *     `USAGE_VOICE_COMMUNICATION` уходит в громкоговоритель, а через секунду
 *     движок переводит режим — и тон на полуслове перескакивает в ухо.
 *
 * Счётчик ссылок решает оба: исходный режим запоминается ОДИН раз (при первом
 * [acquire]) и восстанавливается ОДИН раз (при последнем [release]), а тон и
 * движок могут держать сессию одновременно и в любом порядке.
 *
 * ## Тип фокуса
 *
 * Запрашивается [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE] — ровно то,
 * что делает телефония: чужое воспроизведение ПРИОСТАНАВЛИВАЕТСЯ (без
 * приглушения, чтобы музыка не подмешивалась в разговор) и возобновляется после
 * звонка. Прежний `AUDIOFOCUS_GAIN` был постоянным: плеер получал команду
 * «остановись насовсем» и после звонка сам не возвращался.
 *
 * Все методы потокобезопасны и идемпотентны в паре: колбэки WebRTC приходят с
 * нативных потоков, тон гасится из корутины, блокировка аккаунта — из третьей.
 */
object CallAudioSession {

    private const val DIAG_TAG = "call-audio"

    private val lock = Any()

    /** Сколько владельцев (движок, проигрыватель тонов) держат сессию. */
    private var refCount = 0

    /** Режим до первого [acquire]; `null` — сессия не удерживается. */
    private var savedMode: Int? = null

    /** Запрос фокуса нужно отменять ТЕМ ЖЕ объектом, поэтому храним его. */
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Фокус меняется системой (входящий системный звонок, будильник). Своё
     * поведение мы от этого не меняем — звонок AEGIS продолжается, — но факт
     * важен для разбора жалоб «пропал звук», поэтому журналируем.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        DiagnosticLog.event(DIAG_TAG, "фокус изменён", "код" to change)
    }

    /**
     * Захватывает сессию. Первый вызов переводит систему в режим разговора и
     * забирает фокус; последующие только увеличивают счётчик.
     */
    fun acquire(context: Context) {
        val manager = audioManager(context) ?: return
        synchronized(lock) {
            refCount++
            if (refCount > 1) {
                DiagnosticLog.event(DIAG_TAG, "сессия удержана", "ссылок" to refCount)
                return
            }
            savedMode = runCatching { manager.mode }.getOrNull()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                // Отложенный фокус означал бы звонок без звука до момента, когда
                // его отдадут; для разговора это хуже, чем отказ.
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .build()
            val granted = runCatching { manager.requestAudioFocus(request) }
                .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            focusRequest = request
            runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
            DiagnosticLog.sync(
                DIAG_TAG, "сессия захвачена",
                "былРежим" to savedMode,
                "сталРежим" to runCatching { manager.mode }.getOrNull(),
                "фокус" to granted,
            )
        }
    }

    /**
     * Отпускает сессию. Последний владелец восстанавливает исходный режим и
     * возвращает фокус. Лишние вызовы игнорируются (счётчик не уходит ниже нуля).
     */
    fun release(context: Context) {
        val manager = audioManager(context) ?: return
        synchronized(lock) {
            if (refCount == 0) {
                DiagnosticLog.w(DIAG_TAG) { "release без acquire — игнорируем" }
                return
            }
            refCount--
            if (refCount > 0) {
                DiagnosticLog.event(DIAG_TAG, "сессия ещё нужна", "ссылок" to refCount)
                return
            }
            restore(manager)
        }
    }

    /**
     * Принудительно освобождает сессию, каким бы ни был счётчик.
     *
     * Нужно на аварийных путях, где владельцы могли не отработать: блокировка
     * аккаунта/дюресс-стирание (`AppContainer`) и восстановление после падения
     * нативного потока WebRTC. Без этого система могла остаться в режиме
     * разговора до перезапуска процесса.
     */
    fun reset(context: Context) {
        val manager = audioManager(context) ?: return
        synchronized(lock) {
            if (refCount == 0 && savedMode == null && focusRequest == null) return
            DiagnosticLog.w(DIAG_TAG) { "принудительный сброс сессии, ссылок=$refCount" }
            refCount = 0
            restore(manager)
        }
    }

    /** Удерживается ли сессия сейчас (для диагностики и тестов). */
    val isHeld: Boolean get() = synchronized(lock) { refCount > 0 }

    /** Восстановление состояния системы. Вызывать только под [lock]. */
    private fun restore(manager: AudioManager) {
        focusRequest?.let { request ->
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
        // MODE_NORMAL как запасной вариант: если прочитать режим при захвате не
        // удалось, оставить систему в MODE_IN_COMMUNICATION нельзя — это ломает
        // звук всему устройству, а не только приложению.
        val target = savedMode ?: AudioManager.MODE_NORMAL
        runCatching { manager.mode = target }
        savedMode = null
        DiagnosticLog.sync(
            DIAG_TAG, "сессия освобождена",
            "режим" to runCatching { manager.mode }.getOrNull(),
        )
    }

    private fun audioManager(context: Context): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
}
