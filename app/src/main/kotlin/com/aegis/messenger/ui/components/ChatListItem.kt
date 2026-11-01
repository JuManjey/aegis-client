package com.aegis.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aegis.core.common.model.ChatSummary
import com.aegis.core.ui.hud.AegisHudAvatar
import com.aegis.core.ui.hud.AegisHudRow
import com.aegis.core.ui.hud.aegisHudInitials
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R

/**
 * Строка чата в списке главного экрана (§13, раскладка — `docs/ui_hud.md`):
 * «ручка» HUD, квадратный аватар с инициалами, прописное имя, превью последнего
 * сообщения, время, счётчик непрочитанных, индикатор E2E и статус верификации.
 *
 * [selected] инвертирует строку (заливка бледным цианом, тёмный контент) — как
 * выбранный контакт в телефонном меню референса. На телефоне список
 * одноколоночный, поэтому по умолчанию инверсия не используется; параметр нужен
 * для планшетной раскладки «список + переписка» и для превью.
 */
@Composable
fun ChatListItem(
    chat: ChatSummary,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = AegisTheme.extendedColors
    val muted = if (selected) colors.onSelectionFill.copy(alpha = 0.7f) else colors.textSecondary
    val accent = if (selected) colors.onSelectionFill else colors.securityAccent

    AegisHudRow(
        modifier = modifier,
        selected = selected,
        trailing = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = chat.timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                if (chat.unreadCount > 0) {
                    UnreadBadge(count = chat.unreadCount)
                }
            }
        },
    ) {
        AegisHudAvatar(initials = aegisHudInitials(chat.displayName), accent = accent)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = chat.displayName.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chat.verified) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.chat_item_verified_cd),
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (chat.e2eeActive) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.chat_item_e2e_cd),
                        tint = muted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                text = chat.lastMessagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Счётчик непрочитанных: рубленый маджентовый маркер вместо круглого
 * Material-`Badge`. Трёхзначные значения сворачиваются в «99+», иначе бейдж
 * начинает толкать время сообщения.
 */
@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = AegisTheme.extendedColors
    Box(
        modifier = modifier
            .clip(AegisChipShape)
            .background(colors.accentMagenta)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > UNREAD_CAP) "$UNREAD_CAP+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onAccentMagenta,
        )
    }
}

private const val UNREAD_CAP = 99
