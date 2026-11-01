package com.aegis.messenger.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.hud.aegisHudTextFieldColors
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme

/**
 * Поле ввода формы входа в HUD-раскладке: рубленая рамка со срезанным углом,
 * прописная моноширинная подпись и необязательная сноска под полем.
 *
 * Подпись остаётся в слоте `label` Material-поля, а не выносится отдельным
 * `Text` над ним: только так TalkBack связывает её с полем ввода.
 */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String?,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { FieldLabel(text = label, enabled = enabled) },
        supportingText = supporting?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AegisTheme.extendedColors.textSecondary,
                )
            }
        },
        enabled = enabled,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else PHRASE_MAX_LINES,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = AegisChipShape,
        colors = aegisHudTextFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Поле пароля с переключателем видимости. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { FieldLabel(text = label, enabled = enabled) },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            // Переключатель работает и на погашенном поле, поэтому цвет акцента
            // не гасится вместе с ним.
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = AegisTheme.extendedColors.securityAccent,
                )
            }
        },
        shape = AegisChipShape,
        colors = aegisHudTextFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Подпись поля: моноширинная прописная в цвете хрома. Явный `style` гасит
 * Material-анимацию размера «плавающего» лейбла — подпись выглядит одинаково и
 * внутри пустого поля, и поднятой над рамкой.
 */
@Composable
private fun FieldLabel(text: String, enabled: Boolean) {
    val colors = AegisTheme.extendedColors
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) colors.securityAccent else colors.textSecondary,
    )
}

/**
 * Строка с переключателем формы входа. Обёртка над [AegisHudSwitchRow]: свой
 * порядок аргументов сохранён, потому что её вызывают ещё экраны профиля и
 * переноса аккаунта.
 */
@Composable
fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AegisHudSwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
    )
}

/** Сколько строк показывает поле мнемоники до внутренней прокрутки (24 слова). */
private const val PHRASE_MAX_LINES = 6
