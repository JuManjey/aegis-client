package com.aegis.core.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Формы AEGIS в эстетике Cyberpunk 2077 (§13): рубленая геометрия, углы почти
 * прямые (максимум 2–4px), никаких «мягких пузырей».
 */
internal val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/**
 * Форма со срезанным углом — «обрезанные» панели/кнопки как в интерфейсе игры.
 * Использовать точечно для важных карточек/кнопок (например, кнопка отправки).
 */
val AegisClippedCornerShape = CutCornerShape(topEnd = 10.dp)

/**
 * HUD-панель раздела: срезаны диагонально противоположные углы. Так рамка
 * читается как «вырезанный из пластины» блок, а не как Material-карточка.
 */
val AegisPanelShape = CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp)

/**
 * Баббл ИСХОДЯЩЕГО сообщения: срез нижнего-правого угла — «хвост» реплики
 * уходит к своему краю ленты (референс: залитая реплика справа).
 */
val AegisOutgoingBubbleShape = CutCornerShape(bottomEnd = 12.dp)

/** Баббл ВХОДЯЩЕГО сообщения: зеркальный срез нижнего-левого угла. */
val AegisIncomingBubbleShape = CutCornerShape(bottomStart = 12.dp)

/** Чип HUD (индикатор Tor, бейджи, подсказки клавиш) — лёгкий срез справа. */
val AegisChipShape = CutCornerShape(topEnd = 6.dp)

/**
 * Строка списка: срез правого края, как у выделенной строки в телефонном
 * меню референса. Заметен только когда строка залита (выбрана).
 */
val AegisRowShape = CutCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
