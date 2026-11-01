package com.aegis.messenger.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aegis.core.common.appearance.ChatWallpaper
import com.aegis.core.ui.theme.AegisTheme

/**
 * Отрисовка обоев чата (§14.6) как программного [Brush] из токенов темы —
 * без растровых ассетов и разрешений на хранилище. Согласуется и с тёмной, и
 * со светлой темой (использует цвета текущей [AegisTheme]).
 *
 * [ChatWallpaper.DEFAULT] возвращает `null`: фон остаётся однотонным
 * (`background` темы), рисовать поверх нечего — как было до §14.6.
 */
@Composable
fun ChatWallpaper.toBrushOrNull(): Brush? {
    val accent = AegisTheme.extendedColors.securityAccent
    // Обои опираются на приподнятую поверхность, а не на цвет баббла:
    // входящие сообщения — жёлтый акцент, фон чата должен оставаться нейтральным.
    val surface = AegisTheme.extendedColors.surfaceElevated
    return when (this) {
        ChatWallpaper.DEFAULT -> null
        ChatWallpaper.TEAL_GRADIENT -> Brush.verticalGradient(
            listOf(
                surface,
                blend(surface, accent, 0.22f),
            ),
        )
        ChatWallpaper.GRAPHITE -> Brush.verticalGradient(
            listOf(
                surface,
                blend(surface, Color(0xFF3A4652), 0.35f),
            ),
        )
        ChatWallpaper.MIDNIGHT -> Brush.verticalGradient(
            listOf(
                blend(surface, Color.Black, 0.35f),
                blend(surface, accent, 0.16f),
            ),
        )
    }
}

/** Линейная интерполяция двух цветов (доля [t] от [a] к [b]). */
private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
