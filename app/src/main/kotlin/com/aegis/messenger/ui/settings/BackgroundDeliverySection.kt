package com.aegis.messenger.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.notifications.SystemGrants

/**
 * Секция «Приём в фоне»: системные разрешения, которых нельзя получить кодом,
 * но без которых приложение ведёт себя не так, как ждёт пользователь.
 *
 * Почему это вынесено на экран, а не «просто работает»:
 *
 *  * **Полноэкранный звонок.** С Android 14 разрешение `USE_FULL_SCREEN_INTENT`
 *    выдаётся автоматически только звонилкам и будильникам. Без него входящий
 *    звонок звучит и вибрирует, но экран звонка не поднимается — пользователю
 *    приходится разблокировать телефон и искать уведомление в шторке. Со стороны
 *    это выглядит как баг приложения, хотя это системное ограничение, снять
 *    которое может только сам пользователь.
 *  * **Работа в фоне.** Приём держится на long-poll поверх Tor (push мы не
 *    используем принципиально, §7.6). Foreground-сервис и замок процессора мы
 *    берём сами, но поверх них на прошивках Samsung/Xiaomi/Huawei работает свой
 *    «усыплятель», который замораживает свёрнутое приложение в режиме батареи
 *    «Оптимизировано» — входящий звонок тогда ждёт на релее, пока пользователь
 *    сам не откроет приложение. Режим «Ограничено» снимает foreground-статус с
 *    сервиса приёма сразу при уходе с экрана.
 *
 * Карточка показывает статус и ведёт в нужный системный экран. Ничего не
 * включает сама и не давит: если пользователь сознательно выбрал экономию
 * батареи, это его выбор — но последствия он должен видеть здесь, а не
 * выяснять по пропущенным звонкам.
 *
 * Когда все разрешения в порядке, карточка не показывается вовсе.
 */
@Composable
fun BackgroundDeliverySection() {
    val context = LocalContext.current
    // Разрешения меняются только в системных экранах, и возврат оттуда сам
    // перерисовку не вызывает: без перечитывания на ON_RESUME карточка после
    // «Разрешить» продолжала бы показывать запрет.
    var fullScreenOk by remember { mutableStateOf(SystemGrants.canUseFullScreenIntent(context)) }
    var batteryOk by remember { mutableStateOf(SystemGrants.backgroundDeliveryAllowed(context)) }
    var restricted by remember { mutableStateOf(SystemGrants.backgroundRestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        fullScreenOk = SystemGrants.canUseFullScreenIntent(context)
        batteryOk = SystemGrants.backgroundDeliveryAllowed(context)
        restricted = SystemGrants.backgroundRestricted(context)
    }
    if (fullScreenOk && batteryOk) return

    val secondary = AegisTheme.extendedColors.textSecondary
    AegisHudCard(
        title = stringResource(R.string.bg_delivery_title),
        icon = Icons.Outlined.NotificationsActive,
    ) {
        Text(
            text = stringResource(R.string.bg_delivery_intro),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        if (!fullScreenOk) {
            AegisHudStatusLine(
                text = stringResource(R.string.bg_delivery_fsi_off),
                ok = false,
                failColor = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.bg_delivery_fsi_explain),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
            AegisHudOutlinedButton(
                text = stringResource(R.string.bg_delivery_fsi_action),
                icon = Icons.Outlined.NotificationsActive,
                onClick = {
                    SystemGrants.fullScreenIntentSettings(context)?.let { openSettings(context, it) }
                },
            )
        }

        if (!batteryOk) {
            AegisHudStatusLine(
                text = stringResource(
                    if (restricted) R.string.bg_delivery_restricted_off else R.string.bg_delivery_battery_off,
                ),
                ok = false,
                failColor = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(
                    if (restricted) R.string.bg_delivery_restricted_explain else R.string.bg_delivery_battery_explain,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
            AegisHudOutlinedButton(
                text = stringResource(R.string.bg_delivery_battery_action),
                icon = Icons.Outlined.BatteryAlert,
                onClick = { SystemGrants.openBatteryExemption(context) },
            )
        }
    }
}

/**
 * Открывает системный экран настроек.
 *
 * `FLAG_ACTIVITY_NEW_TASK` обязателен: экран принадлежит системному приложению,
 * и без своей задачи он либо не откроется, либо ляжет поверх нашей — тогда
 * возврат «назад» уведёт пользователя из AEGIS, а не в настройки.
 *
 * Отказ гасим молча: на кастомных прошивках отдельных экранов может не быть,
 * и падать из-за этого приложение не должно.
 */
private fun openSettings(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
