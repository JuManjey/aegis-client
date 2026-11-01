package com.aegis.messenger.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.aegisHudTextFieldColors
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.contacts.ContactRequestManager
import com.aegis.messenger.contacts.DirectoryPublisher
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import kotlinx.coroutines.launch

/**
 * Экран добавления контакта (§14.1, `docs/contact_requests.md`), HUD-раскладка —
 * `docs/ui_hud.md`.
 *
 * ЧТО ЗДЕСЬ ВАЖНО ДЛЯ БЕЗОПАСНОСТИ:
 *  * при отказе проверки записи каталога показывается ЯВНАЯ ошибка «возможна
 *    подмена», а не мягкое «попробуйте позже» — иначе пользователь принял бы
 *    подмену за сетевой сбой;
 *  * рядом с удалённым добавлением всегда остаётся путь личного обмена (QR/NFC)
 *    и честно сказано, чем они отличаются;
 *  * предупреждение о перечислимости публичного ника показано ДО его занятия.
 *
 * Экран — панель раздела «Контакты», а не самостоятельный маршрут: фон, инсеты,
 * шапку приложения и переключатель разделов даёт хозяин вкладки
 * ([com.aegis.messenger.ui.contacts.ContactsScreen]), поэтому здесь только
 * содержимое — без второго `aegisHudBackdrop` и второго учёта инсетов
 * (см. `AegisHudScreen`).
 */
@Composable
fun AddContactScreen(
    mode: ContactRequestMode,
    myAddressProvider: suspend () -> String?,
    currentAlias: String,
    onSendRequest: suspend (String, String) -> ContactRequestManager.SendResult,
    onClaimAlias: suspend (String) -> DirectoryPublisher.AliasResult,
    onReleaseAlias: suspend () -> Unit,
    onRotateAddress: suspend () -> String?,
    onOpenInPerson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AegisTheme.extendedColors

    var query by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf(currentAlias) }
    var myAddress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<StatusMessage?>(null) }
    var aliasClaimed by remember { mutableStateOf(currentAlias) }

    LaunchedEffect(mode) {
        if (mode.enabled) myAddress = myAddressProvider()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // --- Личный обмен: всегда доступен и остаётся самым строгим путём ---
        AegisHudCard(
            title = stringResource(R.string.contact_add_in_person),
            icon = Icons.Outlined.QrCode2,
        ) {
            Text(
                text = stringResource(R.string.contact_add_in_person_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            AegisHudOutlinedButton(
                text = stringResource(R.string.contact_add_in_person),
                onClick = onOpenInPerson,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!mode.enabled) {
            AegisHudBanner(
                text = stringResource(R.string.contact_req_err_unavailable),
                tone = HudBannerTone.DANGER,
            )
            return@Column
        }

        // --- Удалённое добавление ---
        AegisHudCard(
            title = stringResource(R.string.contact_add_remote_title),
            icon = Icons.AutoMirrored.Filled.Send,
        ) {
            Text(
                text = stringResource(R.string.contact_add_remote_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; status = null },
                label = { Text(stringResource(R.string.contact_add_remote_hint)) },
                singleLine = false,
                maxLines = 3,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = AegisChipShape,
                colors = aegisHudTextFieldColors(),
            )
            OutlinedTextField(
                value = greeting,
                onValueChange = { greeting = it.take(GREETING_LIMIT) },
                label = { Text(stringResource(R.string.contact_add_greeting_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = AegisChipShape,
                colors = aegisHudTextFieldColors(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AegisHudButton(
                    text = stringResource(R.string.contact_add_send),
                    onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val result = onSendRequest(query.trim(), greeting.trim())
                            status = result.toStatus(context)
                            if (result is ContactRequestManager.SendResult.Sent) query = ""
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !busy && query.isNotBlank(),
                )
                // Индикатор рядом с кнопкой, а не внутри: у HUD-кнопки подпись
                // прописная и моноширинная, кольцо внутри ломало бы её ширину.
                if (busy) {
                    AegisHudBusy()
                }
            }
            status?.let { message ->
                AegisHudStatusLine(
                    text = message.text,
                    ok = !message.isError,
                    failColor = colors.danger,
                )
            }
        }

        // --- Мой адрес ---
        AegisHudCard(
            title = stringResource(R.string.contact_add_my_address),
            icon = Icons.Outlined.Link,
        ) {
            Text(
                text = myAddress ?: "…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contact_add_copy),
                    onClick = {
                        myAddress?.let {
                            copyToClipboard(context, it)
                            status = StatusMessage(
                                context.getString(R.string.contact_add_copied),
                                isError = false,
                            )
                        }
                    },
                    enabled = myAddress != null,
                )
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contact_add_rotate),
                    onClick = {
                        busy = true
                        scope.launch {
                            myAddress = onRotateAddress()
                            busy = false
                        }
                    },
                    enabled = !busy,
                )
            }
            Text(
                text = stringResource(R.string.contact_add_rotate_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }

        // --- Публичный ник: только когда режим это разрешает ---
        if (mode.aliasAllowed) {
            AegisHudCard(
                title = stringResource(R.string.contact_add_alias_title),
                icon = Icons.Outlined.AlternateEmail,
            ) {
                Text(
                    text = stringResource(R.string.contact_add_alias_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                if (aliasClaimed.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.contact_add_alias_current, aliasClaimed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it.trim() },
                    label = { Text(stringResource(R.string.contact_add_alias_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AegisChipShape,
                    colors = aegisHudTextFieldColors(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AegisHudButton(
                        text = stringResource(R.string.contact_add_alias_claim),
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = onClaimAlias(aliasInput)
                                status = result.toStatus(context)
                                if (result is DirectoryPublisher.AliasResult.Ok) {
                                    aliasClaimed = result.normalized
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && aliasInput.isNotBlank(),
                    )
                    if (aliasClaimed.isNotBlank()) {
                        AegisHudOutlinedButton(
                            text = stringResource(R.string.contact_add_alias_release),
                            onClick = {
                                busy = true
                                scope.launch {
                                    onReleaseAlias()
                                    aliasClaimed = ""
                                    aliasInput = ""
                                    busy = false
                                }
                            },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

/** Сообщение под кнопкой: [isError] красит его в цвет ошибки. */
private data class StatusMessage(val text: String, val isError: Boolean)

private fun ContactRequestManager.SendResult.toStatus(context: Context): StatusMessage = when (this) {
    is ContactRequestManager.SendResult.Sent -> StatusMessage(
        context.getString(
            R.string.contact_req_sent,
            nickname.ifBlank { ContactAddress.shortAccount(peerAccountId) },
        ),
        isError = false,
    )
    is ContactRequestManager.SendResult.AlreadyContact ->
        StatusMessage(context.getString(R.string.contact_req_err_already), isError = true)
    ContactRequestManager.SendResult.AlreadyPending ->
        StatusMessage(context.getString(R.string.contact_req_err_pending), isError = true)
    ContactRequestManager.SendResult.SelfAddress ->
        StatusMessage(context.getString(R.string.contact_req_err_self), isError = true)
    ContactRequestManager.SendResult.BadAddress ->
        StatusMessage(context.getString(R.string.contact_req_err_bad_address), isError = true)
    ContactRequestManager.SendResult.NotFound ->
        StatusMessage(context.getString(R.string.contact_req_err_not_found), isError = true)
    ContactRequestManager.SendResult.Unavailable ->
        StatusMessage(context.getString(R.string.contact_req_err_unavailable), isError = true)
    is ContactRequestManager.SendResult.Untrusted -> StatusMessage(
        context.getString(
            R.string.contact_req_err_untrusted,
            reason::class.simpleName.orEmpty(),
        ),
        isError = true,
    )
    is ContactRequestManager.SendResult.Failed ->
        StatusMessage(context.getString(R.string.contact_req_err_failed, reason), isError = true)
}

private fun DirectoryPublisher.AliasResult.toStatus(context: Context): StatusMessage = when (this) {
    is DirectoryPublisher.AliasResult.Ok ->
        StatusMessage(context.getString(R.string.contact_add_alias_current, normalized), false)
    DirectoryPublisher.AliasResult.Taken ->
        StatusMessage(context.getString(R.string.contact_req_err_already), isError = true)
    DirectoryPublisher.AliasResult.Unavailable ->
        StatusMessage(context.getString(R.string.contact_req_err_unavailable), isError = true)
    is DirectoryPublisher.AliasResult.Invalid ->
        StatusMessage(context.getString(R.string.contact_req_err_failed, reason), isError = true)
    is DirectoryPublisher.AliasResult.Failed ->
        StatusMessage(context.getString(R.string.contact_req_err_failed, reason), isError = true)
}

/**
 * Копирует адрес в буфер обмена.
 *
 * Адрес — НЕ секрет переписки: это публичный идентификатор плюс тег-capability,
 * его назначение — быть переданным другому человеку. Секреты (PSK, мнемоника,
 * теги в сыром виде) в буфер обмена по-прежнему не попадают никогда (§6.2).
 */
private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("aegis-contact-address", text))
}

/** Потолок длины приветствия в UI — синхронно с `ContactInvite.MAX_GREETING`. */
private const val GREETING_LIMIT = 200
