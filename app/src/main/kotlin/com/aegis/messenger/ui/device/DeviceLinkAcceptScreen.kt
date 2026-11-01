package com.aegis.messenger.ui.device

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
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.device.LinkPayload
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.account.LinkApplyResult
import com.aegis.messenger.account.LinkOpenResult
import com.aegis.messenger.account.LinkSummary
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.onboarding.PasswordField
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * данные пакета линковки: код попадает на любой скриншот (§6 `docs/ui_hud.md`).
 */
private const val DEVICE_LINK_ACCEPT_HUD_SLUG = "device.link.accept"

/**
 * Экран «Принять устройство» на НОВОМ (чистом) устройстве (мультидевайс §7.1.1,
 * `docs/multidevice.md` §5) в HUD-раскладке (`docs/ui_hud.md`). Принимает пакет
 * линковки вне сети (QR/файл), распечатывает паролем и после подтверждения
 * разворачивает: сохраняет идентичность этого устройства
 * (`localDeviceId = assignedDeviceId`), контакты/PSK/устройства/настройки и
 * создаёт аккаунт из общей мнемоники. Signal identity этого устройства
 * создаётся ЛОКАЛЬНО и заново (инвариант MD-1).
 *
 * Применение — в [appScope]: создание аккаунта мгновенно переводит приложение в
 * разблокированное состояние и уводит из онбординга.
 */
@Composable
fun DeviceLinkAcceptScreen(
    deviceLinking: DeviceLinking,
    appScope: CoroutineScope,
    onLinked: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var envelopeJson by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Pair<LinkPayload, LinkSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

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
        val jsonStr = envelopeJson ?: return
        error = null
        busy = true
        scope.launch {
            when (val res = deviceLinking.openEnvelope(jsonStr, password)) {
                is LinkOpenResult.Success -> opened = res.payload to res.summary
                LinkOpenResult.WrongPassword -> error = wrongPasswordMsg
                is LinkOpenResult.Invalid -> error = res.reason
            }
            busy = false
        }
    }

    fun apply() {
        val payload = opened?.first ?: return
        error = null
        busy = true
        appScope.launch {
            when (val res = deviceLinking.apply(payload, password)) {
                is LinkApplyResult.Success -> onLinked()
                is LinkApplyResult.Failure -> {
                    error = res.reason
                    busy = false
                }
            }
        }
    }

    // Кнопка «Отмена» осталась в контенте, а не в шапке экрана: пока идёт
    // распечатывание/разворачивание пакета, уходить нельзя (`enabled = !busy`),
    // а стрелка «назад» в шапке такого состояния не имеет.
    AegisHudScreen(
        title = stringResource(R.string.link_accept_title),
        modifier = modifier,
        slug = DEVICE_LINK_ACCEPT_HUD_SLUG,
        icon = Icons.Outlined.PhonelinkSetup,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.link_accept_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )

            val summary = opened?.second
            when {
                summary != null -> {
                    AcceptSummaryCard(summary)
                    error?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                    }
                    AegisHudButton(
                        text = stringResource(R.string.link_accept_apply),
                        onClick = { apply() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
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
                        label = stringResource(R.string.link_password_label),
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
 * Данные принимаемого пакета. Логин, выданный device id и имя устройства —
 * моноширинным и без смены регистра: их сверяют с первичным устройством.
 */
@Composable
private fun AcceptSummaryCard(summary: LinkSummary) {
    AegisHudCard(title = stringResource(R.string.link_accept_summary_title)) {
        if (summary.login.isNotBlank()) {
            AegisHudKeyValue(
                label = stringResource(R.string.transfer_summary_login),
                value = summary.login,
            )
        }
        AegisHudKeyValue(
            label = stringResource(R.string.link_summary_device_id),
            value = summary.assignedDeviceId.toString(),
        )
        if (summary.assignedLabel.isNotBlank()) {
            AegisHudKeyValue(
                label = stringResource(R.string.link_summary_label),
                value = summary.assignedLabel,
            )
        }
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_contacts),
            value = summary.contactCount.toString(),
        )
        AegisHudKeyValue(
            label = stringResource(R.string.transfer_summary_psk),
            value = summary.pskCount.toString(),
        )
    }
}
