package com.aegis.core.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme

/**
 * Выпадающее меню в HUD-раскладке (`docs/ui_hud.md`): панель со срезанными по
 * диагонали углами, циановая рамка, тёмная приподнятая заливка и никакой
 * Material-тени.
 *
 * Построено НА Material3 [DropdownMenu]: он уже умеет позиционирование поповера
 * у края экрана, закрытие по тапу вне окна и по системной кнопке «назад»,
 * прокрутку длинного списка и семантику всплывающего меню для TalkBack.
 * Переопределяются только форма, цвет и рамка.
 *
 * Тень убрана намеренно (`shadowElevation = 0`): в HUD глубина показывается
 * рамкой и скобками, а Material-тень под срезанным углом даёт грязный ореол.
 */
@Composable
fun AegisHudDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = AegisPanelShape,
        containerColor = colors.surfaceElevated,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.panelBorder),
        content = content,
    )
}

/**
 * Пункт HUD-меню: акцентная иконка и прописная моноширинная подпись.
 *
 * [danger] красит пункт в цвет опасного действия (удалить чат, отозвать
 * устройство) — по §5.8 документации только для необратимого.
 */
@Composable
fun AegisHudMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = AegisTheme.extendedColors
    val accent: Color = when {
        !enabled -> colors.textSecondary
        danger -> colors.danger
        else -> colors.securityAccent
    }
    val label: Color = when {
        !enabled -> colors.textSecondary
        danger -> colors.danger
        else -> MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        text = {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = label,
            )
        },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = if (icon != null) {
            {
                // Подпись пункта видна текстом, поэтому иконка своего описания не
                // несёт — иначе TalkBack прочитал бы пункт дважды.
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        colors = MenuDefaults.itemColors(
            textColor = label,
            leadingIconColor = accent,
            disabledTextColor = colors.textSecondary,
            disabledLeadingIconColor = colors.textSecondary,
        ),
    )
}
