package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame

/**
 * Строка-переключатель: заголовок, пояснение и Material-`Switch` в цветах HUD
 * (`docs/ui_hud.md`).
 *
 * Один компонент вместо трёх почти одинаковых приватных хелперов, которые до
 * перехода на HUD жили копиями в `CallSettingsSection` (`ToggleRow`),
 * `PrivacySettingsSection` и `NotificationSettingsSection` (`SwitchRow`).
 *
 * Нажатие обрабатывается на ВСЕЙ строке через [toggleable], а не только на
 * ползунке: цель касания у `Switch` — около 50dp, а строка с двумя абзацами
 * текста заметно выше, и тапать в неё естественнее. `Switch` внутри своего
 * обработчика не имеет, иначе TalkBack объявлял бы два независимых элемента.
 */
@Composable
fun AegisHudSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AegisHudTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    colors.textSecondary
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = aegisHudSwitchColors(),
        )
    }
}

/** Цвета `Switch` в палитре HUD: включённый — циановый хром. */
@Composable
fun aegisHudSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = AegisTheme.extendedColors.onSelectionFill,
    checkedTrackColor = AegisTheme.extendedColors.securityAccent,
    checkedBorderColor = AegisTheme.extendedColors.securityAccent,
    uncheckedThumbColor = AegisTheme.extendedColors.textSecondary,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = AegisTheme.extendedColors.borderSubtle,
)

/**
 * Строка выбора одного значения из списка: рубленый квадратный маркер вместо
 * круглого `RadioButton`, заголовок и пояснение.
 *
 * Выбранное состояние показывается И заливкой маркера, И цветом текста: только
 * цвет для передачи состояния недостаточен при дальтонизме.
 */
@Composable
fun AegisHudOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    accent: Color = AegisTheme.extendedColors.securityAccent,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AegisHudTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AegisHudMarker(selected = selected, enabled = enabled, accent = accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> colors.textSecondary
                    selected -> accent
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Квадратный маркер выбора: рамка, а внутри — залитый квадрат у выбранного
 * варианта. Своя геометрия вместо `RadioButton`, у которого круглая форма
 * противоречит рубленой геометрии §13.
 */
@Composable
private fun AegisHudMarker(
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val frame = when {
        !enabled -> colors.borderSubtle
        selected -> accent
        else -> colors.textSecondary
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .aegisHudFrame(
                color = frame,
                shape = MaterialTheme.shapes.extraSmall,
                cornerArm = 0.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(if (enabled) accent else colors.borderSubtle),
            )
        }
    }
}

/**
 * Строка-флажок для многозначного выбора. Отличается от [AegisHudOptionRow]
 * семантикой ([Role.Checkbox]) и маркером-«галочкой», чтобы пользователь видел
 * разницу между «выбрать один» и «выбрать несколько».
 */
@Composable
fun AegisHudCheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    accent: Color = AegisTheme.extendedColors.securityAccent,
) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AegisHudTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AegisHudCheckMark(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            enabled = enabled,
            accent = accent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textSecondary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AegisHudCheckMark(
    state: ToggleableState,
    enabled: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val frame = when {
        !enabled -> colors.borderSubtle
        state == ToggleableState.On -> accent
        else -> colors.textSecondary
    }
    Box(
        modifier = modifier
            .size(18.dp)
            .aegisHudFrame(
                color = frame,
                shape = MaterialTheme.shapes.extraSmall,
                cornerArm = 0.dp,
            )
            .drawBehind {
                if (state != ToggleableState.On) return@drawBehind
                val stroke = 2.dp.toPx()
                val w = size.width
                val h = size.height
                drawLine(
                    color = frame,
                    start = Offset(w * 0.24f, h * 0.52f),
                    end = Offset(w * 0.44f, h * 0.72f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = frame,
                    start = Offset(w * 0.44f, h * 0.72f),
                    end = Offset(w * 0.76f, h * 0.28f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            },
    )
}
