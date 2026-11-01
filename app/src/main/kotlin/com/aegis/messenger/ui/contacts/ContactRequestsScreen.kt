package com.aegis.messenger.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.contact.ContactRequestDirection
import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.contact.ContactRequestView
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudChip
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudScrollRail
import com.aegis.core.ui.theme.AegisPanelShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.crypto.PskCrypto
import com.aegis.messenger.R
import com.aegis.messenger.contacts.ContactRequestManager
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.components.PlaceholderContent
import kotlinx.coroutines.launch

/**
 * Экран заявок на добавление в контакты (`docs/contact_requests.md`),
 * HUD-раскладка — `docs/ui_hud.md`.
 *
 * ПОЧЕМУ РЕШЕНИЕ ПРИНИМАЕТСЯ ЗДЕСЬ, А НЕ В УВЕДОМЛЕНИИ. Принять заявку —
 * значит поверить, что за присланным `accountId` стоит тот человек, о котором
 * вы думаете. Для этого нужно ВИДЕТЬ `accountId` и отпечаток, поэтому экран
 * показывает их полностью, а кнопок «принять» в шторке нет вовсе.
 *
 * Экран — панель раздела «Контакты»: фон, инсеты, шапку приложения и
 * переключатель разделов даёт хозяин вкладки
 * ([com.aegis.messenger.ui.contacts.ContactsScreen]), поэтому здесь только
 * содержимое — без второго `aegisHudBackdrop` и второго учёта инсетов
 * (см. `AegisHudScreen`).
 */
@Composable
fun ContactRequestsScreen(
    requests: List<ContactRequestView>,
    onAccept: suspend (String) -> ContactRequestManager.DecisionResult,
    onDecline: suspend (String) -> ContactRequestManager.DecisionResult,
    onCancel: suspend (String) -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }
    var banner by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        if (requests.isEmpty()) {
            PlaceholderContent(
                icon = Icons.Outlined.Inbox,
                title = stringResource(R.string.contact_req_title),
                subtitle = stringResource(R.string.contact_req_empty),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        banner?.let { message ->
            AegisHudBanner(text = message, tone = HudBannerTone.DANGER)
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = requests, key = { it.id }) { request ->
                    RequestCard(
                        request = request,
                        busy = busyId == request.id,
                        onAccept = {
                            busyId = request.id
                            scope.launch {
                                when (val result = onAccept(request.id)) {
                                    is ContactRequestManager.DecisionResult.Accepted -> {
                                        banner = null
                                        onOpenChat(result.contactId)
                                    }
                                    is ContactRequestManager.DecisionResult.Failed ->
                                        banner = result.reason
                                    else -> banner = null
                                }
                                busyId = null
                            }
                        },
                        onDecline = {
                            busyId = request.id
                            scope.launch {
                                onDecline(request.id)
                                busyId = null
                            }
                        },
                        onCancel = {
                            busyId = request.id
                            scope.launch {
                                onCancel(request.id)
                                busyId = null
                            }
                        },
                    )
                }
            }
            AegisHudScrollRail(
                listState = listState,
                modifier = Modifier.padding(end = 6.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * Одна заявка: направление — прописной заголовок карточки, у входящей акцент
 * жёлтый (она требует решения), у исходящей — циановый служебный.
 */
@Composable
private fun RequestCard(
    request: ContactRequestView,
    busy: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AegisTheme.extendedColors
    val incoming = request.direction == ContactRequestDirection.INCOMING
    AegisHudCard(
        title = stringResource(
            if (incoming) R.string.contact_req_incoming else R.string.contact_req_outgoing,
        ),
        icon = if (incoming) Icons.Outlined.Inbox else Icons.AutoMirrored.Filled.Send,
        accent = if (incoming) colors.accentPrimary else colors.securityAccent,
        titleTrailing = {
            if (busy) AegisHudBusy(size = 14.dp)
        },
    ) {
        // Имя собеседника (или короткий accountId вместо него) — данные
        // пользователя: регистр не меняем, иначе сверять станет труднее.
        Text(
            text = request.peerNickname.ifBlank {
                ContactAddress.shortAccount(request.peerAccountId)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (request.greeting.isNotBlank()) {
            Text(
                text = request.greeting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // accountId и отпечаток — то, ЧТО НУЖНО СВЕРИТЬ. Отдельная рубленая
        // рамка и моноширинный стиль: сравнение идёт символ в символ, и блок
        // должен читаться как «данные для сверки», а не как подпись к карточке.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aegisHudFrame(color = colors.securityAccent, shape = AegisPanelShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.contact_req_account,
                    request.peerAccountId.removePrefix("aegis:").uppercase(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (request.peerFingerprint.isNotBlank()) {
                Text(
                    text = stringResource(
                        R.string.contact_req_fingerprint,
                        PskCrypto.fingerprintGrouped(request.peerFingerprint),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (incoming && request.state == ContactRequestState.INCOMING_PENDING) {
            Text(
                text = stringResource(R.string.contact_req_verify_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AegisHudButton(
                    text = stringResource(R.string.contact_req_accept),
                    onClick = onAccept,
                    enabled = !busy,
                )
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contact_req_decline),
                    onClick = onDecline,
                    enabled = !busy,
                    danger = true,
                )
            }
        } else {
            AegisHudChip(
                text = stringResource(
                    when (request.state) {
                        ContactRequestState.OUTGOING_PENDING -> R.string.contact_req_state_pending
                        ContactRequestState.OUTGOING_DECLINED,
                        ContactRequestState.INCOMING_DECLINED,
                        -> R.string.contact_req_state_declined
                        else -> R.string.contact_req_state_pending
                    },
                ),
                accent = colors.textSecondary,
            )
            if (!incoming) {
                AegisHudOutlinedButton(
                    text = stringResource(R.string.contact_req_cancel),
                    onClick = onCancel,
                    enabled = !busy,
                    danger = true,
                )
            }
        }
    }
}
