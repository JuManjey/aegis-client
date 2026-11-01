package com.aegis.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudCodeRow
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.network.tor.TorStatus

/**
 * Шапка раздела (§13, раскладка — `docs/ui_hud.md`): служебные микро-коды,
 * прописной узкий заголовок с угловым глифом, индикатор Tor справа и
 * двухцветная линейка снизу.
 *
 * [slug] — СТАТИЧЕСКИЙ идентификатор экрана для декоративных кодов
 * (см. инвариант в [com.aegis.core.ui.hud.AegisHudCodes]): маршрут раздела,
 * никогда не данные пользователя.
 *
 * Приложение рисует edge-to-edge, а `Scaffold` не добавляет отступы своему
 * `topBar` — верхние и боковые инсеты берём сами, иначе заголовок уезжает под
 * статусбар и вырез камеры.
 */
@Composable
fun AegisTopBar(
    title: String,
    torStatus: TorStatus,
    slug: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    torEnabled: Boolean = true,
    networkSwitching: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        AegisHudCodeRow(slug = slug, modifier = Modifier.padding(horizontal = 8.dp))
        AegisHudSectionHeader(
            title = title,
            icon = icon,
            trailing = {
                TorStatusIndicator(status = torStatus, torEnabled = torEnabled, switching = networkSwitching)
            },
        )
    }
}
