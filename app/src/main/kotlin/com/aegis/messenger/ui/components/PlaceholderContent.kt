package com.aegis.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudPanel
import com.aegis.core.ui.hud.AegisHudRule
import com.aegis.core.ui.hud.aegisHudBezel
import com.aegis.core.ui.theme.AegisTheme

/**
 * Пустое состояние раздела в виде HUD-сводки: иконка в рамке-скобках,
 * прописной узкий заголовок, двухцветная линейка и пояснение
 * (`docs/ui_hud.md`).
 *
 * Используется и как «здесь пока ничего нет» (список чатов), и как заглушка
 * разделов, функционал которых появится позже.
 */
@Composable
fun PlaceholderContent(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        AegisHudPanel(contentPadding = 20.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .aegisHudBezel(
                            color = colors.securityAccent,
                            selected = true,
                            glow = colors.scanlinesEnabled,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.securityAccent,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                AegisHudRule()
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
