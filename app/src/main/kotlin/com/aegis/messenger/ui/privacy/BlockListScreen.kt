package com.aegis.messenger.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudRow
import com.aegis.core.ui.hud.AegisHudScreen
import com.aegis.core.ui.hud.AegisHudScrollRail
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.aegisHudSwitchColors
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.data.storage.StoredContact
import com.aegis.messenger.R
import com.aegis.messenger.messaging.MessagingRepository
import com.aegis.messenger.ui.components.PlaceholderContent

/**
 * Статический slug раздела для декоративных микро-кодов HUD. Литерал, а не
 * contactId: код попадает на любой скриншот (§6 `docs/ui_hud.md`).
 */
private const val BLOCKLIST_HUD_SLUG = "blocklist"

/**
 * Экран чёрного списка (§14.2) в HUD-раскладке (`docs/ui_hud.md`): все контакты
 * одним списком с переключателем блокировки. Блокировка локальна и незаметна для
 * собеседника (§10.9): входящие от него отбрасываются, исходящие не отправляются.
 */
@Composable
fun BlockListScreen(
    repository: MessagingRepository,
    onSetBlocked: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val conversations by repository.conversations.collectAsStateWithLifecycle()
    var contacts by remember { mutableStateOf<List<StoredContact>>(emptyList()) }
    val listState = rememberLazyListState()

    // Перечитываем контакты при входе и при изменении списка чатов (после блока).
    LaunchedEffect(conversations) {
        contacts = repository.allContacts()
    }

    AegisHudScreen(
        title = stringResource(R.string.blocklist_title),
        slug = BLOCKLIST_HUD_SLUG,
        icon = Icons.Outlined.Block,
        onBack = onBack,
        backContentDescription = stringResource(R.string.common_back),
    ) {
        if (contacts.isEmpty()) {
            PlaceholderContent(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.blocklist_title),
                subtitle = stringResource(R.string.blocklist_empty),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AegisHudPanel(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp),
                contentPadding = 4.dp,
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(items = contacts, key = { it.contact.id }) { stored ->
                            ContactBlockRow(
                                name = stored.contact.displayLabel(),
                                blocked = stored.contact.blocked,
                                onToggle = { blocked ->
                                    onSetBlocked(stored.contact.id, blocked)
                                    // Оптимистично отражаем в UI до перечитывания.
                                    contacts = contacts.map {
                                        if (it.contact.id == stored.contact.id) {
                                            it.copy(contact = it.contact.copy(blocked = blocked))
                                        } else {
                                            it
                                        }
                                    }
                                },
                            )
                        }
                    }
                    AegisHudScrollRail(
                        listState = listState,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Строка контакта: «ручка» HUD, имя как есть (пользовательские данные регистр не
 * меняют), строка состояния и переключатель блокировки.
 *
 * Интерактивен только переключатель: строку целиком кликабельной не делаем,
 * иначе TalkBack объявил бы две независимые цели на одном элементе.
 */
@Composable
private fun ContactBlockRow(
    name: String,
    blocked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = AegisTheme.extendedColors
    AegisHudRow(
        minHeight = 56.dp,
        trailing = {
            Switch(
                checked = blocked,
                onCheckedChange = onToggle,
                colors = aegisHudSwitchColors(),
            )
        },
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AegisHudStatusLine(
                text = if (blocked) {
                    stringResource(R.string.blocklist_status_blocked)
                } else {
                    stringResource(R.string.blocklist_status_active)
                },
                ok = !blocked,
                failColor = colors.danger,
            )
        }
    }
}
