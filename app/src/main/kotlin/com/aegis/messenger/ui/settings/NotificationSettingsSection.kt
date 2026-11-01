package com.aegis.messenger.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.core.ui.hud.AegisHudActionChip
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Секция «Уведомления» (§14.5). Zero-trust: без FCM/push — уведомления
 * формируются локально из уже расшифрованного сообщения (см. [com.aegis.messenger.notifications.AegisNotifier]).
 *
 * Приватность по умолчанию (§14.3): отправитель и текст в превью СКРЫТЫ —
 * показывается нейтральное «Новое сообщение». Раскрытие включается явно.
 *
 * Также: тонкая настройка звука/вибрации для сообщений и звонков, «внутренние»
 * уведомления (когда приложение открыто) и быстрая пауза всех уведомлений.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsSection(
    settings: NotificationSettings,
    onSetNotificationSettings: (NotificationSettings) -> Unit,
    onSnoozeAll: (Long) -> Unit,
    systemNotificationsAllowed: Boolean,
    onRequestSystemPermission: () -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary

    AegisHudCard(
        title = stringResource(R.string.notif_section_title),
        icon = Icons.Outlined.NotificationsActive,
    ) {
        Text(
            text = stringResource(R.string.notif_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        if (!systemNotificationsAllowed) {
            AegisHudBanner(
                text = stringResource(R.string.notif_system_blocked),
                tone = HudBannerTone.DANGER,
                actionLabel = stringResource(R.string.notif_system_allow),
                onAction = onRequestSystemPermission,
            )
        }

        AegisHudSwitchRow(
            title = stringResource(R.string.notif_toggle_main_title),
            subtitle = stringResource(R.string.notif_toggle_main_sub),
            checked = settings.enabled,
            onCheckedChange = { onSetNotificationSettings(settings.copy(enabled = it)) },
        )

        if (settings.enabled) {
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_sender_title),
                subtitle = stringResource(R.string.notif_toggle_sender_sub),
                checked = settings.showSender,
                onCheckedChange = { onSetNotificationSettings(settings.copy(showSender = it)) },
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_content_title),
                subtitle = stringResource(R.string.notif_toggle_content_sub),
                checked = settings.showContent,
                onCheckedChange = { onSetNotificationSettings(settings.copy(showContent = it)) },
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_msg_sound_title),
                subtitle = stringResource(R.string.notif_toggle_msg_sound_sub),
                checked = settings.messageSound,
                onCheckedChange = { onSetNotificationSettings(settings.copy(messageSound = it)) },
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_msg_vibrate_title),
                subtitle = stringResource(R.string.notif_toggle_msg_vibrate_sub),
                checked = settings.messageVibrate,
                onCheckedChange = { onSetNotificationSettings(settings.copy(messageVibrate = it)) },
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_call_sound_title),
                subtitle = stringResource(R.string.notif_toggle_call_sound_sub),
                checked = settings.callSound,
                onCheckedChange = { onSetNotificationSettings(settings.copy(callSound = it)) },
            )
            // Стиль мелодии имеет смысл только когда звук звонка включён —
            // иначе переключатель обещает звук, которого не будет.
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_call_synth_title),
                subtitle = stringResource(R.string.notif_toggle_call_synth_sub),
                checked = settings.callSynthRingtone,
                onCheckedChange = {
                    onSetNotificationSettings(settings.copy(callSynthRingtone = it))
                },
                enabled = settings.callSound,
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_call_vibrate_title),
                subtitle = stringResource(R.string.notif_toggle_call_vibrate_sub),
                checked = settings.callVibrate,
                onCheckedChange = { onSetNotificationSettings(settings.copy(callVibrate = it)) },
            )
            // Тоны разговора НЕ подчинены callSound: тот управляет звонком
            // телефона (канал рингтона), а «гудки» идут по каналу разговора.
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_call_tones_title),
                subtitle = stringResource(R.string.notif_toggle_call_tones_sub),
                checked = settings.callProgressTones,
                onCheckedChange = {
                    onSetNotificationSettings(settings.copy(callProgressTones = it))
                },
            )
            AegisHudSwitchRow(
                title = stringResource(R.string.notif_toggle_inapp_title),
                subtitle = stringResource(R.string.notif_toggle_inapp_sub),
                checked = settings.inAppNotifications,
                onCheckedChange = { onSetNotificationSettings(settings.copy(inAppNotifications = it)) },
            )

            AegisHudGroupLabel(text = stringResource(R.string.notif_snooze_title))
            val mutedNow = settings.isMutedNow()
            AegisHudStatusLine(text = snoozeStatusText(settings), ok = mutedNow)
            // Чипы паузы — ДЕЙСТВИЯ, а не выбор из набора: они не отмечают
            // вариант, а сразу переносят срок. Поэтому AegisHudActionChip
            // (Role.Button), иначе TalkBack добавлял бы к каждому «не выбрано».
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = System.currentTimeMillis()
                AegisHudActionChip(
                    text = stringResource(R.string.notif_snooze_1h),
                    onClick = { onSnoozeAll(now + 60L * 60_000L) },
                )
                AegisHudActionChip(
                    text = stringResource(R.string.notif_snooze_8h),
                    onClick = { onSnoozeAll(now + 8L * 60L * 60_000L) },
                )
                AegisHudActionChip(
                    text = stringResource(R.string.notif_snooze_forever),
                    onClick = { onSnoozeAll(NotificationSettings.MUTE_FOREVER) },
                )
                if (mutedNow) {
                    AegisHudActionChip(
                        text = stringResource(R.string.notif_snooze_resume),
                        onClick = { onSnoozeAll(0L) },
                        accent = AegisTheme.extendedColors.accentPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun snoozeStatusText(settings: NotificationSettings): String = when {
    settings.muteAllUntilMs == NotificationSettings.MUTE_FOREVER ->
        stringResource(R.string.notif_snooze_status_forever)
    settings.isMutedNow() -> {
        val fmt = SimpleDateFormat(stringResource(R.string.notif_snooze_date_format), Locale.getDefault())
        stringResource(R.string.notif_snooze_status_until, fmt.format(Date(settings.muteAllUntilMs)))
    }
    else -> stringResource(R.string.notif_snooze_status_active)
}
