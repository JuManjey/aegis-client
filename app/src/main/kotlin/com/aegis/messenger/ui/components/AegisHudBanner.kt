package com.aegis.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.theme.AegisTheme

/** Смысловая окраска HUD-баннера. */
enum class HudBannerTone {
    /** Проблема, блокирующая работу: не задан релей, контакт не сверен. */
    DANGER,

    /** Информация о состоянии защиты: ключи в пути, новое устройство. */
    SECURITY,
}

/**
 * Служебная полоса-баннер над контентом раздела: вертикальный акцентный маркер
 * у ведущего края, приглушённая заливка того же цвета и текст
 * (`docs/ui_hud.md`).
 *
 * Один компонент на все баннеры приложения. Раньше «адрес релея не задан»
 * существовал двумя независимыми копиями — в списке чатов и в переписке — и
 * расходился по отступам.
 *
 * Маркер РИСУЕТСЯ, а не выкладывается элементом с `fillMaxHeight`: высота
 * баннера определяется текстом (длинные предупреждения переносятся на 2–3
 * строки), а `fillMaxHeight` внутри `Row` растянулся бы по входящему
 * ограничению, а не по фактической высоте строки.
 *
 * [onClick] делает кликабельной всю полосу, [onAction] добавляет кнопку справа.
 * Одновременно задавать не нужно — получатся две конкурирующие цели касания.
 */
@Composable
fun AegisHudBanner(
    text: String,
    tone: HudBannerTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = AegisTheme.extendedColors
    val accent: Color = when (tone) {
        HudBannerTone.DANGER -> colors.danger
        HudBannerTone.SECURITY -> colors.securityAccent
    }
    // Выключенная полоса гаснет вся целиком: маркер, заливка и текст. Кликабельная
    // полоса, которая выглядит активной и молча ничего не делает, читается как
    // зависшее приложение.
    val marker = if (enabled) accent else colors.textSecondary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinBannerHeight)
            .background(marker.copy(alpha = FILL_ALPHA))
            .drawBehind {
                val width = MarkerWidth.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                drawRect(
                    color = marker,
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height),
                )
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            )
            .padding(start = MarkerWidth + 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textSecondary,
        )
        if (actionLabel != null && onAction != null) {
            AegisHudOutlinedButton(
                text = actionLabel,
                onClick = onAction,
                enabled = enabled,
                danger = tone == HudBannerTone.DANGER,
            )
        }
    }
}

private val MinBannerHeight = 38.dp
private val MarkerWidth = 3.dp

private const val FILL_ALPHA = 0.13f
