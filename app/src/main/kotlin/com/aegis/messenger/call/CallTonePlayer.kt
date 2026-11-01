package com.aegis.messenger.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.aegis.core.common.call.tone.CallTone
import com.aegis.core.common.call.tone.CallToneRoute
import com.aegis.core.common.call.tone.CallToneSynth
import com.aegis.core.common.diag.DiagnosticLog
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Воспроизведение тонов звонка ([CallTone]) через [AudioTrack].
 *
 * ## Почему AudioTrack, а не MediaPlayer/SoundPool
 *
 *  * `MediaPlayer` умеет только источники (файл/URI) — у нас PCM в памяти;
 *  * `SoundPool` декодирует свои форматы и не даёт бесшовной петли нужной длины;
 *  * `AudioTrack` в режиме [AudioTrack.MODE_STATIC] принимает готовый буфер и
 *    зацикливает его АППАРАТНО ([AudioTrack.setLoopPoints]) — «гудки» идут без
 *    паузы и дрейфа на стыке, чего циклом на Java добиться нельзя.
 *
 * ## Маршрутизация
 *
 * Тон канала разговора ([CallToneRoute.CALL]) на время звучания держит
 * [CallAudioSession]: это переводит систему в `MODE_IN_COMMUNICATION` ДО первого
 * кадра, поэтому «гудок» сразу идёт в ухо, а не начинается в громкоговорителе и
 * прыгает в ухо, когда медиадвижок поднимет свой звук. Рингтон
 * ([CallToneRoute.RINGER]) режим не трогает — ему нужен громкоговоритель и
 * громкость звонка.
 *
 * ## Инварианты
 *
 *  * зацикленный тон — РОВНО ОДИН НА МАРШРУТ ([CallToneRoute]); следующий тон
 *    того же маршрута вытесняет прежний, чужой маршрут не трогает;
 *  * одиночные тоны могут наложиться на зацикленные и друг на друга;
 *  * повторный [startLoop] с тем же тоном — no-op (не перезапускает каденцию);
 *  * все методы потокобезопасны и идемпотентны: их зовут и из корутин, и с
 *    UI-потока (мгновенная тишина по нажатию), и из колбэков WebRTC.
 *
 * ## Почему петли разделены по маршрутам
 *
 * Рингтоном входящего ([CallToneRoute.RINGER]) управляет [CallRingtonePlayer], а
 * тонами разговора ([CallToneRoute.CALL]) — [CallToneController], и это ДВА
 * независимых подписчика одного и того же `CallManager.state`. С единственным
 * слотом порядок их пробуждения решал бы исход: на переходе
 * `RINGING → CONNECTING` подписчик рингтона гасил бы петлю, которую подписчик
 * тонов только что завёл, и согласование проходило бы молча. Слот на маршрут
 * убирает эту гонку по построению: каждый владелец гасит только своё.
 */
class CallTonePlayer(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    /**
     * Отрендеренный PCM по тону. Рендер детерминирован и не имеет побочных
     * эффектов, поэтому гонка за вычисление безвредна — берём
     * [ConcurrentHashMap] вместо блокировки на время синтеза.
     */
    private val pcmCache = ConcurrentHashMap<CallTone, ShortArray>()

    private val lock = Any()

    /** Текущая зацикленная петля по маршруту (см. KDoc класса). */
    private val loops = EnumMap<CallToneRoute, Playback>(CallToneRoute::class.java)

    /** Играющие одиночные тоны (обычно 0–1, на стыке событий — 2). */
    private val oneShots = mutableSetOf<Playback>()

    /**
     * Прогревает кэш PCM, чтобы первый тон звонка не ждал синтеза. Дёшево
     * (единицы миллисекунд на тон), но выполняется в фоне: вызывается в момент,
     * когда процессор уже занят поднятием движка и Tor-цепочки.
     */
    fun prewarm(vararg tones: CallTone) {
        scope.launch {
            tones.forEach { tone -> runCatching { pcm(tone) } }
        }
    }

    /**
     * Запускает зацикленный тон, вытесняя предыдущую петлю ЕГО маршрута. Тон
     * обязан быть зацикливаемым ([CallTone.looping]) — иначе это ошибка
     * вызывающего, и молча подменять семантику хуже, чем упасть в отладке.
     *
     * @return звучит ли теперь этот тон. Результат ВАЖЕН для вызывающего:
     *   синтез или создание `AudioTrack` может не удаться (нет свободной
     *   дорожки, отказ прошивки), и владелец рингтона обязан узнать об этом,
     *   чтобы уйти на резервный источник звука. Беззвучный входящий звонок —
     *   это пропущенный звонок, а не косметический дефект.
     */
    fun startLoop(tone: CallTone): Boolean {
        require(tone.looping) { "тон ${tone.name} не зацикливаемый" }
        // Синтез — ВНЕ блокировки: иначе первый вызов с UI-потока подвесил бы
        // кадр на время рендера 2–3 секунд звука.
        val samples = pcm(tone) ?: return false
        synchronized(lock) {
            val current = loops[tone.route]
            if (current?.tone == tone) return true
            // Прежняя петля гасится ДО старта новой, под той же блокировкой:
            // иначе на смене фазы два зацикленных тона на мгновение звучат
            // вместе, а счётчик аудиосессии успевает подскочить до двух.
            current?.let { stopPlayback(it) }
            val started = start(tone, samples, looping = true)
            if (started == null) {
                loops.remove(tone.route)
                DiagnosticLog.w(DIAG_TAG) { "зацикленный тон ${tone.name} не запустился" }
                return false
            }
            loops[tone.route] = started
            return true
        }
    }

    /**
     * Останавливает зацикленный тон указанного маршрута (идемпотентно).
     * Маршрут обязателен намеренно: он и есть граница ответственности между
     * владельцем рингтона и владельцем тонов разговора.
     */
    fun stopLoop(route: CallToneRoute) {
        synchronized(lock) {
            loops.remove(route)?.let { stopPlayback(it) }
        }
    }

    /**
     * Проигрывает тон один раз. Дорожка освобождается сама по истечении
     * длительности тона — воспроизведение переживает закрытие экрана звонка
     * (тон завершения обязан догореть после того, как окно уже закрылось).
     */
    fun playOnce(tone: CallTone) {
        val samples = pcm(tone) ?: return
        val playback = synchronized(lock) {
            start(tone, samples, looping = false)?.also { oneShots += it }
        } ?: return
        val durationMs = samples.size * 1000L / CallToneSynth.SAMPLE_RATE_HZ
        playback.finisher = scope.launch {
            delay(durationMs + TAIL_SLACK_MS)
            synchronized(lock) {
                if (oneShots.remove(playback)) {
                    // Это наша собственная корутина: ссылку снимаем заранее,
                    // чтобы stopPlayback не отменял сам себя.
                    playback.finisher = null
                    stopPlayback(playback)
                }
            }
        }
    }

    /**
     * Обрывает все одиночные тоны. Отдельно от [stopLoop], потому что владелец
     * у них тот же ([CallToneController]), а у петли рингтона — другой: «выключи
     * тоны разговора» не должно глушить звонящий телефон.
     */
    fun stopOneShots() {
        synchronized(lock) {
            oneShots.forEach { stopPlayback(it) }
            oneShots.clear()
        }
    }

    /**
     * Отдаёт память кэша PCM (~1 МБ на полную палитру в 48 кГц). Играющие тоны
     * НЕ обрывает: `AudioTrack` в `MODE_STATIC` держит собственную копию
     * буфера, поэтому чистка кэша для них безопасна.
     *
     * Вызывается, когда звонков больше нет: держать буферы «на всякий случай»
     * на слабом устройстве дороже, чем пересинтезировать их за миллисекунды.
     */
    fun clearCache() {
        pcmCache.clear()
    }

    /**
     * Полная и немедленная тишина + освобождение памяти.
     *
     * Только для аварийных путей (блокировка аккаунта, дюресс-стирание): в
     * штатном завершении звонка это ОБОРВАЛО БЫ тон завершения, который по
     * длительности переживает закрытие окна звонка.
     */
    fun release() {
        synchronized(lock) {
            loops.values.forEach { stopPlayback(it) }
            loops.clear()
            oneShots.forEach { stopPlayback(it) }
            oneShots.clear()
        }
        pcmCache.clear()
    }

    // --- Внутреннее ----------------------------------------------------------

    private fun pcm(tone: CallTone): ShortArray? = runCatching {
        pcmCache.computeIfAbsent(tone) { CallToneSynth.render(it) }
    }.onFailure { error ->
        DiagnosticLog.e(DIAG_TAG, error) { "синтез тона ${tone.name} не удался" }
    }.getOrNull()

    /**
     * Создаёт и запускает дорожку. Вызывать только под [lock].
     *
     * Дорожка создаётся на каждое воспроизведение, а не переиспользуется:
     * `MODE_STATIC` требует [AudioTrack.reloadStaticData] для повторного пуска,
     * и на части прошивок повторный пуск «залипшей» дорожки даёт тишину без
     * ошибки. Создание занимает единицы миллисекунд — цена верна.
     */
    private fun start(tone: CallTone, samples: ShortArray, looping: Boolean): Playback? {
        val holdsSession = tone.route == CallToneRoute.CALL
        // Сессия захватывается ДО создания дорожки: маршрут (ухо/динамик)
        // фиксируется системой в момент создания AudioTrack, и режим разговора
        // должен быть уже выставлен.
        if (holdsSession) CallAudioSession.acquire(appContext)
        val track = runCatching { buildTrack(tone, samples, looping) }
            .onFailure { error ->
                DiagnosticLog.e(DIAG_TAG, error) { "дорожка тона ${tone.name} не создана" }
            }
            .getOrNull()
        if (track == null) {
            if (holdsSession) CallAudioSession.release(appContext)
            return null
        }
        DiagnosticLog.event(
            DIAG_TAG, "тон",
            "тон" to tone.name,
            "петля" to looping,
            "маршрут" to tone.route.name,
            "кадров" to samples.size,
        )
        return Playback(tone, track, holdsSession)
    }

    private fun buildTrack(tone: CallTone, samples: ShortArray, looping: Boolean): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (tone.route) {
                    CallToneRoute.CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                    CallToneRoute.RINGER -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CallToneSynth.SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC)
            // Для MODE_STATIC размер буфера — это и есть длина данных.
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .build()
        try {
            val written = track.write(samples, 0, samples.size)
            if (written < samples.size) {
                error("записано $written из ${samples.size} отсчётов")
            }
            if (looping) {
                // -1 — бесконечно. Границы в КАДРАХ; поток моно, значит кадр = отсчёт.
                val result = track.setLoopPoints(0, samples.size, -1)
                if (result != AudioTrack.SUCCESS) error("setLoopPoints вернул $result")
            }
            track.play()
        } catch (t: Throwable) {
            runCatching { track.release() }
            throw t
        }
        return track
    }

    /**
     * Останавливает и освобождает дорожку. Вызывается под [lock] — сам он
     * блокировок не берёт, кроме внутренней у [CallAudioSession], которая
     * обратно в проигрыватель не заходит (порядок захвата всегда один).
     */
    private fun stopPlayback(playback: Playback) {
        playback.finisher?.cancel()
        runCatching { playback.track.pause() }
        runCatching { playback.track.flush() }
        runCatching { playback.track.stop() }
        runCatching { playback.track.release() }
        if (playback.holdsSession) CallAudioSession.release(appContext)
    }

    private class Playback(
        val tone: CallTone,
        val track: AudioTrack,
        /** Держит ли это воспроизведение [CallAudioSession]. */
        val holdsSession: Boolean,
    ) {
        /** Корутина, освобождающая дорожку одиночного тона по таймеру. */
        @Volatile
        var finisher: Job? = null
    }

    private companion object {
        const val DIAG_TAG = "call-tone"

        /**
         * Запас к длительности одиночного тона перед освобождением дорожки.
         * Покрывает задержку самого микшера: без запаса `release()` обрезал
         * последние миллисекунды затухания и возвращал щелчок, от которого
         * избавлялись огибающей.
         */
        const val TAIL_SLACK_MS = 120L
    }
}
