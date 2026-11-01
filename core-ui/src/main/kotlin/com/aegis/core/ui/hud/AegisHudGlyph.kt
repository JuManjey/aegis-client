package com.aegis.core.ui.hud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.drawCornerBrackets
import kotlin.math.min

/**
 * Глиф нижней навигации в HUD-рамке: угловые скобки, внутренние уголки у
 * активной вкладки, неоновое свечение обводки (без blur/elevation — дёшево
 * и не размывает иконку) и прописная моноширинная подпись под глифом.
 *
 * Цвета только из [AegisTheme.extendedColors]: активный хром — `securityAccent`
 * (циан), неактивный — `textSecondary`. HEX на экранах не хардкодится.
 *
 * [label] — имя раздела. Подписи не декорация: в референсе нижние строки всегда
 * подписаны (`CALL`, `OPEN MESSAGES`, `CLOSE`), а четыре безымянных контурных
 * глифа приходилось нарочно разводить по геометрии, чтобы «Контакты» и «Мой
 * профиль» не сходились в одну фигуру — то есть сами иконки неоднозначны.
 * `null` возвращает прежний вид без подписи.
 *
 * Когда подпись есть, «рейка» штрихов под глифом выключается: её роль служебной
 * насечки берёт на себя текст, а вместе они спорят за одно место.
 *
 * [contentDescription] не задаётся здесь: его вешает кликабельная ячейка
 * панели, чтобы TalkBack не читал имя дважды.
 */
@Composable
fun AegisHudGlyph(
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = AegisTheme.extendedColors
    val chrome = colors.securityAccent
    val tint = if (selected) chrome else colors.textSecondary
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = HudGlyphSize, minHeight = HudGlyphSize)
            .aegisHudBezel(
                color = chrome,
                selected = selected,
                glow = selected && colors.scanlinesEnabled,
                ticks = label == null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(HudIconSize),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(HudIconSize),
                )
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/** Минимальный квадрат рамки: не меньше цели касания 48 dp. Ячейка меню может растянуть панель шире. */
val HudGlyphSize: Dp = 48.dp

private val HudIconSize: Dp = 22.dp

/**
 * Рамка-скобки вокруг контента. На активном состоянии рисуется второй,
 * внутренний комплект уголков и короткий разрыв по серединам сторон —
 * «ломаный прямоугольник» из игрового HUD, а не сплошной Material-прямоугольник.
 *
 * [ticks] — «рейка» из трёх штрихов под контентом. Она осмысленна для вкладок
 * нижнего меню (высота 48dp+), но на мелких элементах — чипах статуса, кнопках-
 * иконках, аватарах — накладывалась бы на текст и иконку, поэтому там её
 * выключают.
 */
fun Modifier.aegisHudBezel(
    color: Color,
    selected: Boolean,
    glow: Boolean = false,
    stroke: Dp = 1.dp,
    arm: Dp = if (selected) 10.dp else 5.dp,
    ticks: Boolean = true,
): Modifier = drawWithContent {
    drawContent()
    val strokePx = stroke.toPx().coerceAtLeast(1f)
    val inset = strokePx
    val maxArm = min(size.width, size.height) / 2f - inset - strokePx
    val armPx = arm.toPx().coerceIn(2f, maxArm)
    val frameColor = if (selected) color else color.copy(alpha = 0.28f)

    if (glow) {
        drawCornerBrackets(
            color = color.copy(alpha = 0.22f),
            stroke = strokePx * 3.2f,
            arm = armPx + 2.dp.toPx(),
            inset = inset,
        )
    }
    drawCornerBrackets(
        color = frameColor,
        stroke = strokePx,
        arm = armPx,
        inset = inset,
    )

    if (selected) {
        val innerInset = 6.dp.toPx()
        val innerArm = 4.5.dp.toPx().coerceAtMost(
            min(size.width, size.height) / 2f - innerInset - strokePx,
        )
        drawCornerBrackets(
            color = color.copy(alpha = 0.85f),
            stroke = strokePx,
            arm = innerArm,
            inset = innerInset,
        )
        val mid = 3.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawLine(frameColor, Offset(cx - mid, inset), Offset(cx + mid, inset), strokePx, StrokeCap.Butt)
        drawLine(
            frameColor,
            Offset(cx - mid, size.height - inset),
            Offset(cx + mid, size.height - inset),
            strokePx,
            StrokeCap.Butt,
        )
        drawLine(frameColor, Offset(inset, cy - mid), Offset(inset, cy + mid), strokePx, StrokeCap.Butt)
        drawLine(
            frameColor,
            Offset(size.width - inset, cy - mid),
            Offset(size.width - inset, cy + mid),
            strokePx,
            StrokeCap.Butt,
        )
    }

    if (ticks) {
        drawTickRail(
            color = if (selected) color else color.copy(alpha = 0.4f),
            selected = selected,
            stroke = strokePx,
        )
    }
}

private fun DrawScope.drawTickRail(
    color: Color,
    selected: Boolean,
    stroke: Float,
) {
    val segs = 3
    val railWidth = 14.dp.toPx()
    val gap = 2.dp.toPx()
    val segW = (railWidth - gap * (segs - 1)) / segs
    val startX = (size.width - railWidth) / 2f
    val y = size.height - 5.dp.toPx()
    repeat(segs) { i ->
        val alpha = when {
            selected -> 1f
            i == 1 -> 0.7f
            else -> 0.35f
        }
        val x = startX + i * (segW + gap)
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(x, y),
            end = Offset(x + segW, y),
            strokeWidth = stroke,
            cap = StrokeCap.Butt,
        )
    }
}
