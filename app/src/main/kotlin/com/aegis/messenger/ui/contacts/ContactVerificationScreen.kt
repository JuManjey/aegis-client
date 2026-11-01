package com.aegis.messenger.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisCodeDisplayStyle
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import com.aegis.messenger.messaging.ContactVerification
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import kotlinx.coroutines.launch

/**
 * Экран ручной верификации отпечатка / safety number контакта (§14.3),
 * HUD-раскладка — `docs/ui_hud.md`.
 *
 * Обе стороны видят ОДИН И ТОТ ЖЕ код (выведен из публичных identity-ключей
 * обеих сторон и ручного PSK). Пользователь сверяет код лично / по доверенному
 * каналу; при совпадении отмечает контакт проверенным. Несовпадение = возможный
 * MITM (или рассинхрон ключей) — чат помечается непроверенным.
 *
 * Сам код НИКОГДА не переводится в другой регистр и не перегруппировывается:
 * его вид задаёт [ContactVerification], и обе стороны должны видеть посимвольно
 * одинаковую строку.
 */
@Composable
fun ContactVerificationScreen(
    contactId: String,
    title: String,
    verification: ContactVerification,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val colors = AegisTheme.extendedColors
    var state by remember { mutableStateOf<ContactVerification.Result?>(null) }
    var verified by remember { mutableStateOf(false) }
    var deviceCodes by remember { mutableStateOf<List<ContactVerification.DeviceCode>>(emptyList()) }

    LaunchedEffect(contactId) {
        val result = verification.safetyCode(contactId)
        state = result
        verified = when (result) {
            is ContactVerification.Result.Ready -> result.alreadyVerified
            is ContactVerification.Result.Unavailable -> result.alreadyVerified
        }
        deviceCodes = verification.peerDeviceCodes(contactId)
    }

    AegisHudScreen(
        title = stringResource(R.string.verify_title, title),
        modifier = modifier,
        slug = VERIFY_SLUG,
        icon = Icons.Outlined.Fingerprint,
        onBack = onBack,
        backContentDescription = stringResource(R.string.common_back),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VerificationStatusCard(verified = verified)

            when (val current = state) {
                null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { AegisHudBusy() }

                is ContactVerification.Result.Unavailable -> AegisHudBanner(
                    text = current.reason,
                    tone = HudBannerTone.DANGER,
                )

                is ContactVerification.Result.Ready -> AegisHudCard(
                    title = stringResource(R.string.verify_code_label),
                ) {
                    SafetyCode(code = current.code)
                    Text(
                        text = stringResource(R.string.verify_instructions),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    if (verified) {
                        AegisHudOutlinedButton(
                            text = stringResource(R.string.verify_unmark),
                            onClick = {
                                scope.launch {
                                    verification.setVerified(contactId, false)
                                    verified = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        AegisHudButton(
                            text = stringResource(R.string.verify_mark),
                            onClick = {
                                scope.launch {
                                    verification.setVerified(contactId, true)
                                    verified = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (deviceCodes.isNotEmpty()) {
                AegisHudGroupLabel(
                    text = stringResource(R.string.verify_devices_section),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.verify_devices_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
                for (device in deviceCodes) {
                    PeerDeviceCard(
                        device = device,
                        onSetVerified = { newValue ->
                            scope.launch {
                                verification.setDeviceVerified(contactId, device.deviceId, newValue)
                                deviceCodes = verification.peerDeviceCodes(contactId)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Статический slug экрана для декоративных HUD-кодов (см. `AegisHudCodes`). */
private const val VERIFY_SLUG = "contacts.verify"

/**
 * Код сверки: рубленая рамка с угловыми скобками, моноширинное крупное
 * начертание, группы символов — ровно как их отдал [ContactVerification].
 *
 * Моноширинность здесь не украшение: только при равной ширине символов группы
 * цифр выстраиваются в столбцы и сверка вслух не сбивается.
 */
@Composable
private fun SafetyCode(code: String, modifier: Modifier = Modifier) {
    val colors = AegisTheme.extendedColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = code,
            style = AegisCodeDisplayStyle,
            color = colors.securityAccent,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Дополнительное устройство контакта: у каждого свой identity, поэтому и свой
 * safety-код (`docs/multidevice.md` §8). Состояние сверки дублируется цветом
 * акцента карточки И строкой состояния — только цвет читался бы хуже.
 */
@Composable
private fun PeerDeviceCard(
    device: ContactVerification.DeviceCode,
    onSetVerified: (Boolean) -> Unit,
) {
    val colors = AegisTheme.extendedColors
    AegisHudCard(
        title = stringResource(R.string.verify_device_title, device.deviceId),
        icon = Icons.Outlined.Devices,
        accent = if (device.verified) colors.securityAccent else colors.textSecondary,
    ) {
        AegisHudStatusLine(
            text = stringResource(
                if (device.verified) {
                    R.string.verify_status_verified
                } else {
                    R.string.verify_status_unverified
                },
            ),
            ok = device.verified,
        )
        if (device.code != null) {
            SafetyCode(code = device.code)
            if (device.verified) {
                AegisHudOutlinedButton(
                    text = stringResource(R.string.verify_unmark),
                    onClick = { onSetVerified(false) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AegisHudButton(
                    text = stringResource(R.string.verify_mark),
                    onClick = { onSetVerified(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                text = device.reason ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** Сводка состояния контакта: щит-иконка, прописной статус и пояснение. */
@Composable
private fun VerificationStatusCard(verified: Boolean) {
    val colors = AegisTheme.extendedColors
    AegisHudCard(
        title = stringResource(
            if (verified) R.string.verify_status_verified else R.string.verify_status_unverified,
        ),
        icon = if (verified) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe,
        accent = if (verified) colors.securityAccent else colors.textSecondary,
    ) {
        Text(
            text = stringResource(
                if (verified) {
                    R.string.verify_status_verified_desc
                } else {
                    R.string.verify_status_unverified_desc
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}
