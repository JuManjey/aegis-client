package com.aegis.messenger.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aegis.core.common.call.CallInfo
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.call.CallStage
import com.aegis.core.common.call.CallStageEntry
import com.aegis.core.common.call.CallStageState
import com.aegis.core.ui.hud.AegisHudBubble
import com.aegis.core.ui.hud.AegisHudChip
import com.aegis.core.ui.hud.AegisHudCodeRow
import com.aegis.core.ui.hud.AegisHudRule
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.aegisHudBezel
import com.aegis.core.ui.theme.AegisClippedCornerShape
import com.aegis.core.ui.theme.AegisCodeDisplayStyle
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisGlow
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

/**
 * Полноэкранный экран активного звонка (P8, §7.5/§13) в HUD-раскладке
 * (`docs/ui_hud.md`). Показывается поверх UI, когда есть активный звонок.
 * Отражает фазу, тип защиты (SAS для сверки, PSK, медиа-через-Tor), ленту
 * этапов установки и даёт управление (принять/отклонить/сбросить/микрофон/камера).
 *
 * Для ВИДЕОзвонка (когда доступен реальный WebRTC) рендерит видео собеседника на
 * весь экран и локальный предпросмотр «картинка-в-картинке». Приёмники кадров
 * ([VideoSink]) привязываются к движку через [onSetRemoteSink]/[onSetLocalSink].
 *
 * Панели поверх видео получают затемнение из токена `scrimOverlay`: раньше здесь
 * стояли четыре захардкоженных HEX-константы, чего §13 не допускает — цвет
 * должен приходить из темы, иначе светлая тема и правки палитры их не касаются.
 *
 * ## Экран блокировки (§14.3)
 *
 * Во время звонка окно показывается поверх блокировки, поэтому пока телефон не
 * разблокирован ([rememberDeviceLocked]) карточка ОБЕЗЛИЧЕНА: ни имени
 * контакта, ни кода сверки, ни деталей этапов. Это тот же уровень скрытности,
 * что у уведомлений (`VISIBILITY_SECRET`), — принять или отклонить звонок можно,
 * а увидеть, кто и с какой защитой звонит, только после разблокировки.
 */
@Composable
fun CallScreen(
    call: CallInfo,
    mediaEngineReal: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    eglBaseContext: EglBase.Context? = null,
    onSetLocalSink: (VideoSink?) -> Unit = {},
    onSetRemoteSink: (VideoSink?) -> Unit = {},
    onToggleCamera: (Boolean) -> Unit = {},
    onSwitchCamera: () -> Unit = {},
) {
    var muted by remember { mutableStateOf(false) }
    val deviceLocked = rememberDeviceLocked()
    // Видео на экране блокировки не показываем: кадр собеседника прочитывается
    // без разблокировки так же, как имя.
    val showVideo = call.media == CallMedia.VIDEO &&
        mediaEngineReal &&
        eglBaseContext != null &&
        !deviceLocked

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Слой видео (позади элементов управления). Только для видеозвонка.
            if (showVideo) {
                VideoLayer(
                    eglBaseContext = eglBaseContext,
                    cameraEnabled = call.cameraEnabled,
                    onSetLocalSink = onSetLocalSink,
                    onSetRemoteSink = onSetRemoteSink,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoPanel(call = call, scrim = showVideo, deviceLocked = deviceLocked)

                // Середина забирает остаток высоты: лента этапов прокручивается
                // внутри, а кнопки управления остаются прибиты к низу и не
                // уезжают за край на длинной ленте.
                Column(
                    modifier = Modifier.weight(1f, fill = true),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                ) {
                    SecurityPanel(
                        call = call,
                        mediaEngineReal = mediaEngineReal,
                        scrim = showVideo,
                        deviceLocked = deviceLocked,
                    )
                    if (call.stages.isNotEmpty()) {
                        StageTimelinePanel(
                            stages = call.stages,
                            scrim = showVideo,
                            deviceLocked = deviceLocked,
                            // fill = false: лента забирает остаток высоты, но не
                            // растягивается пустотой на большом экране.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                CallControls(
                    call = call,
                    muted = muted,
                    onAccept = onAccept,
                    onDecline = onDecline,
                    onHangUp = onHangUp,
                    onToggleMute = {
                        muted = !muted
                        onToggleMute(muted)
                    },
                    onToggleCamera = { onToggleCamera(!call.cameraEnabled) },
                    onSwitchCamera = onSwitchCamera,
                    scrim = showVideo,
                )
            }
        }
    }
}

/** Статический slug экрана звонка для декоративных HUD-кодов. */
private const val CALL_HUD_SLUG = "call.active"

/**
 * Рендерит видео собеседника на весь экран и локальный предпросмотр в углу.
 * Создаёт два [SurfaceViewRenderer], инициализирует их общим EGL-контекстом
 * движка и привязывает как приёмники кадров; освобождает на выходе с экрана.
 */
@Composable
private fun VideoLayer(
    eglBaseContext: EglBase.Context,
    cameraEnabled: Boolean,
    onSetLocalSink: (VideoSink?) -> Unit,
    onSetRemoteSink: (VideoSink?) -> Unit,
) {
    val context = LocalContext.current
    val colors = AegisTheme.extendedColors
    val remoteRenderer = remember { SurfaceViewRenderer(context) }
    val localRenderer = remember { SurfaceViewRenderer(context) }

    DisposableEffect(Unit) {
        runCatching {
            remoteRenderer.init(eglBaseContext, null)
            remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            remoteRenderer.setEnableHardwareScaler(true)
        }
        runCatching {
            localRenderer.init(eglBaseContext, null)
            localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            localRenderer.setMirror(true)
            localRenderer.setZOrderMediaOverlay(true)
            localRenderer.setEnableHardwareScaler(true)
        }
        onSetRemoteSink(remoteRenderer)
        onSetLocalSink(localRenderer)
        onDispose {
            onSetRemoteSink(null)
            onSetLocalSink(null)
            runCatching { remoteRenderer.release() }
            runCatching { localRenderer.release() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { remoteRenderer }, modifier = Modifier.fillMaxSize())
        // Предпросмотр себя — рубленый кадр с циановой рамкой вместо скруглённого
        // прямоугольника: тот же «вырез в пластине», что у панелей HUD.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 108.dp, end = 4.dp)
                .size(width = 108.dp, height = 160.dp)
                .clip(AegisClippedCornerShape)
                .background(colors.surfaceElevated)
                .aegisHudFrame(
                    color = colors.securityAccent,
                    shape = AegisClippedCornerShape,
                    cornerArm = 10.dp,
                ),
        ) {
            AndroidView(factory = { localRenderer }, modifier = Modifier.fillMaxSize())
            if (!cameraEnabled) {
                Text(
                    text = stringResource(R.string.call_camera_off).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/**
 * Шапка звонка: микро-коды, имя собеседника прописным узким начертанием,
 * двухцветная линейка, фаза и тип звонка чипом.
 */
@Composable
private fun InfoPanel(call: CallInfo, scrim: Boolean, deviceLocked: Boolean) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(scrimModifier(scrim))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AegisHudCodeRow(slug = CALL_HUD_SLUG)
        Text(
            text = if (deviceLocked) {
                stringResource(R.string.call_locked_title).uppercase()
            } else {
                call.displayName.uppercase()
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        AegisHudRule()
        Text(
            text = phaseLabel(call).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (call.phase == CallPhase.ACTIVE) colors.securityAccent else colors.textSecondary,
        )
        AegisHudChip(
            text = stringResource(
                if (call.media == CallMedia.VIDEO) R.string.call_type_video else R.string.call_type_voice,
            ),
            icon = if (call.media == CallMedia.VIDEO) Icons.Filled.Videocam else Icons.Filled.Call,
            accent = colors.textSecondary,
        )
    }
}

/**
 * Панель защиты: код сверки SAS крупным моноширинным в рамке-акценте, затем
 * строки состояния PSK и медиа-через-Tor.
 */
@Composable
private fun SecurityPanel(
    call: CallInfo,
    mediaEngineReal: Boolean,
    scrim: Boolean,
    deviceLocked: Boolean,
) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(scrimModifier(scrim))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (deviceLocked) {
            // Код сверки и признаки защиты — такие же метаданные звонка, как
            // имя: на заблокированном экране не показываем ничего из них.
            Text(
                text = stringResource(R.string.call_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        // Код сверки (SAS) — устно подтвердить с собеседником (§10.10). Это
        // главный элемент экрана против man-in-the-middle, поэтому он получает
        // рамку и свечение, а не просто крупный шрифт.
        if (call.sas.isNotBlank()) {
            Text(
                text = stringResource(R.string.call_sas_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .aegisGlow(colors.securityAccent, AegisPanelShape, radius = 6.dp, alpha = 0.4f)
                    .clip(AegisPanelShape)
                    .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                // Код сверки читают вслух собеседнику, поэтому он моноширинный:
                // в узком пропорциональном начертании `0`/`O` не различить.
                Text(
                    text = call.sas,
                    style = AegisCodeDisplayStyle,
                    color = colors.securityAccent,
                )
            }
        }
        AegisHudStatusLine(
            text = stringResource(
                if (call.pskProtected) R.string.call_psk_protected else R.string.call_psk_absent,
            ),
            ok = call.pskProtected,
        )
        AegisHudStatusLine(
            text = stringResource(
                if (call.frameLayer) R.string.call_frame_layer else R.string.call_frame_layer_off,
            ),
            ok = call.frameLayer,
        )
        // Без Tor это не ошибка, а выбранный режим: строка информационная.
        AegisHudStatusLine(
            text = stringResource(
                if (call.mediaOverTor) R.string.call_media_tor else R.string.call_media_direct,
            ),
            ok = call.mediaOverTor,
            failColor = colors.accentPrimary,
        )
        // Без Tor медиа пошло через резервный TURN сервера: это объясняет и
        // задержку, и то, почему звонок всё-таки соединился.
        if (!call.mediaOverTor && call.relayedPath) {
            AegisHudStatusLine(
                text = stringResource(R.string.call_path_relay_fallback),
                ok = false,
                failColor = colors.accentPrimary,
            )
        }
        if (call.reconnecting) {
            AegisHudStatusLine(
                text = stringResource(R.string.call_reconnecting),
                ok = false,
                failColor = colors.accentPrimary,
            )
        }
        if (!mediaEngineReal) {
            Text(
                text = stringResource(R.string.call_no_webrtc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Лента этапов установки звонка «как переписка»: каждый шаг — входящий баббл
 * мессенджера ([AegisHudBubble]) с отметкой времени от начала звонка.
 *
 * Зачем именно лента, а не одна строка статуса: соединение через Tor и ICE
 * занимает секунды, и единственная надпись «Соединение…» не отличает «ждём
 * ответа релея» от «ищем сетевой путь» и от «путь не нашёлся». Ленту видно
 * сразу, и по ней понятно, на чём именно встал звонок.
 *
 * На заблокированном экране показываются только ярлыки этапов без деталей: сами
 * детали (например, «усилено ручным PSK») — это метаданные звонка.
 */
@Composable
private fun StageTimelinePanel(
    stages: List<CallStageEntry>,
    scrim: Boolean,
    deviceLocked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val listState = rememberLazyListState()

    // Свежая запись всегда видна: лента растёт вниз, и без автопрокрутки
    // пользователь смотрел бы на начало установки, а не на текущий шаг.
    LaunchedEffect(stages.size) {
        if (stages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(stages.lastIndex) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(scrimModifier(scrim))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.call_stages_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        AegisHudRule()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = TIMELINE_MAX_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = stages, key = { "${it.stage.name}:${it.atMs}" }) { entry ->
                StageBubble(entry = entry, deviceLocked = deviceLocked)
            }
        }
    }
}

@Composable
private fun StageBubble(entry: CallStageEntry, deviceLocked: Boolean) {
    val colors = AegisTheme.extendedColors
    val accent = when (entry.state) {
        CallStageState.ACTIVE -> colors.textSecondary
        CallStageState.DONE -> colors.securityAccent
        CallStageState.WARN -> colors.accentPrimary
        CallStageState.FAILED -> colors.danger
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AegisHudBubble(outgoing = false, modifier = Modifier.weight(1f, fill = false)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageMarker(state = entry.state, accent = accent)
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = stageLabel(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                    )
                    val detail = entry.detail
                    if (!deviceLocked && detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                }
                Text(
                    text = stageTimeLabel(entry.atMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Маркер этапа. Состояние передаётся И цветом, И формой (пустая рамка — идёт,
 * залитый квадрат — пройден, полоса — предупреждение/отказ): только цветом
 * различать нельзя при дальтонизме (как и в [AegisHudStatusLine]).
 */
@Composable
private fun StageMarker(state: CallStageState, accent: Color) {
    when (state) {
        CallStageState.ACTIVE -> Box(
            modifier = Modifier
                .size(8.dp)
                .aegisHudFrame(color = accent, shape = MaterialTheme.shapes.extraSmall, cornerArm = 0.dp),
        )

        CallStageState.DONE -> Box(modifier = Modifier.size(8.dp).background(accent))

        CallStageState.WARN, CallStageState.FAILED -> Box(
            modifier = Modifier.width(8.dp).height(3.dp).background(accent),
        )
    }
}

/** Подпись времени этапа от начала звонка: «+0.4 с», «+12 с». */
@Composable
private fun stageTimeLabel(atMs: Long): String {
    val seconds = atMs / 1000.0
    return if (seconds < 10.0) {
        stringResource(R.string.call_stage_at_fraction, seconds)
    } else {
        stringResource(R.string.call_stage_at_seconds, (atMs / 1000))
    }
}

@Composable
private fun stageLabel(entry: CallStageEntry): String {
    val done = entry.state == CallStageState.DONE
    return when (entry.stage) {
        CallStage.OUTGOING_START -> stringResource(R.string.call_stage_outgoing)
        CallStage.INCOMING_OFFER ->
            if (done) stringResource(R.string.call_stage_incoming_accepted)
            else stringResource(R.string.call_stage_incoming)

        CallStage.MEDIA_SERVICE -> stringResource(R.string.call_stage_service)
        CallStage.TURN -> stringResource(R.string.call_stage_turn)
        CallStage.ENGINE -> stringResource(R.string.call_stage_engine)
        CallStage.LOCAL_SDP -> stringResource(R.string.call_stage_local_sdp)
        CallStage.SIGNAL_OFFER ->
            if (done) stringResource(R.string.call_stage_signal_offer_done)
            else stringResource(R.string.call_stage_signal_offer)

        CallStage.SIGNAL_ANSWER ->
            if (done) stringResource(R.string.call_stage_signal_answer_done)
            else stringResource(R.string.call_stage_signal_answer)

        CallStage.KEYS -> stringResource(R.string.call_stage_keys)
        CallStage.ICE ->
            if (done) stringResource(R.string.call_stage_ice_done)
            else stringResource(R.string.call_stage_ice)

        CallStage.MEDIA ->
            if (done) stringResource(R.string.call_stage_media_done)
            else stringResource(R.string.call_stage_media)

        CallStage.ENDED -> stringResource(R.string.call_stage_ended)
    }
}

/**
 * Затемнение под панелью, когда под ней идёт видео. Цвет — из токена темы:
 * панель лежит на кадре камеры, поэтому подложка тёмная в обеих темах.
 */
@Composable
private fun scrimModifier(scrim: Boolean): Modifier {
    val colors = AegisTheme.extendedColors
    return if (scrim) {
        Modifier
            .clip(AegisPanelShape)
            .background(colors.scrimOverlay)
            .aegisHudFrame(color = colors.panelBorder, shape = AegisPanelShape)
    } else {
        Modifier
    }
}

@Composable
private fun CallControls(
    call: CallInfo,
    muted: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    scrim: Boolean,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(scrimModifier(scrim))
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (call.phase) {
            CallPhase.RINGING -> {
                CallActionButton(
                    icon = Icons.Filled.CallEnd,
                    label = stringResource(R.string.call_btn_decline),
                    accent = colors.danger,
                    filled = true,
                    onClick = onDecline,
                )
                CallActionButton(
                    icon = Icons.Filled.Call,
                    label = stringResource(R.string.call_btn_accept),
                    accent = colors.securityAccent,
                    filled = true,
                    onClick = onAccept,
                )
            }

            CallPhase.ENDED -> {
                Text(
                    text = call.endReason.ifBlank { stringResource(R.string.call_ended_default) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                CallActionButton(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = stringResource(R.string.call_btn_mic),
                    accent = if (muted) colors.accentPrimary else colors.textSecondary,
                    onClick = onToggleMute,
                )
                if (call.media == CallMedia.VIDEO) {
                    CallActionButton(
                        icon = if (call.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        label = stringResource(R.string.call_btn_camera),
                        accent = if (call.cameraEnabled) colors.textSecondary else colors.accentPrimary,
                        onClick = onToggleCamera,
                    )
                    CallActionButton(
                        icon = Icons.Filled.Cameraswitch,
                        label = stringResource(R.string.call_btn_switch_camera),
                        accent = colors.textSecondary,
                        onClick = onSwitchCamera,
                    )
                }
                CallActionButton(
                    icon = Icons.Filled.CallEnd,
                    label = stringResource(R.string.call_btn_hangup),
                    accent = colors.danger,
                    filled = true,
                    onClick = onHangUp,
                )
            }
        }
    }
}

/**
 * Кнопка управления звонком: рубленый квадрат со скобками по углам и подпись
 * под ним. Круглые кнопки заменены на срезанный квадрат — круг противоречит
 * рубленой геометрии §13.
 *
 * Подпись видима, поэтому иконка внутри своего `contentDescription` не несёт;
 * описание для TalkBack висит на самой кнопке.
 */
@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 58.dp,
) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .then(if (filled) Modifier.aegisGlow(accent, AegisClippedCornerShape) else Modifier)
                .clip(AegisClippedCornerShape)
                .then(if (filled) Modifier.background(accent) else Modifier)
                .aegisHudBezel(color = accent, selected = true, glow = false, ticks = false)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filled) colors.onSelectionFill else accent,
                modifier = Modifier.size(size * ICON_RATIO),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private const val ICON_RATIO = 0.42f

/** Лента этапов не должна выдавливать кнопки управления за край экрана. */
private val TIMELINE_MAX_HEIGHT = 168.dp

@Composable
private fun phaseLabel(call: CallInfo): String = when (call.phase) {
    CallPhase.DIALING -> stringResource(R.string.call_phase_dialing)
    CallPhase.RINGING -> stringResource(R.string.call_phase_ringing)
    CallPhase.CONNECTING -> stringResource(R.string.call_phase_connecting)
    CallPhase.ACTIVE -> stringResource(R.string.call_phase_active)
    CallPhase.ENDED -> stringResource(R.string.call_phase_ending)
    CallPhase.IDLE -> ""
}
