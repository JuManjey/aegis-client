package com.aegis.messenger.messaging

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.aegis.core.common.AegisConstants
import com.aegis.core.common.audio.VoiceWaveform
import com.aegis.core.common.diag.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Запись голосового сообщения (§14.4) ПОЛНОСТЬЮ В ПАМЯТИ.
 *
 * ПОЧЕМУ НЕ [android.media.MediaRecorder]. Он умеет писать только в файл или в
 * `FileDescriptor` — то есть незашифрованный звук неизбежно оказывался бы на
 * диске, пусть и в приватном кэше (как вынужденно происходит со съёмкой на
 * камеру, см. [CameraCapture]). Для голосового такой компромисс не нужен:
 * [AudioRecord] отдаёт сырой PCM, [MediaCodec] кодирует его в AAC-LC, а кадры
 * ADTS собираются в `ByteArray` и уходят прямо в конвейер и в SQLCipher (§6.3).
 * Plaintext звука не существует нигде, кроме оперативной памяти.
 *
 * ПОЧЕМУ ADTS, А НЕ MP4/OGG. Контейнер MP4 требует перемещаемого вывода
 * (`MediaMuxer` умеет писать только в файл или FD — снова диск), а Opus в OGG
 * пришлось бы мультиплексировать вручную. ADTS — это поток самодостаточных
 * кадров: 7-байтовый заголовок несёт конфигурацию декодера, поэтому кадры можно
 * просто дописывать в буфер, а [android.media.MediaPlayer] воспроизводит такой
 * поток штатно ([VoicePlayer]).
 *
 * ОГИБАЮЩАЯ считается на лету из того же PCM ([VoiceWaveform.peakOfPcm16]) — это
 * бесплатно, потому что байты и так проходят через процесс. Получатель рисует
 * полоски, не декодируя звук.
 *
 * Жизненный цикл: [start] → (наблюдаем [state]) → [stop] либо [cancel].
 * Экземпляр одноразовый по смыслу, но переиспользуемый: после остановки его
 * можно запускать заново.
 *
 * ВАЖНО: вызывающий обязан иметь [android.Manifest.permission.RECORD_AUDIO] и
 * держать экран на переднем плане. Начиная с Android 14 микрофон в фоне
 * беззвучно отдаёт нули без foreground-сервиса типа `microphone`, поэтому UI
 * останавливает запись при уходе экрана в фон ([cancel]) вместо того, чтобы
 * отправить тишину.
 */
class VoiceRecorder(private val scope: CoroutineScope) {

    /** Состояние записи для UI. */
    sealed interface State {
        /** Запись не идёт. */
        data object Idle : State

        /**
         * Идёт запись. [levels] — скользящее окно последних измерений громкости
         * (не вся запись): полный массив нужен только в конце, а гнать его в UI
         * 20 раз в секунду означало бы копировать тысячи элементов на кадр.
         */
        data class Recording(val elapsedMs: Long, val levels: IntArray) : State {
            // IntArray в data class сравнивается по ссылке — для StateFlow это
            // означало бы «значение изменилось» даже при том же содержимом.
            // Здесь это и нужно (каждый тик — новый массив), но equals/hashCode
            // всё равно обязаны быть согласованы, иначе поведение зависит от
            // деталей реализации StateFlow.
            override fun equals(other: Any?): Boolean =
                other is Recording && other.elapsedMs == elapsedMs && other.levels.contentEquals(levels)

            override fun hashCode(): Int = 31 * elapsedMs.hashCode() + levels.contentHashCode()
        }
    }

    /** Готовая запись: закодированные байты + метаданные для [com.aegis.core.common.model.VoiceMeta]. */
    data class Result(
        /** AAC-LC в контейнере ADTS (`audio/aac`). */
        val bytes: ByteArray,
        val durationMs: Long,
        /** Огибающая в формате v1 ([VoiceWaveform.encode]). */
        val waveformB64: String,
    )

    /**
     * Чем закончилась запись. Именно исход, а не `Result?`: причину «ничего не
     * отправлено» обязан знать UI, иначе отказ микрофона и промах по кнопке
     * показывались бы одним и тем же сообщением. Как состояние в [state] это
     * выразить нельзя — по завершении записи состояние всегда [State.Idle]
     * (панель обязана исчезнуть), и терминальный исход в нём не удержался бы.
     */
    sealed interface Outcome {
        /** Запись готова к отправке. */
        data class Recorded(val result: Result) : Outcome

        /** Пользователь отменил (сдвиг влево / кнопка отмены). Сообщать не о чем. */
        data object Cancelled : Outcome

        /** Короче [AegisConstants.VOICE_MIN_DURATION_MS] — промах по кнопке. */
        data object TooShort : Outcome

        /** Микрофон недоступен: нет разрешения, занят другим приложением, отказ кодека. */
        data object Failed : Outcome
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Идёт ли запись прямо сейчас (для блокировки повторного старта). */
    val isRecording: Boolean get() = _state.value is State.Recording

    @Volatile
    private var stopRequested = false

    @Volatile
    private var discardRequested = false

    private var job: Job? = null

    /**
     * Начинает запись. Возвращает `false`, если запись уже идёт.
     *
     * [onFinished] вызывается РОВНО ОДИН раз, в корутине [scope] (то есть на
     * главном диспетчере для scope композиции). Единый терминальный колбэк вместо
     * наблюдения за состоянием гарантирует, что UI не отправит одну и ту же
     * запись дважды.
     */
    fun start(onFinished: (Outcome) -> Unit): Boolean {
        if (job?.isActive == true) return false
        stopRequested = false
        discardRequested = false
        job = scope.launch {
            val outcome = withContext(Dispatchers.IO) { record() }
            _state.value = State.Idle
            onFinished(outcome)
        }
        return true
    }

    /** Останавливает запись и отдаёт результат в `onFinished` из [start]. */
    fun stop() {
        stopRequested = true
    }

    /** Останавливает запись и ВЫБРАСЫВАЕТ её (в `onFinished` придёт `null`). */
    fun cancel() {
        discardRequested = true
        stopRequested = true
    }

    /**
     * Цикл записи: PCM → огибающая → AAC → кадры ADTS. Выполняется на IO.
     *
     * Все ресурсы освобождаются в `finally` на каждом уровне: недоосвобождённый
     * [AudioRecord] держит микрофон занятым для всей системы, и следующая запись
     * (в том числе звонок) уже не началась бы.
     */
    private fun record(): Outcome {
        val sampleRate = AegisConstants.VOICE_SAMPLE_RATE_HZ
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            DiagnosticLog.e(DIAG_TAG) { "AudioRecord: неподдерживаемый формат ($minBuffer)" }
            return Outcome.Failed
        }
        // Запас ×4: чтение идёт в одном потоке с кодированием, и на слабом
        // устройстве всплеск загрузки не должен приводить к переполнению
        // аппаратного буфера (это слышно как щелчки и пропуски слогов).
        val bufferBytes = maxOf(minBuffer * 4, READ_CHUNK_BYTES * 4)

        val recorder = createAudioRecord(sampleRate, bufferBytes) ?: return Outcome.Failed
        var codec: MediaCodec? = null
        try {
            codec = createEncoder(sampleRate)
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                DiagnosticLog.e(DIAG_TAG) { "AudioRecord не перешёл в RECORDING" }
                return Outcome.Failed
            }
            return runLoop(recorder, codec, sampleRate)
        } catch (e: Exception) {
            DiagnosticLog.e(DIAG_TAG, e) { "запись голосового сорвана" }
            return Outcome.Failed
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            runCatching { recorder.release() }
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    /**
     * Создаёт [AudioRecord], предпочитая источник `VOICE_RECOGNITION`.
     *
     * Он выбран не случайно: `MIC` на многих прошивках проходит через
     * агрессивную автоподстройку усиления и шумодав, из-за чего голосовое звучит
     * «пульсирующим», а огибающая становится ровной колбасой. `VOICE_COMMUNICATION`
     * добавляет эхоподавление, настроенное на дуплекс звонка, — для монолога оно
     * вредно. Если источник недоступен, честно откатываемся на `MIC`.
     */
    private fun createAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord? {
        for (source in intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val record = try {
                @Suppress("MissingPermission") // RECORD_AUDIO проверяет вызывающий (UI).
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: Exception) {
                // Чаще всего это отсутствующее разрешение (SecurityException) или
                // занятый микрофон — оба случая не исключительные для UI.
                DiagnosticLog.w(DIAG_TAG) { "AudioRecord($source) не создан: ${e.javaClass.simpleName}" }
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record.release() }
        }
        DiagnosticLog.e(DIAG_TAG) { "микрофон недоступен: нет ни одного рабочего источника" }
        return null
    }

    private fun createEncoder(sampleRate: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AegisConstants.VOICE_BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_CHUNK_BYTES * 2)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun runLoop(recorder: AudioRecord, codec: MediaCodec, sampleRate: Int): Outcome {
        val out = ByteArrayOutputStream(INITIAL_OUTPUT_CAPACITY)
        val pcm = ByteArray(READ_CHUNK_BYTES)
        val bufferInfo = MediaCodec.BufferInfo()
        val allLevels = ArrayList<Int>(EXPECTED_LEVELS)
        val liveWindow = ArrayDeque<Int>(LIVE_WINDOW)

        val samplesPerBucket = sampleRate * AegisConstants.VOICE_WAVEFORM_STEP_MS / 1000
        val maxSamples = AegisConstants.VOICE_MAX_DURATION_MS * sampleRate / 1000

        var totalSamples = 0L
        var bucketSamples = 0
        var bucketPeak = 0
        var presentationUs = 0L
        var endOfStreamSent = false
        // Число холостых опросов ПОСЛЕ отправки EOS. Ограничение обязательно:
        // если кодек так и не отдаст кадр с флагом конца потока, цикл вращался бы
        // вечно, удерживая микрофон занятым для всей системы (включая звонки).
        var idleAfterEos = 0

        while (true) {
            val reachedLimit = totalSamples >= maxSamples
            val finishing = stopRequested || reachedLimit

            if (!finishing) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read < 0) {
                    DiagnosticLog.e(DIAG_TAG) { "AudioRecord.read вернул ошибку: $read" }
                    return Outcome.Failed
                }
                if (read > 0) {
                    // Кодировать ОБЯЗАТЕЛЬНО до учёта длительности: если кадр
                    // не влез (кодек не отдал входной буфер), считать его
                    // сэмплы нельзя — длительность и огибающая разъехались бы с
                    // реальным звуком, а по длительности потом считается
                    // положение курсора при воспроизведении.
                    val queued = feedEncoder(codec, bufferInfo, out, sampleRate, pcm, read, presentationUs)
                    if (queued) {
                        // Огибающая — из ТОГО ЖЕ буфера, до кодирования: после AAC
                        // пришлось бы декодировать обратно, чтобы узнать громкость.
                        val peak = VoiceWaveform.peakOfPcm16(pcm, read)
                        if (peak > bucketPeak) bucketPeak = peak
                        val samples = read / BYTES_PER_SAMPLE
                        bucketSamples += samples
                        totalSamples += samples
                        if (bucketSamples >= samplesPerBucket) {
                            allLevels += bucketPeak
                            if (liveWindow.size == LIVE_WINDOW) liveWindow.removeFirst()
                            liveWindow.addLast(bucketPeak)
                            publish(totalSamples, sampleRate, liveWindow)
                            bucketPeak = 0
                            bucketSamples = 0
                        }
                        presentationUs += samples.toLong() * 1_000_000L / sampleRate
                    } else {
                        DiagnosticLog.w(DIAG_TAG) { "кодек не принял кадр PCM ($read Б) — пропуск" }
                    }
                }
            } else if (!endOfStreamSent) {
                // Флаг ставим ТОЛЬКО когда EOS реально поставлен в очередь: иначе
                // кодек его не получит, кадра с концом потока не будет, и запись
                // завершится по таймауту, потеряв последние закодированные кадры.
                endOfStreamSent =
                    feedEncoder(codec, bufferInfo, out, sampleRate, pcm, 0, presentationUs, endOfStream = true)
            }

            when (drainEncoder(codec, bufferInfo, out, sampleRate)) {
                DrainResult.END_OF_STREAM -> break
                // Кадр записан: после EOS кодек обычно доотдаёт буферизованное
                // несколькими кадрами, и это нормальный ход — счётчик холостых
                // опросов сбрасываем, чтобы не оборвать выгрузку на полпути.
                DrainResult.WROTE -> idleAfterEos = 0
                DrainResult.TRY_AGAIN -> if (endOfStreamSent) {
                    idleAfterEos++
                    if (idleAfterEos >= EOS_GRACE_ATTEMPTS) {
                        DiagnosticLog.w(DIAG_TAG) { "кодек не отдал EOS — завершаю по таймауту" }
                        break
                    }
                }
            }
        }

        if (bucketSamples > 0) allLevels += bucketPeak
        val durationMs = durationMs(totalSamples, sampleRate)
        val bytes = out.toByteArray()

        if (discardRequested) {
            DiagnosticLog.event(DIAG_TAG, "запись голосового отменена", "мс" to durationMs)
            return Outcome.Cancelled
        }
        if (durationMs < AegisConstants.VOICE_MIN_DURATION_MS || bytes.isEmpty()) {
            DiagnosticLog.event(
                DIAG_TAG, "запись голосового отброшена как слишком короткая",
                "мс" to durationMs, "байт" to bytes.size,
            )
            return Outcome.TooShort
        }
        DiagnosticLog.event(
            DIAG_TAG, "голосовое записано",
            "мс" to durationMs, "байт" to bytes.size, "измерений" to allLevels.size,
        )
        return Outcome.Recorded(
            Result(
                bytes = bytes,
                durationMs = durationMs,
                waveformB64 = VoiceWaveform.encode(
                    VoiceWaveform.normalize(VoiceWaveform.resample(allLevels.toIntArray())),
                ),
            ),
        )
    }

    /**
     * Публикует тик живой дорожки.
     *
     * Массив ВСЕГДА фиксированной длины [LIVE_WINDOW], а реальные измерения
     * лежат в начале: иначе в первые секунды записи дорожка рисовала бы три
     * широких блока на всю ширину, а потом они скачком «худели» бы до нормальной
     * толщины. С фиксированной длиной дорожка заполняется слева направо, как
     * лента монтажного стола, а после заполнения начинает прокручиваться
     * (старейшее измерение выпадает из очереди).
     */
    private fun publish(totalSamples: Long, sampleRate: Int, window: ArrayDeque<Int>) {
        val levels = IntArray(LIVE_WINDOW)
        var i = 0
        for (value in window) {
            if (i >= LIVE_WINDOW) break
            levels[i++] = value
        }
        _state.value = State.Recording(
            elapsedMs = durationMs(totalSamples, sampleRate),
            levels = levels,
        )
    }

    private fun durationMs(totalSamples: Long, sampleRate: Int): Long =
        totalSamples * 1000L / sampleRate

    /**
     * Ставит кадр PCM (или маркер конца потока) в очередь кодека. `true` — принят.
     *
     * Между попытками кодек ОПУСТОШАЕТСЯ: входных буферов нет именно тогда, когда
     * не забраны выходные, поэтому «подождать ещё» без чтения выхода — это ждать
     * состояния, которое само не изменится.
     */
    @Suppress("LongParameterList")
    private fun feedEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        out: ByteArrayOutputStream,
        sampleRate: Int,
        pcm: ByteArray,
        length: Int,
        presentationUs: Long,
        endOfStream: Boolean = false,
    ): Boolean {
        repeat(FEED_ATTEMPTS) { attempt ->
            val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    // Буфер выдан, но недоступен: вернуть его кодеку нельзя иначе,
                    // чем поставив в очередь пустым, иначе он «утечёт» из пула.
                    codec.queueInputBuffer(index, 0, 0, presentationUs, 0)
                    return false
                }
                buffer.clear()
                if (length > 0) buffer.put(pcm, 0, length)
                codec.queueInputBuffer(
                    index,
                    0,
                    length,
                    presentationUs,
                    if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                )
                return true
            }
            if (attempt < FEED_ATTEMPTS - 1) {
                drainEncoder(codec, info, out, sampleRate)
            }
        }
        return false
    }

    private enum class DrainResult { WROTE, TRY_AGAIN, END_OF_STREAM }

    /**
     * Забирает готовые кадры и дописывает их в поток с заголовком ADTS.
     *
     * Кадр `BUFFER_FLAG_CODEC_CONFIG` (csd-0 AAC) намеренно ПРОПУСКАЕТСЯ: в ADTS
     * конфигурация декодера уже закодирована в заголовке каждого кадра, а лишние
     * байты в начале потока сделали бы его неразбираемым для `MediaExtractor`.
     */
    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        out: ByteArrayOutputStream,
        sampleRate: Int,
    ): DrainResult {
        val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
        if (index < 0) return DrainResult.TRY_AGAIN
        try {
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val frame = ByteArray(info.size)
                    buffer.get(frame)
                    out.write(adtsHeader(info.size, sampleRate))
                    out.write(frame)
                }
            }
            return if (eos) DrainResult.END_OF_STREAM else DrainResult.WROTE
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private companion object {
        const val DIAG_TAG = "voice-rec"

        /** 16-битный моно-сэмпл. */
        const val BYTES_PER_SAMPLE = 2

        /**
         * Размер чтения. 2048 Б = 1024 сэмпла = ровно один кадр AAC при 16 кГц
         * (64 мс): кодек получает целые кадры, и ему не приходится буферизовать
         * «хвосты» между вызовами.
         */
        const val READ_CHUNK_BYTES = 2048

        const val CODEC_TIMEOUT_US = 10_000L

        /**
         * Сколько раз пытаться отдать кадр кодеку (с опустошением выхода между
         * попытками) до того, как признать кадр потерянным.
         */
        const val FEED_ATTEMPTS = 4

        /** Сколько холостых опросов ждать кадр с EOS, прежде чем закрыть поток. */
        const val EOS_GRACE_ATTEMPTS = 20

        /** Окно живой дорожки: ~5 секунд при шаге 50 мс. */
        const val LIVE_WINDOW = 100

        /** Ожидаемое число измерений (для предразмера списка): минута записи. */
        const val EXPECTED_LEVELS = 60 * 1000 / AegisConstants.VOICE_WAVEFORM_STEP_MS

        /** Предразмер выходного буфера: ~10 с при целевом битрейте. */
        const val INITIAL_OUTPUT_CAPACITY = 32 * 1024

        /**
         * Индексы частот дискретизации ADTS (таблица ISO/IEC 13818-7).
         * Нужен только индекс НАШЕЙ частоты, но таблица приведена целиком, чтобы
         * смена [AegisConstants.VOICE_SAMPLE_RATE_HZ] не требовала правки кода.
         */
        val ADTS_SAMPLE_RATES = intArrayOf(
            96_000, 88_200, 64_000, 48_000, 44_100, 32_000,
            24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
        )
    }

    /**
     * Семибайтовый заголовок кадра ADTS (без CRC) для AAC-LC, 1 канал.
     *
     * Раскладка полей — ISO/IEC 13818-7. Собирается вручную, потому что
     * платформа его не отдаёт: `MediaCodec` выдаёт «сырой» AAC, а единственный
     * штатный мультиплексор (`MediaMuxer`) умеет писать только в файл/FD, то
     * есть вернул бы plaintext на диск — ровно то, чего мы избегаем (§6.3).
     */
    private fun adtsHeader(payloadLength: Int, sampleRate: Int): ByteArray {
        val frameLength = payloadLength + ADTS_HEADER_SIZE
        val freqIndex = ADTS_SAMPLE_RATES.indexOf(sampleRate).let { if (it >= 0) it else 8 }
        val profile = 2 // AAC-LC
        val channels = 1
        val header = ByteArray(ADTS_HEADER_SIZE)
        header[0] = 0xFF.toByte()
        // 0xF9: MPEG-4, Layer 00, protection_absent = 1 (без CRC).
        header[1] = 0xF9.toByte()
        header[2] = (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 0x03) shl 6) or (frameLength shr 11)).toByte()
        header[4] = ((frameLength and 0x7FF) shr 3).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header
    }
}

/** Длина заголовка ADTS без CRC. */
private const val ADTS_HEADER_SIZE = 7
