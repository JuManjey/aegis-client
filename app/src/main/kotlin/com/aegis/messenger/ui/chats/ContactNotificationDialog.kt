package com.aegis.messenger.ui.chats

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.aegis.core.common.model.Contact
import com.aegis.core.ui.hud.AegisHudActionChip
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диалог персональных уведомлений контакта (§14.5): заглушить на время/навсегда
 * и выбрать персональный звук сообщения / мелодию звонка. Значения хранятся в
 * зашифрованной записи контакта (SQLCipher) и переопределяют глобальные.
 *
 * Выбор звука — системный [RingtoneManager.ACTION_RINGTONE_PICKER]: сам файл
 * рингтона не копируется, хранится лишь его content-URI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactNotificationDialog(
    contactName: String,
    mutedUntilMs: Long,
    messageSoundUri: String,
    callRingtoneUri: String,
    onSetMuted: (Long) -> Unit,
    onSetSounds: (messageSoundUri: String, callRingtoneUri: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = AegisTheme.extendedColors.securityAccent
    val secondary = AegisTheme.extendedColors.textSecondary

    var messageSound by remember { mutableStateOf(messageSoundUri) }
    var callRingtone by remember { mutableStateOf(callRingtoneUri) }

    val pickMessageSound = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            messageSound = uri?.toString().orEmpty()
            onSetSounds(messageSound, callRingtone)
        }
    }
    val pickCallRingtone = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            callRingtone = uri?.toString().orEmpty()
            onSetSounds(messageSound, callRingtone)
        }
    }

    val mutedNow = Contact(id = "", nickname = "", mutedUntilMs = mutedUntilMs).isMutedNow()

    AegisHudDialog(
        title = stringResource(R.string.contact_notif_title, contactName),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_done),
        onConfirm = onDismiss,
    ) {
        AegisHudStatusLine(
            text = muteStatusText(mutedUntilMs),
            // «Уведомления включены» — нормальное состояние, а не достижение:
            // маркер зажигается именно на заглушённом контакте, чтобы отличие
            // от глобальных настроек бросалось в глаза.
            ok = mutedNow,
            okColor = accent,
            failColor = secondary,
        )
        AegisHudGroupLabel(text = stringResource(R.string.contact_notif_mute_title))
        // Это ДЕЙСТВИЯ, а не выбор варианта: чип сразу переносит срок заглушения.
        // Поэтому Role.Button, иначе TalkBack добавлял бы к каждому «не выбрано».
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val now = System.currentTimeMillis()
            AegisHudActionChip(
                text = stringResource(R.string.notif_snooze_1h),
                onClick = { onSetMuted(now + 60L * 60_000L) },
            )
            AegisHudActionChip(
                text = stringResource(R.string.notif_snooze_8h),
                onClick = { onSetMuted(now + 8L * 60L * 60_000L) },
            )
            AegisHudActionChip(
                text = stringResource(R.string.notif_snooze_forever),
                onClick = { onSetMuted(Contact.MUTED_FOREVER) },
            )
            if (mutedNow) {
                AegisHudActionChip(
                    text = stringResource(R.string.contact_notif_enable),
                    onClick = { onSetMuted(0L) },
                    accent = accent,
                )
            }
        }

        AegisHudGroupLabel(text = stringResource(R.string.contact_notif_sounds_title))
        SoundRow(
            label = stringResource(R.string.contact_notif_msg_sound),
            value = messageSound,
            onPick = {
                pickMessageSound.launch(
                    ringtonePickerIntent(messageSound, RingtoneManager.TYPE_NOTIFICATION),
                )
            },
            onClear = {
                messageSound = ""
                onSetSounds(messageSound, callRingtone)
            },
        )
        SoundRow(
            label = stringResource(R.string.contact_notif_call_ringtone),
            value = callRingtone,
            onPick = {
                pickCallRingtone.launch(
                    ringtonePickerIntent(callRingtone, RingtoneManager.TYPE_RINGTONE),
                )
            },
            onClear = {
                callRingtone = ""
                onSetSounds(messageSound, callRingtone)
            },
        )
    }
}

@Composable
private fun SoundRow(
    label: String,
    value: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (value.isBlank()) {
                        R.string.contact_notif_sound_default
                    } else {
                        R.string.contact_notif_sound_custom
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
            )
        }
        AegisHudOutlinedButton(text = stringResource(R.string.common_choose), onClick = onPick)
        if (value.isNotBlank()) {
            AegisHudOutlinedButton(text = stringResource(R.string.common_reset), onClick = onClear)
        }
    }
}

@Composable
private fun muteStatusText(mutedUntilMs: Long): String = when {
    mutedUntilMs == Contact.MUTED_FOREVER -> stringResource(R.string.contact_mute_status_forever)
    mutedUntilMs > System.currentTimeMillis() -> {
        val fmt = SimpleDateFormat(stringResource(R.string.notif_snooze_date_format), Locale.getDefault())
        stringResource(R.string.contact_mute_status_until, fmt.format(Date(mutedUntilMs)))
    }
    else -> stringResource(R.string.contact_mute_status_active)
}

private fun ringtonePickerIntent(currentUri: String, type: Int): android.content.Intent =
    android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        val existing = currentUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
    }
