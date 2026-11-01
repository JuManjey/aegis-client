package com.aegis.core.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme

/**
 * Диалог в HUD-раскладке (`docs/ui_hud.md`): панель со срезанными углами,
 * прописной узкий заголовок с двухцветной линейкой под ним и моноширинные
 * подписи кнопок.
 *
 * Построен НА Material3 [AlertDialog], а не с нуля: тот уже умеет перехват
 * системной кнопки «назад», закрытие по тапу вне окна, корректную работу с
 * клавиатурой и семантику диалога для TalkBack. Переопределяются только форма,
 * цвета и типографика — поведение остаётся штатным.
 *
 * [confirmLabel]/[onConfirm] и [dismissLabel]/[onDismissAction] задают кнопки;
 * пропуск пары убирает соответствующую кнопку. [danger] красит подтверждение в
 * цвет опасного действия (удаление чата, экстренная очистка).
 */
@Composable
fun AegisHudDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismissAction: (() -> Unit)? = null,
    danger: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = AegisPanelShape,
        containerColor = colors.surfaceElevated,
        titleContentColor = colors.securityAccent,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        icon = if (icon != null) {
            {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.securityAccent,
                )
            }
        } else {
            null
        },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.securityAccent,
                )
                AegisHudRule()
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        },
        confirmButton = {
            if (confirmLabel != null && onConfirm != null) {
                AegisHudDialogButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    color = if (danger) colors.danger else colors.accentPrimary,
                )
            }
        },
        dismissButton = if (dismissLabel != null && onDismissAction != null) {
            {
                AegisHudDialogButton(
                    text = dismissLabel,
                    onClick = onDismissAction,
                    color = colors.textSecondary,
                    enabled = dismissEnabled,
                )
            }
        } else {
            null
        },
    )
}

/**
 * Кнопка диалога: моноширинная прописная подпись без заливки.
 *
 * Заливка тут была бы лишней — в узком окне две залитых кнопки рядом читаются
 * как «две главные», а приоритет задаётся цветом текста.
 */
@Composable
private fun AegisHudDialogButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else AegisTheme.extendedColors.textSecondary,
        )
    }
}
