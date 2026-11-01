package com.aegis.messenger.ui.call

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.messenger.BuildConfig
import com.aegis.messenger.R
import com.aegis.messenger.notifications.SystemGrants

/**
 * Мастер настроек, без которых звонки и сообщения не доходят до свёрнутого
 * приложения: уведомления, работа в фоне, полноэкранный звонок и приём при
 * закрытом аккаунте.
 *
 * КОГДА СПРАШИВАЕТ. Пункт показывается, только если он сейчас НЕ выставлен, и не
 * чаще одного раза на версию приложения: после установки — все недостающие,
 * после обновления — снова те, что так и не выставлены. «Позже» откладывает
 * пункт до следующего обновления; путь остаётся в «Настройки → Приём в фоне»,
 * «Уведомления» и «Безопасность».
 *
 * ПОЧЕМУ ДИАЛОГИ, А НЕ ТОЛЬКО НАСТРОЙКИ. Каждый пункт ломает звонок так, что по
 * симптому причину не найти: телефон молчит, а при открытии приложения на
 * экране сразу «входящий звонок». Пассивную карточку в настройках не видно,
 * пока не начнёшь искать, поэтому вопрос задаётся на пути пользователя — так
 * поступают все мессенджеры со звонками. Решает пользователь: мастер ничего не
 * включает сам, кроме того, на что нажали «Включить».
 *
 * По одному окну за раз и в порядке важности: без уведомлений и работы в фоне
 * звонок не доходит вовсе, и полноэкранное окно ему уже не поможет.
 */
@Composable
fun DeliverySetupPrompt(
    callsEnabled: Boolean,
    deliverWhileLocked: Boolean,
    onEnableDeliverWhileLocked: () -> Unit,
) {
    val context = LocalContext.current
    // Пункты меняются в системных экранах и диалогах, а возврат оттуда
    // перерисовку сам не вызывает — состояние перечитывается на каждом ON_RESUME.
    var generation by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { generation++ }
    val step = remember(generation, callsEnabled, deliverWhileLocked) {
        SetupStep.entries.firstOrNull { it.shouldAsk(context, callsEnabled, deliverWhileLocked) }
    } ?: return

    val close: () -> Unit = {
        markAsked(context, step)
        generation++
    }
    AegisHudDialog(
        title = stringResource(step.title),
        icon = step.icon,
        confirmLabel = stringResource(step.confirm),
        onConfirm = {
            close()
            when (step) {
                SetupStep.NOTIFICATIONS -> open(context, SystemGrants.notificationSettings(context))
                SetupStep.BACKGROUND -> SystemGrants.openBatteryExemption(context)
                SetupStep.FULL_SCREEN -> SystemGrants.fullScreenIntentSettings(context)?.let { open(context, it) }
                SetupStep.LOCKED_DELIVERY -> onEnableDeliverWhileLocked()
            }
        },
        dismissLabel = stringResource(R.string.setup_later),
        onDismissAction = close,
        onDismiss = close,
    ) {
        Text(
            text = stringResource(step.message),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private enum class SetupStep(
    val key: String,
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val confirm: Int,
    val icon: ImageVector,
) {
    NOTIFICATIONS(
        key = "notifications",
        title = R.string.setup_notif_title,
        message = R.string.setup_notif_message,
        confirm = R.string.setup_notif_open,
        icon = Icons.Outlined.NotificationsActive,
    ),
    BACKGROUND(
        key = "background",
        title = R.string.bg_prompt_title,
        message = R.string.bg_prompt_message,
        confirm = R.string.bg_prompt_allow,
        icon = Icons.Outlined.BatteryAlert,
    ),
    FULL_SCREEN(
        key = "full_screen",
        title = R.string.fsi_prompt_title,
        message = R.string.fsi_prompt_message,
        confirm = R.string.fsi_prompt_open,
        icon = Icons.Outlined.PhoneCallback,
    ),
    LOCKED_DELIVERY(
        key = "locked_delivery",
        title = R.string.setup_locked_title,
        message = R.string.setup_locked_message,
        confirm = R.string.setup_locked_enable,
        icon = Icons.Outlined.Lock,
    ),
    ;

    /** Выставлен ли пункт прямо сейчас. */
    fun satisfied(context: Context, callsEnabled: Boolean, deliverWhileLocked: Boolean): Boolean = when (this) {
        NOTIFICATIONS -> SystemGrants.notificationsAllowed(context)
        BACKGROUND -> SystemGrants.backgroundDeliveryAllowed(context)
        FULL_SCREEN -> !callsEnabled || SystemGrants.canUseFullScreenIntent(context)
        LOCKED_DELIVERY -> deliverWhileLocked
    }

    fun shouldAsk(context: Context, callsEnabled: Boolean, deliverWhileLocked: Boolean): Boolean =
        !satisfied(context, callsEnabled, deliverWhileLocked) &&
            prefs(context).getInt(askedKey(this), 0) < BuildConfig.VERSION_CODE
}

private fun markAsked(context: Context, step: SetupStep) {
    runCatching { prefs(context).edit { putInt(askedKey(step), BuildConfig.VERSION_CODE) } }
}

/** Системный экран — в своей задаче, иначе «назад» уводил бы из AEGIS. */
private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun askedKey(step: SetupStep) = "setup_asked_version_${step.key}"

private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private const val PREFS = "aegis.ui.prompts"
