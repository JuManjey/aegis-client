package com.aegis.messenger.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.theme.AegisChipShape
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudFrame
import com.aegis.messenger.R
import com.aegis.messenger.ui.components.AegisHudBanner
import com.aegis.messenger.ui.components.HudBannerTone

/** Статический slug экрана мнемоники для декоративных HUD-кодов. */
private const val RECOVERY_SLUG = "account.recovery"

/** Слов в ряду сетки: два влезают на 360dp вместе с номером. */
private const val MNEMONIC_COLUMNS = 2

private val WhitespaceRegex = Regex("\\s+")

/**
 * Одноразовый экран показа мнемоники (§7.1). Содержит ЧЕСТНОЕ предупреждение о
 * безвозвратной потере доступа. Скриншоты запрещены глобально (FLAG_SECURE, §14.3).
 * Мнемоника НЕ копируется в буфер обмена (§6.2).
 */
@Composable
fun RecoveryPhraseScreen(
    mnemonic: String,
    identity: AccountIdentity?,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = remember(mnemonic) {
        mnemonic.trim().split(WhitespaceRegex).filter { it.isNotEmpty() }
    }

    AuthColumn(slug = RECOVERY_SLUG, modifier = modifier) {
        AuthHeader(
            title = stringResource(R.string.recovery_title),
            subtitle = stringResource(R.string.recovery_screenshot_note),
        )

        // Жёлтые угловые скобки вместо циановых: фраза — главное на экране.
        AegisHudCard(accent = AegisTheme.extendedColors.accentPrimary) {
            MnemonicGrid(words = words)
        }

        identity?.let {
            AegisHudCard {
                // Строки ресурсов уже содержат и подпись, и значение, поэтому
                // это не `AegisHudKeyValue`; моноширинный стиль оставлен —
                // отпечаток сверяют символ за символом.
                Text(
                    text = stringResource(R.string.recovery_account_id, it.shortId()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.recovery_fingerprint, it.fingerprintGrouped()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        AegisHudBanner(
            text = stringResource(R.string.recovery_warning),
            tone = HudBannerTone.DANGER,
        )

        AegisHudButton(
            text = stringResource(R.string.recovery_ack),
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Сетка слов мнемоники: два столбца рубленых ячеек с нумерацией. */
@Composable
private fun MnemonicGrid(words: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        words.chunked(MNEMONIC_COLUMNS).forEachIndexed { rowIndex, rowWords ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowWords.forEachIndexed { columnIndex, word ->
                    MnemonicCell(
                        number = rowIndex * MNEMONIC_COLUMNS + columnIndex + 1,
                        word = word,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Неполный последний ряд: добираем пустым местом, иначе
                // единственная ячейка растянулась бы на всю ширину.
                repeat(MNEMONIC_COLUMNS - rowWords.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Ячейка одного слова: номер служебным моноширинным, слово — моноширинным
 * контентным.
 *
 * Слово НЕ переводится в верхний регистр: его сверяют посимвольно с бумажной
 * копией, а словарь BIP39 набран строчными буквами.
 */
@Composable
private fun MnemonicCell(number: Int, word: String, modifier: Modifier = Modifier) {
    val colors = AegisTheme.extendedColors
    Row(
        modifier = modifier
            .aegisHudFrame(color = colors.panelBorder, shape = AegisChipShape, cornerArm = 0.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        Text(
            text = word,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
