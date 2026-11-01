package com.aegis.messenger.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.model.ChatSummary
import com.aegis.core.ui.hud.AegisHudDropdownMenu
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudMenuItem
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudScrollRail
import com.aegis.messenger.R
import com.aegis.messenger.ui.settings.LocalTorEnabled
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.ChatListItem
import com.aegis.messenger.ui.components.DebugBuildBanner
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.components.PlaceholderContent

/**
 * Главный раздел «Чаты» (§13): список активных чатов в HUD-панели или пустое
 * состояние. Раскладка — `docs/ui_hud.md`.
 *
 * Короткое нажатие открывает переписку. Долгое нажатие на строку открывает
 * меню с переименованием: имя локальное, собеседника и ключи не затрагивает.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    chats: List<ChatSummary>,
    modifier: Modifier = Modifier,
    onOpenChat: (String) -> Unit = {},
    onRenameChat: (String, String) -> Unit = { _, _ -> },
    relayConfigured: Boolean = true,
) {
    var menuChatId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSummary?>(null) }
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        DebugBuildBanner()
        if (!relayConfigured) {
            AegisHudBanner(
                text = stringResource(
                    if (LocalTorEnabled.current) R.string.relay_missing_banner else R.string.relay_missing_banner_direct,
                ),
                tone = HudBannerTone.DANGER,
            )
        }
        if (chats.isEmpty()) {
            PlaceholderContent(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = stringResource(R.string.chats_empty_title),
                subtitle = stringResource(R.string.chats_empty_subtitle),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AegisHudGroupLabel(
                text = stringResource(R.string.chats_group_chats, chats.size),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            AegisHudPanel(
                modifier = Modifier
                    .fillMaxSize()
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
                        items(items = chats, key = { it.id }) { chat ->
                            Box {
                                ChatListItem(
                                    chat = chat,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { onOpenChat(chat.id) },
                                            onLongClick = { menuChatId = chat.id },
                                        ),
                                )
                                AegisHudDropdownMenu(
                                    expanded = menuChatId == chat.id,
                                    onDismissRequest = { menuChatId = null },
                                ) {
                                    AegisHudMenuItem(
                                        text = stringResource(R.string.chat_menu_rename),
                                        onClick = {
                                            menuChatId = null
                                            renameTarget = chat
                                        },
                                        icon = Icons.Filled.Edit,
                                    )
                                }
                            }
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

    renameTarget?.let { chat ->
        RenameChatDialog(
            initial = chat.displayName,
            originalNickname = chat.nickname,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameChat(chat.id, newName)
                renameTarget = null
            },
        )
    }
}
