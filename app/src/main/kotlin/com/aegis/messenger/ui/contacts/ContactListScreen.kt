package com.aegis.messenger.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.model.ContactCard
import com.aegis.core.ui.hud.AegisHudAvatar
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudIconButton
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudRow
import com.aegis.core.ui.hud.AegisHudScrollRail
import com.aegis.core.ui.hud.AegisHudTextField
import com.aegis.core.ui.hud.aegisHudInitials
import com.aegis.core.ui.icons.AegisHudIcons
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.ui.components.PlaceholderContent
import kotlinx.coroutines.flow.drop

/**
 * Список уже добавленных контактов с поиском (§14.1), HUD-раскладка —
 * `docs/ui_hud.md`. Нажатие на строку открывает подробности контакта
 * ([ContactDetailsScreen]), откуда его можно удалить.
 *
 * ПОЧЕМУ ПОИСК ИЩЕТ И ПО КОДАМ. Имя чата локально и его легко подделать
 * социальной инженерией («Анна» в списке — ещё не Анна). Настоящая
 * идентичность — accountId и отпечаток, поэтому по ним тоже можно искать:
 * получив отпечаток по независимому каналу, пользователь находит контакт
 * именно по нему, а не по подписи, которую сам же и придумал.
 *
 * Экран — панель раздела «Контакты», а не самостоятельный маршрут: фон, инсеты,
 * шапку приложения и переключатель разделов даёт хозяин вкладки
 * ([ContactsScreen]), поэтому здесь ни второго `aegisHudBackdrop`, ни второго
 * учёта инсетов (см. `AegisHudScreen`).
 */
@Composable
fun ContactListScreen(
    contacts: List<ContactCard>,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Фильтр считается на изменение (список, запрос), а не на каждую
    // рекомпозицию: рекомпозиции приходят и от чужих состояний (фокус поля,
    // клавиатура), а проход по всем контактам на каждую из них — лишняя работа
    // на главном потоке ровно в тот момент, когда пользователь печатает.
    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts else contacts.filter { it.matches(query) }
    }

    // Сузили выборку — показываем её с начала. Без этого LazyColumn удерживает
    // прежний индекс и после ввода запроса список открывается «в пустоте» ниже
    // единственного совпадения. `drop(1)` гасит срабатывание на первом кадре,
    // иначе возврат из подробностей сбрасывал бы прокрутку.
    LaunchedEffect(listState) {
        snapshotFlow { query }.drop(1).collect { listState.scrollToItem(0) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (contacts.isEmpty()) {
            PlaceholderContent(
                icon = AegisHudIcons.Contacts,
                title = stringResource(R.string.contacts_empty_title),
                subtitle = stringResource(R.string.contacts_placeholder),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AegisHudTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.contacts_search_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            if (query.isNotEmpty()) {
                AegisHudIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.contacts_search_clear),
                    onClick = {
                        query = ""
                        focusManager.clearFocus()
                    },
                )
            }
        }

        AegisHudGroupLabel(
            text = if (query.isBlank()) {
                stringResource(R.string.contacts_group_all, filtered.size)
            } else {
                stringResource(R.string.contacts_group_found, filtered.size)
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )

        if (filtered.isEmpty()) {
            PlaceholderContent(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.contacts_not_found_title),
                subtitle = stringResource(R.string.contacts_not_found_subtitle),
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        AegisHudPanel(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    items(items = filtered, key = { it.id }) { contact ->
                        ContactListItem(
                            contact = contact,
                            modifier = Modifier.clickable(
                                role = Role.Button,
                                onClick = {
                                    focusManager.clearFocus()
                                    onOpenDetails(contact.id)
                                },
                            ),
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

/**
 * Строка контакта: та же геометрия, что у строки чата ([ChatListItem]) —
 * «ручка», квадратный аватар, прописное имя и бейджи состояния.
 *
 * Второй строкой идёт КОРОТКИЙ accountId, а не превью сообщения: в списке
 * контактов различать тёзок нужно по идентичности, а не по тому, кто что
 * написал последним.
 */
@Composable
private fun ContactListItem(
    contact: ContactCard,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    AegisHudRow(
        modifier = modifier,
        minHeight = 56.dp,
        trailing = {
            if (contact.blocked) {
                Icon(
                    imageVector = Icons.Outlined.Block,
                    contentDescription = stringResource(R.string.blocklist_status_blocked),
                    tint = colors.danger,
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        },
    ) {
        AegisHudAvatar(initials = aegisHudInitials(contact.displayName))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = contact.displayName.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (contact.verified) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.chat_item_verified_cd),
                        tint = colors.securityAccent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (contact.e2eeActive) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.chat_item_e2e_cd),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                text = ContactAddress.shortAccount(contact.id),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
