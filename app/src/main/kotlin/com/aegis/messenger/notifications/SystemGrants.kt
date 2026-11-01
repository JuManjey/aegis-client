package com.aegis.messenger.notifications

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.aegis.core.common.diag.DiagnosticLog

/**
 * Системные разрешения, которых нельзя получить кодом, но без которых
 * приложение тихо перестаёт работать так, как ожидает пользователь.
 *
 * Все выдаются ТОЛЬКО пользователем. Здесь — проверка текущего состояния и
 * интенты, открывающие нужный системный экран или диалог. Приложение ничего не
 * включает само: решение остаётся за человеком, а UI лишь честно объясняет
 * последствия отказа.
 *
 * ### 1. Full-screen intents (Android 14+, API 34)
 * До Android 14 `USE_FULL_SCREEN_INTENT` выдавалось при установке. Начиная с 14
 * его получают автоматически только звонилки и будильники; всем остальным —
 * вручную. Без него full-screen intent деградирует до heads-up: телефон звонит и
 * вибрирует, но экран звонка не поднимается. Ровно этот симптом («звук и
 * вибрация есть, окна нет, приходится лезть в шторку») он и вызывает.
 *
 * ### 2. Работа в фоне без ограничений батареи
 * Foreground-сервис защищает процесс, замок процессора — потоки, но на прошивках
 * Samsung/Xiaomi/Huawei поверх этого работает СВОЙ «усыплятель», который для
 * приложений в режиме «Оптимизировано» замораживает процесс вскоре после ухода
 * в фон. Для мессенджера без push это означает: входящий звонок лежит на релее
 * до тех пор, пока пользователь сам не откроет приложение, — без звука, без
 * вибрации и без уведомления. Снаружи это неотличимо от «звонки не работают».
 *
 * Режим «Ограничено» (Android 9+, [backgroundRestricted]) ещё жёстче: система
 * снимает с сервиса приёма foreground-статус, как только приложение уходит с
 * экрана. Системный диалог исключения из оптимизации его не снимает — только
 * экран приложения «Батарея → Без ограничений».
 */
object SystemGrants {

    /** Разрешены ли приложению уведомления (на Android 13+ — `POST_NOTIFICATIONS`). */
    fun notificationsAllowed(context: Context): Boolean =
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()
        }.getOrDefault(true)

    /**
     * Экран уведомлений нашего приложения. Работает и тогда, когда системный
     * запрос разрешения больше не показывается (пользователь отказал дважды).
     */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * Может ли приложение поднимать полноэкранный входящий звонок.
     *
     * На API < 34 разрешение выдано при установке, поэтому `true`.
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)
    }

    /**
     * Интент экрана «Full screen intents» для нашего приложения, либо `null`,
     * если версия Android его не знает.
     *
     * `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` ведёт СРАЗУ к переключателю
     * нашего приложения — в отличие от общих настроек уведомлений, где этот
     * пункт спрятан и его почти невозможно найти по описанию.
     */
    fun fullScreenIntentSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    /** Исключено ли приложение из оптимизации батареи. */
    fun ignoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = runCatching {
            context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull() ?: return true
        return runCatching {
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(true)
    }

    /**
     * Включён ли для приложения режим батареи «Ограничено».
     *
     * В этом режиме система снимает foreground-статус с сервиса приёма при уходе
     * приложения с экрана, и никакие замки процессора его не спасают.
     */
    fun backgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activity = runCatching {
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull() ?: return false
        return runCatching { activity.isBackgroundRestricted }.getOrDefault(false)
    }

    /** Разрешено ли приложению держать соединение с релеем, когда оно свёрнуто. */
    fun backgroundDeliveryAllowed(context: Context): Boolean =
        ignoringBatteryOptimizations(context) && !backgroundRestricted(context)

    /**
     * Открывает путь к снятию ограничений батареи — первый экран, который
     * система согласится показать.
     *
     *  1. При «Ограничено» — экран приложения: только там есть «Батарея → Без
     *     ограничений», системный диалог этот режим не снимает.
     *  2. Иначе — системный диалог «Разрешить работу в фоне?» на одно нажатие.
     *     Разрешение `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` объявлено в
     *     манифесте именно для него. Политика Google Play допускает этот диалог
     *     для мессенджеров и звонилок, которые не могут опираться на FCM, а AEGIS
     *     отказывается от FCM принципиально (§7.6). Прежний путь через общий
     *     список оптимизации на Samsung требовал переключить фильтр списка и
     *     найти приложение вручную — до этого шага почти никто не доходил.
     *  3. Если прошивка не знает этого диалога — общий список, затем экран
     *     приложения.
     *
     * @return удалось ли открыть хоть какой-то экран.
     */
    @SuppressLint("BatteryLife")
    fun openBatteryExemption(context: Context): Boolean {
        val candidates = buildList {
            if (backgroundRestricted(context)) add(appDetailsSettings(context))
            add(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(appDetailsSettings(context))
        }
        // FLAG_ACTIVITY_NEW_TASK: экран принадлежит системному приложению, и без
        // своей задачи «назад» уводил бы пользователя из AEGIS, а не в настройки.
        val opened = candidates.any { intent ->
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
        DiagnosticLog.event(DIAG_TAG, "запрос работы в фоне", "экранОткрыт" to opened)
        return opened
    }

    /** Экран «О приложении» — отсюда на любой прошивке доступна «Батарея». */
    private fun appDetailsSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )

    /**
     * Корзина App Standby (Android 9+): 5 — без ограничений, 10…40 — по частоте
     * использования, 45 — «Ограничено»; `null`, если версия её не знает.
     */
    private fun standbyBucket(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val usage = runCatching {
            context.applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        }.getOrNull() ?: return null
        return runCatching { usage.appStandbyBucket }.getOrNull()
    }

    /**
     * Пишет в журнал текущее состояние разрешений. Вызывается при запуске
     * приёма: без этой строки разбор жалоб «не вижу звонка» / «не приходят
     * сообщения» начинается с угадывания.
     */
    fun logState(context: Context) {
        DiagnosticLog.event(
            DIAG_TAG, "системные разрешения",
            "уведомления" to notificationsAllowed(context),
            "полноэкранныйЗвонок" to canUseFullScreenIntent(context),
            "безОптимизацииБатареи" to ignoringBatteryOptimizations(context),
            "фонОграничен" to backgroundRestricted(context),
            "корзинаStandby" to standbyBucket(context),
            "sdk" to Build.VERSION.SDK_INT,
            "производитель" to Build.MANUFACTURER,
        )
    }

    private const val DIAG_TAG = "grants"
}
