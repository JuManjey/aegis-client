package com.aegis.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudGlyph
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisScanlines
import com.aegis.messenger.ui.navigation.TopLevelDestination

/**
 * Нижняя навигация главного экрана (§13): четыре HUD-вкладки без подписей.
 *
 * Не используем Material3 `NavigationBarItem` — его скруглённый индикатор-таблетка
 * ломает эстетику Cyberpunk 2077. Вместо этого: циановая кромка сверху (ярче на
 * активной вкладке), угловые скобки вокруг глифа, тонкие вертикальные разделители.
 *
 * Инсеты системной навигации те же, что у Material `NavigationBar`
 * ([NavigationBarDefaults.windowInsets]) — жесты/кнопки Android не перекрывают
 * цели. Каждая ячейка — `Role.Tab` + `contentDescription` из [TopLevelDestination.labelRes].
 */
@Composable
fun AegisBottomBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column {
            // Маджентовая кромка — тот же нижний край HUD, что над панелью ввода
            // в переписке (`docs/ui_hud.md`). Идёт ДО инсетов: линия должна лечь
            // по краю панели, а не отъехать вместе с иконками.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.ruleTail),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                    .height(HudBarHeight)
                    .selectableGroup()
                    .aegisScanlines(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val destinations = TopLevelDestination.entries
                destinations.forEachIndexed { index, dest ->
                    val selected = dest == current
                    HudNavCell(
                        dest = dest,
                        selected = selected,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                    if (index < destinations.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(colors.borderSubtle),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudNavCell(
    dest: TopLevelDestination,
    selected: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val chrome = colors.securityAccent
    // TalkBack получает ПОЛНОЕ имя раздела, под глифом стоит короткое: озвучивать
    // сокращение вместо «Мой профиль» смысла нет.
    val sectionName = stringResource(dest.labelRes)
    val tabLabel = stringResource(dest.tabLabelRes)
    val interactionSource = remember(dest) { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = { onSelect(dest) },
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = ripple(
                    bounded = true,
                    color = chrome.copy(alpha = 0.28f),
                ),
            )
            // Подпись раздела теперь видна текстом, поэтому имя ставится через
            // `contentDescription` на ячейке, а не дублируется на глифе.
            .semantics { contentDescription = sectionName },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HudTopEdgeSlot),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (selected) HudTopEdgeSlot else 1.dp)
                    .background(if (selected) chrome else colors.borderSubtle),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Активная вкладка ещё и подсвечена заливкой: одних скобок мало,
                // когда рядом стоят четыре одинаково обведённых ячейки.
                .then(
                    if (selected) {
                        Modifier.background(chrome.copy(alpha = SelectedCellAlpha))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AegisHudGlyph(
                icon = dest.icon,
                selected = selected,
                label = tabLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Высота ряда вкладок; системные инсеты добавляются отдельно и не сжимают цели.
 * Выросла с 64dp, чтобы под глифом уместилась подпись раздела и текст не
 * прижимался к рамке-скобкам.
 */
private val HudBarHeight = 72.dp

private val HudTopEdgeSlot = 2.dp

/** Заливка активной ячейки: заметно, но текст и глиф остаются контрастными. */
private const val SelectedCellAlpha = 0.10f
