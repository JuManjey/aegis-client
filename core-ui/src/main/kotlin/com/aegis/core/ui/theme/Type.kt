package com.aegis.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Типографика AEGIS в эстетике Cyberpunk 2077 (§13, раскладка — `docs/ui_hud.md`).
 *
 * Три гарнитуры и жёсткое разделение ролей:
 *
 *  * [Condensed] — узкий системный `sans-serif-condensed` (Roboto Condensed есть
 *    на любом Android). Заголовки, имена, названия разделов: узкие прописные с
 *    увеличенным трекингом — это и даёт «телефон Ви» без покупки шрифта.
 *  * [FontFamily.Default] — контент сообщений: читабельность важнее стиля,
 *    UPPERCASE к тексту переписки НЕ применяется.
 *  * [Mono] — служебный HUD-хром: время, статусы доставки, ID, микро-коды по
 *    углам панелей, подписи клавиш в нижней подсказке.
 *
 * Фирменный шрифт (Rajdhani / Chakra Petch) в проекте не подключён (`res/font`
 * пуст, а тянуть Google Fonts по сети нельзя — приложение работает через Tor и
 * не должно ходить в сторонние CDN, §7.2/§10). Если шрифт когда-нибудь ляжет в
 * `res/font`, менять надо ТОЛЬКО [Condensed] — экраны не тронутся.
 *
 * Задан ПОЛНЫЙ набор ролей Material3. Раньше половина шкалы (`bodySmall`,
 * `titleSmall`, `headlineSmall`, `labelLarge`, `headlineMedium`) в теме
 * отсутствовала и молча падала на стоковые Material-стили — самый частый в
 * приложении `bodySmall` рисовался чужой типографикой.
 */
private val Condensed = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold),
)

private val Mono = FontFamily.Monospace

/**
 * Метрики шрифта не должны добавлять «воздух» сверху/снизу: HUD-строки стоят
 * вплотную к рамкам, и лишний leading ломает выравнивание с линейками.
 */
private val HudLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@Suppress("DEPRECATION")
private val HudPlatform = PlatformTextStyle(includeFontPadding = false)

/** Заголовок раздела: узкий, прописной, с широким трекингом. */
private fun hudTitle(
    size: Int,
    lineHeight: Int,
    tracking: Double,
    weight: FontWeight = FontWeight.SemiBold,
) = TextStyle(
    fontFamily = Condensed,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = HudPlatform,
    lineHeightStyle = HudLineHeight,
)

/** Служебная подпись HUD: моноширинная, с трекингом. */
private fun hudLabel(
    size: Int,
    lineHeight: Int,
    tracking: Double,
    weight: FontWeight = FontWeight.Medium,
) = TextStyle(
    fontFamily = Mono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = HudPlatform,
    lineHeightStyle = HudLineHeight,
)

/** Текст контента: обычная гарнитура, умеренный трекинг. */
private fun hudBody(
    size: Int,
    lineHeight: Int,
    tracking: Double,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    platformStyle = HudPlatform,
    lineHeightStyle = HudLineHeight,
)

internal val AegisTypography = Typography(
    // --- Display: крупные экраны-заглушки, онбординг ---
    displayLarge = hudTitle(size = 44, lineHeight = 48, tracking = 2.0, weight = FontWeight.Bold),
    displayMedium = hudTitle(size = 36, lineHeight = 40, tracking = 1.8, weight = FontWeight.Bold),
    displaySmall = hudTitle(size = 30, lineHeight = 34, tracking = 1.6, weight = FontWeight.Bold),

    // --- Headline: шапки полноэкранных разделов ---
    headlineLarge = hudTitle(size = 27, lineHeight = 32, tracking = 1.5),
    headlineMedium = hudTitle(size = 24, lineHeight = 28, tracking = 1.4),
    headlineSmall = hudTitle(size = 21, lineHeight = 26, tracking = 1.3),

    // --- Title: заголовок шапки, имена контактов, названия карточек ---
    titleLarge = hudTitle(size = 22, lineHeight = 27, tracking = 1.6),
    titleMedium = hudTitle(size = 17, lineHeight = 22, tracking = 0.9, weight = FontWeight.Medium),
    titleSmall = hudTitle(size = 15, lineHeight = 20, tracking = 0.8, weight = FontWeight.Medium),

    // --- Body: контент. Тексты переписки и описания настроек ---
    bodyLarge = hudBody(size = 16, lineHeight = 24, tracking = 0.4),
    bodyMedium = hudBody(size = 14, lineHeight = 20, tracking = 0.2),
    bodySmall = hudBody(size = 13, lineHeight = 19, tracking = 0.15),

    // --- Label: HUD-хром. Моноширинный, прописной по месту вызова ---
    labelLarge = hudLabel(size = 13, lineHeight = 17, tracking = 1.2, weight = FontWeight.SemiBold),
    labelMedium = hudLabel(size = 12, lineHeight = 16, tracking = 1.0),
    labelSmall = hudLabel(size = 11, lineHeight = 14, tracking = 0.8),
)

/**
 * Стиль микро-кодов по углам HUD-панелей (`TRN_TCLAS_000090` в референсе).
 * Отдельно от шкалы Material: 8sp — заведомо ниже минимума для читаемого
 * текста, это чистая декорация, и попадать в `labelSmall` она не должна.
 */
val AegisCodeTextStyle: TextStyle = hudLabel(
    size = 8,
    lineHeight = 10,
    tracking = 0.6,
    weight = FontWeight.Normal,
)

/**
 * КРУПНЫЙ моноширинный код для сверки человеком: safety-номер контакта,
 * код сверки звонка (SAS), отпечаток устройства.
 *
 * Отдельная роль, потому что в шкале Material её нет и быть не может: у
 * `headline*`/`title*` гарнитура узкая пропорциональная (там важна плотность
 * заголовка), а такие коды читают вслух и сверяют символ за символом — им нужна
 * фиксированная ширина знака, чтобы `0`/`O` и `1`/`l` не сливались. Трекинг
 * увеличен, чтобы группы не слипались.
 *
 * До появления этой роли экраны обходились `titleLarge.copy(fontFamily =
 * Monospace)`, то есть правили гарнитуру по месту — ровно то, что §13 запрещает.
 */
val AegisCodeDisplayStyle: TextStyle = hudLabel(
    size = 24,
    lineHeight = 30,
    tracking = 2.2,
    weight = FontWeight.SemiBold,
)
