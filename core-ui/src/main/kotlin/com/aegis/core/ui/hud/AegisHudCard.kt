package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame

/**
 * Карточка-секция HUD (`docs/ui_hud.md`): рамка со срезанными углами, угловые
 * скобки, необязательная шапка «иконка + прописной заголовок + линия до края».
 *
 * Заменяет паттерн `Surface(color = surface, shape = medium)` + собственная
 * строка заголовка, который до перехода на HUD был скопирован по экранам
 * настроек четырьмя почти одинаковыми приватными хелперами (`SectionCard`,
 * `SecurityCard`, `TorNetworkCard`, `MetadataPrivacyCard`).
 *
 * [onClick] делает всю карточку кликабельной (карточка-кнопка на экране
 * профиля); по умолчанию карточка неинтерактивна.
 */
@Composable
fun AegisHudCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    accent: Color = AegisTheme.extendedColors.securityAccent,
    shape: Shape = AegisPanelShape,
    contentPadding: Dp = 14.dp,
    verticalGap: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    titleTrailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panelFill)
            .aegisHudFrame(color = colors.panelBorder, shape = shape, cornerColor = accent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalGap),
    ) {
        if (title != null) {
            AegisHudCardTitle(
                title = title,
                icon = icon,
                accent = accent,
                trailing = titleTrailing,
            )
        }
        content()
    }
}

/** Шапка карточки: иконка, прописной узкий заголовок и тонкая линия до края. */
@Composable
private fun AegisHudCardTitle(
    title: String,
    icon: ImageVector?,
    accent: Color,
    trailing: @Composable (RowScope.() -> Unit)?,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.borderSubtle),
        )
        trailing?.invoke(this)
    }
}

/** Тонкий разделитель внутри карточек и списков. */
@Composable
fun AegisHudDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AegisTheme.extendedColors.borderSubtle),
    )
}

/**
 * Пара «подпись → значение» для служебных данных: отпечатки ключей, ID
 * устройств, onion-адреса, размеры.
 *
 * Значение всегда моноширинное: такие строки сверяют символ за символом, и
 * пропорциональный шрифт для этого не годится (`docs/ui_hud.md`).
 */
@Composable
fun AegisHudKeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AegisTheme.extendedColors.textSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Строка состояния «выполнено / не выполнено»: квадратный маркер вместо
 * круглого `●`/`○`, циан против приглушённого.
 *
 * Признак дублируется цветом И формой маркера (залит/пустой): состояние должно
 * читаться и при дальтонизме, а не только по цвету.
 */
@Composable
fun AegisHudStatusLine(
    text: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    okColor: Color = AegisTheme.extendedColors.securityAccent,
    failColor: Color = AegisTheme.extendedColors.textSecondary,
) {
    val color = if (ok) okColor else failColor
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .then(
                    if (ok) {
                        Modifier.background(color)
                    } else {
                        Modifier.aegisHudFrame(color = color, shape = MaterialTheme.shapes.extraSmall, cornerArm = 0.dp)
                    },
                ),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * Горизонтальная «шкала» HUD для прогресса с известной долей (загрузка,
 * bootstrap Tor): рубленая рамка и залитая часть акцентом.
 *
 * [fraction] вне `0..1` подрезается: значения приходят из внешних источников
 * (прогресс bootstrap, размер вложения), и выход за границы не должен рисовать
 * полосу за пределами рамки.
 */
@Composable
fun AegisHudMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    accent: Color = AegisTheme.extendedColors.securityAccent,
    height: Dp = 6.dp,
) {
    val colors = AegisTheme.extendedColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(colors.railTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(accent),
        )
    }
}

/** Узкая вертикальная «насечка»-акцент для маркировки строк слева. */
@Composable
fun AegisHudTickMark(
    modifier: Modifier = Modifier,
    color: Color = AegisTheme.extendedColors.accentPrimary,
    height: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .width(3.dp)
            .height(height)
            .background(color),
    )
}
