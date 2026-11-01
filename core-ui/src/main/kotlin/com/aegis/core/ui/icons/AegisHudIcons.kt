package com.aegis.core.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Оригинальные HUD-глифы нижней навигации AEGIS.
 *
 * Стиль — тонкий контур, рубленые углы, без заливок: как интерфейс телефона
 * в Cyberpunk 2077 (вкладки «Сообщения / Файлы / Сеть»). Это НЕ ассеты игры
 * и не копии чужих SVG — собственная геометрия под разделы AEGIS.
 *
 * Цвет задаётся через `Icon(tint = …)` из токенов темы: в векторе обводка
 * чёрная, чтобы `ColorFilter.SrcIn` красился целиком. Заливки нет — иначе
 * tint залил бы фигуру и пропал «проволочный» вид.
 */
object AegisHudIcons {
    /** Чаты: угловой речевой кадр с хвостом и двумя линиями «данных». */
    val Chats: ImageVector by lazy {
        hudIcon(name = "AegisHud.Chats", autoMirror = true) {
            hudStroke {
                moveTo(6.2f, 4.6f)
                lineTo(17.8f, 4.6f)
                lineTo(20.2f, 7.0f)
                lineTo(20.2f, 13.2f)
                lineTo(17.8f, 15.6f)
                lineTo(10.6f, 15.6f)
                lineTo(7.0f, 20.4f)
                lineTo(8.4f, 15.6f)
                lineTo(6.2f, 15.6f)
                lineTo(3.8f, 13.2f)
                lineTo(3.8f, 7.0f)
                close()
            }
            hudStroke {
                moveTo(7.4f, 8.0f)
                horizontalLineTo(16.6f)
                moveTo(7.4f, 11.2f)
                horizontalLineTo(13.6f)
            }
        }
    }

    /**
     * Контакты: четыре узла, связанные в ромб — «граф людей», тот же приём,
     * что у сетевых вкладок в референсе, но своя сетка.
     */
    val Contacts: ImageVector by lazy {
        hudIcon(name = "AegisHud.Contacts") {
            hudStroke { nodeRect(left = 10.9f, top = 2.6f, size = 2.2f) }
            hudStroke { nodeRect(left = 2.6f, top = 10.9f, size = 2.2f) }
            hudStroke { nodeRect(left = 19.2f, top = 10.9f, size = 2.2f) }
            hudStroke { nodeRect(left = 10.9f, top = 19.2f, size = 2.2f) }
            hudStroke {
                moveTo(12.0f, 4.8f)
                lineTo(4.8f, 12.0f)
                moveTo(12.0f, 4.8f)
                lineTo(19.2f, 12.0f)
                moveTo(4.8f, 12.0f)
                lineTo(12.0f, 19.2f)
                moveTo(19.2f, 12.0f)
                lineTo(12.0f, 19.2f)
            }
        }
    }

    /** Настройки: двойная рубленая рамка и перекрестье — не шестерёнка Material. */
    val Settings: ImageVector by lazy {
        hudIcon(name = "AegisHud.Settings") {
            hudStroke { chamferedRect(4.0f, 4.0f, 20.0f, 20.0f, 2.6f) }
            hudStroke { chamferedRect(8.0f, 8.0f, 16.0f, 16.0f, 1.6f) }
            hudStroke {
                moveTo(12.0f, 10.2f)
                verticalLineTo(13.8f)
                moveTo(10.2f, 12.0f)
                horizontalLineTo(13.8f)
            }
        }
    }

    /**
     * Профиль: голова + трапеция корпуса и короткий ID-штрих. Без круглой
     * «учётной записи» Material — иначе на нижней панели без подписей
     * сходилась бы с «Контактами».
     */
    val Profile: ImageVector by lazy {
        hudIcon(name = "AegisHud.Profile") {
            hudStroke {
                moveTo(10.1f, 3.8f)
                lineTo(13.9f, 3.8f)
                lineTo(15.4f, 5.3f)
                lineTo(15.4f, 8.3f)
                lineTo(13.9f, 9.8f)
                lineTo(10.1f, 9.8f)
                lineTo(8.6f, 8.3f)
                lineTo(8.6f, 5.3f)
                close()
            }
            hudStroke {
                moveTo(12.0f, 9.8f)
                verticalLineTo(12.8f)
            }
            hudStroke {
                moveTo(6.4f, 20.4f)
                lineTo(8.6f, 13.4f)
                lineTo(15.4f, 13.4f)
                lineTo(17.6f, 20.4f)
            }
            hudStroke {
                moveTo(10.4f, 16.4f)
                horizontalLineTo(13.6f)
            }
        }
    }
}

private const val HUD_STROKE = 1.15f

private fun hudIcon(
    name: String,
    autoMirror: Boolean = false,
    builder: ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).apply(builder).build()

private fun ImageVector.Builder.hudStroke(pathBuilder: PathBuilder.() -> Unit) {
    path(
        fill = null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = HUD_STROKE,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        strokeLineMiter = 2f,
        pathBuilder = pathBuilder,
    )
}

private fun PathBuilder.nodeRect(left: Float, top: Float, size: Float) {
    moveTo(left, top)
    lineTo(left + size, top)
    lineTo(left + size, top + size)
    lineTo(left, top + size)
    close()
}

/** Прямоугольник со срезанными углами (рубленая геометрия §13). */
private fun PathBuilder.chamferedRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    chamfer: Float,
) {
    moveTo(left + chamfer, top)
    lineTo(right - chamfer, top)
    lineTo(right, top + chamfer)
    lineTo(right, bottom - chamfer)
    lineTo(right - chamfer, bottom)
    lineTo(left + chamfer, bottom)
    lineTo(left, bottom - chamfer)
    lineTo(left, top + chamfer)
    close()
}
