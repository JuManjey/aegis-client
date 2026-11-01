package com.aegis.messenger.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.core.common.AegisConstants
import com.aegis.core.common.appearance.ChatWallpaper
import com.aegis.core.common.model.AttachmentKind
import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.Message
import com.aegis.core.common.model.MessageDirection
import com.aegis.core.common.model.MessageStatus
import com.aegis.core.ui.hud.AegisHudBubble
import com.aegis.core.ui.hud.AegisHudCodeRow
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudDropdownMenu
import com.aegis.core.ui.hud.AegisHudMenuItem
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudHint
import com.aegis.core.ui.hud.AegisHudHintBar
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudTextField
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisClippedCornerShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.ui.settings.LocalTorEnabled
import com.aegis.messenger.appearance.toBrushOrNull
import com.aegis.messenger.messaging.CameraCapture
import com.aegis.messenger.messaging.VideoFrameDecoder
import com.aegis.messenger.messaging.VoicePlaybackController
import com.aegis.messenger.messaging.VoiceRecorder
import com.aegis.messenger.messaging.rememberVoicePlaybackController
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Отступ нижней панели чата под системную навигацию и клавиатуру.
 *
 * Приложение рисует edge-to-edge ([com.aegis.messenger.MainActivity]), а
 * Material3 `Scaffold` НЕ добавляет отступы своему `bottomBar` — он ложится
 * вплотную к нижней кромке экрана, то есть под кнопки Android. `safeDrawing`
 * берёт максимум из навбара, IME и выреза, поэтому при открытой клавиатуре
 * отступ не удваивается, а в ландшафте учитывается боковой навбар.
 */
private val bottomBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/**
 * Экран переписки (§13). Сообщения приходят уже расшифрованными из SQLCipher;
 * отправка запускает конвейер слой A → слой B → Tor. Индикатор E2EE показывает,
 * что для чата установлена сессия/ключи.
 *
 * §14.2: под заголовком показываются эфемерные сигналы собеседника ([peerStatus]:
 * «печатает…»/«в сети»); §7.3: долгое нажатие на сообщение открывает удаление
 * «у меня» / «у всех». Меню в шапке — блокировка контакта (чёрный список §14.2).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    title: String,
    e2eeReady: Boolean,
    messages: List<Message>,
    onSend: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    callsEnabled: Boolean = true,
    verified: Boolean = false,
    requireVerify: Boolean = false,
    newDevicePending: Boolean = false,
    blocked: Boolean = false,
    peerStatus: String? = null,
    mutedUntilMs: Long = 0L,
    messageSoundUri: String = "",
    callRingtoneUri: String = "",
    wallpaper: ChatWallpaper = ChatWallpaper.DEFAULT,
    onVerify: () -> Unit = {},
    onAcknowledgeNewDevice: () -> Unit = {},
    onRetryKeyExchange: () -> Unit = {},
    relayConfigured: Boolean = true,
    onInputChange: () -> Unit = {},
    onToggleBlock: () -> Unit = {},
    onSetContactMuted: (Long) -> Unit = {},
    onSetContactSounds: (String, String) -> Unit = { _, _ -> },
    onDeleteForMe: (Message) -> Unit = {},
    onDeleteForEveryone: (Message) -> Unit = {},
    onClearChat: () -> Unit = {},
    onDeleteChat: () -> Unit = {},
    /** Публичный никнейм обмена — подсказка в диалоге сброса имени. */
    nickname: String = "",
    onRename: (String) -> Unit = {},
    onStartAudioCall: () -> Unit = {},
    onStartVideoCall: () -> Unit = {},
    onAttachmentPicked: (Uri) -> Unit = {},
    onCameraCaptured: (java.io.File) -> Unit = {},
    /** Готовая запись голосового (§14.4): байты AAC/ADTS + метаданные. */
    onVoiceRecorded: (VoiceRecorder.Result) -> Unit = {},
    attachmentDownloader: suspend (Message) -> Boolean = { false },
    /** Повтор отправки неотправленного сообщения (текст или вложение, §14.2). */
    messageRetrier: suspend (Message) -> Boolean = { false },
    attachmentBytesProvider: suspend (String) -> ByteArray? = { null },
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var pendingInfo by remember { mutableStateOf<Message?>(null) }
    var notifDialogOpen by remember { mutableStateOf(false) }
    var clearChatOpen by remember { mutableStateOf(false) }
    var deleteChatOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val voicePlayback = rememberVoicePlaybackController()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    // Подзаголовок: эфемерный статус собеседника (§14.2) имеет приоритет над
    // статичной подсказкой про E2EE.
    val subtitle = peerStatus ?: if (e2eeReady) {
        stringResource(R.string.chat_subtitle_e2ee)
    } else {
        stringResource(R.string.chat_subtitle_exchange)
    }
    val subtitleColor = if (peerStatus != null) {
        AegisTheme.extendedColors.securityAccent
    } else {
        AegisTheme.extendedColors.textSecondary
    }

    // Обои чата (§14.6): программный фон из токенов темы. Для DEFAULT — null
    // (остаётся однотонный фон приложения, как было). Рисуется на области
    // контента поверх фона Scaffold, бабблы остаются читаемыми.
    val wallpaperBrush = wallpaper.toBrushOrNull()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ChatHudHeader(
                title = title,
                subtitle = subtitle,
                subtitleColor = subtitleColor,
                verified = verified,
                callControlsVisible = callsEnabled && !blocked,
                blocked = blocked,
                muted = mutedNow(mutedUntilMs),
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onOpenRename = { renameOpen = true },
                onVerify = onVerify,
                onStartAudioCall = onStartAudioCall,
                onStartVideoCall = onStartVideoCall,
                onOpenNotifications = { notifDialogOpen = true },
                onToggleBlock = onToggleBlock,
                onOpenClearChat = { clearChatOpen = true },
                onOpenDeleteChat = { deleteChatOpen = true },
            )
        },
        bottomBar = {
            if (blocked) {
                BlockedBar(onUnblock = onToggleBlock)
            } else {
                MessageInput(
                    value = input,
                    onValueChange = {
                        input = it
                        if (it.isNotEmpty()) onInputChange()
                    },
                    onSend = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            input = ""
                        }
                    },
                    onAttachmentPicked = onAttachmentPicked,
                    onCameraCaptured = onCameraCaptured,
                    onVoiceRecorded = { result ->
                        // Новая запись глушит воспроизведение: слушать одно
                        // голосовое во время отправки другого — каша в динамике.
                        voicePlayback.release()
                        onVoiceRecorded(result)
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (wallpaperBrush != null) Modifier.background(wallpaperBrush) else Modifier)
                .padding(innerPadding),
        ) {
            // Слой B ещё не готов (§6.2): бандл собеседника не доехал, отправка
            // будет падать. Догоняющий обмен идёт сам, но даём и явный повтор.
            if (!relayConfigured && !blocked) {
                AegisHudBanner(
                    text = stringResource(
                        if (LocalTorEnabled.current) R.string.relay_missing_banner else R.string.relay_missing_banner_direct,
                    ),
                    tone = HudBannerTone.DANGER,
                )
            }
            if (!e2eeReady && !blocked) {
                KeysPendingBanner(onRetry = onRetryKeyExchange)
            }
            if (requireVerify && !verified) {
                UnverifiedBanner(onVerify = onVerify)
            }
            if (newDevicePending) {
                NewDeviceBanner(onVerify = { onAcknowledgeNewDevice(); onVerify() })
            }

            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AegisTheme.extendedColors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(items = messages, key = { _, m -> m.id }) { index, message ->
                        val showDate = index == 0 ||
                            !isSameDay(messages[index - 1].timestampMs, message.timestampMs)
                        if (showDate) {
                            DateDivider(label = formatDateLabel(message.timestampMs))
                        }
                        MessageBubble(
                            message = message,
                            onClick = { pendingInfo = message },
                            onLongClick = { pendingDelete = message },
                            voicePlayback = voicePlayback,
                            attachmentDownloader = attachmentDownloader,
                            messageRetrier = messageRetrier,
                            attachmentBytesProvider = attachmentBytesProvider,
                        )
                    }
                }
            }
        }
    }

    pendingInfo?.let { message ->
        MessageInfoDialog(
            message = message,
            onDismiss = { pendingInfo = null },
        )
    }

    pendingDelete?.let { message ->
        DeleteMessageDialog(
            message = message,
            onDismiss = { pendingDelete = null },
            onDeleteForMe = {
                onDeleteForMe(message)
                pendingDelete = null
            },
            onDeleteForEveryone = {
                onDeleteForEveryone(message)
                pendingDelete = null
            },
        )
    }

    if (renameOpen) {
        RenameChatDialog(
            initial = title,
            originalNickname = nickname,
            onDismiss = { renameOpen = false },
            onConfirm = { newName ->
                renameOpen = false
                onRename(newName)
            },
        )
    }

    if (notifDialogOpen) {
        ContactNotificationDialog(
            contactName = title,
            mutedUntilMs = mutedUntilMs,
            messageSoundUri = messageSoundUri,
            callRingtoneUri = callRingtoneUri,
            onSetMuted = onSetContactMuted,
            onSetSounds = onSetContactSounds,
            onDismiss = { notifDialogOpen = false },
        )
    }

    if (clearChatOpen) {
        AegisHudDialog(
            title = stringResource(R.string.chat_clear_title),
            onDismiss = { clearChatOpen = false },
            confirmLabel = stringResource(R.string.chat_clear_confirm),
            onConfirm = {
                clearChatOpen = false
                onClearChat()
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { clearChatOpen = false },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.chat_clear_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (deleteChatOpen) {
        AegisHudDialog(
            title = stringResource(R.string.chat_delete_chat_title),
            onDismiss = { deleteChatOpen = false },
            confirmLabel = stringResource(R.string.chat_delete_chat_confirm),
            onConfirm = {
                deleteChatOpen = false
                onDeleteChat()
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { deleteChatOpen = false },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.chat_delete_chat_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Заглушён ли контакт прямо сейчас (для иконки в меню, §14.5). */
private fun mutedNow(mutedUntilMs: Long): Boolean =
    mutedUntilMs == Long.MAX_VALUE || mutedUntilMs > System.currentTimeMillis()

/**
 * Шапка переписки в стиле телефонного меню референса (`docs/ui_hud.md`):
 * микро-коды, хлебная крошка «СООБЩЕНИЯ › ИМЯ», двухцветная линейка и строка
 * эфемерного статуса собеседника под ней.
 *
 * Инсеты берём сами: приложение рисует edge-to-edge, а `Scaffold` не добавляет
 * отступы своему `topBar`.
 *
 * Декоративные коды считаются от СТАТИЧЕСКОГО slug экрана, а не от контакта:
 * код из `contactId` был бы стабильным идентификатором переписки, видимым на
 * любом скриншоте (инвариант [com.aegis.core.ui.hud.AegisHudCodes]).
 */
@Composable
private fun ChatHudHeader(
    title: String,
    subtitle: String,
    subtitleColor: androidx.compose.ui.graphics.Color,
    verified: Boolean,
    callControlsVisible: Boolean,
    blocked: Boolean,
    muted: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenRename: () -> Unit,
    onVerify: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenNotifications: () -> Unit,
    onToggleBlock: () -> Unit,
    onOpenClearChat: () -> Unit,
    onOpenDeleteChat: () -> Unit,
) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        AegisHudCodeRow(slug = CHAT_HUD_SLUG, modifier = Modifier.padding(horizontal = 8.dp))
        // Крошка занимает всю ширину строки, а действия вынесены под линейку.
        // Если сложить их в одну строку, на экране 360dp кнопки съедают ~250dp
        // и от «СООБЩЕНИЯ › ИМЯ» остаётся многоточие.
        AegisHudSectionHeader(
            title = stringResource(R.string.chat_header_section),
            subtitle = title,
            icon = Icons.AutoMirrored.Filled.Message,
            leading = {
                AegisHudIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    onClick = onBack,
                    size = 34.dp,
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Статус собеседника («печатает…»/«в сети»). Нажатие переименовывает
            // чат: то же действие, что и раньше по тапу на заголовок.
            Text(
                text = subtitle,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenRename)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AegisHudIconButton(
                icon = if (verified) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe,
                contentDescription = stringResource(
                    if (verified) R.string.chat_verified_cd else R.string.chat_verify_cd,
                ),
                onClick = onVerify,
                accent = if (verified) colors.securityAccent else colors.textSecondary,
                size = 38.dp,
            )
            if (callControlsVisible) {
                AegisHudIconButton(
                    icon = Icons.Filled.Call,
                    contentDescription = stringResource(R.string.call_type_voice),
                    onClick = onStartAudioCall,
                    size = 38.dp,
                )
                AegisHudIconButton(
                    icon = Icons.Filled.Videocam,
                    contentDescription = stringResource(R.string.call_type_video),
                    onClick = onStartVideoCall,
                    size = 38.dp,
                )
            }
            Box {
                AegisHudIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.chat_more_cd),
                    onClick = { onMenuOpenChange(true) },
                    size = 38.dp,
                )
                AegisHudDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) },
                ) {
                    AegisHudMenuItem(
                        text = stringResource(R.string.chat_menu_rename),
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenRename()
                        },
                        icon = Icons.Filled.Edit,
                    )
                    AegisHudMenuItem(
                        text = stringResource(R.string.chat_menu_notifications),
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenNotifications()
                        },
                        icon = if (muted) {
                            Icons.Filled.NotificationsOff
                        } else {
                            Icons.Filled.Notifications
                        },
                    )
                    // Блокировка контакта — защитное действие, а разблокировка
                    // ослабляет фильтр, поэтому в danger красится именно она (§5.8).
                    AegisHudMenuItem(
                        text = stringResource(
                            if (blocked) R.string.chat_menu_unblock else R.string.chat_menu_block,
                        ),
                        onClick = {
                            onMenuOpenChange(false)
                            onToggleBlock()
                        },
                        icon = Icons.Filled.Block,
                        danger = blocked,
                    )
                    AegisHudDivider()
                    AegisHudMenuItem(
                        text = stringResource(R.string.chat_menu_clear),
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenClearChat()
                        },
                        icon = Icons.Filled.DeleteSweep,
                        danger = true,
                    )
                    AegisHudMenuItem(
                        text = stringResource(R.string.chat_menu_delete),
                        onClick = {
                            onMenuOpenChange(false)
                            onOpenDeleteChat()
                        },
                        icon = Icons.Filled.Delete,
                        danger = true,
                    )
                }
            }
        }
    }
}

/** Статический slug экрана переписки для декоративных HUD-кодов. */
private const val CHAT_HUD_SLUG = "chat.thread"

@Composable
private fun DeleteMessageDialog(
    message: Message,
    onDismiss: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    // Обе кнопки удаляют, поэтому обе помечены как опасные: «у всех» уходит
    // подтверждением (акцентный danger), «у меня» — отменяющей кнопкой, но текст
    // всё равно красный, чтобы промах не выглядел безобидным.
    AegisHudDialog(
        title = stringResource(R.string.chat_delete_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.chat_delete_for_everyone),
        onConfirm = onDeleteForEveryone,
        dismissLabel = stringResource(R.string.chat_delete_for_me),
        onDismissAction = onDeleteForMe,
        danger = true,
    ) {
        Text(
            text = stringResource(R.string.chat_delete_body),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MessageInfoDialog(
    message: Message,
    onDismiss: () -> Unit,
) {
    val crypto = message.crypto
    val colors = AegisTheme.extendedColors
    AegisHudDialog(
        title = stringResource(R.string.chat_msg_info_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_done),
        onConfirm = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (crypto == null || !crypto.recorded) {
                Text(
                    text = stringResource(R.string.chat_msg_info_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            } else {
                // Слои шифрования — строки состояния: маркер показывает, что
                // именно отработало на этом сообщении, а не просто цветной текст.
                AegisHudStatusLine(text = stringResource(R.string.chat_msg_info_verified), ok = true)
                AegisHudStatusLine(
                    text = stringResource(
                        if (crypto.layerA) {
                            R.string.chat_msg_info_layer_a_on
                        } else {
                            R.string.chat_msg_info_layer_a_off
                        },
                    ),
                    ok = crypto.layerA,
                )
                if (crypto.layerA) {
                    // Названия алгоритмов каскада — моноширинным: их сверяют с
                    // документацией формата контейнера.
                    crypto.cascadeAlgorithms.forEach { algo ->
                        Text(
                            text = "· $algo",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.securityAccent,
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_msg_info_layer_a_kdf),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
                if (crypto.layerBProtocol.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.chat_msg_info_layer_b, crypto.layerBProtocol),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (crypto.layerBType.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.chat_msg_info_layer_b_type, crypto.layerBType),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                }
                if (crypto.transport.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.chat_msg_info_transport, crypto.transport),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (crypto.attachmentEncrypted) {
                    AegisHudStatusLine(
                        text = stringResource(R.string.chat_msg_info_attachment),
                        ok = true,
                    )
                }
            }
            AegisHudDivider()
            Text(
                text = stringResource(R.string.chat_msg_info_storage),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * Нижняя панель заблокированного чата: вместо поля ввода — статус и HUD-строка
 * подсказок с единственным действием «Разблокировать» (`docs/ui_hud.md`).
 */
@Composable
private fun BlockedBar(onUnblock: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(bottomBarInsets),
        ) {
            AegisHudBanner(
                text = stringResource(R.string.chat_blocked_bar),
                tone = HudBannerTone.DANGER,
            )
            AegisHudHintBar(
                hints = listOf(
                    AegisHudHint(
                        key = "U",
                        label = stringResource(R.string.chat_menu_unblock),
                        onClick = onUnblock,
                        danger = true,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun UnverifiedBanner(onVerify: () -> Unit) {
    AegisHudBanner(
        text = stringResource(R.string.chat_unverified_banner),
        tone = HudBannerTone.DANGER,
        onClick = onVerify,
    )
}

/**
 * Баннер «ключи слоя B ещё не получены» (§6.2). Показывается, пока у контакта
 * нет prekey-бандла: слой A (PSK) уже установлен личным обменом, поэтому
 * догоняющий обмен идёт сам по keyx-каналу, но отправка до его завершения
 * падает — объясняем это и даём кнопку повтора, чтобы не ждать цикл.
 */
@Composable
private fun KeysPendingBanner(onRetry: () -> Unit) {
    AegisHudBanner(
        text = stringResource(R.string.chat_keys_pending_banner),
        tone = HudBannerTone.SECURITY,
        actionLabel = stringResource(R.string.chat_keys_pending_retry),
        onAction = onRetry,
    )
}

/** Баннер «у контакта новое устройство — сверьте отпечаток» (мультидевайс §8). */
@Composable
private fun NewDeviceBanner(onVerify: () -> Unit) {
    AegisHudBanner(
        text = stringResource(R.string.chat_new_device_banner),
        tone = HudBannerTone.SECURITY,
        onClick = onVerify,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    voicePlayback: VoicePlaybackController,
    attachmentDownloader: suspend (Message) -> Boolean,
    messageRetrier: suspend (Message) -> Boolean,
    attachmentBytesProvider: suspend (String) -> ByteArray?,
) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val onColor = if (outgoing) {
        AegisTheme.extendedColors.onOutgoingBubble
    } else {
        AegisTheme.extendedColors.onIncomingBubble
    }
    // Повтор отправки — для ЛЮБОГО неотправленного исходящего, а не только для
    // вложений: неудача бывает и у текста (нет ключей слоя B §6.2, недоступен
    // Tor/релей), и без кнопки такое сообщение оставалось бы в тупике — помечено
    // как неудачное, а повторить нечем. Интерактивная строка живёт ВНУТРИ баббла;
    // обычная метка времени/статуса — под бабблом (моноширинная подпись).
    val showRetryInBubble = outgoing && message.status == MessageStatus.FAILED
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            AegisHudBubble(
                outgoing = outgoing,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            ) {
                message.attachment?.let { ref ->
                    // Аудио получает собственный рендер: плеер с дорожкой вместо
                    // карточки файла (§14.4). Проверка по ref, а не по kind:
                    // голосовое едет как FILE ради совместимости (см. VoiceMeta).
                    if (ref.isPlayableAudio) {
                        VoiceMessageContent(
                            message = message,
                            ref = ref,
                            onColor = onColor,
                            controller = voicePlayback,
                            attachmentDownloader = attachmentDownloader,
                            attachmentBytesProvider = attachmentBytesProvider,
                        )
                    } else {
                        AttachmentContent(
                            message = message,
                            ref = ref,
                            onColor = onColor,
                            attachmentDownloader = attachmentDownloader,
                            attachmentBytesProvider = attachmentBytesProvider,
                        )
                    }
                }
                if (message.body.isNotEmpty()) {
                    if (message.attachment != null) Spacer(Modifier.height(6.dp))
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onColor,
                    )
                }
                if (showRetryInBubble) {
                    Spacer(Modifier.height(4.dp))
                    MessageRetryRow(message = message, onColor = onColor, retrier = messageRetrier)
                }
            }
            // Служебная подпись под бабблом: время + статус доставки, моноширинный
            // приглушённый шрифт (как ID/таймстампы в интерфейсе игры).
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = formatMessageTime(message.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = AegisTheme.extendedColors.textSecondary,
                )
                if (outgoing && !showRetryInBubble) {
                    val label = statusLabel(message.status)
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = AegisTheme.extendedColors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** Время сообщения (HH:mm) для служебной подписи под бабблом. */
private fun formatMessageTime(timestampMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestampMs))

/** Одна ли календарная дата у двух таймстампов (для разделителей в ленте). */
private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Подпись даты для разделителя между группами сообщений. */
private fun formatDateLabel(timestampMs: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(timestampMs))

/**
 * Разделитель даты в ленте сообщений — центрированная моноширинная подпись между
 * тонкими линиями (эстетика Cyberpunk 2077).
 */
@Composable
private fun DateDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = AegisTheme.extendedColors.borderSubtle,
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AegisTheme.extendedColors.textSecondary,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = AegisTheme.extendedColors.borderSubtle,
        )
    }
}

/**
 * Показ вложения в пузыре (§14.4): картинка рендерится инлайн из локальных
 * (расшифрованных) байтов; видео/файл — карточкой с иконкой, именем и размером.
 * Если байтов ещё нет (входящее без автозагрузки) — кнопка «Скачать».
 *
 * Расшифрованные байты держатся только в памяти (декод в bitmap) — на диск в
 * открытом виде ничего не пишется, во внешние приложения не отдаётся (§6.3/§13).
 */
@Composable
private fun AttachmentContent(
    message: Message,
    ref: AttachmentRef,
    onColor: androidx.compose.ui.graphics.Color,
    attachmentDownloader: suspend (Message) -> Boolean,
    attachmentBytesProvider: suspend (String) -> ByteArray?,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, message.id, message.attachmentLocal) {
        value = if (message.attachmentLocal) attachmentBytesProvider(message.id) else null
    }

    if (ref.kind == AttachmentKind.IMAGE && bytes != null) {
        val bitmap = remember(bytes) {
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = ref.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(AegisClippedCornerShape),
            )
            return
        }
    }

    // Видео-вложение с локальными байтами (§14.4, follow-up): показываем превью-
    // кадр из памяти (VideoFrameDecoder, plaintext на диск не пишется) + иконку
    // play. Декодирование — на IO-диспетчере, чтобы не блокировать кадр UI.
    if (ref.kind == AttachmentKind.VIDEO && bytes != null) {
        val frame by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes) {
            val src = bytes
            value = if (src != null) {
                withContext(Dispatchers.IO) { VideoFrameDecoder.firstFrame(src)?.asImageBitmap() }
            } else {
                null
            }
        }
        frame?.let { fr ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .heightIn(max = 320.dp)
                    .clip(AegisClippedCornerShape),
            ) {
                Image(
                    bitmap = fr,
                    contentDescription = ref.fileName,
                    contentScale = ContentScale.Fit,
                )
                // Кадр произвольный, поэтому под глифом «play» лежит скрим из
                // токена: без подложки иконка теряется на светлом кадре.
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = stringResource(R.string.chat_video_play_cd),
                    tint = AegisTheme.extendedColors.securityAccent,
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            AegisTheme.extendedColors.scrimOverlay,
                            AegisChipShape,
                        )
                        .padding(4.dp),
                )
            }
            return
        }
    }

    // Карточка не-картиночного вложения либо медиа без локальных байтов.
    Surface(
        color = onColor.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 200.dp)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = when (ref.kind) {
                    AttachmentKind.IMAGE -> Icons.Filled.Image
                    AttachmentKind.VIDEO -> Icons.Filled.PlayCircle
                    AttachmentKind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = onColor,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = ref.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onColor,
                    maxLines = 2,
                )
                Text(
                    text = humanSize(ref.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.7f),
                )
            }
            if (message.direction == MessageDirection.INCOMING && !message.attachmentLocal) {
                val scope = rememberCoroutineScope()
                var downloading by remember(message.id) { mutableStateOf(false) }
                var failed by remember(message.id) { mutableStateOf(false) }
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = onColor,
                    )
                } else {
                    // Повторный тап после обрыва = «докачка» (best-effort повтор).
                    IconButton(onClick = {
                        downloading = true
                        failed = false
                        scope.launch {
                            val ok = attachmentDownloader(message)
                            downloading = false
                            failed = !ok
                        }
                    }) {
                        Icon(
                            imageVector = if (failed) Icons.Filled.Refresh else Icons.Filled.Download,
                            contentDescription = stringResource(
                                if (failed) R.string.chat_download_retry_cd else R.string.chat_download_cd,
                            ),
                            tint = if (failed) AegisTheme.extendedColors.danger else onColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Строка статуса для неотправленного исходящего сообщения (§14.2/§14.4): причина
 * и кнопка «Повторить». Для текста повтор заново прогоняет конвейер слой
 * A→B→Tor с тем же id; для вложения — фазу 2 (upload+send) на уже сохранённых
 * локально байтах. Пока идёт повтор — индикатор прогресса вместо кнопки, чтобы
 * повторные нажатия не плодили параллельные отправки.
 */
@Composable
private fun MessageRetryRow(
    message: Message,
    onColor: androidx.compose.ui.graphics.Color,
    retrier: suspend (Message) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var retrying by remember(message.id) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_status_failed),
            style = MaterialTheme.typography.labelSmall,
            color = onColor,
        )
        if (retrying) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = onColor,
            )
        } else {
            TextButton(
                onClick = {
                    retrying = true
                    scope.launch {
                        retrier(message)
                        retrying = false
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_attach_retry),
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor,
                )
            }
        }
    }
}

@Composable
private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> stringResource(R.string.chat_size_bytes, bytes.toInt())
    bytes < 1024 * 1024 -> stringResource(R.string.chat_size_kb, (bytes / 1024).toInt())
    else -> stringResource(R.string.chat_size_mb, bytes / (1024.0 * 1024.0))
}

@Composable
private fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> stringResource(R.string.chat_status_pending)
    MessageStatus.SENT -> stringResource(R.string.chat_status_sent)
    MessageStatus.READ -> stringResource(R.string.chat_status_read)
    MessageStatus.FAILED -> stringResource(R.string.chat_status_failed)
    MessageStatus.RECEIVED -> ""
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachmentPicked: (Uri) -> Unit,
    onCameraCaptured: (java.io.File) -> Unit,
    onVoiceRecorded: (VoiceRecorder.Result) -> Unit,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var attachMenu by remember { mutableStateOf(false) }

    val submit = {
        if (value.trim().isNotEmpty()) {
            onSend()
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // Photo Picker (§14.4): картинки/видео без разрешений на хранилище.
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAttachmentPicked) }

    // Storage Access Framework: произвольный файл (только чтение выбранного).
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAttachmentPicked) }

    // Съёмка на камеру (§14.4, follow-up): камера пишет во временный файл в кэше,
    // после чтения он затирается (CameraCapture, §6.3/§7.4). Держим цель между
    // запуском и колбэком, чтобы вернуть File по факту успеха/отмены.
    var cameraTarget by remember { mutableStateOf<CameraCapture.Target?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val target = cameraTarget
        cameraTarget = null
        if (target != null) {
            if (success) onCameraCaptured(target.file) else CameraCapture.discard(target.file)
        }
    }
    val launchCamera = {
        val target = CameraCapture.newTarget(context)
        cameraTarget = target
        takePicture.launch(target.uri)
    }
    // CAMERA объявлено в манифесте (QR/видеозвонки), поэтому для съёмки нужно
    // runtime-разрешение (иначе TakePicture упадёт на устройстве с M+).
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() }

    // --- Запись голосового (§14.4) ------------------------------------------
    val scope = rememberCoroutineScope()
    val recorder = remember(scope) { VoiceRecorder(scope) }
    val micGate = rememberMicPermissionGate()
    val recorderState by recorder.state.collectAsStateWithLifecycle()
    var cancelArmed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val cancelPx = with(density) { VoiceCancelThreshold.toPx() }
    val lockPx = with(density) { VoiceLockThreshold.toPx() }

    // Микрофон в фоне с Android 14 беззвучно отдаёт нули без foreground-сервиса
    // типа `microphone`, а держать такой сервис ради удержания кнопки — это
    // постоянное уведомление «идёт запись» ради жеста длиной в секунды. Поэтому
    // уход экрана в фон ОТМЕНЯЕТ запись: отправить тишину хуже, чем не отправить.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, recorder) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recorder.isRecording) recorder.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recorder.cancel()
        }
    }

    // Нерезультативный исход обязан быть объяснён: без подсказки слишком короткое
    // удержание выглядело бы как неработающая кнопка (ripple есть, сообщения нет),
    // а отказ микрофона — как молчаливая потеря записи. Отмену пользователем,
    // наоборот, комментировать не нужно: он сам её и запросил.
    var recordHint by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(recordHint) {
        if (recordHint != null) {
            kotlinx.coroutines.delay(HINT_VISIBLE_MS)
            recordHint = null
        }
    }

    // Возвращает, началась ли запись: при отсутствии разрешения жест не должен
    // «вооружаться» (иначе отпускание отправляло бы несуществующую запись).
    val startRecording: () -> Boolean = {
        cancelArmed = false
        locked = false
        recordHint = null
        // Разрешение спрашиваем ДО старта: системный диалог поверх экрана
        // разорвал бы жест удержания, поэтому при первом нажатии запись не
        // начинается — пользователь нажимает повторно, уже имея разрешение.
        micGate {
            keyboardController?.hide()
            recorder.start { outcome ->
                cancelArmed = false
                locked = false
                when (outcome) {
                    is VoiceRecorder.Outcome.Recorded -> onVoiceRecorded(outcome.result)
                    VoiceRecorder.Outcome.TooShort -> recordHint = R.string.chat_voice_too_short
                    VoiceRecorder.Outcome.Failed -> recordHint = R.string.chat_voice_mic_failed
                    VoiceRecorder.Outcome.Cancelled -> Unit
                }
            }
        }
    }

    val recording = recorderState as? VoiceRecorder.State.Recording

    // Автостоп по достижению потолка длительности: обрывать запись с потерей
    // хуже, чем отправить то, что уже записано (см. VOICE_MAX_DURATION_MS).
    LaunchedEffect(recording?.elapsedMs) {
        val elapsed = recording?.elapsedMs ?: return@LaunchedEffect
        if (elapsed >= AegisConstants.VOICE_MAX_DURATION_MS) recorder.stop()
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.windowInsetsPadding(bottomBarInsets)) {
            // Маджентовая кромка над панелью ввода — нижний край HUD, как в
            // референсе (`docs/ui_hud.md`).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AegisTheme.extendedColors.ruleTail),
            )
            if (recording != null) {
                VoiceRecordingPanel(
                    state = VoiceRecordingUiState(
                        elapsedMs = recording.elapsedMs,
                        levels = recording.levels,
                        cancelArmed = cancelArmed,
                        locked = locked,
                    ),
                    maxDurationMs = AegisConstants.VOICE_MAX_DURATION_MS,
                    onCancel = { recorder.cancel() },
                    onSend = { recorder.stop() },
                )
            }
            recordHint?.let { hint ->
                AegisHudBanner(text = stringResource(hint), tone = HudBannerTone.SECURITY)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    AegisHudIconButton(
                        icon = Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.chat_attach_cd),
                        onClick = { attachMenu = true },
                        enabled = recording == null,
                        accent = AegisTheme.extendedColors.textSecondary,
                        size = 38.dp,
                    )
                    AegisHudDropdownMenu(
                        expanded = attachMenu,
                        onDismissRequest = { attachMenu = false },
                    ) {
                        AegisHudMenuItem(
                            text = stringResource(R.string.chat_attach_camera),
                            onClick = {
                                attachMenu = false
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    launchCamera()
                                } else {
                                    requestCamera.launch(Manifest.permission.CAMERA)
                                }
                            },
                            icon = Icons.Filled.PhotoCamera,
                        )
                        AegisHudMenuItem(
                            text = stringResource(R.string.chat_attach_photo_video),
                            onClick = {
                                attachMenu = false
                                pickMedia.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                    ),
                                )
                            },
                            icon = Icons.Filled.Image,
                        )
                        AegisHudMenuItem(
                            text = stringResource(R.string.chat_attach_file),
                            onClick = {
                                attachMenu = false
                                openDocument.launch(arrayOf("*/*"))
                            },
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                        )
                    }
                }
                if (recording == null) {
                    AegisHudTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.chat_input_placeholder),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                    )
                } else {
                    // Во время записи поле ввода бесполезно, а подсказка о жесте
                    // нужна именно здесь — под пальцем, а не в верхней панели.
                    Text(
                        text = stringResource(
                            if (cancelArmed) {
                                R.string.chat_voice_slide_cancel_armed
                            } else {
                                R.string.chat_voice_slide_cancel
                            },
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cancelArmed) {
                            AegisTheme.extendedColors.danger
                        } else {
                            AegisTheme.extendedColors.textSecondary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // ЕДИНСТВЕННАЯ ведущая кнопка справа — и на отправку текста, и на
                // запись голоса. Место вызова обязано быть ОДНО и безусловное:
                // ветвление `if (…) Send else Mic` утилизировало бы узел ввода
                // при старте записи (текст пуст → показываем микрофон), и жест
                // удержания оборвался бы в тот же момент, когда начался.
                MessageInputActionButton(
                    sendMode = value.isNotBlank() && recording == null,
                    recording = recording != null,
                    cancelArmed = cancelArmed,
                    onSendText = submit,
                    gesture = Modifier.voiceRecordGesture(
                        enabled = value.isBlank(),
                        cancelThresholdPx = cancelPx,
                        lockThresholdPx = lockPx,
                        onStart = { !recorder.isRecording && startRecording() },
                        onDrag = { dx, dy ->
                            // Порог «отмены» читается только пока запись не
                            // зафиксирована: после фиксации палец уже отпущен.
                            if (!locked) {
                                cancelArmed = dx <= -cancelPx * ARM_HINT_FRACTION
                            }
                            if (dy <= -lockPx) locked = true
                        },
                        onCancel = { recorder.cancel() },
                        onLock = { locked = true },
                        onFinish = { if (!locked) recorder.stop() },
                    ),
                )
            }
        }
    }
}

/**
 * Доля порога отмены, на которой подсказка уже переключается в «отпустите для
 * отмены». Предупредить надо ДО точки невозврата, иначе пользователь узнаёт об
 * отмене по факту исчезнувшего сообщения.
 */
private const val ARM_HINT_FRACTION = 0.6f

/** Сколько держать подсказку «удерживайте кнопку» после слишком короткого нажатия. */
private const val HINT_VISIBLE_MS = 2_500L

/**
 * Ведущая кнопка строки ввода: отправка текста либо запись голосового.
 *
 * Вынесена в отдельный композабл, чтобы [MessageInput] вызывал её безусловно
 * (см. комментарий на месте вызова): узел жеста должен переживать переход
 * «нет записи → идёт запись», иначе удержание обрывается само.
 */
@Composable
private fun MessageInputActionButton(
    sendMode: Boolean,
    recording: Boolean,
    cancelArmed: Boolean,
    onSendText: () -> Unit,
    gesture: Modifier,
) {
    val colors = AegisTheme.extendedColors
    AegisHudIconButton(
        icon = if (sendMode) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic,
        contentDescription = stringResource(
            if (sendMode) R.string.chat_send_cd else R.string.chat_voice_record_cd,
        ),
        // Тап по микрофону без удержания ничего не отправляет: запись начинает и
        // заканчивает жест ([gesture]). onClick остаётся только за отправкой
        // текста, иначе короткий тап дал бы пустое голосовое.
        onClick = { if (sendMode) onSendText() },
        modifier = if (sendMode) Modifier else gesture,
        filled = true,
        accent = when {
            cancelArmed -> colors.danger
            recording -> colors.accentMagenta
            else -> colors.accentPrimary
        },
        size = 42.dp,
    )
}
