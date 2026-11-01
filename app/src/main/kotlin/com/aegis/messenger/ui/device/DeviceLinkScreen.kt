package com.aegis.messenger.ui.device

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
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Save
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
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import com.aegis.messenger.account.DeviceLinking
import com.aegis.messenger.account.LinkBuildResult
import com.aegis.messenger.account.LinkSummary
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.onboarding.LabeledTextField
import com.aegis.messenger.ui.onboarding.PasswordField
import com.aegis.messenger.ui.psk.encodeQrBitmap
import kotlinx.coroutines.launch

/** Максимальный размер оболочки (байты), который ещё разумно кодировать в QR. */
private const val QR_MAX_BYTES = 2200

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * device id или отпечаток: код попадает на любой скриншот (§6 `docs/ui_hud.md`).
 */
private const val DEVICE_LINK_HUD_SLUG = "device.link"

/**
 * Экран «Привязать устройство» на ПЕРВИЧНОМ устройстве (мультидевайс §7.1.1,
 * `docs/multidevice.md` §5) в HUD-раскладке (`docs/ui_hud.md`). Собирает
 * зашифрованный паролем пакет линковки (мнемоника + выданный device id +
 * публичные данные контактов/PSK) и показывает его QR-кодом (лично, FLAG_SECURE
 * §14.3) или сохраняет в файл. Пакет НЕ несёт Signal-состояние (MD-1) и
 * бесполезен без пароля; передаётся строго вне сети.
 */
@Composable
fun DeviceLinkScreen(
    deviceLinking: DeviceLinking,
    multiDeviceAllowed: Boolean,
    onLinked: suspend () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var label by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(false) }

    var envelopeJson by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<LinkSummary?>(null) }
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
            val result = deviceLinking.buildEnvelope(password = password, newDeviceLabel = label)
            building = false
            when (result) {
                is LinkBuildResult.Success -> {
                    envelopeJson = result.envelopeJson
                    summary = result.summary
                    sizeBytes = result.sizeBytes
                    onLinked()
                }
                is LinkBuildResult.Failure -> error = result.reason
            }
        }
    }

    AegisHudScreen(
        title = stringResource(R.string.link_export_title),
        modifier = modifier,
        slug = DEVICE_LINK_HUD_SLUG,
        icon = Icons.Outlined.AddLink,
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
                text = stringResource(R.string.link_export_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )

            if (!multiDeviceAllowed) {
                AegisHudBanner(
                    text = stringResource(R.string.link_export_disabled_notice),
                    tone = HudBannerTone.DANGER,
                )
            }

            val built = envelopeJson != null
            if (!built) {
                AegisHudCard {
                    // Подпись — в слоте `label` поля, а не placeholder'ом:
                    // только так TalkBack связывает её с вводом, и подсказка
                    // про «Планшет» не теряется при наборе.
                    LabeledTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = stringResource(R.string.link_label_field),
                        supporting = null,
                        enabled = !building && multiDeviceAllowed,
                    )
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.link_password_label),
                        enabled = !building && multiDeviceAllowed,
                    )
                    PasswordField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = stringResource(R.string.account_password_confirm_label),
                        enabled = !building && multiDeviceAllowed,
                    )
                }
                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = colors.danger)
                }
                AegisHudButton(
                    text = stringResource(R.string.link_build),
                    onClick = { build() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !building && multiDeviceAllowed,
                )
                if (building) {
                    AegisHudBusy(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            } else {
                summary?.let { BuiltSummaryCard(it) }
                AegisHudBanner(
                    text = stringResource(R.string.link_built_hint),
                    tone = HudBannerTone.SECURITY,
                )

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
                        text = stringResource(R.string.link_qr_hint),
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
                    onClick = { saveLauncher.launch("aegis-link.aegis") },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Save,
                )
            }
        }
    }
}

/**
 * Сводка собранного пакета. Выданный device id и имя нового устройства
 * выводятся моноширинным и без смены регистра: их сверяют с экраном
 * принимающего устройства символ за символом.
 */
@Composable
private fun BuiltSummaryCard(summary: LinkSummary) {
    AegisHudCard(title = stringResource(R.string.link_built_title)) {
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
