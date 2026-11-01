package com.aegis.messenger.ui.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.transfer.TransferPayload
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudCheckRow
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountTransfer
import com.aegis.messenger.account.TransferApplyResult
import com.aegis.messenger.account.TransferMergeReport
import com.aegis.messenger.account.TransferMergeResult
import com.aegis.messenger.account.TransferOpenResult
import com.aegis.messenger.account.TransferSummary
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.onboarding.PasswordField
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Режим приёма пакета переноса (§7.1.2):
 *  - [Clean] — на ЧИСТОЕ устройство: создаётся аккаунт из перенесённой
 *    идентичности (онбординг).
 *  - [Merge] — слияние в УЖЕ существующий аккаунт: add-only (контакты/переписки/
 *    PSK), идентичность и Signal-состояние не трогаются.
 */
enum class TransferImportMode { Clean, Merge }

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * логин или отпечаток из пакета: код попадает на любой скриншот
 * (§6 `docs/ui_hud.md`).
 */
private const val TRANSFER_IMPORT_HUD_SLUG = "transfer.import"

/**
 * Импорт переноса аккаунта на ЧИСТОЕ устройство (§7.1.2) в HUD-раскладке
 * (`docs/ui_hud.md`). Пакет принимается вне сети — сканированием QR либо из
 * файла — распечатывается паролем и (после подтверждения содержимого)
 * разворачивается: создаётся аккаунт из перенесённой мнемоники,
 * восстанавливаются выбранные контакты/переписки/PSK/настройки.
 *
 * Применение выполняется в [appScope] (переживает смену экрана): успешное
 * восстановление аккаунта мгновенно переводит приложение в разблокированное
 * состояние и уводит из онбординга.
 */
@Composable
fun TransferImportScreen(
    accountTransfer: AccountTransfer,
    appScope: CoroutineScope,
    onImported: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    mode: TransferImportMode = TransferImportMode.Clean,
) {
    val colors = AegisTheme.extendedColors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var envelopeJson by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Pair<TransferPayload, TransferSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Merge-режим: предпросмотр слияния + опция применить настройки из пакета.
    var mergeReport by remember { mutableStateOf<TransferMergeReport?>(null) }
    var overwriteSettings by remember { mutableStateOf(false) }

    val fileReadFailMsg = stringResource(R.string.transfer_file_read_fail)
    val wrongPasswordMsg = stringResource(R.string.transfer_wrong_password)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            envelopeJson = contents
            error = null
        }
    }
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
                if (text.isNullOrBlank()) error = fileReadFailMsg else {
                    envelopeJson = text
                    error = null
                }
            }
        }
    }

    fun open() {
        val json = envelopeJson ?: return
        error = null
        busy = true
        scope.launch {
            when (val res = accountTransfer.openEnvelope(json, password)) {
                is TransferOpenResult.Success -> opened = res.payload to res.summary
                TransferOpenResult.WrongPassword -> error = wrongPasswordMsg
                is TransferOpenResult.Invalid -> error = res.reason
            }
            busy = false
        }
    }

    fun apply() {
        val payload = opened?.first ?: return
        error = null
        busy = true
        // На appScope: восстановление аккаунта переведёт состояние в Unlocked и
        // уведёт этот экран из композиции — корутина UI-scope была бы отменена.
        appScope.launch {
            when (val res = accountTransfer.apply(payload, password)) {
                is TransferApplyResult.Success -> onImported()
                is TransferApplyResult.Failure -> {
                    error = res.reason
                    busy = false
                }
            }
        }
    }

    // Merge-режим: аккаунт УЖЕ существует, состояние остаётся Unlocked — экран не
    // покидает композицию, поэтому работаем в UI-scope, а после успеха
    // обновляем живые потоки (onImported) и возвращаемся назад.
    fun merge() {
        val payload = opened?.first ?: return
        error = null
        busy = true
        scope.launch {
            when (val res = accountTransfer.merge(payload, overwriteSettings)) {
                is TransferMergeResult.Success -> {
                    onImported()
                    onBack()
                }
                is TransferMergeResult.Failure -> {
                    error = res.reason
                    busy = false
                }
            }
        }
    }

    // При входе в сводку в merge-режиме считаем предпросмотр слияния (без записи).
    LaunchedEffect(opened, mode) {
        val payload = opened?.first
        if (mode == TransferImportMode.Merge && payload != null && mergeReport == null) {
            when (val res = accountTransfer.previewMerge(payload)) {
                is TransferMergeResult.Success -> mergeReport = res.report
                is TransferMergeResult.Failure -> error = res.reason
            }
        }
    }

    // Имя намеренно не `merge`: так называется локальная функция слияния выше.
    val mergeMode = mode == TransferImportMode.Merge
    // Кнопка «Отмена» осталась в контенте, а не в шапке экрана: пока идёт
    // распечатывание/слияние, уходить нельзя (`enabled = !busy`), а стрелка
    // «назад» в шапке такого состояния не имеет.
    AegisHudScreen(
        title = stringResource(
            if (mergeMode) R.string.transfer_merge_import_title else R.string.transfer_import_title,
        ),
        modifier = modifier,
        slug = TRANSFER_IMPORT_HUD_SLUG,
        icon = if (mergeMode) Icons.Outlined.MergeType else Icons.Outlined.Download,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (mergeMode) R.string.transfer_merge_import_desc else R.string.transfer_import_desc,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )

            val summary = opened?.second
            when {
                summary != null && mergeMode -> {
                    ImportSummaryCard(summary)
                    val report = mergeReport
                    if (report != null) {
                        MergeReportCard(report)
                        AegisHudBanner(
                            text = stringResource(
                                if (report.identityMatchesLocal) {
                                    R.string.transfer_merge_identity_match
                                } else {
                                    R.string.transfer_merge_identity_other
                                },
                            ),
                            tone = if (report.identityMatchesLocal) {
                                HudBannerTone.SECURITY
                            } else {
                                HudBannerTone.DANGER
                            },
                        )
                        if (report.hasImportableSettings) {
                            AegisHudCheckRow(
                                title = stringResource(R.string.transfer_merge_settings_checkbox),
                                checked = overwriteSettings,
                                onCheckedChange = { overwriteSettings = it },
                                enabled = !busy,
                            )
                        }
                        val nothingToAdd = report.contactsAdded == 0 &&
                            report.messagesAdded == 0 &&
                            report.psksAdded == 0 &&
                            !(report.hasImportableSettings && overwriteSettings)
                        if (nothingToAdd) {
                            AegisHudBanner(
                                text = stringResource(R.string.transfer_merge_nothing),
                                tone = HudBannerTone.SECURITY,
                            )
                        }
                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                            )
                        }
                        AegisHudButton(
                            text = stringResource(R.string.transfer_merge_apply),
                            onClick = { merge() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && !nothingToAdd,
                        )
                        if (busy) {
                            AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    } else {
                        AegisHudBusy(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            size = 24.dp,
                        )
                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                            )
                        }
                    }
                    AegisHudOutlinedButton(
                        text = stringResource(R.string.common_back),
                        onClick = { opened = null; mergeReport = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                summary != null -> {
                    ImportSummaryCard(summary)
                    if (!summary.hasAccount) {
                        AegisHudBanner(
                            text = stringResource(R.string.transfer_import_no_account_notice),
                            tone = HudBannerTone.DANGER,
                        )
                    }
                    error?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                    }
                    AegisHudButton(
                        text = stringResource(R.string.transfer_apply),
                        onClick = { apply() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && summary.hasAccount,
                    )
                    if (busy) {
                        AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    AegisHudOutlinedButton(
                        text = stringResource(R.string.common_back),
                        onClick = { opened = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                envelopeJson != null -> {
                    AegisHudBanner(
                        text = stringResource(R.string.transfer_import_received),
                        tone = HudBannerTone.SECURITY,
                    )
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.transfer_password_label),
                        enabled = !busy,
                    )
                    error?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                    }
                    AegisHudButton(
                        text = stringResource(R.string.transfer_open),
                        onClick = { open() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && password.isNotEmpty(),
                    )
                    if (busy) {
                        AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    AegisHudOutlinedButton(
                        text = stringResource(R.string.transfer_choose_other),
                        onClick = { envelopeJson = null; password = "" },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
                }

                else -> {
                    AegisHudButton(
                        text = stringResource(R.string.transfer_scan_qr),
                        onClick = {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setBeepEnabled(false)
                                    .setPrompt(""),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.QrCodeScanner,
                    )
                    AegisHudOutlinedButton(
                        text = stringResource(R.string.transfer_open_file),
                        onClick = { openFileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.FileOpen,
                    )
                    error?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                    }
                }
            }

            AegisHudOutlinedButton(
                text = stringResource(R.string.common_cancel),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
    }
}

/**
 * Содержимое распечатанного пакета. Логин выводится моноширинным и без смены
 * регистра: его сверяют с устройством-источником.
 */
@Composable
private fun ImportSummaryCard(summary: TransferSummary) {
    AegisHudCard(title = stringResource(R.string.transfer_summary_title)) {
        if (summary.login.isNotBlank()) {
            AegisHudKeyValue(
                label = stringResource(R.string.transfer_summary_login),
                value = summary.login,
            )
        }
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_account),
            value = if (summary.hasAccount) {
                stringResource(R.string.transfer_value_included)
            } else {
                stringResource(R.string.transfer_value_none)
            },
        )
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_contacts),
            value = summary.contactCount.toString(),
        )
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_messages),
            value = summary.messageCount.toString(),
        )
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_psk),
            value = summary.pskCount.toString(),
        )
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_settings),
            value = if (summary.hasSettings) {
                stringResource(R.string.transfer_value_settings_included)
            } else {
                stringResource(R.string.transfer_value_none)
            },
        )
    }
}

/**
 * Предпросмотр слияния. Строки приходят уже собранными («Контакты: +5 (уже
 * есть: 3)»), поэтому это не пары «подпись → значение», а готовый текст.
 */
@Composable
private fun MergeReportCard(report: TransferMergeReport) {
    AegisHudCard(title = stringResource(R.string.transfer_merge_report_title)) {
        ReportLine(
            stringResource(
                R.string.transfer_merge_contacts,
                report.contactsAdded,
                report.contactsExisting,
            ),
        )
        ReportLine(
            stringResource(
                R.string.transfer_merge_messages,
                report.messagesAdded,
                report.messagesExisting,
            ),
        )
        if (report.messagesOrphaned > 0) {
            ReportLine(stringResource(R.string.transfer_merge_messages_orphaned, report.messagesOrphaned))
        }
        ReportLine(
            stringResource(
                R.string.transfer_merge_psks,
                report.psksAdded,
                report.psksConflicting,
            ),
        )
    }
}

@Composable
private fun ReportLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
