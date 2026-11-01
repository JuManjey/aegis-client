package com.aegis.core.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisClippedCornerShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisGlow

/**
 * Главная кнопка HUD: жёлтый акцент, срезанный угол, неоновое свечение и
 * прописной моноширинный лейбл (`docs/ui_hud.md`).
 */
@Composable
fun AegisHudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val colors = AegisTheme.extendedColors
    val container = if (danger) colors.danger else colors.accentPrimary
    val content = if (danger) colors.onDanger else MaterialTheme.colorScheme.onPrimary
    Button(
        onClick = onClick,
        modifier = modifier.then(
            if (enabled) Modifier.aegisGlow(container, AegisClippedCornerShape) else Modifier,
        ),
        enabled = enabled,
        shape = AegisClippedCornerShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp),
        )
    }
}

/** Вторичная кнопка HUD: только рамка акцента, без заливки и свечения. */
@Composable
fun AegisHudOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val colors = AegisTheme.extendedColors
    val accent = if (danger) colors.danger else colors.securityAccent
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AegisChipShape,
        border = BorderStroke(1.dp, if (enabled) accent else colors.borderSubtle),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
        )
    }
}

/**
 * Квадратная HUD-кнопка-иконка со скобками по углам (кнопка отправки, действия
 * в шапке). [filled] делает её главной: заливка акцентом + свечение.
 */
@Composable
fun AegisHudIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    accent: Color = AegisTheme.extendedColors.securityAccent,
    size: Dp = 40.dp,
) {
    val colors = AegisTheme.extendedColors
    val interaction = remember { MutableInteractionSource() }
    val tint = when {
        !enabled -> colors.textSecondary
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> accent
    }
    Box(
        modifier = modifier
            .size(size)
            .then(if (filled && enabled) Modifier.aegisGlow(accent, AegisClippedCornerShape) else Modifier)
            .clip(AegisClippedCornerShape)
            .then(if (filled) Modifier.background(if (enabled) accent else colors.surfaceElevated) else Modifier)
            .aegisHudBezel(color = accent, selected = enabled && !filled, glow = false, ticks = false)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(bounded = true, color = accent.copy(alpha = 0.28f)),
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * ICON_RATIO),
        )
    }
}

private const val ICON_RATIO = 0.5f

/**
 * Небольшой HUD-чип: рамка акцента, прописной моноширинный текст, опциональная
 * иконка. Используется для статусов (Tor, E2EE) и бейджей.
 */
@Composable
fun AegisHudChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = AegisTheme.extendedColors.securityAccent,
    filled: Boolean = false,
    contentDescription: String? = null,
    /**
     * Размер глифа. Отдельный параметр нужен индикатору Tor: он стоит в шапке
     * каждого экрана и читается издалека, тогда как чипы внутри карточек —
     * подписи рядом с текстом, и там глиф должен быть по высоте строки.
     */
    iconSize: Dp = 13.dp,
) {
    val onFilled = AegisTheme.extendedColors.onSelectionFill
    Row(
        modifier = modifier
            .clip(AegisChipShape)
            .then(if (filled) Modifier.background(accent) else Modifier)
            .then(
                if (filled) {
                    Modifier
                } else {
                    Modifier.aegisHudBezel(color = accent, selected = true, glow = false, ticks = false)
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val content = if (filled) onFilled else accent
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(iconSize),
            )
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * Поле ввода HUD: рамка акцента, без Material-«плавающего» лейбла.
 *
 * Под капотом — Material3 [OutlinedTextField], а не самодельный `BasicTextField`:
 * он уже умеет IME-действия, выделение, автозаполнение и семантику для TalkBack.
 * Переопределяются только цвета и форма.
 */
@Composable
fun AegisHudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    accent: Color = AegisTheme.extendedColors.accentPrimary,
    maxLines: Int = 1,
    singleLine: Boolean = maxLines == 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = placeholder?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AegisTheme.extendedColors.textSecondary,
                )
            }
        },
        shape = AegisChipShape,
        maxLines = maxLines,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = aegisHudTextFieldColors(accent),
    )
}

/**
 * Цвета HUD для любого [OutlinedTextField] (в том числе с [TextFieldValue] и
 * своим `decorationBox`), чтобы экраны не собирали палитру полей руками.
 */
@Composable
fun aegisHudTextFieldColors(
    accent: Color = AegisTheme.extendedColors.accentPrimary,
): TextFieldColors {
    val colors = AegisTheme.extendedColors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedBorderColor = accent,
        unfocusedBorderColor = colors.borderSubtle,
        disabledBorderColor = colors.borderSubtle,
        errorBorderColor = colors.danger,
        cursorColor = accent,
        focusedContainerColor = colors.surfaceElevated,
        unfocusedContainerColor = colors.surfaceElevated,
        disabledContainerColor = colors.surfaceElevated,
        focusedPlaceholderColor = colors.textSecondary,
        unfocusedPlaceholderColor = colors.textSecondary,
    )
}

/**
 * Инициалы для [AegisHudAvatar]: до двух первых букв слов имени.
 *
 * Разбор идёт по кодовым ТОЧКАМ, а не по `Char`: `first().uppercase()` на имени,
 * начинающемся с эмодзи или другого символа вне BMP, вернул бы половину
 * surrogate-пары и в аватаре появился бы «?».
 *
 * Живёт рядом с самим аватаром, а не в экранах: строки чатов и строки контактов
 * рисуют один и тот же аватар, и расхождение в правилах разбора имени сразу
 * читалось бы как «это разные люди».
 */
fun aegisHudInitials(name: String): String = name.trim()
    .split(INITIALS_SEPARATOR)
    .filter { it.isNotEmpty() }
    .take(2)
    .joinToString("") { word ->
        String(Character.toChars(word.codePointAt(0))).uppercase()
    }
    .ifEmpty { "?" }

private val INITIALS_SEPARATOR = Regex("[\\s_]+")

/**
 * Квадратный аватар-«ячейка» HUD: рубленая рамка акцента и инициалы внутри.
 * Круглых аватаров в системе нет — они ломают рубленую геометрию §13.
 */
@Composable
fun AegisHudAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    accent: Color = AegisTheme.extendedColors.securityAccent,
    size: Dp = 42.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .aegisHudBezel(color = accent, selected = true, glow = false, ticks = false),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
        )
    }
}

/**
 * Чип-переключатель HUD: рубленая рамка, прописная моноширинная подпись, у
 * выбранного — заливка акцентом и тёмный текст (инверсия, как у выбранной
 * строки списка).
 *
 * Заменяет Material `FilterChip`: его скруглённая «таблетка» и галочка перед
 * текстом противоречат рубленой геометрии §13.
 *
 * Состояние передаётся И заливкой, И цветом текста — не только цветом, иначе
 * выбор неразличим при дальтонизме. Семантика — [Role.RadioButton] для выбора
 * одного значения из набора.
 */
@Composable
fun AegisHudToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    accent: Color = AegisTheme.extendedColors.securityAccent,
) {
    val colors = AegisTheme.extendedColors
    val frame = when {
        !enabled -> colors.borderSubtle
        selected -> accent
        else -> colors.textSecondary
    }
    val content = when {
        !enabled -> colors.textSecondary
        selected -> colors.onSelectionFill
        else -> MaterialTheme.colorScheme.onSurface
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(AegisChipShape)
            .then(if (selected && enabled) Modifier.background(accent) else Modifier)
            .aegisHudBezel(color = frame, selected = true, glow = false, ticks = false)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = ripple(bounded = true, color = accent.copy(alpha = 0.28f)),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

/**
 * Чип-ДЕЙСТВИЕ: выглядит как [AegisHudToggleChip], но это кнопка без состояния
 * выбора («заглушить на час», «пауза до утра»).
 *
 * Отдельный компонент нужен из-за семантики. У чипа-переключателя стоит
 * [Role.RadioButton] и `selectable`, поэтому TalkBack к каждому такому чипу
 * добавляет «не выбрано» — для одноразового действия это ложь: чип ничего не
 * выбирает, он запускает операцию. Здесь `clickable` + [Role.Button].
 */
@Composable
fun AegisHudActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    accent: Color = AegisTheme.extendedColors.securityAccent,
) {
    val colors = AegisTheme.extendedColors
    val frame = if (enabled) accent else colors.borderSubtle
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(AegisChipShape)
            .aegisHudBezel(color = frame, selected = true, glow = false, ticks = false)
            .clickable(
                enabled = enabled,
                onClick = onClick,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(bounded = true, color = accent.copy(alpha = 0.28f)),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

/**
 * Индикатор занятости HUD: тонкое кольцо в цвете акцента.
 *
 * Обёртка над Material [CircularProgressIndicator] — своя анимация здесь не
 * нужна, требуется только единая толщина и цвет по всему приложению вместо
 * россыпи `strokeWidth = 2.dp` по экранам.
 */
@Composable
fun AegisHudBusy(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    accent: Color = AegisTheme.extendedColors.securityAccent,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 2.dp,
        color = accent,
        trackColor = AegisTheme.extendedColors.railTrack,
    )
}

/** Отступ по умолчанию между HUD-элементами в списках и панелях. */
val AegisHudGap: Dp = 6.dp

/** Минимальная высота интерактивной строки (цель касания). */
val AegisHudTouchTarget: Dp = 48.dp
