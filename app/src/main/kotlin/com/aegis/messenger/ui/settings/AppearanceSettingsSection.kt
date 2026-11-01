package com.aegis.messenger.ui.settings

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.core.common.appearance.AppLanguage
import com.aegis.core.common.appearance.AppThemeMode
import com.aegis.core.common.appearance.AppearanceSettings
import com.aegis.core.common.appearance.ChatWallpaper
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudToggleChip
import com.aegis.core.ui.hud.aegisHudBezel
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.appearance.AppIcon
import com.aegis.messenger.appearance.AppIconManager
import com.aegis.messenger.appearance.LocaleManager

/**
 * Секция «Оформление» (§14.6): тема (тёмная/светлая/системная), язык интерфейса,
 * обои чата и смена/маскировка иконки приложения.
 *
 * Тема и обои — часть [AppearanceSettings] (хранятся через SecureStore, меняются
 * «на лету» через [onSetAppearance]). Язык и иконка — операции над Android-
 * контекстом (per-app locale и activity-alias'ы), поэтому выполняются прямо
 * здесь через [LocaleManager] и [AppIconManager] без отдельной проводки.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsSection(
    settings: AppearanceSettings,
    onSetAppearance: (AppearanceSettings) -> Unit,
) {
    val secondary = AegisTheme.extendedColors.textSecondary
    val context = LocalContext.current

    // Язык и иконка читаются из системы/менеджеров, а не из AppearanceSettings.
    var language by remember { mutableStateOf(LocaleManager.currentLanguage(context)) }
    var appIcon by remember { mutableStateOf(AppIconManager.current(context)) }
    var iconApplyOk by remember { mutableStateOf<Boolean?>(null) }
    val brandIcons = remember { AppIcon.entries.filter { !it.disguise } }
    val disguiseIcons = remember { AppIcon.entries.filter { it.disguise } }

    AegisHudCard(
        title = stringResource(R.string.appearance_section_title),
        icon = Icons.Outlined.Palette,
    ) {
        // --- Тема ---
        AegisHudGroupLabel(text = stringResource(R.string.appearance_theme_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_theme_dark),
                selected = settings.theme == AppThemeMode.DARK,
                onClick = { onSetAppearance(settings.copy(theme = AppThemeMode.DARK)) },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_theme_light),
                selected = settings.theme == AppThemeMode.LIGHT,
                onClick = { onSetAppearance(settings.copy(theme = AppThemeMode.LIGHT)) },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_theme_system),
                selected = settings.theme == AppThemeMode.SYSTEM,
                onClick = { onSetAppearance(settings.copy(theme = AppThemeMode.SYSTEM)) },
            )
        }
        Text(
            text = stringResource(R.string.appearance_theme_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        AegisHudDivider()

        // --- Язык ---
        AegisHudGroupLabel(text = stringResource(R.string.appearance_language_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_lang_system),
                selected = language == AppLanguage.SYSTEM,
                onClick = { applyLanguage(context, AppLanguage.SYSTEM) { language = it } },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_lang_russian),
                selected = language == AppLanguage.RUSSIAN,
                onClick = { applyLanguage(context, AppLanguage.RUSSIAN) { language = it } },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_lang_english),
                selected = language == AppLanguage.ENGLISH,
                onClick = { applyLanguage(context, AppLanguage.ENGLISH) { language = it } },
            )
        }
        Text(
            text = stringResource(R.string.appearance_language_desc),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )

        AegisHudDivider()

        // --- Обои чата ---
        AegisHudGroupLabel(text = stringResource(R.string.appearance_wallpaper_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_wallpaper_none),
                selected = settings.chatWallpaper == ChatWallpaper.DEFAULT,
                onClick = { onSetAppearance(settings.copy(chatWallpaper = ChatWallpaper.DEFAULT)) },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_wallpaper_teal),
                selected = settings.chatWallpaper == ChatWallpaper.TEAL_GRADIENT,
                onClick = { onSetAppearance(settings.copy(chatWallpaper = ChatWallpaper.TEAL_GRADIENT)) },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_wallpaper_graphite),
                selected = settings.chatWallpaper == ChatWallpaper.GRAPHITE,
                onClick = { onSetAppearance(settings.copy(chatWallpaper = ChatWallpaper.GRAPHITE)) },
            )
            AegisHudToggleChip(
                text = stringResource(R.string.appearance_wallpaper_midnight),
                selected = settings.chatWallpaper == ChatWallpaper.MIDNIGHT,
                onClick = { onSetAppearance(settings.copy(chatWallpaper = ChatWallpaper.MIDNIGHT)) },
            )
        }

        AegisHudDivider()

        // --- Иконка приложения / маскировка ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AegisHudGroupLabel(text = stringResource(R.string.appearance_icon_title))
            Text(
                text = stringResource(R.string.appearance_icon_brand_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
            )
            IconPickerGrid(
                icons = brandIcons,
                selected = appIcon,
                onSelect = { chosen ->
                    iconApplyOk = AppIconManager.apply(context, chosen)
                    appIcon = AppIconManager.current(context)
                },
            )
            Text(
                text = stringResource(R.string.appearance_icon_disguise_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            IconPickerGrid(
                icons = disguiseIcons,
                selected = appIcon,
                onSelect = { chosen ->
                    iconApplyOk = AppIconManager.apply(context, chosen)
                    appIcon = AppIconManager.current(context)
                },
            )
            when (iconApplyOk) {
                true -> AegisHudStatusLine(
                    text = stringResource(R.string.appearance_icon_applied),
                    ok = true,
                )
                false -> AegisHudStatusLine(
                    text = stringResource(R.string.appearance_icon_apply_failed),
                    ok = false,
                    failColor = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }
            Text(
                text = stringResource(R.string.appearance_icon_desc),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
            )
        }
    }
}

@Composable
private fun IconPickerGrid(
    icons: List<AppIcon>,
    selected: AppIcon,
    onSelect: (AppIcon) -> Unit,
    columns: Int = 4,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icons.chunked(columns).forEach { rowIcons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowIcons.forEach { icon ->
                    IconPickerTile(
                        icon = icon,
                        selected = icon == selected,
                        onClick = { onSelect(icon) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowIcons.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IconPickerTile(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val chrome = colors.securityAccent
    val label = stringResource(icon.pickerLabelRes)
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .aegisHudBezel(
                    color = chrome,
                    selected = selected,
                    glow = selected && colors.scanlinesEnabled,
                )
                .padding(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                Image(
                    painter = painterResource(icon.previewBackgroundRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Image(
                    painter = painterResource(icon.previewForegroundRes),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) chrome else colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Применяет язык (§14.6): сохраняет тег и пересоздаёт Activity, чтобы новая
 * локаль подхватилась в [android.app.Activity.attachBaseContext]. Для того же
 * языка — no-op (не дёргаем recreate зря).
 */
private fun applyLanguage(
    context: android.content.Context,
    language: AppLanguage,
    onApplied: (AppLanguage) -> Unit,
) {
    if (LocaleManager.currentLanguage(context) == language) return
    LocaleManager.persist(context, language)
    onApplied(language)
    (context as? Activity)?.recreate()
}
