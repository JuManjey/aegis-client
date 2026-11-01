package com.aegis.messenger.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCodeRow
import com.aegis.core.ui.hud.AegisHudRule
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudBackdrop

/**
 * Каркас экранов онбординга/входа в HUD-раскладке (`docs/ui_hud.md`): фон с
 * сеткой и виньеткой, микро-коды экрана сверху, прокрутка и единый интервал
 * между блоками.
 *
 * Инсеты берутся здесь: auth-gate показывает эти экраны напрямую, без
 * [com.aegis.core.ui.hud.AegisHudScreen] и без `Scaffold`, а приложение рисует
 * edge-to-edge. Фон ставится ДО инсетов, чтобы сетка уходила под статусбар, а
 * прокрутка — ПОСЛЕ, чтобы виньетка не ездила вместе с контентом.
 *
 * [slug] — СТАТИЧЕСКИЙ идентификатор экрана для декоративных кодов; передавать
 * данные пользователя запрещено ([com.aegis.core.ui.hud.AegisHudCodes]).
 */
@Composable
fun AuthColumn(
    slug: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .aegisHudBackdrop()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AegisHudCodeRow(slug = slug)
        content()
    }
}

/**
 * Шапка экрана входа: крупный прописной узкий заголовок цианом, двухцветная
 * линейка и пояснение под ней — «загрузочный терминал» вместо Material-титула.
 */
@Composable
fun AuthHeader(title: String, subtitle: String) {
    val colors = AegisTheme.extendedColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.securityAccent,
        )
        AegisHudRule(segments = 2)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

/** Сообщение об ошибке валидации или неудачного запроса под формой. */
@Composable
fun AuthErrorText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = AegisTheme.extendedColors.danger,
    )
}

/**
 * Главная кнопка формы входа с индикатором работы.
 *
 * У [AegisHudButton] нет слота контента, поэтому занятость показывается
 * отдельным [AegisHudBusy] поверх правого края кнопки: в разметке рядом он
 * отбирал бы у кнопки ширину и дёргал её на время запроса.
 */
@Composable
fun AuthSubmitButton(
    text: String,
    onClick: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        AegisHudButton(
            text = text,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
        if (busy) {
            // Кольцо ложится на жёлтую заливку кнопки, поэтому берёт её цвет
            // контента: циановый индикатор на жёлтом сливается в грязь.
            AegisHudBusy(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                size = 18.dp,
                accent = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
