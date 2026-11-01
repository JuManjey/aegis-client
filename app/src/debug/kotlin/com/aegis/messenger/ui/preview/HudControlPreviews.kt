package com.aegis.messenger.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aegis.core.ui.hud.AegisHudActionChip
import com.aegis.core.ui.hud.AegisHudBusy
import com.aegis.core.ui.hud.AegisHudButton
import com.aegis.core.ui.hud.AegisHudCard
import com.aegis.core.ui.hud.AegisHudChip
import com.aegis.core.ui.hud.AegisHudCheckRow
import com.aegis.core.ui.hud.AegisHudDivider
import com.aegis.core.ui.hud.AegisHudDropdownMenu
import com.aegis.core.ui.hud.AegisHudMenuItem
import com.aegis.core.ui.hud.AegisHudGroupLabel
import com.aegis.core.ui.hud.AegisHudKeyValue
import com.aegis.core.ui.hud.AegisHudMeter
import com.aegis.core.ui.hud.AegisHudOptionRow
import com.aegis.core.ui.hud.AegisHudOutlinedButton
import com.aegis.core.ui.hud.AegisHudStatusLine
import com.aegis.core.ui.hud.AegisHudSwitchRow
import com.aegis.core.ui.hud.AegisHudTextField
import com.aegis.core.ui.hud.AegisHudToggleChip
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.core.ui.theme.aegisHudBackdrop

/**
 * Превью строительных блоков HUD (`docs/ui_hud.md` §5.6) для Android Studio.
 *
 * Собраны в один экран, чтобы визуально сверять карточки, строки, кнопки и
 * состояния между собой — расхождения в отступах и оттенках видны только рядом.
 * Лежит в `src/debug`, в релизный APK не попадает.
 */
@Preview(name = "HUD — блоки (тёмная)", showBackground = true, backgroundColor = 0xFF0A0E12, heightDp = 1100)
@Composable
private fun HudControlsDarkPreview() {
    AegisTheme { HudControlsGallery() }
}

@Preview(name = "HUD — блоки (светлая)", showBackground = true, backgroundColor = 0xFFF3F6F8, heightDp = 1100)
@Composable
private fun HudControlsLightPreview() {
    AegisTheme(darkTheme = false) { HudControlsGallery() }
}

@Composable
private fun HudControlsGallery() {
    var switchOn by remember { mutableStateOf(true) }
    var checkOn by remember { mutableStateOf(false) }
    var option by remember { mutableStateOf(1) }
    var chip by remember { mutableStateOf(0) }
    var field by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .aegisHudBackdrop()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AegisHudGroupLabel(text = "Переключатели и выбор")
        AegisHudCard(title = "Звонки", icon = Icons.Outlined.Call) {
            AegisHudSwitchRow(
                title = "Разрешить звонки",
                subtitle = "Голос и видео через защищённый канал",
                checked = switchOn,
                onCheckedChange = { switchOn = it },
            )
            AegisHudSwitchRow(
                title = "Медиапоток через Tor",
                subtitle = "Скрывает адрес, но снижает качество связи",
                checked = false,
                onCheckedChange = {},
                enabled = switchOn,
            )
            AegisHudDivider()
            AegisHudOptionRow(
                title = "Автоматически",
                subtitle = "Решение принимает активный профиль",
                selected = option == 0,
                onClick = { option = 0 },
            )
            AegisHudOptionRow(
                title = "Всегда включено",
                selected = option == 1,
                onClick = { option = 1 },
            )
            AegisHudCheckRow(
                title = "Подтверждать отпечаток вручную",
                checked = checkOn,
                onCheckedChange = { checkOn = it },
            )
        }

        AegisHudGroupLabel(text = "Чипы, кнопки, состояния")
        AegisHudCard(title = "Оформление", icon = Icons.Outlined.Palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Тёмная", "Светлая", "Системная").forEachIndexed { index, label ->
                    AegisHudToggleChip(
                        text = label,
                        selected = chip == index,
                        onClick = { chip = index },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AegisHudChip(text = "Tor активен", icon = Icons.Filled.Shield)
                AegisHudChip(
                    text = "E2EE",
                    accent = AegisTheme.extendedColors.accentPrimary,
                    filled = true,
                )
            }
            // Чипы-действия выглядят как чипы выбора, но объявляются кнопками:
            // визуально их легко спутать, поэтому стоят рядом для сверки.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AegisHudActionChip(text = "На час", onClick = {})
                AegisHudActionChip(text = "На 8 часов", onClick = {})
                AegisHudActionChip(
                    text = "Возобновить",
                    onClick = {},
                    accent = AegisTheme.extendedColors.accentPrimary,
                )
            }
            AegisHudStatusLine(text = "Ключ слоя A установлен", ok = true)
            AegisHudStatusLine(text = "Отпечаток не сверен", ok = false)
            AegisHudMeter(fraction = 0.62f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AegisHudButton(text = "Сохранить", onClick = {})
                AegisHudOutlinedButton(text = "Отмена", onClick = {})
                AegisHudBusy()
            }
            AegisHudButton(text = "Экстренная очистка", onClick = {}, danger = true)
        }

        // Меню-поповер рисуется вне обычного потока разметки, поэтому в превью
        // он показан развёрнутым: иначе стиль пунктов не проверить.
        AegisHudGroupLabel(text = "Выпадающее меню (вложения)")
        AegisHudCard {
            Box {
                AegisHudDropdownMenu(expanded = true, onDismissRequest = {}) {
                    AegisHudMenuItem(text = "Камера", onClick = {}, icon = Icons.Filled.PhotoCamera)
                    AegisHudMenuItem(text = "Фото и видео", onClick = {}, icon = Icons.Filled.Image)
                    AegisHudMenuItem(
                        text = "Файл",
                        onClick = {},
                        icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    )
                    AegisHudDivider()
                    AegisHudMenuItem(
                        text = "Удалить чат",
                        onClick = {},
                        icon = Icons.Filled.Delete,
                        danger = true,
                    )
                }
            }
        }

        AegisHudGroupLabel(text = "Служебные данные")
        AegisHudCard(title = "Устройство") {
            AegisHudTextField(
                value = field,
                onValueChange = { field = it },
                placeholder = "Имя устройства",
                modifier = Modifier.fillMaxWidth(),
            )
            // Отпечаток и onion-адрес — моноширинные и без UPPERCASE: их сверяют
            // символ за символом.
            AegisHudKeyValue(
                label = "Отпечаток ключа",
                value = "4f2a 91c8 dd03 7b61  a5e0 12ff 8c74 3ba9",
            )
            AegisHudKeyValue(
                label = "Onion-релей",
                value = "q5oskjtrxwrijuugusipwgpjcgsv5yj4kf3lz7w4vk4rud2rbukkwrqd.onion",
                maxLines = 2,
            )
            Text(
                text = "Значения нельзя переводить в верхний регистр — они сверяются буквально.",
                style = MaterialTheme.typography.bodySmall,
                color = AegisTheme.extendedColors.textSecondary,
            )
        }
    }
}
