package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisIncomingBubbleShape
import com.aegis.core.ui.theme.AegisOutgoingBubbleShape
import com.aegis.core.ui.theme.AegisTheme

/**
 * Баббл сообщения в стиле референса (`docs/ui_hud.md`).
 *
 * Направления различаются не только выравниванием, но и способом окраски —
 * поэтому реплики читаются как «свои/чужие» даже на скриншоте без хвостов:
 *
 *  * ИСХОДЯЩЕЕ — сплошная заливка цианом, тёмный текст, срез правого нижнего угла;
 *  * ВХОДЯЩЕЕ — почти прозрачная заливка, жёлтая рамка и жёлтый текст,
 *    срез левого нижнего угла.
 *
 * Цвет и базовый стиль текста отдаются вниз через [LocalContentColor] и
 * [ProvideTextStyle]: вложенный контент (текст, иконки вложений, статусы) не
 * обязан знать направление сообщения.
 */
@Composable
fun AegisHudBubble(
    outgoing: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    val shape = if (outgoing) AegisOutgoingBubbleShape else AegisIncomingBubbleShape
    val fill = if (outgoing) colors.outgoingBubble else colors.incomingBubble
    val border = if (outgoing) colors.outgoingBubbleBorder else colors.incomingBubbleBorder
    val onBubble = if (outgoing) colors.onOutgoingBubble else colors.onIncomingBubble

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides onBubble) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = onBubble)) {
                content()
            }
        }
    }
}
