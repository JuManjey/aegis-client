package com.aegis.messenger.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudWaveformLive
import com.aegis.core.ui.hud.aegisFormatTimecode
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/** Состояние панели записи для UI (данные приходят из `VoiceRecorder`). */
internal data class VoiceRecordingUiState(
    val elapsedMs: Long,
    val levels: IntArray,
    /** Палец уехал влево за порог: отпускание ОТМЕНИТ запись. */
    val cancelArmed: Boolean,
    /** Запись зафиксирована (руки свободны): управление кнопками. */
    val locked: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is VoiceRecordingUiState &&
        other.elapsedMs == elapsedMs &&
        other.cancelArmed == cancelArmed &&
        other.locked == locked &&
        other.levels.contentEquals(levels)

    override fun hashCode(): Int {
        var result = elapsedMs.hashCode()
        result = 31 * result + levels.contentHashCode()
        result = 31 * result + cancelArmed.hashCode()
        result = 31 * result + locked.hashCode()
        return result
    }
}

/**
 * Панель идущей записи голосового (§14.4) — встаёт НАД строкой ввода.
 *
 * Почему над, а не вместо. Кнопка микрофона удерживается пальцем, и её узел
 * ввода обязан жить всё время жеста: если подменить содержимое строки целиком,
 * Compose утилизирует `pointerInput` вместе со старым поддеревом, жест
 * оборвётся, и запись прекратится в момент, когда пользователь ещё держит палец.
 * Поэтому строка ввода структурно не меняется, а индикация приезжает отдельной
 * панелью ([ChatScreen] держит кнопку на неизменном месте дерева).
 *
 * Мигающая точка REC — единственная анимация: она отвечает на вопрос «идёт ли
 * запись» без чтения текста, что важно, когда палец закрывает часть экрана.
 */
@Composable
internal fun VoiceRecordingPanel(
    state: VoiceRecordingUiState,
    maxDurationMs: Long,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = AegisTheme.extendedColors
    val blink by rememberInfiniteTransition(label = "rec-blink").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-alpha",
    )
    // Красный здесь — не «опасное действие» (§5.8), а индикатор идущей записи:
    // общепринятое значение точки REC, и цвет тут несёт смысл «идёт захват
    // микрофона», а не «нажатие потеряет данные».
    val recColor = if (state.cancelArmed) colors.danger else colors.accentMagenta
    val remainingMs = (maxDurationMs - state.elapsedMs).coerceAtLeast(0L)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(recColor.copy(alpha = blink), AegisChipShape),
        )
        Text(
            text = stringResource(R.string.chat_voice_rec),
            style = MaterialTheme.typography.labelSmall,
            color = recColor,
        )
        AegisHudWaveformLive(
            levels = state.levels,
            timecode = { aegisFormatTimecode(state.elapsedMs) },
            accent = recColor,
            modifier = Modifier.weight(1f),
        )
        if (state.locked) {
            // Зафиксированная запись управляется кнопками: палец уже отпущен,
            // и «отпустите для отправки» перестало быть правдой.
            AegisHudIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.chat_voice_cancel_cd),
                onClick = onCancel,
                accent = colors.danger,
                size = 36.dp,
            )
            AegisHudIconButton(
                icon = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send_cd),
                onClick = onSend,
                filled = true,
                accent = colors.accentPrimary,
                size = 38.dp,
            )
        } else {
            // Остаток времени показываем только на последней минуте: постоянный
            // счётчик «осталось 4:53» превращает отправку голосового в гонку.
            if (remainingMs <= REMAINING_HINT_MS) {
                Text(
                    text = stringResource(
                        R.string.chat_voice_remaining,
                        aegisFormatTimecode(remainingMs),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                when {
                    state.locked -> R.string.chat_voice_hint_locked
                    state.cancelArmed -> R.string.chat_voice_hint_release_to_cancel
                    else -> R.string.chat_voice_hint_hold
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.cancelArmed) colors.danger else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!state.locked) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** С какого остатка показывать обратный отсчёт до автоостановки. */
private const val REMAINING_HINT_MS = 30_000L

/**
 * Жест «удерживай для записи» на кнопке микрофона.
 *
 * Правила (как в привычных мессенджерах, но без скрытых состояний):
 *  * нажатие — начать запись ([onStart]);
 *  * отпускание — отправить ([onFinish]);
 *  * сдвиг ВЛЕВО за [cancelThresholdPx] — отпускание отменяет ([onCancel]);
 *  * сдвиг ВВЕРХ за [lockThresholdPx] — фиксация ([onLock]): палец можно убрать,
 *    дальше управляют кнопки в панели.
 *
 * Слишком короткое нажатие отдельным случаем НЕ обрабатывается: запись короче
 * `VOICE_MIN_DURATION_MS` отбрасывает сам `VoiceRecorder` — так порог живёт в
 * одном месте, а не дублируется в жесте и в записи с риском разойтись.
 *
 * [PointerEventPass.Initial] для отслеживания смещения обязателен: кнопка лежит
 * внутри баббл-строки и списка, и без перехвата на начальном проходе вертикальный
 * сдвиг «фиксации» утаскивал бы прокрутку `LazyColumn` вместо фиксации записи.
 */
@Suppress("LongParameterList")
internal fun Modifier.voiceRecordGesture(
    enabled: Boolean,
    cancelThresholdPx: Float,
    lockThresholdPx: Float,
    onStart: () -> Boolean,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onFinish: () -> Unit,
): Modifier = pointerInput(enabled, cancelThresholdPx, lockThresholdPx) {
    awaitEachGesture {
        // requireUnconsumed = false: нажатие может быть уже помечено обработанным
        // ripple-эффектом самой кнопки, и жест не должен от этого срываться.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled) return@awaitEachGesture
        if (!onStart()) return@awaitEachGesture

        var outcome = Outcome.FINISH
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                // Палец «потерялся» (например, окно ушло из фокуса): считаем это
                // отменой, а не отправкой — отправлять то, что пользователь не
                // подтвердил отпусканием, нельзя.
                outcome = Outcome.CANCEL
                break
            }
            if (!change.pressed) break
            change.consume()
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            onDrag(dx, dy)
            if (dx <= -cancelThresholdPx) {
                outcome = Outcome.CANCEL
                break
            }
            if (dy <= -lockThresholdPx) {
                outcome = Outcome.LOCK
                break
            }
        }
        when (outcome) {
            Outcome.CANCEL -> onCancel()
            Outcome.LOCK -> onLock()
            Outcome.FINISH -> onFinish()
        }
    }
}

private enum class Outcome { FINISH, CANCEL, LOCK }

/** Порог сдвига влево, после которого отпускание отменяет запись. */
internal val VoiceCancelThreshold = 72.dp

/** Порог сдвига вверх для фиксации записи (руки свободны). */
internal val VoiceLockThreshold = 56.dp

/**
 * Разрешение на микрофон для голосовых сообщений.
 *
 * Отдельно от [com.aegis.messenger.ui.call.rememberCallPermissionRequester]:
 * тому нужна ещё и камера для видеозвонка, а здесь запрашивать её было бы
 * непрошеным расширением прав ради записи звука.
 *
 * Возвращает `ensure(onGranted)`: если разрешение есть — действие выполняется
 * сразу (важно для жеста: диалог во время удержания разорвал бы его), иначе
 * показывается системный диалог, и действие НЕ выполняется — пользователь
 * нажмёт кнопку заново, уже имея разрешение.
 */
@Composable
internal fun rememberMicPermissionGate(): (() -> Unit) -> Boolean {
    val context = LocalContext.current
    val requested = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { requested.value = false }

    return remember(context) {
        { onGranted ->
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                onGranted()
                true
            } else {
                if (!requested.value) {
                    requested.value = true
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
                false
            }
        }
    }
}
