package com.aegis.messenger.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.messenger.R

/**
 * Смена иконки/имени приложения на лаунчере, включая «маскировку» (§14.6/§14.1).
 *
 * Реализация — через activity-alias'ы (см. AndroidManifest): каждый вариант
 * иконки — это отдельный alias с ярлыком LAUNCHER, нацеленный на `MainActivity`.
 * Включён ровно ОДИН alias; переключение = сначала включить выбранный, затем
 * выключить остальные ([PackageManager.setComponentEnabledSetting]). Компонент
 * самой `MainActivity` НЕ трогаем — он всегда доступен для явных интентов
 * (deep-link уведомлений §14.5), иначе их запуск сломался бы.
 *
 * Порядок «сначала включить, потом выключить» обязателен: если сначала погасить
 * текущий alias, на части OEM-лаунчеров на миг не остаётся ни одного LAUNCHER-
 * компонента, процесс убивается, и новый alias так и не включается — с точки
 * зрения UI «кнопка не работает», а ярлык может исчезнуть.
 *
 * Замечание (честно, показываем в UI): при смене launcher-alias система на
 * короткое время убирает и возвращает ярлык, а некоторые лаунчеры могут
 * ненадолго закрыть приложение или сбросить позицию иконки — это ограничение
 * механизма, а не баг.
 */
enum class AppIcon(
    internal val aliasClass: String,
    val pickerLabelRes: Int,
    val previewBackgroundRes: Int,
    val previewForegroundRes: Int,
    val statusIconRes: Int,
    val disguise: Boolean,
) {
    DEFAULT(
        aliasClass = "$ALIAS_NAMESPACE.LauncherDefault",
        pickerLabelRes = R.string.appearance_icon_default,
        previewBackgroundRes = R.drawable.ic_launcher_background,
        previewForegroundRes = R.drawable.ic_launcher_foreground,
        statusIconRes = R.drawable.ic_stat_aegis,
        disguise = false,
    ),
    MINIMAL(
        aliasClass = "$ALIAS_NAMESPACE.LauncherMinimal",
        pickerLabelRes = R.string.appearance_icon_minimal,
        previewBackgroundRes = R.drawable.ic_launcher_minimal_background,
        previewForegroundRes = R.drawable.ic_launcher_minimal_foreground,
        statusIconRes = R.drawable.ic_stat_aegis,
        disguise = false,
    ),
    CALCULATOR(
        aliasClass = "$ALIAS_NAMESPACE.LauncherCalc",
        pickerLabelRes = R.string.app_icon_calc_label,
        previewBackgroundRes = R.drawable.ic_launcher_calc_background,
        previewForegroundRes = R.drawable.ic_launcher_calc_foreground,
        statusIconRes = R.drawable.ic_stat_calc,
        disguise = true,
    ),
    NOTES(
        aliasClass = "$ALIAS_NAMESPACE.LauncherNotes",
        pickerLabelRes = R.string.app_icon_notes_label,
        previewBackgroundRes = R.drawable.ic_launcher_notes_background,
        previewForegroundRes = R.drawable.ic_launcher_notes_foreground,
        statusIconRes = R.drawable.ic_stat_notes,
        disguise = true,
    ),
    WEATHER(
        aliasClass = "$ALIAS_NAMESPACE.LauncherWeather",
        pickerLabelRes = R.string.app_icon_weather_label,
        previewBackgroundRes = R.drawable.ic_launcher_weather_background,
        previewForegroundRes = R.drawable.ic_launcher_weather_foreground,
        statusIconRes = R.drawable.ic_stat_weather,
        disguise = true,
    ),
    CLOCK(
        aliasClass = "$ALIAS_NAMESPACE.LauncherClock",
        pickerLabelRes = R.string.app_icon_clock_label,
        previewBackgroundRes = R.drawable.ic_launcher_clock_background,
        previewForegroundRes = R.drawable.ic_launcher_clock_foreground,
        statusIconRes = R.drawable.ic_stat_clock,
        disguise = true,
    ),
    COMPASS(
        aliasClass = "$ALIAS_NAMESPACE.LauncherCompass",
        pickerLabelRes = R.string.app_icon_compass_label,
        previewBackgroundRes = R.drawable.ic_launcher_compass_background,
        previewForegroundRes = R.drawable.ic_launcher_compass_foreground,
        statusIconRes = R.drawable.ic_stat_compass,
        disguise = true,
    ),
    FILES(
        aliasClass = "$ALIAS_NAMESPACE.LauncherFiles",
        pickerLabelRes = R.string.app_icon_files_label,
        previewBackgroundRes = R.drawable.ic_launcher_files_background,
        previewForegroundRes = R.drawable.ic_launcher_files_foreground,
        statusIconRes = R.drawable.ic_stat_files,
        disguise = true,
    ),
    CALENDAR(
        aliasClass = "$ALIAS_NAMESPACE.LauncherCalendar",
        pickerLabelRes = R.string.app_icon_calendar_label,
        previewBackgroundRes = R.drawable.ic_launcher_calendar_background,
        previewForegroundRes = R.drawable.ic_launcher_calendar_foreground,
        statusIconRes = R.drawable.ic_stat_calendar,
        disguise = true,
    ),
    FLASHLIGHT(
        aliasClass = "$ALIAS_NAMESPACE.LauncherFlashlight",
        pickerLabelRes = R.string.app_icon_flashlight_label,
        previewBackgroundRes = R.drawable.ic_launcher_flashlight_background,
        previewForegroundRes = R.drawable.ic_launcher_flashlight_foreground,
        statusIconRes = R.drawable.ic_stat_flashlight,
        disguise = true,
    ),
    MUSIC(
        aliasClass = "$ALIAS_NAMESPACE.LauncherMusic",
        pickerLabelRes = R.string.app_icon_music_label,
        previewBackgroundRes = R.drawable.ic_launcher_music_background,
        previewForegroundRes = R.drawable.ic_launcher_music_foreground,
        statusIconRes = R.drawable.ic_stat_music,
        disguise = true,
    ),
    GALLERY(
        aliasClass = "$ALIAS_NAMESPACE.LauncherGallery",
        pickerLabelRes = R.string.app_icon_gallery_label,
        previewBackgroundRes = R.drawable.ic_launcher_gallery_background,
        previewForegroundRes = R.drawable.ic_launcher_gallery_foreground,
        statusIconRes = R.drawable.ic_stat_gallery,
        disguise = true,
    ),
    RECORDER(
        aliasClass = "$ALIAS_NAMESPACE.LauncherRecorder",
        pickerLabelRes = R.string.app_icon_recorder_label,
        previewBackgroundRes = R.drawable.ic_launcher_recorder_background,
        previewForegroundRes = R.drawable.ic_launcher_recorder_foreground,
        statusIconRes = R.drawable.ic_stat_recorder,
        disguise = true,
    ),
    ;

    /** В манифесте включён по умолчанию только стандартный alias. */
    internal val manifestEnabled: Boolean get() = this == DEFAULT
}

/**
 * Полное имя пакета, от которого раскрываются имена activity-alias'ов в
 * манифесте (это NAMESPACE `com.aegis.messenger`), а НЕ applicationId. В debug
 * applicationId имеет суффикс `.debug`, но имена классов компонентов от него не
 * зависят — иначе [ComponentName] не найдёт компонент.
 */
private const val ALIAS_NAMESPACE = "com.aegis.messenger.appearance"

object AppIconManager {

    /** Возвращает активную сейчас иконку (по фактически включённому alias'у). */
    fun current(context: Context): AppIcon {
        val app = context.applicationContext
        val enabled = AppIcon.entries.filter { isEffectivelyEnabled(app, it) }
        if (enabled.size == 1) return enabled.first()
        if (enabled.size > 1) {
            return enabled.firstOrNull { it.disguise }
                ?: enabled.firstOrNull { it != AppIcon.DEFAULT }
                ?: AppIcon.DEFAULT
        }
        return AppIcon.DEFAULT
    }

    /**
     * Чинит рассинхрон после обновления APK (новые alias'ы, OEM убила процесс
     * посреди переключения): ровно один LAUNCHER-alias должен быть активен.
     * Идемпотентно — если состояние уже согласовано, PackageManager не трогаем.
     */
    fun reconcile(context: Context) {
        val app = context.applicationContext
        val enabled = AppIcon.entries.filter { isEffectivelyEnabled(app, it) }
        when {
            enabled.size == 1 -> Unit
            enabled.size > 1 -> {
                val preferred = enabled.firstOrNull { it.disguise }
                    ?: enabled.firstOrNull { it != AppIcon.DEFAULT }
                    ?: AppIcon.DEFAULT
                DiagnosticLog.event(
                    "ui", "иконка: несколько alias включены, оставляем один",
                    "вариант" to preferred.name,
                    "было" to enabled.size,
                )
                apply(app, preferred)
            }
            else -> {
                DiagnosticLog.event("ui", "иконка: ни один alias не включён, возвращаем стандарт")
                apply(app, AppIcon.DEFAULT)
            }
        }
    }

    /**
     * Применяет выбранную иконку: СНАЧАЛА включает её alias, ЗАТЕМ выключает
     * остальные. Идемпотентно; `DONT_KILL_APP`, чтобы не убивать процесс без нужды.
     * Возвращает false, если PackageManager отверг компонент (битый манифест).
     */
    fun apply(context: Context, icon: AppIcon): Boolean {
        val app = context.applicationContext
        val extras = AppIcon.entries.filter { it != icon && isEffectivelyEnabled(app, it) }
        if (current(app) == icon && extras.isEmpty() && isEffectivelyEnabled(app, icon)) {
            return true
        }
        val pm = app.packageManager
        val pkg = app.packageName
        val flags = PackageManager.DONT_KILL_APP
        return try {
            val target = component(pkg, icon)
            if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    target,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    flags,
                )
            }
            for (other in AppIcon.entries) {
                if (other == icon) continue
                if (!isEffectivelyEnabled(app, other)) continue
                pm.setComponentEnabledSetting(
                    component(pkg, other),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    flags,
                )
            }
            DiagnosticLog.event("ui", "иконка лаунчера", "вариант" to icon.name)
            true
        } catch (t: Throwable) {
            DiagnosticLog.event(
                "ui", "ошибка смены иконки",
                "вариант" to icon.name,
                "ошибка" to (t.message ?: t.javaClass.simpleName),
            )
            false
        }
    }

    internal fun isEffectivelyEnabled(context: Context, icon: AppIcon): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context.packageName, icon))
        return effectiveEnabled(state, icon.manifestEnabled)
    }

    // pkg = applicationId (в debug — с суффиксом .debug), class = namespace-based.
    private fun component(pkg: String, icon: AppIcon): ComponentName =
        ComponentName(pkg, icon.aliasClass)
}

internal fun effectiveEnabled(state: Int, manifestEnabledByDefault: Boolean): Boolean = when (state) {
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabledByDefault
    else -> manifestEnabledByDefault
}
