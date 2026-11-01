package com.aegis.core.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min

/**
 * Общая геометрия HUD-хрома: угловые скобки «ломаного прямоугольника».
 *
 * Один примитив на весь дизайн-систем: им рисуются и рамки панелей
 * ([Modifier.aegisHudFrame][aegisHudFrame]), и обводка активной вкладки нижнего
 * меню ([com.aegis.core.ui.hud.aegisHudBezel]). Раньше эта разметка жила копией
 * в двух местах.
 */
internal fun DrawScope.drawCornerBrackets(
    color: Color,
    stroke: Float,
    arm: Float,
    inset: Float,
) {
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    // Скобки не должны сомкнуться в сплошную рамку на маленьких элементах.
    val maxArm = (min(size.width, size.height) / 2f - inset).coerceAtLeast(0f)
    val a = arm.coerceIn(0f, maxArm)
    if (a <= 0f) return
    val cap = StrokeCap.Butt

    drawLine(color, Offset(left, top), Offset(left + a, top), stroke, cap)
    drawLine(color, Offset(left, top), Offset(left, top + a), stroke, cap)

    drawLine(color, Offset(right - a, top), Offset(right, top), stroke, cap)
    drawLine(color, Offset(right, top), Offset(right, top + a), stroke, cap)

    drawLine(color, Offset(left, bottom), Offset(left + a, bottom), stroke, cap)
    drawLine(color, Offset(left, bottom - a), Offset(left, bottom), stroke, cap)

    drawLine(color, Offset(right - a, bottom), Offset(right, bottom), stroke, cap)
    drawLine(color, Offset(right, bottom - a), Offset(right, bottom), stroke, cap)
}
