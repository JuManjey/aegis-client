package com.aegis.core.ui.hud

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import com.aegis.core.ui.theme.AegisTheme

/**
 * Дорожка голосового сообщения в раскладке «монтажного стола» референса
 * (`docs/ui_hud.md` §5.9): несколько ТОНКИХ горизонтальных дорожек друг под
 * другом, штрихованные блоки громкости, циановая рейка прогресса, вертикальный
 * курсор воспроизведения и моноширинный таймкод в рамке под ним.
 *
 * ПОЧЕМУ НЕ «СТОЛБИКИ КАК В МЕССЕНДЖЕРАХ». Привычная гистограмма из скруглённых
 * столбиков противоречит §13 (рубленая геометрия) и выглядит чужеродно рядом с
 * бабблами со срезанными углами. Раскладка референса даёт ту же информацию
 * (громкость по времени + позиция), но родным для интерфейса языком: дорожки,
 * штриховка, засечки, машинный таймкод.
 *
 * ПОЧЕМУ ВСЁ В ОДНОМ [Canvas] И ЧЕРЕЗ ЛЯМБДЫ. [progress] и [timecode] — функции,
 * а не значения: они читаются ВНУТРИ лямбды рисования, поэтому смещение курсора
 * 10+ раз в секунду перерисовывает дорожку, но НЕ рекомпозирует пузырь и тем
 * более список сообщений (тот же приём, что в `AegisHudScrollRail`). Если
 * передать `Float`, каждый кадр воспроизведения рекомпозировал бы элемент
 * `LazyColumn`.
 *
 * @param levels громкость по отрезкам записи, 0..255 (см.
 *   `VoiceWaveform`); длина задаёт число блоков.
 * @param progress текущая позиция 0f..1f, читается при каждой отрисовке.
 * @param timecode подпись под курсором (`MM:SS`), читается при каждой отрисовке.
 * @param accent цвет непройденной части дорожки.
 * @param progressAccent цвет пройденной части и рейки прогресса.
 * @param onSeek если задан — тап и горизонтальное перетаскивание отдают позицию
 *   0f..1f (перемотка). `null` делает дорожку неинтерактивной.
 */
@Composable
fun AegisHudWaveform(
    levels: IntArray,
    progress: () -> Float,
    timecode: () -> String,
    modifier: Modifier = Modifier,
    accent: Color = AegisTheme.extendedColors.accentPrimary,
    progressAccent: Color = AegisTheme.extendedColors.securityAccent,
    railColor: Color = AegisTheme.extendedColors.borderSubtle,
    height: Dp = DefaultHeight,
    onSeek: ((Float) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val codeStyle = MaterialTheme.typography.labelSmall
    // Перемотка ловится ОДНИМ pointerInput: отдельные модификаторы на тап и
    // перетаскивание конкурировали бы за жест, и тап по дорожке иногда уходил
    // бы в combinedClickable баббла (то есть в «инфо о сообщении»).
    val seekModifier = if (onSeek == null) {
        Modifier
    } else {
        Modifier.pointerInput(onSeek) {
            val report = { x: Float -> onSeek((x / size.width.toFloat()).coerceIn(0f, 1f)) }
            detectTapGestures(onTap = { report(it.x) })
        }.pointerInput(onSeek) {
            val report = { x: Float -> onSeek((x / size.width.toFloat()).coerceIn(0f, 1f)) }
            detectHorizontalDragGestures(
                onDragStart = { report(it.x) },
                onHorizontalDrag = { change, _ -> report(change.position.x) },
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(seekModifier)
            // Дорожка — визуализация; озвучивать 56 уровней громкости нечем и
            // незачем. Доступное описание несёт кнопка воспроизведения рядом.
            .clearAndSetSemantics {},
    ) {
        drawTimeline(
            levels = levels,
            progress = progress().coerceIn(0f, 1f),
            timecode = timecode(),
            accent = accent,
            progressAccent = progressAccent,
            railColor = railColor,
            measurer = measurer,
            codeStyle = codeStyle,
            showCursor = true,
        )
    }
}

/**
 * Живая дорожка ИДУЩЕЙ записи: те же дорожки и штриховка, но без курсора и
 * перемотки — позиции в незаконченной записи нет. Новые измерения приходят
 * справа, [levels] — скользящее окно последних значений.
 *
 * Отдельный композабл, а не флаг у [AegisHudWaveform]: у записи нет ни
 * прогресса, ни перемотки, и внутри [AegisHudWaveform] это дало бы три
 * взаимоисключающих параметра, половина из которых игнорируется.
 */
@Composable
fun AegisHudWaveformLive(
    levels: IntArray,
    timecode: () -> String,
    modifier: Modifier = Modifier,
    accent: Color = AegisTheme.extendedColors.danger,
    railColor: Color = AegisTheme.extendedColors.borderSubtle,
    height: Dp = DefaultHeight,
) {
    val measurer = rememberTextMeasurer()
    val codeStyle = MaterialTheme.typography.labelSmall
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics {},
    ) {
        drawTimeline(
            levels = levels,
            // Запись «полностью пройдена»: все блоки уже прозвучали, поэтому
            // красятся активным цветом, а не приглушённым.
            progress = 1f,
            timecode = timecode(),
            accent = accent,
            progressAccent = accent,
            railColor = railColor,
            measurer = measurer,
            codeStyle = codeStyle,
            showCursor = false,
        )
    }
}

/** Высота дорожки по умолчанию: три полосы + строка таймкода под ними. */
private val DefaultHeight: Dp = 46.dp

// --- Рисование --------------------------------------------------------------

/**
 * Полная раскладка монтажного стола сверху вниз:
 *
 * ```
 *  ▏▏                    ← засечка-каретка над курсором
 * ══▓▓▒▒░░══════════════  ← дорожка громкости: штрихованные блоки
 * ──────────────────────  ← базовая рейка
 * ━━━━━━━━━┄┄┄┄┄┄┄┄┄┄┄┄  ← рейка прогресса (сплошная до курсора)
 *      ┌──────┐
 *      │ 00:20│           ← таймкод в рамке со срезом
 *      └──────┘
 * ```
 */
@Suppress("LongParameterList")
private fun DrawScope.drawTimeline(
    levels: IntArray,
    progress: Float,
    timecode: String,
    accent: Color,
    progressAccent: Color,
    railColor: Color,
    measurer: TextMeasurer,
    codeStyle: TextStyle,
    showCursor: Boolean,
) {
    val hairline = 1.dp.toPx()
    val chipBlock = 3.dp.toPx()
    // Слева — два микро-чипа маркировки дорожки (в референсе там же стоят
    // квадратные индикаторы слоёв). Они занимают полосу, поэтому дорожки
    // начинаются после неё, иначе блоки громкости налезали бы на чипы.
    val contentLeft = chipBlock * 2 + 4.dp.toPx()
    val contentWidth = (size.width - contentLeft).coerceAtLeast(1f)

    val caretBottom = 6.dp.toPx()
    val energyTop = caretBottom + 2.dp.toPx()
    val energyHeight = 14.dp.toPx()
    val energyCenter = energyTop + energyHeight / 2f
    val railY = energyTop + energyHeight + 4.dp.toPx()
    val progressY = railY + 5.dp.toPx()
    val cursorX = contentLeft + contentWidth * progress

    // 1. Микро-чипы слева: активный (циан/акцент) и приглушённый.
    drawRect(
        color = progressAccent,
        topLeft = Offset(0f, energyCenter - chipBlock),
        size = Size(chipBlock, chipBlock),
    )
    drawRect(
        color = railColor,
        topLeft = Offset(0f, energyCenter + chipBlock * 0.4f),
        size = Size(chipBlock, chipBlock),
    )

    // 2. Базовая линия дорожки громкости — она видна в паузах записи, поэтому
    // дорожка читается как непрерывная лента, а не как рассыпанные блоки.
    drawLine(
        color = railColor,
        start = Offset(contentLeft, energyCenter),
        end = Offset(size.width, energyCenter),
        strokeWidth = hairline,
        cap = StrokeCap.Butt,
    )

    // 3. Блоки громкости со штриховкой.
    if (levels.isNotEmpty()) {
        val slot = contentWidth / levels.size
        val gap = (slot * 0.28f).coerceIn(0.5f, 2.dp.toPx())
        val blockWidth = (slot - gap).coerceAtLeast(0.7f)
        val minHeight = 1.5.dp.toPx()
        for (i in levels.indices) {
            val level = levels[i].coerceIn(0, MaxLevel) / MaxLevel.toFloat()
            val h = (minHeight + (energyHeight - minHeight) * level).coerceAtMost(energyHeight)
            val left = contentLeft + slot * i
            val rect = Rect(
                left = left,
                top = energyCenter - h / 2f,
                right = left + blockWidth,
                bottom = energyCenter + h / 2f,
            )
            // Пройденная часть — активным цветом. Сравнение по ЦЕНТРУ блока:
            // по левому краю блок под курсором перекрашивался бы раньше, чем
            // курсор его пересечёт, и цвет обгонял бы звук.
            val passed = (left + blockWidth / 2f) <= cursorX
            val color = if (passed) progressAccent else accent
            // Штриховка нужна только там, где блок достаточно высок: в 2px
            // полосе диагональ вырождается в грязный пунктир.
            if (h >= 6.dp.toPx() && blockWidth >= 2.dp.toPx()) {
                drawHatchedBlock(rect, color, hairline)
            } else {
                drawRect(
                    color = color.copy(alpha = 0.85f),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                )
            }
        }
    }

    // 4. Базовая рейка под дорожкой (тёмная линия референса).
    drawLine(
        color = railColor,
        start = Offset(contentLeft, railY),
        end = Offset(size.width, railY),
        strokeWidth = hairline,
        cap = StrokeCap.Butt,
    )

    // 5. Рейка прогресса: сплошная до курсора, пунктир после — так остаток
    // читается как «ещё не проигранное», а не как вторая дорожка.
    drawLine(
        color = progressAccent,
        start = Offset(contentLeft, progressY),
        end = Offset(cursorX.coerceAtLeast(contentLeft), progressY),
        strokeWidth = hairline * 2f,
        cap = StrokeCap.Butt,
    )
    if (cursorX < size.width) {
        drawLine(
            color = railColor,
            start = Offset(cursorX, progressY),
            end = Offset(size.width, progressY),
            strokeWidth = hairline,
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(2.dp.toPx(), 2.dp.toPx()),
                0f,
            ),
        )
    }

    if (!showCursor) return

    // 6. Курсор: каретка сверху + вертикальная линия через все дорожки.
    drawLine(
        color = accent,
        start = Offset(cursorX, 0f),
        end = Offset(cursorX, caretBottom),
        strokeWidth = hairline * 2f,
        cap = StrokeCap.Butt,
    )
    drawLine(
        color = accent,
        start = Offset(cursorX, energyTop - 1.dp.toPx()),
        end = Offset(cursorX, progressY + 2.dp.toPx()),
        strokeWidth = hairline,
        cap = StrokeCap.Butt,
    )

    // 7. Таймкод в рамке со срезанным углом — «машинная» подпись из референса.
    drawTimecodeChip(
        text = timecode,
        cursorX = cursorX,
        top = progressY + 5.dp.toPx(),
        accent = accent,
        measurer = measurer,
        style = codeStyle,
        hairline = hairline,
    )
}

/**
 * Блок громкости, залитый диагональной штриховкой 45° и обведённый контуром.
 *
 * Штрихи рисуются за пределами блока и обрезаются [clipRect]: иначе для каждой
 * диагонали пришлось бы считать пересечения с прямоугольником — те же линии,
 * но с арифметикой на каждый из ~56 блоков каждого пузыря.
 */
private fun DrawScope.drawHatchedBlock(rect: Rect, color: Color, hairline: Float) {
    clipRect(left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom) {
        drawRect(
            color = color.copy(alpha = 0.16f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
        val spacing = 3.dp.toPx()
        val span = rect.height + rect.width
        var offset = 0f
        while (offset <= span) {
            drawLine(
                color = color,
                start = Offset(rect.left + offset, rect.bottom),
                end = Offset(rect.left + offset - rect.height, rect.top),
                strokeWidth = hairline,
                cap = StrokeCap.Butt,
            )
            offset += spacing
        }
    }
    drawRect(
        color = color,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(width = hairline),
    )
}

/**
 * Таймкод под курсором: рамка со срезанным верхне-правым углом (форма HUD-чипа)
 * и моноширинная подпись внутри.
 *
 * Рамка «упирается» в края: у самого начала и конца записи чип иначе уезжал бы
 * за пределы пузыря и обрезался — а это единственное место, где показано время.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawTimecodeChip(
    text: String,
    cursorX: Float,
    top: Float,
    accent: Color,
    measurer: TextMeasurer,
    style: TextStyle,
    hairline: Float,
) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style.copy(color = accent))
    val padH = 3.dp.toPx()
    val padV = 1.dp.toPx()
    val boxWidth = layout.size.width + padH * 2
    val boxHeight = layout.size.height + padV * 2
    if (top + boxHeight > size.height) return
    val left = (cursorX - boxWidth / 2f).coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    val cut = 4.dp.toPx().coerceAtMost(boxHeight / 2f)

    drawRect(
        color = accent.copy(alpha = 0.10f),
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
    )
    // Контур со срезом правого верхнего угла — четырьмя отрезками, чтобы не
    // собирать Path на каждый кадр воспроизведения.
    val right = left + boxWidth
    val bottom = top + boxHeight
    drawLine(accent, Offset(left, top), Offset(right - cut, top), hairline, StrokeCap.Butt)
    drawLine(accent, Offset(right - cut, top), Offset(right, top + cut), hairline, StrokeCap.Butt)
    drawLine(accent, Offset(right, top + cut), Offset(right, bottom), hairline, StrokeCap.Butt)
    drawLine(accent, Offset(right, bottom), Offset(left, bottom), hairline, StrokeCap.Butt)
    drawLine(accent, Offset(left, bottom), Offset(left, top), hairline, StrokeCap.Butt)

    drawText(
        textLayoutResult = layout,
        topLeft = Offset(left + padH, top + padV),
    )
}

/** Верхняя граница шкалы уровня (совпадает с `VoiceWaveform.MAX_LEVEL`). */
private const val MaxLevel: Int = 255

/**
 * Форматирует позицию/длительность как `MM:SS` (а при длине от часа — `H:MM:SS`).
 *
 * Живёт в `:core-ui` рядом с дорожкой, потому что это часть ЕЁ визуального
 * языка: машинный таймкод референса, а не общий формат времени приложения.
 * Используется и пузырём голосового, и панелью записи — иначе подписи в них
 * разъехались бы (`0:07` против `00:07`).
 */
fun aegisFormatTimecode(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 500) / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${pad2(minutes)}:${pad2(seconds)}"
    } else {
        "${pad2(minutes)}:${pad2(seconds)}"
    }
}

private fun pad2(value: Long): String = if (value < 10) "0$value" else value.toString()
