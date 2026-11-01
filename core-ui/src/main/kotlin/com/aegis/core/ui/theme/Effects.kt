package com.aegis.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Декоративные эффекты темы AEGIS (эстетика Cyberpunk 2077, раскладка —
 * `docs/ui_hud.md`). Держатся здесь, в core-ui, чтобы экраны не хардкодили
 * значения — только вызывали модификаторы.
 *
 * Все эффекты рисуются через [drawWithCache]: тяжёлые объекты (кисти, контуры
 * форм) строятся один раз на размер, а не на каждый кадр.
 */

/** Шаг CRT-линий по умолчанию. */
private val ScanlineStep = 3.dp

/** Шаг фоновой сетки HUD. */
private val GridStep = 28.dp

/**
 * Тонкие горизонтальные CRT scanline-линии поверх контента (как на экранах в
 * игре). Рисуются только если в теме включён флаг
 * [AegisExtendedColors.scanlinesEnabled]; иначе модификатор — no-op.
 *
 * Реализация — ОДНА заливка повторяющимся градиентом, а не цикл `drawLine` по
 * высоте экрана. На FullHD прежний цикл давал ~700 команд рисования на кадр
 * поверх всего контента; теперь это один `drawRect` с кэшированной кистью.
 */
@Composable
fun Modifier.aegisScanlines(
    lineColor: Color = Color.Black.copy(alpha = 0.06f),
    step: Dp = ScanlineStep,
): Modifier {
    if (!AegisTheme.extendedColors.scanlinesEnabled) return this
    val stepPx = with(LocalDensity.current) { step.toPx() }.coerceAtLeast(2f)
    val brush = remember(lineColor, stepPx) { stripeBrush(lineColor, stepPx, horizontal = false) }
    return drawWithCache {
        onDrawWithContent {
            drawContent()
            drawRect(brush = brush)
        }
    }
}

/**
 * Фон HUD-экрана: неоновая сетка + виньетка по краям + CRT-линии поверх
 * контента. Ставится ОДИН раз на контейнер экрана — вложенные вызовы дадут
 * двойное затемнение.
 *
 * Сетка и виньетка идут ПОД контентом, scanlines — ПОВЕРХ, поэтому это один
 * модификатор, а не три: так весь фон рисуется в одном слое рисования.
 */
@Composable
fun Modifier.aegisHudBackdrop(
    gridStep: Dp = GridStep,
    vignetteAlpha: Float = 0.55f,
): Modifier {
    val colors = AegisTheme.extendedColors
    val density = LocalDensity.current
    val gridPx = with(density) { gridStep.toPx() }.coerceAtLeast(8f)
    val scanPx = with(density) { ScanlineStep.toPx() }.coerceAtLeast(2f)
    val gridColor = colors.backdropGrid
    val scanlines = colors.scanlinesEnabled

    val vertical = remember(gridColor, gridPx) { stripeBrush(gridColor, gridPx, horizontal = false) }
    val horizontal = remember(gridColor, gridPx) { stripeBrush(gridColor, gridPx, horizontal = true) }
    val scanBrush = remember(scanPx, scanlines) {
        if (scanlines) stripeBrush(Color.Black.copy(alpha = 0.06f), scanPx, horizontal = false) else null
    }

    return drawWithCache {
        // Виньетка зависит от размера — строим её внутри кэша рисования.
        val vignette = Brush.radialGradient(
            0.55f to Color.Transparent,
            1.0f to Color.Black.copy(alpha = vignetteAlpha),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = maxOf(size.width, size.height) * 0.78f,
        )
        onDrawWithContent {
            drawRect(brush = vertical)
            drawRect(brush = horizontal)
            drawRect(brush = vignette)
            drawContent()
            scanBrush?.let { drawRect(brush = it) }
        }
    }
}

/**
 * Неоновое свечение (glow) для акцентных элементов: несколько обводок формы с
 * убывающей прозрачностью.
 *
 * Раньше здесь была `Modifier.shadow(ambientColor/spotColor)`. Цветные тени
 * Android поддерживает только с API 28, а `minSdk` проекта — 26: на Android 8
 * свечение выходило чёрным пятном. Отрисовка контура работает одинаково на всех
 * версиях и не поднимает отдельный render-node ради тени.
 */
fun Modifier.aegisGlow(
    color: Color,
    shape: Shape,
    radius: Dp = 8.dp,
    alpha: Float = 0.55f,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radiusPx = radius.toPx().coerceAtLeast(1f)
    onDrawBehind {
        // От внешнего слоя к внутреннему: широкий и почти прозрачный → узкий и яркий.
        repeat(GLOW_LAYERS) { layer ->
            val t = (GLOW_LAYERS - layer).toFloat() / GLOW_LAYERS
            drawOutline(
                outline = outline,
                color = color,
                alpha = alpha * (1f - t) * (1f - t),
                style = Stroke(width = radiusPx * t),
            )
        }
    }
}

private const val GLOW_LAYERS = 4

/**
 * Рамка HUD-панели: контур по форме плюс яркие угловые скобки — «вырезанная из
 * пластины» панель вместо Material-карточки с тенью.
 *
 * [cornerArm] в `0.dp` отключает скобки и оставляет только контур.
 */
fun Modifier.aegisHudFrame(
    color: Color,
    shape: Shape = AegisPanelShape,
    stroke: Dp = 1.dp,
    cornerColor: Color = color,
    cornerArm: Dp = 14.dp,
): Modifier = drawWithCache {
    val strokePx = stroke.toPx().coerceAtLeast(1f)
    val outline = shape.createOutline(size, layoutDirection, this)
    val armPx = cornerArm.toPx()
    onDrawWithContent {
        drawContent()
        drawOutline(outline = outline, color = color, style = Stroke(width = strokePx))
        if (armPx > 0f) {
            drawCornerBrackets(
                color = cornerColor,
                stroke = strokePx * 2f,
                arm = armPx,
                inset = strokePx,
            )
        }
    }
}

/**
 * Повторяющаяся полоса шириной 1px с шагом [stepPx]. Одна кисть на весь
 * эффект: жёсткие стопы дают резкий край, [TileMode.Repeated] размножает шаг
 * на всю область.
 */
private fun stripeBrush(color: Color, stepPx: Float, horizontal: Boolean): Brush {
    val edge = (1f / stepPx).coerceIn(0.02f, 0.5f)
    val stops = arrayOf(
        0f to color,
        edge to color,
        edge to Color.Transparent,
        1f to Color.Transparent,
    )
    return if (horizontal) {
        Brush.horizontalGradient(*stops, startX = 0f, endX = stepPx, tileMode = TileMode.Repeated)
    } else {
        Brush.verticalGradient(*stops, startY = 0f, endY = stepPx, tileMode = TileMode.Repeated)
    }
}
