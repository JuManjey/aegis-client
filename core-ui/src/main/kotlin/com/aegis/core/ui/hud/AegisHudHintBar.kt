package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisTheme

/**
 * Одна подсказка нижней панели: «клавиша» + действие (`[C] CLOSE` в референсе).
 *
 * [key] — короткий глиф-маркер (1–2 символа). [onClick] делает подсказку живой
 * кнопкой; `null` оставляет её пояснительной надписью.
 */
@Immutable
data class AegisHudHint(
    val key: String,
    val label: String,
    val onClick: (() -> Unit)? = null,
    /** Опасное действие (удаление, разблокировка) — маркер красится в danger. */
    val danger: Boolean = false,
)

/**
 * Нижняя панель подсказок HUD: тонкая маджентовая линия и справа набор
 * `[клавиша] ДЕЙСТВИЕ` (`docs/ui_hud.md`).
 *
 * Это не декорация: подсказки с [AegisHudHint.onClick] — реальные кнопки. Так
 * вторичные действия экрана уезжают из «Material»-меню в HUD-строку, как в
 * референсе, и остаются доступными в один тап.
 */
@Composable
fun AegisHudHintBar(
    hints: List<AegisHudHint>,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) return
    val colors = AegisTheme.extendedColors
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.ruleTail),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            hints.forEach { hint -> AegisHudHintItem(hint) }
        }
    }
}

@Composable
private fun AegisHudHintItem(hint: AegisHudHint) {
    val colors = AegisTheme.extendedColors
    val accent = if (hint.danger) colors.danger else colors.securityAccent
    val clickable = hint.onClick
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .then(
                if (clickable != null) {
                    Modifier
                        .clickable(onClick = clickable, role = Role.Button)
                        .semantics { contentDescription = hint.label }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Сам маркер клавиши TalkBack не читает: озвучивается действие целиком.
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .border(1.dp, accent, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = hint.key.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
        }
        Text(
            text = hint.label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (hint.danger) colors.danger else MaterialTheme.colorScheme.onBackground,
        )
    }
}
