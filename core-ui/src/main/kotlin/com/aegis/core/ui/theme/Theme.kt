package com.aegis.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Тёмная цветовая схема Material3, собранная из палитры §13 (эстетика
 * Cyberpunk 2077).
 *
 * Роли Material3:
 * - primary — жёлтый акцент (активные табы, важные кнопки, выделения);
 * - secondary — циан (ссылки, активные состояния, индикаторы);
 * - tertiary — маджента (второстепенный акцент, бейджи).
 *
 * Динамические цвета (Material You) НЕ используем: бренд «доверия/защиты»
 * требует стабильной палитры, а не подстройки под обои пользователя.
 */
private val AegisDarkColorScheme = darkColorScheme(
    primary = AegisPalette.AccentPrimary,
    onPrimary = AegisPalette.OnAccentPrimary,
    primaryContainer = AegisPalette.AccentPrimary,
    onPrimaryContainer = AegisPalette.OnAccentPrimary,

    secondary = AegisPalette.AccentCyan,
    onSecondary = AegisPalette.OnAccentCyan,
    secondaryContainer = AegisPalette.SurfaceElevated,
    onSecondaryContainer = AegisPalette.AccentCyan,

    tertiary = AegisPalette.AccentMagenta,
    onTertiary = AegisPalette.OnAccentMagenta,

    background = AegisPalette.Background,
    onBackground = AegisPalette.TextPrimary,

    surface = AegisPalette.Surface,
    onSurface = AegisPalette.TextPrimary,
    surfaceVariant = AegisPalette.SurfaceVariant,
    onSurfaceVariant = AegisPalette.TextSecondary,

    outline = AegisPalette.BorderSubtle,
    outlineVariant = AegisPalette.SurfaceVariant,

    error = AegisPalette.Danger,
    onError = AegisPalette.OnDanger,
)

/**
 * Светлая цветовая схема Material3 (§14.6). Динамические цвета (Material You)
 * НЕ используем и здесь — палитра стабильна для узнаваемости бренда.
 */
private val AegisLightColorScheme = lightColorScheme(
    primary = AegisLightPalette.AccentPrimary,
    onPrimary = AegisLightPalette.OnAccentPrimary,
    primaryContainer = AegisLightPalette.AccentPrimary,
    onPrimaryContainer = AegisLightPalette.OnAccentPrimary,

    secondary = AegisLightPalette.AccentCyan,
    onSecondary = AegisLightPalette.OnAccentCyan,
    secondaryContainer = AegisLightPalette.SurfaceElevated,
    onSecondaryContainer = AegisLightPalette.AccentCyan,

    tertiary = AegisLightPalette.AccentMagenta,
    onTertiary = AegisLightPalette.OnAccentMagenta,

    background = AegisLightPalette.Background,
    onBackground = AegisLightPalette.TextPrimary,

    surface = AegisLightPalette.Surface,
    onSurface = AegisLightPalette.TextPrimary,
    surfaceVariant = AegisLightPalette.SurfaceVariant,
    onSurfaceVariant = AegisLightPalette.TextSecondary,

    outline = AegisLightPalette.BorderSubtle,
    outlineVariant = AegisLightPalette.SurfaceVariant,

    error = AegisLightPalette.Danger,
    onError = AegisLightPalette.OnDanger,
)

/**
 * Корневая тема приложения. Оборачивать ею весь UI.
 *
 * По умолчанию тёмная (база §13); [darkTheme] переключает на светлую схему
 * (§14.6). Выбор режима (тёмная/светлая/по системе) делается выше — в
 * `MainActivity` из [com.aegis.core.common.appearance.AppThemeMode].
 *
 * Пример доступа к расширенным цветам:
 * ```
 * val bubble = AegisTheme.extendedColors.outgoingBubble
 * ```
 */
@Composable
fun AegisTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAegisColors provides if (darkTheme) AegisDarkExtendedColors else AegisLightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) AegisDarkColorScheme else AegisLightColorScheme,
            typography = AegisTypography,
            shapes = AegisShapes,
            content = content,
        )
    }
}

/** Точка доступа к токенам темы AEGIS (аналог `MaterialTheme`). */
object AegisTheme {
    val extendedColors: AegisExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAegisColors.current
}
