package com.aegis.core.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisRowShape
import com.aegis.core.ui.theme.AegisTheme

/**
 * Строка списка в стиле телефонного меню референса: слева «ручка» из трёх
 * штрихов (`≡`), затем контент, справа — служебный слот (время, бейджи).
 *
 * Выбранная строка ИНВЕРТИРУЕТСЯ: заливка бледным цианом со срезанным правым
 * краем, контент — тёмный. Инверсия отдаётся вниз через [LocalContentColor],
 * поэтому вложенные `Text`/`Icon` без явного цвета подхватывают её сами.
 *
 * Клик здесь НЕ обрабатывается: жесты навешивает вызывающий экран (ему нужны и
 * `onClick`, и `onLongClick`, и своя семантика), иначе цель касания
 * дублировалась бы.
 */
@Composable
fun AegisHudRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    handle: Boolean = true,
    minHeight: Dp = 48.dp,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = AegisTheme.extendedColors
    val contentColor = if (selected) colors.onSelectionFill else LocalContentColor.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clip(AegisRowShape)
            .background(if (selected) colors.selectionFill else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (handle) {
            AegisHudHandle(color = if (selected) colors.onSelectionFill else colors.securityAccent)
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            trailing?.invoke(this@Row)
        }
    }
}

/**
 * «Ручка» строки — три коротких штриха. Нарисована, а не набрана символом `≡`:
 * начертание глифа зависит от системного шрифта, а толщина штрихов должна
 * совпадать с остальным HUD-хромом.
 */
@Composable
fun AegisHudHandle(
    color: Color,
    modifier: Modifier = Modifier,
    width: Dp = 10.dp,
    height: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .drawBehind {
                val stroke = 1.2.dp.toPx()
                val gap = (size.height - stroke * HANDLE_LINES) / (HANDLE_LINES + 1)
                repeat(HANDLE_LINES) { i ->
                    val y = gap * (i + 1) + stroke * i + stroke / 2f
                    drawLine(
                        color = color,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Butt,
                    )
                }
            },
    )
}

private const val HANDLE_LINES = 3

/**
 * Вертикальная рейка-скроллбар справа от списка (как у списка контактов в
 * референсе): тонкий трек на всю высоту и бегунок пропорционально видимой части.
 *
 * Состояние списка читается ВНУТРИ лямбды рисования, а не в композиции: при
 * прокрутке перерисовывается только рейка, рекомпозиции нет вообще. Если весь
 * список виден целиком, бегунок не рисуется.
 */
@Composable
fun AegisHudScrollRail(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    width: Dp = 2.dp,
) {
    val colors = AegisTheme.extendedColors
    val track = colors.railTrack
    val thumb = colors.railThumb
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .drawBehind {
                drawRect(color = track)
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val visible = info.visibleItemsInfo.size
                if (total <= 0 || visible <= 0 || visible >= total) return@drawBehind

                val thumbFraction = (visible.toFloat() / total).coerceIn(MIN_THUMB, 1f)
                // Прокручиваемых позиций ровно `total - visible`; иначе бегунок
                // не доезжал бы до низа на коротких списках.
                val scrollable = (total - visible).toFloat()
                val progress = (listState.firstVisibleItemIndex / scrollable).coerceIn(0f, 1f)
                val thumbHeight = size.height * thumbFraction
                val top = (size.height - thumbHeight) * progress
                drawRect(
                    color = thumb,
                    topLeft = Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                )
            },
    )
}

private const val MIN_THUMB = 0.08f
