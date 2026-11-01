package com.aegis.messenger.ui.transfer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.transfer.TransferSelection
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import com.aegis.messenger.account.AccountTransfer
import com.aegis.messenger.account.TransferBuildResult
import com.aegis.messenger.account.TransferSummary
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.onboarding.PasswordField
import com.aegis.messenger.ui.psk.encodeQrBitmap
import kotlinx.coroutines.launch

/** Максимальный размер оболочки (байты), который ещё разумно кодировать в QR. */
private const val QR_MAX_BYTES = 2200

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * логин или отпечаток: код попадает на любой скриншот (§6 `docs/ui_hud.md`).
 */
private const val TRANSFER_EXPORT_HUD_SLUG = "transfer.export"

/**
 * Экспорт переноса аккаунта (§7.1.2) в HUD-раскладке (`docs/ui_hud.md`): выбор
 * данных, пароль и сборка зашифрованного пакета. Небольшой пакет показывается
 * QR-кодом (лично, с FLAG_SECURE §14.3); крупный — сохраняется в зашифрованный
 * файл (SAF). Пакет бесполезен без пароля и передаётся строго вне сети.
 */
@Composable
fun TransferExportScreen(
    accountTransfer: AccountTransfer,
    multiDeviceAllowed: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var account by remember { mutableStateOf(true) }
    var contacts by remember { mutableStateOf(true) }
    var conversations by remember { mutableStateOf(true) }
    var psks by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(true) }
    var includeAttachments by remember { mutableStateOf(false) }

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }

    var envelopeJson by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<TransferSummary?>(null) }
    var sizeBytes by remember { mutableStateOf(0) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val json = envelopeJson
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
        }
    }

    val tooShortMsg = stringResource(R.string.transfer_err_password_too_short, 8)
    val mismatchMsg = stringResource(R.string.account_password_mismatch)

    fun build() {
        error = null
        if (password.length < 8) { error = tooShortMsg; return }
        if (password != confirm) { error = mismatchMsg; return }
        building = true
        scope.launch {
            val selection = TransferSelection(
                account = account,
                contacts = contacts,
                conversations = conversations,
                psks = psks,
                settings = settings,
            )
            val result = accountTransfer.buildEnvelope(
                selection = selection,
                password = password,
                includeAttachmentBytes = includeAttachments && conversations,
            )
            building = false
            when (result) {
                is TransferBuildResult.Success -> {
                    envelopeJson = result.envelopeJson
                    summary = result.summary
                    sizeBytes = result.sizeBytes
                }
                is TransferBuildResult.Failure -> error = result.reason
            }
        }
    }

    AegisHudScreen(
        title = stringResource(R.string.transfer_export_title),
        modifier = modifier,
        slug = TRANSFER_EXPORT_HUD_SLUG,
        icon = Icons.Outlined.Upload,
        onBack = onBack,
        backContentDescription = stringResource(R.string.common_back),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.transfer_export_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )

            if (!multiDeviceAllowed) {
                AegisHudBanner(
                    text = stringResource(R.string.transfer_export_multidevice_notice),
                    tone = HudBannerTone.SECURITY,
                )
            }

            val built = envelopeJson != null
            if (!built) {
                AegisHudCard(
                    title = stringResource(R.string.transfer_section_what),
                    icon = Icons.Outlined.Checklist,
                ) {
                    AegisHudSwitchRow(
                        title = stringResource(R.string.transfer_item_account_title),
                        checked = account,
                        onCheckedChange = { account = it },
                        subtitle = stringResource(R.string.transfer_item_account_sub),
                        enabled = !building,
                    )
                    AegisHudSwitchRow(
                        title = stringResource(R.string.transfer_item_contacts_title),
                        checked = contacts,
                        onCheckedChange = { contacts = it },
                        subtitle = stringResource(R.string.transfer_item_contacts_sub),
                        enabled = !building,
                    )
                    AegisHudSwitchRow(
                        title = stringResource(R.string.transfer_item_conversations_title),
                        checked = conversations,
                        onCheckedChange = { conversations = it },
                        subtitle = stringResource(R.string.transfer_item_conversations_sub),
                        enabled = !building,
                    )
                    if (conversations) {
                        AegisHudSwitchRow(
                            title = stringResource(R.string.transfer_item_attachments_title),
                            checked = includeAttachments,
                            onCheckedChange = { includeAttachments = it },
                            subtitle = stringResource(R.string.transfer_item_attachments_sub),
                            enabled = !building,
                        )
                    }
                    AegisHudSwitchRow(
                        title = stringResource(R.string.transfer_item_psk_title),
                        checked = psks,
                        onCheckedChange = { psks = it },
                        subtitle = stringResource(R.string.transfer_item_psk_sub),
                        enabled = !building,
                    )
                    AegisHudSwitchRow(
                        title = stringResource(R.string.transfer_item_settings_title),
                        checked = settings,
                        onCheckedChange = { settings = it },
                        subtitle = stringResource(R.string.transfer_item_settings_sub),
                        enabled = !building,
                    )
                }

                AegisHudCard(
                    title = stringResource(R.string.transfer_section_password),
                    icon = Icons.Outlined.Password,
                ) {
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.transfer_password_label),
                        enabled = !building,
                    )
                    PasswordField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = stringResource(R.string.account_password_confirm_label),
                        enabled = !building,
                    )
                }

                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                }

                AegisHudButton(
                    text = stringResource(R.string.transfer_build),
                    onClick = { build() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !building &&
                        (account || contacts || conversations || psks || settings),
                )
                if (building) {
                    AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            } else {
                summary?.let { ExportSummaryCard(it) }

                if (sizeBytes <= QR_MAX_BYTES) {
                    val bitmap = remember(envelopeJson) { encodeQrBitmap(envelopeJson!!, 720) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(AegisPanelShape)
                            // Подложка остаётся БЕЛОЙ в обеих темах и не
                            // затемняется: QR кодируется чёрным по белому, на
                            // тёмном фоне сканер его не возьмёт. Это
                            // единственное место экрана с литералом цвета.
                            .background(Color.White)
                            .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.transfer_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                } else {
                    AegisHudBanner(
                        text = stringResource(R.string.transfer_too_big, sizeBytes / 1024),
                        tone = HudBannerTone.SECURITY,
                    )
                }

                AegisHudOutlinedButton(
                    text = stringResource(R.string.transfer_save_file),
                    onClick = { saveLauncher.launch("aegis-transfer.aegis") },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Save,
                )
            }
        }
    }
}

/** Сводка собранного пакета: счётчики и признаки включённых разделов. */
@Composable
private fun ExportSummaryCard(summary: TransferSummary) {
    AegisHudCard(title = stringResource(R.string.transfer_built_title)) {
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
