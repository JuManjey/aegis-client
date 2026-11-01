package com.aegis.messenger.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aegis.core.common.model.ChatSummary
import com.aegis.core.ui.hud.AegisHudBubble
import com.aegis.core.ui.hud.AegisHudCodeRow
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudHint
import com.aegis.core.ui.hud.AegisHudHintBar
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudBackdrop
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.AegisBottomBar
import com.aegis.messenger.ui.components.ChatListItem
import com.aegis.messenger.ui.components.HudBannerTone
import com.aegis.messenger.ui.components.PlaceholderContent
import com.aegis.messenger.ui.components.TorStatusIndicator
import com.aegis.messenger.ui.navigation.TopLevelDestination
import com.aegis.network.tor.TorStatus

/**
 * Превью HUD-раскладки (`docs/ui_hud.md`) для Android Studio.
 *
 * Лежит в `src/debug`, поэтому в релизный APK не попадает: превью нужны только
 * при разработке, а тащить их в прод-сборку — лишний код и лишний вес.
 *
 * Данные здесь синтетические и «плоские» (никаких контактов из хранилища):
 * превью рендерится вне процесса приложения и до любой расшифровки.
 */
private val previewChats = listOf(
    ChatSummary(
        id = "preview-1",
        displayName = "Wakako Okada",
        nickname = "wakako_okada",
        lastMessagePreview = "Данные с Милитеха у меня. Забирай.",
        timestampLabel = "21:04",
        unreadCount = 2,
        verified = true,
        e2eeActive = true,
    ),
    ChatSummary(
        id = "preview-2",
        displayName = "Panam Palmer",
        nickname = "panam_palmer",
        lastMessagePreview = "Держи ухо востро, ага?",
        timestampLabel = "19:47",
        unreadCount = 0,
        verified = false,
        e2eeActive = true,
    ),
    ChatSummary(
        id = "preview-3",
        displayName = "Bartmoss Collective",
        nickname = "bartmoss_collective",
        lastMessagePreview = "Ключи слоя B ещё в пути.",
        timestampLabel = "ВЧ",
        unreadCount = 128,
        verified = false,
        e2eeActive = false,
    ),
)

@Preview(name = "HUD — список чатов", showBackground = true, backgroundColor = 0xFF0A0E12)
@Composable
private fun ChatListPreview() {
    AegisTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .aegisHudBackdrop()
                .padding(vertical = 8.dp),
        ) {
            AegisHudCodeRow(slug = "chats", modifier = Modifier.padding(horizontal = 8.dp))
            AegisHudSectionHeader(
                title = "AEGIS",
                icon = Icons.AutoMirrored.Filled.Message,
                trailing = { TorStatusIndicator(status = TorStatus.Connected) },
            )
            AegisHudBanner(
                text = "Адрес onion-релея не задан — отправка недоступна.",
                tone = HudBannerTone.DANGER,
                modifier = Modifier.padding(top = 8.dp),
            )
            AegisHudGroupLabel(
                text = "Чаты · ${previewChats.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            AegisHudPanel(
                modifier = Modifier.padding(horizontal = 8.dp),
                contentPadding = 4.dp,
            ) {
                previewChats.forEachIndexed { index, chat ->
                    ChatListItem(chat = chat, selected = index == 1)
                }
            }
        }
    }
}

@Preview(name = "HUD — переписка", showBackground = true, backgroundColor = 0xFF0A0E12)
@Composable
private fun ChatThreadPreview() {
    AegisTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .aegisHudBackdrop()
                .padding(vertical = 8.dp),
        ) {
            AegisHudCodeRow(slug = "chat.thread", modifier = Modifier.padding(horizontal = 8.dp))
            AegisHudSectionHeader(
                title = "Сообщения",
                subtitle = "Panam Palmer",
                icon = Icons.AutoMirrored.Filled.Message,
            )
            Text(
                text = "печатает…",
                style = MaterialTheme.typography.labelSmall,
                color = AegisTheme.extendedColors.securityAccent,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 10.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AegisHudBubble(outgoing = true) {
                        Text("что дальше делаем? есть план?")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    AegisHudBubble(outgoing = false) {
                        Text(
                            "Надо спешить. Проскочим под пыльной бурей, но она " +
                                "быстро уляжется… Держи ухо востро, ага?",
                        )
                    }
                }
            }
            AegisHudHintBar(
                hints = listOf(
                    AegisHudHint(key = "V", label = "Сверить"),
                    AegisHudHint(key = "C", label = "Позвонить"),
                    AegisHudHint(key = "U", label = "Разблокировать", danger = true),
                ),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Нижняя навигация во всех четырёх состояниях сразу: подписи под глифами должны
 * помещаться в ячейку без усечения на самом узком экране, поэтому превью задано
 * шириной 320dp — это минимум, который проект поддерживает.
 */
@Preview(
    name = "HUD — нижнее меню (320dp)",
    showBackground = true,
    backgroundColor = 0xFF0A0E12,
    widthDp = 320,
)
@Composable
private fun BottomBarNarrowPreview() {
    AegisTheme {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            TopLevelDestination.entries.forEach { dest ->
                AegisBottomBar(current = dest, onSelect = {})
            }
        }
    }
}

@Preview(name = "HUD — нижнее меню (светлая)", showBackground = true, backgroundColor = 0xFFF3F6F8)
@Composable
private fun BottomBarLightPreview() {
    AegisTheme(darkTheme = false) {
        AegisBottomBar(current = TopLevelDestination.CHATS, onSelect = {})
    }
}

@Preview(name = "HUD — пустое состояние", showBackground = true, backgroundColor = 0xFF0A0E12)
@Composable
private fun EmptyStatePreview() {
    AegisTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .aegisHudBackdrop(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaceholderContent(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = "Пока нет чатов",
                subtitle = "Добавьте контакт по никнейму и проведите ручной обмен " +
                    "ключами, чтобы начать защищённую переписку.",
            )
        }
    }
}

@Preview(name = "HUD — светлая тема", showBackground = true, backgroundColor = 0xFFF3F6F8)
@Composable
private fun LightThemePreview() {
    AegisTheme(darkTheme = false) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .aegisHudBackdrop()
                .padding(vertical = 8.dp),
        ) {
            AegisHudCodeRow(slug = "chats", modifier = Modifier.padding(horizontal = 8.dp))
            AegisHudSectionHeader(
                title = "AEGIS",
                icon = Icons.AutoMirrored.Filled.Message,
                trailing = { TorStatusIndicator(status = TorStatus.Connected) },
            )
            AegisHudPanel(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                contentPadding = 4.dp,
            ) {
                previewChats.forEachIndexed { index, chat ->
                    ChatListItem(chat = chat, selected = index == 1)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AegisHudBubble(outgoing = true) { Text("светлая тема, тот же HUD") }
            }
        }
    }
}
