package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisCodeTextStyle
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame

/**
 * HUD-панель раздела: рамка со срезанными углами, угловые скобки и служебные
 * микро-коды сверху (`docs/ui_hud.md`).
 *
 * [slug] — СТАТИЧЕСКИЙ идентификатор экрана для генерации кодов. Требования к
 * нему — в [AegisHudCodes] (передавать данные пользователя запрещено).
 * `null` убирает коды совсем.
 */
@Composable
fun AegisHudPanel(
    modifier: Modifier = Modifier,
    slug: String? = null,
    shape: Shape = AegisPanelShape,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    Column(modifier = modifier) {
        if (slug != null) {
            AegisHudCodeRow(slug = slug)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.panelFill)
                .aegisHudFrame(
                    color = colors.panelBorder,
                    shape = shape,
                    cornerColor = colors.securityAccent,
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/**
 * Строка микро-кодов над панелью: слева «трассировка», справа «версия».
 *
 * Для TalkBack строка полностью скрыта ([clearAndSetSemantics] без текста):
 * это декорация, и озвучивать бессмысленный код перед каждым разделом — шум.
 */
@Composable
fun AegisHudCodeRow(
    slug: String,
    modifier: Modifier = Modifier,
) {
    val codeColor = AegisTheme.extendedColors.codeLabel
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = AegisHudCodes.trace(slug), style = AegisCodeTextStyle, color = codeColor)
        Text(text = AegisHudCodes.version(slug), style = AegisCodeTextStyle, color = codeColor)
    }
}

/**
 * Шапка раздела в стиле телефонного меню референса:
 *
 * ```
 * ✉ MESSAGES  ›  PANAM PALMER                    [Tor]
 * ━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━ ─────────────────────
 * ```
 *
 * [title] — раздел (циан), [subtitle] — уточнение после «›» (яркий текст).
 * Оба переводятся в UPPERCASE: `uppercase()` без аргумента работает по
 * `Locale.ROOT`, то есть результат не зависит от локали устройства (в турецкой
 * локали `i` не превратится в `İ` и не разъедет вёрстку).
 *
 * [trailing] — правый слот шапки (например, индикатор Tor).
 */
@Composable
fun AegisHudSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = AegisTheme.extendedColors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = colors.securityAccent,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Хлебная крошка занимает всё свободное место ОДНИМ взвешенным
            // блоком. Если раздать `weight` и названию раздела, и уточнению,
            // каждое получит лишь свою долю ширины и на узком экране оба
            // усохнут до многоточия ещё до того, как место реально кончится.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.securityAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textSecondary,
                    )
                    // Усечению подлежит уточнение (имя контакта), а не название
                    // раздела: по нему пользователь понимает, где находится.
                    Text(
                        text = subtitle.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            trailing?.invoke(this)
        }
        AegisHudRule(
            modifier = Modifier.padding(top = 4.dp),
            segments = if (subtitle != null) 2 else 1,
        )
    }
}

/**
 * Двухцветная линейка под шапкой: толстые циановые сегменты слева и тонкий
 * маджентовый «хвост» до правого края.
 *
 * Ширины сегментов заданы долями, а не измерением текста: измерять надписи
 * пришлось бы через `SubcomposeLayout` (лишний слой компоновки на каждой шапке),
 * а на глаз при усечении длинного имени фиксированные доли выглядят так же.
 */
@Composable
fun AegisHudRule(
    modifier: Modifier = Modifier,
    segments: Int = 1,
    thickness: Dp = 2.dp,
    tailThickness: Dp = 1.dp,
) {
    val colors = AegisTheme.extendedColors
    val accent = colors.securityAccent
    val tail = colors.ruleTail
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .drawBehind {
                val strong = thickness.toPx()
                val thin = tailThickness.toPx()
                val gap = 10.dp.toPx()
                val y = size.height / 2f
                var x = 0f
                repeat(segments.coerceIn(1, 2)) { index ->
                    val fraction = if (index == 0) FIRST_SEGMENT else SECOND_SEGMENT
                    val end = (x + size.width * fraction).coerceAtMost(size.width)
                    drawLine(accent, Offset(x, y), Offset(end, y), strong, StrokeCap.Butt)
                    x = end + gap
                }
                if (x < size.width) {
                    drawLine(tail, Offset(x, y), Offset(size.width, y), thin, StrokeCap.Butt)
                }
            },
    )
}

private const val FIRST_SEGMENT = 0.34f
private const val SECOND_SEGMENT = 0.26f

/**
 * Заголовок группы внутри экрана (над списком/карточками): короткая скобка,
 * прописной моноширинный лейбл и тонкая линия до правого края.
 */
@Composable
fun AegisHudGroupLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(colors.accentPrimary),
        )
        CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.borderSubtle),
        )
    }
}
