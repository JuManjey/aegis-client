package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.aegisHudBackdrop

/**
 * Каркас полноэкранного раздела в HUD-раскладке (`docs/ui_hud.md`): фон с
 * сеткой и виньеткой, микро-коды, шапка с кнопкой «назад» и хлебной крошкой,
 * двухцветная линейка и необязательная нижняя строка подсказок.
 *
 * Заменяет пару Material `Scaffold` + `TopAppBar`, которая до перехода на HUD
 * повторялась по экранам десятком копий с разными отступами.
 *
 * Приложение рисует edge-to-edge, поэтому инсеты берутся здесь: верхние — на
 * шапку, нижние и боковые — на контент. Своего `Scaffold` внутри быть не должно,
 * иначе отступы посчитаются дважды.
 *
 * [slug] — СТАТИЧЕСКИЙ идентификатор экрана для декоративных кодов; требования
 * к нему в [AegisHudCodes] (данные пользователя передавать запрещено).
 *
 * [content] размещается в скроллящемся снаружи слоте: экран сам решает, что
 * внутри — `LazyColumn` или `Column` с `verticalScroll`.
 */
@Composable
fun AegisHudScreen(
    title: String,
    modifier: Modifier = Modifier,
    slug: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "",
    hints: List<AegisHudHint> = emptyList(),
    headerTrailing: @Composable (RowScope.() -> Unit)? = null,
    contentPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .aegisHudBackdrop(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(top = 6.dp),
        ) {
            if (slug != null) {
                AegisHudCodeRow(slug = slug, modifier = Modifier.padding(horizontal = 8.dp))
            }
            AegisHudSectionHeader(
                title = title,
                subtitle = subtitle,
                icon = icon,
                leading = if (onBack != null) {
                    {
                        AegisHudIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = backContentDescription,
                            onClick = onBack,
                            size = 34.dp,
                        )
                    }
                } else {
                    null
                },
                trailing = headerTrailing,
            )
        }
        // Нижние инсеты держит строка подсказок, если она есть, иначе — сам
        // контент. Без этого на устройствах с жестовой навигацией последняя
        // карточка уезжает под системную полоску.
        val contentSides = if (hints.isEmpty()) {
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
        } else {
            WindowInsetsSides.Horizontal
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(contentSides))
                .padding(horizontal = contentPadding),
            content = content,
        )
        if (hints.isNotEmpty()) {
            AegisHudHintBar(
                hints = hints,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                ),
            )
        }
    }
}

/** Значения по умолчанию каркаса HUD-экрана. */
object AegisHudScreenDefaults {
    /** Внутренний горизонтальный отступ контента раздела. */
    val ContentPadding: Dp = 12.dp

    /** Вертикальный интервал между карточками раздела. */
    val CardGap: Dp = 10.dp
}
