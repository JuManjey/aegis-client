package com.aegis.messenger.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.audio.VoiceWaveform
import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.ui.hud.AegisHudWaveform
import com.aegis.core.ui.hud.aegisFormatTimecode
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.messaging.VoicePlaybackController
import kotlinx.coroutines.launch

/**
 * Голосовое сообщение в пузыре чата (§14.4): кнопка воспроизведения и дорожка
 * «монтажного стола» (`AegisHudWaveform`, `docs/ui_hud.md` §5.9).
 *
 * Огибающая рисуется СРАЗУ, ещё до скачивания блоба: её прислал отправитель
 * внутри E2EE-конверта ([com.aegis.core.common.model.VoiceMeta]). Поэтому
 * входящее голосовое с первого кадра выглядит как готовое сообщение с длиной и
 * формой, а не как пустая полоса с кнопкой «Скачать». Для аудиофайла из пикера
 * (огибающей нет) подставляется детерминированная
 * [VoiceWaveform.synthetic] — она не притворяется настоящей осциллограммой, но и
 * не выглядит сломанной.
 *
 * Байты берутся из зашифрованной БД только когда вложение уже локально, и живут
 * в памяти (§6.3): plaintext звука на диск не попадает никогда.
 */
@Composable
internal fun VoiceMessageContent(
    message: Message,
    ref: AttachmentRef,
    onColor: Color,
    controller: VoicePlaybackController,
    attachmentDownloader: suspend (Message) -> Boolean,
    attachmentBytesProvider: suspend (String) -> ByteArray?,
) {
    val scope = rememberCoroutineScope()
    val colors = AegisTheme.extendedColors

    // Огибающая считается один раз на сообщение: разбор base64 (или синтез по
    // objectId) на каждой перерисовке дорожки означал бы аллокацию на кадр
    // воспроизведения.
    val levels = remember(ref.objectId, ref.voice?.waveformB64) {
        VoiceWaveform.forDisplay(ref.voice?.waveformB64, ref.objectId)
    }
    val declaredDurationMs = ref.voice?.durationMs ?: 0L

    val bytes by produceState<ByteArray?>(initialValue = null, message.id, message.attachmentLocal) {
        value = if (message.attachmentLocal) attachmentBytesProvider(message.id) else null
    }

    var downloading by remember(message.id) { mutableStateOf(false) }
    var downloadFailed by remember(message.id) { mutableStateOf(false) }

    val isActive = controller.activeMessageId.value == message.id
    val playbackFailed = controller.failedMessageId.value == message.id

    // Длительность для подписи: своя из метаданных приоритетнее, чем оценка
    // платформы (для потока ADTS она считается по битрейту и врёт).
    val totalMs = when {
        declaredDurationMs > 0L -> declaredDurationMs
        isActive -> controller.durationMs.value
        else -> 0L
    }

    Row(
        modifier = Modifier.widthIn(min = 220.dp, max = 260.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                downloading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = onColor,
                )

                // Входящее без локальных байтов: автозагрузка выключена или не
                // успела. Дорожку всё равно уже видно — тап качает звук.
                bytes == null && message.direction == MessageDirection.INCOMING ->
                    IconButton(
                        onClick = {
                            downloading = true
                            downloadFailed = false
                            scope.launch {
                                val ok = attachmentDownloader(message)
                                downloading = false
                                downloadFailed = !ok
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (downloadFailed) Icons.Filled.Refresh else Icons.Filled.Download,
                            contentDescription = stringResource(
                                if (downloadFailed) {
                                    R.string.chat_download_retry_cd
                                } else {
                                    R.string.chat_voice_download_cd
                                },
                            ),
                            tint = if (downloadFailed) colors.danger else onColor,
                        )
                    }

                // Исходящее, которое ещё грузится на релей: байты есть, играть
                // можно — своё сообщение слушать логично сразу.
                bytes != null -> IconButton(
                    onClick = {
                        bytes?.let { controller.toggle(message.id, it, declaredDurationMs) }
                    },
                ) {
                    Icon(
                        imageVector = when {
                            playbackFailed -> Icons.Filled.ErrorOutline
                            isActive && controller.playing.value -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (isActive && controller.playing.value) {
                                R.string.chat_voice_pause_cd
                            } else {
                                R.string.chat_voice_play_cd
                            },
                        ),
                        tint = if (playbackFailed) colors.danger else onColor,
                    )
                }

                // Исходящее без байтов вообще (БД почистили по сроку хранения
                // §7.3): играть нечего, показываем, что это было голосовое.
                else -> Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = onColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            AegisHudWaveform(
                levels = levels,
                // Лямбды, а не значения: позиция меняется ~16 раз в секунду, и
                // передача Float рекомпозировала бы элемент LazyColumn на каждый
                // тик (см. AegisHudWaveform).
                progress = {
                    val total = if (declaredDurationMs > 0L) {
                        declaredDurationMs
                    } else {
                        controller.durationMs.value
                    }
                    if (isActive && total > 0L) {
                        controller.positionMs.value.toFloat() / total
                    } else {
                        0f
                    }
                },
                timecode = {
                    when {
                        isActive -> aegisFormatTimecode(controller.positionMs.value)
                        totalMs > 0L -> aegisFormatTimecode(totalMs)
                        // Длительность неизвестна (аудиофайл из пикера — метаданных
                        // нет, платформу ещё не спрашивали). Показать «00:00» значило
                        // бы соврать про пустую запись, поэтому чип не рисуется вовсе.
                        else -> ""
                    }
                },
                accent = onColor.copy(alpha = 0.55f),
                progressAccent = onColor,
                railColor = onColor.copy(alpha = 0.25f),
                // Перематывать можно только загруженное и активное сообщение:
                // иначе тап по дорожке выглядел бы как рабочая перемотка, но
                // ничего не делал.
                onSeek = if (isActive && bytes != null) {
                    { fraction -> controller.seekTo(message.id, fraction) }
                } else {
                    null
                },
            )
            if (playbackFailed) {
                Text(
                    text = stringResource(R.string.chat_voice_play_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
}
