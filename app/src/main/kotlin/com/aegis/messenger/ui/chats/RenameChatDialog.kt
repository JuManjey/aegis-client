package com.aegis.messenger.ui.chats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.aegis.core.common.model.Contact
import com.aegis.core.ui.hud.AegisHudDialog
import com.aegis.core.ui.hud.aegisHudTextFieldColors
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Диалог локального переименования чата. Пустое поле сбрасывает имя к публичному
 * никнейму контакта. Ввод режется по [Contact.LOCAL_NAME_MAX_CHARS] сразу в поле,
 * чтобы пользователь видел лимит до сохранения.
 */
@Composable
fun RenameChatDialog(
    initial: String,
    originalNickname: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember {
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val save = {
        onConfirm(value.text)
    }

    AegisHudDialog(
        title = stringResource(R.string.chat_rename_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_save),
        onConfirm = save,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismissAction = onDismiss,
    ) {
        // Поле остаётся Material-овым: у него `TextFieldValue` с ручным
        // управлением выделением (обрезка по лимиту) и `supportingText`, чего
        // AegisHudTextField не покрывает. HUD-вид даёт палитра + форма.
        OutlinedTextField(
            value = value,
            onValueChange = { incoming ->
                val clipped = incoming.text.take(Contact.LOCAL_NAME_MAX_CHARS)
                value = incoming.copy(
                    text = clipped,
                    selection = TextRange(
                        incoming.selection.start.coerceIn(0, clipped.length),
                        incoming.selection.end.coerceIn(0, clipped.length),
                    ),
                )
            },
            singleLine = true,
            shape = AegisChipShape,
            colors = aegisHudTextFieldColors(),
            label = {
                Text(
                    text = stringResource(R.string.chat_rename_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            supportingText = {
                val hint = if (originalNickname.isBlank()) {
                    stringResource(R.string.chat_rename_hint_id)
                } else {
                    stringResource(R.string.chat_rename_hint, originalNickname)
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = AegisTheme.extendedColors.textSecondary,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}
