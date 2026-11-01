package com.aegis.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Семантические цвета AEGIS, которых нет в стандартной схеме Material3
 * (бабблы сообщений, неоновые акценты, границы, «опасные» действия, HUD-хром).
 *
 * Доступ через [LocalAegisColors] — так экраны не хардкодят HEX (§13).
 * Оформление — эстетика Cyberpunk 2077 (неон + рубленая геометрия),
 * раскладка HUD описана в `docs/ui_hud.md`.
 */
@Immutable
data class AegisExtendedColors(
    /**
     * Заливка исходящих бабблов (свои сообщения) — СПЛОШНОЙ циан.
     * В референсе свои сообщения «залиты» акцентом, а текст на них тёмный
     * ([onOutgoingBubble]); чужие — только обведены ([incomingBubble]).
     */
    val outgoingBubble: Color,
    val onOutgoingBubble: Color,
    /** Тонкая рамка исходящего баббла (1px), циан. */
    val outgoingBubbleBorder: Color,
    /** Заливка входящих бабблов — почти прозрачный жёлтый акцент бренда. */
    val incomingBubble: Color,
    /** Текст входящего баббла — жёлтый акцент (как реплики собеседника в референсе). */
    val onIncomingBubble: Color,
    /** Тонкая рамка входящего баббла (1px), жёлтый акцент. */
    val incomingBubbleBorder: Color,
    /** Акцент индикаторов безопасности/активных состояний (циан). */
    val securityAccent: Color,
    /** Главный акцент бренда (жёлтый): активные табы, выделения, важные кнопки. */
    val accentPrimary: Color,
    /** Второстепенный акцент (маджента): бейджи, hover. */
    val accentMagenta: Color,
    /** Контрастный текст поверх [accentMagenta] (бейдж непрочитанных). */
    val onAccentMagenta: Color,
    /** Цвет опасных действий (экстренная очистка, ослабление профиля). */
    val danger: Color,
    val onDanger: Color,
    /** Вторичный текст (подписи, таймстампы, статусы). */
    val textSecondary: Color,
    /** Тонкие границы/разделители (1px). */
    val borderSubtle: Color,
    /** Приподнятая поверхность (модалки, поповеры). */
    val surfaceElevated: Color,
    /** Заливка HUD-панели (рамка раздела). */
    val panelFill: Color,
    /** Рамка HUD-панели и её угловых скобок. */
    val panelBorder: Color,
    /** Трек вертикальной рейки-скроллбара. */
    val railTrack: Color,
    /** Бегунок рейки-скроллбара. */
    val railThumb: Color,
    /** Заливка выбранной строки списка (инверсия). */
    val selectionFill: Color,
    /** Текст на выбранной строке списка. */
    val onSelectionFill: Color,
    /** Служебные микро-коды по углам панелей. */
    val codeLabel: Color,
    /** «Хвост» двухцветной линейки под заголовком раздела. */
    val ruleTail: Color,
    /** Линии фоновой сетки. */
    val backdropGrid: Color,
    /** Затемнение под панелями поверх видео (экран звонка). */
    val scrimOverlay: Color,
    /**
     * Флаг тонких CRT scanline-линий и неонового свечения поверх фона (эстетика
     * игры). В светлой теме выключен: там свечение читается как грязь.
     */
    val scanlinesEnabled: Boolean,
)

/**
 * Альфа заливки ВХОДЯЩЕГО баббла: акцент только намекает на фон, работу по
 * контрасту делает цветной текст и рамка.
 */
private const val INCOMING_FILL_ALPHA = 0.10f

internal val AegisDarkExtendedColors = AegisExtendedColors(
    // Исходящие: сплошной циан #02D7F2, текст тёмный — «залитая» реплика.
    outgoingBubble = AegisPalette.AccentCyan,
    onOutgoingBubble = AegisPalette.OnAccentCyan,
    outgoingBubbleBorder = AegisPalette.AccentCyan,
    // Входящие: рамка + жёлтый текст на почти прозрачной заливке.
    incomingBubble = AegisPalette.AccentPrimary.copy(alpha = INCOMING_FILL_ALPHA),
    onIncomingBubble = AegisPalette.AccentPrimary,
    incomingBubbleBorder = AegisPalette.AccentPrimary,
    securityAccent = AegisPalette.AccentCyan,
    accentPrimary = AegisPalette.AccentPrimary,
    accentMagenta = AegisPalette.AccentMagenta,
    onAccentMagenta = AegisPalette.OnAccentMagenta,
    danger = AegisPalette.Danger,
    onDanger = AegisPalette.OnDanger,
    textSecondary = AegisPalette.TextSecondary,
    borderSubtle = AegisPalette.BorderSubtle,
    surfaceElevated = AegisPalette.SurfaceElevated,
    panelFill = AegisPalette.PanelFill,
    panelBorder = AegisPalette.PanelBorder,
    railTrack = AegisPalette.RailTrack,
    railThumb = AegisPalette.RailThumb,
    selectionFill = AegisPalette.SelectionFill,
    onSelectionFill = AegisPalette.OnSelectionFill,
    codeLabel = AegisPalette.CodeLabel,
    ruleTail = AegisPalette.RuleTail,
    backdropGrid = AegisPalette.BackdropGrid,
    scrimOverlay = AegisPalette.ScrimOverlay,
    scanlinesEnabled = true,
)

/** Расширенные цвета для светлой темы (§14.6). */
internal val AegisLightExtendedColors = AegisExtendedColors(
    outgoingBubble = AegisLightPalette.AccentCyan,
    onOutgoingBubble = AegisLightPalette.OnAccentCyan,
    outgoingBubbleBorder = AegisLightPalette.AccentCyan,
    incomingBubble = AegisLightPalette.AccentPrimary.copy(alpha = INCOMING_FILL_ALPHA),
    onIncomingBubble = AegisLightPalette.AccentPrimary,
    incomingBubbleBorder = AegisLightPalette.AccentPrimary,
    securityAccent = AegisLightPalette.AccentCyan,
    accentPrimary = AegisLightPalette.AccentPrimary,
    accentMagenta = AegisLightPalette.AccentMagenta,
    onAccentMagenta = AegisLightPalette.OnAccentMagenta,
    danger = AegisLightPalette.Danger,
    onDanger = AegisLightPalette.OnDanger,
    textSecondary = AegisLightPalette.TextSecondary,
    borderSubtle = AegisLightPalette.BorderSubtle,
    surfaceElevated = AegisLightPalette.SurfaceElevated,
    panelFill = AegisLightPalette.PanelFill,
    panelBorder = AegisLightPalette.PanelBorder,
    railTrack = AegisLightPalette.RailTrack,
    railThumb = AegisLightPalette.RailThumb,
    selectionFill = AegisLightPalette.SelectionFill,
    onSelectionFill = AegisLightPalette.OnSelectionFill,
    codeLabel = AegisLightPalette.CodeLabel,
    ruleTail = AegisLightPalette.RuleTail,
    backdropGrid = AegisLightPalette.BackdropGrid,
    scrimOverlay = AegisLightPalette.ScrimOverlay,
    scanlinesEnabled = false,
)

/**
 * CompositionLocal для доступа к расширенным цветам из любого Composable:
 * `AegisTheme.extendedColors` (см. [AegisTheme]).
 */
val LocalAegisColors = staticCompositionLocalOf { AegisDarkExtendedColors }
