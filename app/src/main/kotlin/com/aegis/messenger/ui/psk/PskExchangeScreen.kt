package com.aegis.messenger.ui.psk

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.psk.PskMeta
import com.aegis.core.common.psk.PskQrPayload
import com.aegis.core.common.psk.PskSource
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudChip
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import com.aegis.messenger.ui.settings.LocalTorEnabled
import com.aegis.messenger.nfc.PskNfcPayloadHolder
import com.aegis.messenger.psk.PskGenerated
import com.aegis.messenger.psk.PskImportResult
import com.aegis.messenger.psk.PskManager
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.components.PlaceholderContent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Автоскрытие QR (сек) — минимизирует окно подсматривания (§6.2). */
private const val QR_AUTO_HIDE_SECONDS = 60
private const val PSK_UI_TAG = "psk-ui"

private sealed interface PskFlow {
    data object Hub : PskFlow
    data object Mutual : PskFlow
    data class Result(val message: String, val ok: Boolean) : PskFlow
}

/**
 * Экран ручного обмена ключом контейнера (PSK), P4 §6.2/§10.3. HUD-раскладка —
 * `docs/ui_hud.md`.
 *
 * Все способы — строго вне сети: показ/приём QR лично, ручной ввод мнемоники,
 * NFC при касании. PSK хранится только в защищённом хранилище (Keystore) и
 * НИКОГДА не уходит в сеть/буфер обмена. FLAG_SECURE включён глобально (§14.3).
 */
@Composable
fun PskExchangeScreen(
    pskManager: PskManager,
    selfIdentity: AccountIdentity?,
    modifier: Modifier = Modifier,
    selfLogin: String = "",
    relayConfigured: Boolean = true,
    onOpenChat: (String) -> Unit = {},
    /**
     * Выход из раздела личного обмена. `null` — экран открыт как самостоятельный
     * (прежнее поведение, выхода не нужно); иначе в хабе появляется кнопка
     * «назад» — без неё раздел становился тупиком, когда его открывают из
     * экрана добавления контакта.
     */
    onExit: (() -> Unit)? = null,
) {
    val entries by pskManager.entries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { pskManager.refresh() }

    var flow by remember { mutableStateOf<PskFlow>(PskFlow.Hub) }

    if (!pskManager.isReady) {
        NativeMissing(modifier)
        return
    }

    when (val current = flow) {
        PskFlow.Hub -> PskHub(
            entries = entries,
            modifier = modifier,
            onNew = { flow = PskFlow.Mutual },
            pskManager = pskManager,
            onExit = onExit,
        )

        PskFlow.Mutual -> PskMutualExchange(
            modifier = modifier,
            pskManager = pskManager,
            selfIdentity = selfIdentity,
            selfLogin = selfLogin,
            relayConfigured = relayConfigured,
            onBack = { flow = PskFlow.Hub },
            onOpenChat = onOpenChat,
            onResult = { msg, ok -> flow = PskFlow.Result(msg, ok) },
        )

        is PskFlow.Result -> PskResultScreen(
            message = current.message,
            ok = current.ok,
            modifier = modifier,
            onDone = { flow = PskFlow.Hub },
        )
    }
}

// ---------------------------------------------------------------------------
// Хаб: список установленных PSK + кнопка нового обмена.
// ---------------------------------------------------------------------------

/**
 * Хаб раздела. Экран живёт внутри вкладки «Контакты», поэтому фон, инсеты и
 * шапку приложения даёт хозяин вкладки: здесь только своя шапка
 * ([AegisHudSectionHeader]) без второго `aegisHudBackdrop` (см. `AegisHudScreen`).
 */
@Composable
private fun PskHub(
    entries: List<PskMeta>,
    pskManager: PskManager,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    onExit: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = AegisTheme.extendedColors
    var confirmDelete by remember { mutableStateOf<PskMeta?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        AegisHudSectionHeader(
            title = stringResource(R.string.psk_hub_title),
            icon = Icons.Outlined.VpnKey,
            leading = if (onExit != null) {
                {
                    AegisHudIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        onClick = onExit,
                        size = 34.dp,
                    )
                }
            } else {
                null
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AegisHudCard {
                Text(
                    text = stringResource(R.string.psk_hub_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            AegisHudButton(
                text = stringResource(R.string.psk_new),
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Add,
            )

            if (entries.isEmpty()) {
                PlaceholderContent(
                    icon = Icons.Outlined.VpnKey,
                    title = stringResource(R.string.psk_hub_title),
                    subtitle = stringResource(R.string.psk_empty),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                entries.forEach { meta ->
                    PskMetaCard(meta = meta, onDelete = { confirmDelete = meta })
                }
            }
        }
    }

    confirmDelete?.let { meta ->
        AegisHudDialog(
            title = stringResource(R.string.psk_delete_confirm_title),
            onDismiss = { confirmDelete = null },
            icon = Icons.Outlined.Delete,
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = null
                scope.launch { pskManager.remove(meta.contactId) }
            },
            dismissLabel = stringResource(R.string.common_cancel),
            onDismissAction = { confirmDelete = null },
            danger = true,
        ) {
            Text(
                text = stringResource(R.string.psk_delete_confirm_text),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Строка установленного ключа. Метка контакта — данные пользователя, поэтому
 * заголовка карточки у неё нет: заголовок HUD-карточки прописной, а имя
 * собеседника регистр менять не должно.
 */
@Composable
private fun PskMetaCard(meta: PskMeta, onDelete: () -> Unit) {
    val colors = AegisTheme.extendedColors
    AegisHudCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = meta.label.ifBlank { meta.contactId },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                AegisHudChip(text = sourceLabel(meta.source))
                // Отпечаток сверяют вслух — только моноширинным.
                Text(
                    text = groupFingerprint(meta.pskFingerprintHex),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                )
                if (meta.createdAtEpochMs > 0) {
                    Text(
                        text = stringResource(
                            R.string.psk_card_created,
                            formatDate(meta.createdAtEpochMs),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
            AegisHudIconButton(
                icon = Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.psk_delete),
                onClick = onDelete,
                accent = colors.danger,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Взаимный обмен: показываю свой QR И сканирую QR собеседника (§6.2).
// ---------------------------------------------------------------------------

/**
 * Взаимный обмен ключом контейнера лично. ОБЕ стороны открывают этот экран,
 * показывают свой QR и сканируют QR собеседника. Общий PSK = XOR двух половин
 * (обе стороны получают один секрет), контакт сохраняется под РЕАЛЬНЫМ accountId
 * собеседника, и чат появляется у ОБОИХ. Бандл слоя B едет в том же QR
 * (компактный формат v3); релей для самого обмена не нужен.
 */
@Composable
private fun PskMutualExchange(
    pskManager: PskManager,
    selfIdentity: AccountIdentity?,
    selfLogin: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onResult: (String, Boolean) -> Unit,
    relayConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val colors = AegisTheme.extendedColors
    var generated by remember { mutableStateOf<PskGenerated?>(null) }
    var genError by remember { mutableStateOf<String?>(null) }
    // Обмен у МЕНЯ завершён (я отсканировал код собеседника). QR при этом НЕ
    // прячем — собеседник ещё должен отсканировать МОЙ код из того же раунда,
    // иначе половинки PSK разойдутся и safety-код не совпадёт.
    var done by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val cancelledMsg = stringResource(R.string.psk_scan_cancelled)
    val genFailMsg = stringResource(R.string.psk_err_generate)
    val successFmt = stringResource(R.string.psk_import_success)

    LaunchedEffect(Unit) {
        DiagnosticLog.event(PSK_UI_TAG, "открыт взаимный обмен", "я" to DiagnosticLog.shortId(selfIdentity?.accountId))
        generated = runCatching {
            pskManager.beginExchange(self = selfIdentity, displayLabel = selfLogin)
        }.getOrElse { e ->
            DiagnosticLog.e(PSK_UI_TAG, e) { "не удалось сгенерировать свой код обмена" }
            genError = e.message ?: genFailMsg
            null
        }
        // Если вкладку переключили и вернулись — раунд тот же, и обмен мог
        // быть уже завершён на нашей стороне.
        pskManager.completedPeerId()?.let { done = true }
        generated?.let { gen ->
            val bundleInline = gen.hasBundle &&
                gen.qrPayloadJson.length > 1000 &&
                gen.qrPayloadJson.startsWith(PskQrPayload.HEADER)
            DiagnosticLog.event(
                PSK_UI_TAG, "свой QR готов",
                "бандлВQr" to bundleInline,
                "nfcБайт" to gen.payloadJson.length,
                "qrБайт" to gen.qrPayloadJson.length,
                "естьБандл" to gen.hasBundle,
            )
        }
    }

    // Пока экран открыт — отдаём полную нагрузку (с бандлом) по NFC (HCE).
    // Половину PSK при уходе НЕ затираем: переключение на «Чаты» не должно
    // начинать новый раунд.
    val payload = generated
    DisposableEffect(payload) {
        if (payload != null) {
            PskNfcPayloadHolder.set(payload.payloadJson.encodeToByteArray())
        }
        onDispose { PskNfcPayloadHolder.clear() }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            DiagnosticLog.w(PSK_UI_TAG) { "сканирование QR отменено" }
            status = cancelledMsg to false
        } else {
            DiagnosticLog.event(
                PSK_UI_TAG, "QR отсканирован",
                "байт" to contents.length,
                "маркерAegis" to PskQrPayload.containsAegisMarker(contents),
            )
            scope.launch {
                when (val res = pskManager.completeExchange(rawJson = contents, source = PskSource.QR)) {
                    is PskImportResult.Success -> {
                        DiagnosticLog.event(
                            PSK_UI_TAG, "обмен у меня ЗАВЕРШЁН",
                            "контакт" to DiagnosticLog.shortId(res.meta.contactId),
                            "источник" to res.meta.source.name,
                        )
                        done = true
                        status = successFmt.format(res.meta.label.ifBlank { res.meta.contactId }) to true
                    }
                    is PskImportResult.Failure -> {
                        DiagnosticLog.w(PSK_UI_TAG) { "обмен НЕ завершён: ${res.reason}" }
                        status = res.reason to false
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AegisHudBanner(
            text = stringResource(R.string.psk_warning_inperson),
            tone = HudBannerTone.DANGER,
        )

        if (!relayConfigured) {
            AegisHudBanner(
                text = stringResource(
                    if (LocalTorEnabled.current) R.string.psk_relay_missing_hint else R.string.psk_relay_missing_hint_direct,
                ),
                tone = HudBannerTone.DANGER,
            )
        }

        Text(
            text = stringResource(R.string.psk_mutual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )

        when {
            genError != null -> AegisHudBanner(
                text = genError!!,
                tone = HudBannerTone.DANGER,
            )

            payload == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) { AegisHudBusy() }

            else -> QrWithAutoHide(content = payload.qrPayloadJson, autoHide = !done)
        }

        status?.let { (msg, ok) ->
            AegisHudStatusLine(text = msg, ok = ok, failColor = colors.danger)
        }

        if (!done) {
            AegisHudButton(
                text = stringResource(R.string.psk_method_scan),
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setBeepEnabled(false)
                            .setPrompt(""),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = payload != null,
                icon = Icons.Outlined.QrCodeScanner,
            )

            AegisHudOutlinedButton(
                text = stringResource(R.string.common_back),
                onClick = {
                    DiagnosticLog.event(PSK_UI_TAG, "обмен отменён кнопкой «Назад»")
                    pskManager.cancelExchange()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Я завершил, но НЕ ухожу, пока собеседник не отсканирует мой QR.
            AegisHudBanner(
                text = stringResource(R.string.psk_mutual_wait_peer),
                tone = HudBannerTone.SECURITY,
            )
            val peerId = pskManager.completedPeerId()
            if (peerId != null) {
                AegisHudButton(
                    text = stringResource(R.string.psk_open_chat),
                    onClick = { onOpenChat(peerId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AegisHudOutlinedButton(
                text = stringResource(R.string.psk_done),
                onClick = {
                    pskManager.cancelExchange()
                    onResult(status?.first.orEmpty(), true)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Свой код обмена. Подложка под QR остаётся БЕЛОЙ, а текст на ней — тёмным:
 * сканеру нужен максимальный контраст модулей, и затемнение по теме сломало бы
 * распознавание. Это единственное место экрана, где цвет задан константой, а не
 * токеном (§13). Рубленая рамка со скобками рисуется ВОКРУГ белого поля, самого
 * кода не касаясь.
 */
@Composable
private fun QrWithAutoHide(content: String, autoHide: Boolean = true) {
    val colors = AegisTheme.extendedColors
    val bitmap = remember(content) { encodeQrBitmap(content, 720) }

    // QR не удалось закодировать (например, нагрузка слишком велика) — показываем
    // явную ошибку вместо «нажмите, чтобы показать», по которой ничего не
    // происходило бы (клик лишь переключал бы флаг видимости пустого кода).
    if (bitmap == null) {
        AegisHudCard(accent = colors.danger) {
            Text(
                text = stringResource(R.string.psk_qr_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.danger,
            )
        }
        return
    }

    var visible by remember { mutableStateOf(true) }
    var secondsLeft by remember { mutableStateOf(QR_AUTO_HIDE_SECONDS) }

    LaunchedEffect(visible, autoHide) {
        if (visible && autoHide) {
            secondsLeft = QR_AUTO_HIDE_SECONDS
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
            visible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(AegisPanelShape)
            .background(Color.White)
            .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
            .clickable { if (!visible) visible = true }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = stringResource(R.string.psk_show_qr_hidden),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (visible && autoHide) {
        Text(
            text = stringResource(R.string.psk_flagsecure_note, secondsLeft),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// Результат импорта.
// ---------------------------------------------------------------------------

@Composable
private fun PskResultScreen(
    message: String,
    ok: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AegisHudCard(accent = if (ok) colors.securityAccent else colors.danger) {
            AegisHudStatusLine(text = message, ok = ok, failColor = colors.danger)
        }
        Spacer(Modifier.height(24.dp))
        AegisHudButton(
            text = stringResource(R.string.psk_done),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Общие компоненты и утилиты.
// ---------------------------------------------------------------------------

@Composable
private fun NativeMissing(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        AegisHudBanner(
            text = stringResource(R.string.psk_native_missing),
            tone = HudBannerTone.DANGER,
        )
    }
}

@Composable
private fun sourceLabel(source: PskSource): String = stringResource(
    when (source) {
        PskSource.GENERATED -> R.string.psk_source_generated
        PskSource.QR -> R.string.psk_source_qr
        PskSource.MNEMONIC -> R.string.psk_source_mnemonic
        PskSource.NFC -> R.string.psk_source_nfc
        // Удалённое добавление: секрет не переносился, а выведен из рукопожатия
        // (`docs/contact_requests.md`) — это видно в списке PSK как отдельный
        // способ, чтобы личный обмен и удалённый не выглядели одинаково.
        PskSource.REMOTE_HANDSHAKE -> R.string.psk_source_remote
    },
)

private fun groupFingerprint(hex: String, groupSize: Int = 4, groups: Int = 8): String =
    hex.uppercase().take(groupSize * groups).chunked(groupSize).joinToString(" ")

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
