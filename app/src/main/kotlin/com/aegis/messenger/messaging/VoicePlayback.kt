package com.aegis.messenger.messaging

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.aegis.core.common.diag.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Воспроизведение голосовых сообщений в ленте чата (§14.4).
 *
 * ПОЧЕМУ ОДИН КОНТРОЛЛЕР НА ЭКРАН, А НЕ ПЛЕЕР В КАЖДОМ ПУЗЫРЕ. Одновременное
 * звучание двух голосовых — это не «фича», а дефект: пользователь тапает по
 * второму сообщению и слышит кашу. Владение плеером поднято на экран, поэтому
 * запуск нового трека автоматически глушит предыдущий, а сам плеер (и занятый им
 * аудиофокус) гарантированно освобождается при уходе с экрана.
 *
 * ПОЧЕМУ [MediaDataSource], А НЕ ФАЙЛ. Расшифрованный звук живёт только в
 * оперативной памяти (§6.3): `MediaPlayer` читает байты из массива через
 * [ByteArrayMediaDataSource] — тем же приёмом, что [VideoFrameDecoder] для
 * превью видео. Временного plaintext-файла не существует, поэтому и затирать
 * (в отличие от [CameraCapture]) нечего.
 *
 * ПОЗИЦИЯ ОПРАШИВАЕТСЯ, а не приходит колбэком: у `MediaPlayer` нет события
 * прогресса. Опрос идёт только пока реально играет звук, с шагом
 * [POSITION_TICK_MS], и пишет в [androidx.compose.runtime.MutableState] —
 * дорожка перерисовывается, но список сообщений НЕ рекомпозируется (см.
 * `AegisHudWaveform`).
 */
class VoicePlaybackController(private val scope: CoroutineScope) {

    /** id сообщения, которое сейчас загружено в плеер (или `null`). */
    val activeMessageId: MutableState<String?> = mutableStateOf(null)

    /** Идёт ли воспроизведение прямо сейчас. */
    val playing: MutableState<Boolean> = mutableStateOf(false)

    /** Позиция внутри активного сообщения, мс. */
    val positionMs: MutableState<Long> = mutableStateOf(0L)

    /** Длительность активного сообщения, мс (0, если неизвестна). */
    val durationMs: MutableState<Long> = mutableStateOf(0L)

    /** id сообщения, на котором воспроизведение сорвалось (для показа ошибки). */
    val failedMessageId: MutableState<String?> = mutableStateOf(null)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /**
     * СОБСТВЕННАЯ копия открытого звука, живущая ровно столько, сколько играет
     * плеер, и затираемая в [release].
     *
     * Копия обязательна. Массив вызывающего кэшируется композицией пузыря и
     * переиспользуется при повторном нажатии «play»; затирание чужого массива
     * означало бы, что второй тап проигрывает тишину. Цена — один `copyOf`
     * порядка сотни килобайт на запись.
     */
    private var ownedBytes: ByteArray? = null

    /**
     * Запускает (или продолжает) воспроизведение сообщения [messageId].
     *
     * Повторный вызов для УЖЕ активного сообщения работает как пауза/продолжение,
     * то есть одна и та же кнопка в пузыре ведёт себя предсказуемо. Вызов для
     * другого сообщения глушит текущее.
     *
     * [fallbackDurationMs] — длительность из метаданных отправителя. Она в
     * приоритете над `MediaPlayer.getDuration()`: для потока ADTS платформа
     * оценивает длительность по битрейту и на переменном битрейте ошибается, а
     * замер на стороне записи точен.
     */
    fun toggle(messageId: String, bytes: ByteArray, fallbackDurationMs: Long) {
        if (activeMessageId.value == messageId && player != null) {
            if (playing.value) pause() else resume()
            return
        }
        start(messageId, bytes, fallbackDurationMs)
    }

    /** Пауза без выгрузки: позиция сохраняется. */
    fun pause() {
        val current = player ?: return
        runCatching { if (current.isPlaying) current.pause() }
        playing.value = false
        stopTicker()
    }

    /** Продолжает с сохранённой позиции. */
    fun resume() {
        val current = player ?: return
        runCatching { current.start() }
            .onFailure { DiagnosticLog.w(DIAG_TAG) { "не удалось продолжить: ${it.javaClass.simpleName}" } }
        playing.value = true
        startTicker()
    }

    /** Перемотка активного сообщения на долю 0f..1f от длительности. */
    fun seekTo(messageId: String, fraction: Float) {
        if (activeMessageId.value != messageId) return
        val current = player ?: return
        val total = durationMs.value
        if (total <= 0L) return
        val target = (total * fraction.coerceIn(0f, 1f)).toLong()
        runCatching { current.seekTo(target.toInt()) }
        positionMs.value = target
    }

    /** Полностью выгружает плеер (уход с экрана, отправка новой записи). */
    fun release() {
        stopTicker()
        player?.let { p ->
            // reset() ДО затирания буфера: плеер читает MediaDataSource в другом
            // процессе, и обнулить байты под работающим декодером — значит отдать
            // ему мусор вместо конца потока.
            runCatching { p.reset() }
            runCatching { p.release() }
        }
        player = null
        // Открытый звук не остаётся в куче после закрытия экрана/переключения
        // трека. Best-effort, с той же оговоркой, что и в `sendVoice`.
        ownedBytes?.fill(0)
        ownedBytes = null
        playing.value = false
        positionMs.value = 0L
        durationMs.value = 0L
        activeMessageId.value = null
    }

    private fun start(messageId: String, bytes: ByteArray, fallbackDurationMs: Long) {
        release()
        failedMessageId.value = null
        if (bytes.isEmpty()) {
            failedMessageId.value = messageId
            return
        }
        val owned = bytes.copyOf()
        val created = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // SPEECH + MEDIA: громкость едет по медиа-каналу (как
                        // голосовые в любом мессенджере), но система знает, что
                        // это речь, и не применяет к ней музыкальные эффекты.
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                setDataSource(ByteArrayMediaDataSource(owned))
                prepare()
            }
        } catch (e: Exception) {
            // Чужой/битый контейнер или блоб, расшифрованный не полностью.
            // Падать нельзя: остальная лента должна продолжать работать.
            DiagnosticLog.e(DIAG_TAG, e) {
                "голосовое не проигрывается msgId=${DiagnosticLog.fp(messageId)} байт=${bytes.size}"
            }
            owned.fill(0)
            failedMessageId.value = messageId
            return
        }

        val reported = runCatching { created.duration.toLong() }.getOrDefault(0L)
        durationMs.value = when {
            fallbackDurationMs > 0L -> fallbackDurationMs
            reported > 0L -> reported
            else -> 0L
        }
        created.setOnCompletionListener {
            playing.value = false
            // Позицию возвращаем в начало: иначе повторный тап начинал бы с
            // конца и выглядел бы как «кнопка не работает».
            positionMs.value = 0L
            runCatching { it.seekTo(0) }
            stopTicker()
        }
        created.setOnErrorListener { _, what, extra ->
            DiagnosticLog.e(DIAG_TAG) { "MediaPlayer ошибка what=$what extra=$extra" }
            failedMessageId.value = messageId
            release()
            true
        }
        player = created
        ownedBytes = owned
        activeMessageId.value = messageId
        positionMs.value = 0L
        runCatching { created.start() }
        playing.value = true
        startTicker()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val current = player ?: break
                val isPlaying = runCatching { current.isPlaying }.getOrDefault(false)
                if (!isPlaying) break
                positionMs.value = runCatching { current.currentPosition.toLong() }.getOrDefault(0L)
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val DIAG_TAG = "voice-play"

        /**
         * Шаг опроса позиции. ~16 обновлений в секунду: курсор на дорожке едет
         * плавно для глаза, а стоимость — одна перерисовка тонкой дорожки, без
         * рекомпозиции списка.
         */
        const val POSITION_TICK_MS = 60L
    }
}

/**
 * Контроллер воспроизведения, живущий столько же, сколько экран: плеер и
 * аудиофокус освобождаются при уходе с экрана, а не когда сборщик мусора
 * доберётся до `MediaPlayer`.
 */
@Composable
fun rememberVoicePlaybackController(): VoicePlaybackController {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { VoicePlaybackController(scope) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * Источник данных поверх массива в памяти — чтобы расшифрованный звук не
 * материализовался во временный файл (§6.3). Отдельная реализация от
 * [VideoFrameDecoder]: тот класс приватен и живёт под другой задачей (один кадр
 * для превью), а здесь источник читается плеером на протяжении всего
 * воспроизведения.
 */
private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || position >= data.size) return -1
        val available = (data.size - position).toInt()
        val count = minOf(size, available)
        if (count <= 0) return -1
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    /** Данными владеет вызывающий (они же лежат в SQLCipher) — освобождать нечего. */
    override fun close() = Unit
}
