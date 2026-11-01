package com.aegis.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Палитра AEGIS — визуальная тема в эстетике Cyberpunk 2077 (интерфейс
 * мессенджера / телефона Ви): «рубленая» геометрия, неоновые акценты
 * (жёлтый / циан / маджента) на почти-чёрном холодном фоне.
 *
 * ЕДИНСТВЕННОЕ место, где допустимо хардкодить HEX. Все экраны обязаны брать
 * цвета из темы Material3 ([AegisTheme]) или из [AegisExtendedColors], а НЕ
 * ссылаться на эти константы напрямую (§13: «не хардкодить HEX по экранам»).
 */
internal object AegisPalette {
    /** #0A0E12 — основной фон экранов (не чистый чёрный). */
    val Background = Color(0xFF0A0E12)

    /** #10151B — карточки, панель списка чатов, поля ввода. */
    val Surface = Color(0xFF10151B)

    /** #161C24 — приподнятые поверхности: модалки, поповеры, обои чата. */
    val SurfaceElevated = Color(0xFF161C24)

    /** #FCEE0A — главный акцент: активные табы, важные кнопки, выделения. */
    val AccentPrimary = Color(0xFFFCEE0A)

    /** #02D7F2 — циан: свои исходящие сообщения, ссылки, активные состояния. */
    val AccentCyan = Color(0xFF02D7F2)

    /** #FF2A6D — маджента: второстепенный акцент, бейджи, hover. */
    val AccentMagenta = Color(0xFFFF2A6D)

    /** #E8F7FA — основной текст. */
    val TextPrimary = Color(0xFFE8F7FA)

    /** #7A8B94 — вторичный текст, подписи, таймстампы, иконки. */
    val TextSecondary = Color(0xFF7A8B94)

    // --- Производные токены (выведены из палитры для контраста/границ) ---

    /** Тёмный текст поверх жёлтого акцента (важные кнопки, активные табы). */
    val OnAccentPrimary = Color(0xFF0A0E12)

    /** Тёмный текст поверх цианового акцента (бейджи/кнопки на циане). */
    val OnAccentCyan = Color(0xFF041A20)

    /** Тёмный текст поверх мадженты. */
    val OnAccentMagenta = Color(0xFF17040C)

    /** Вариант поверхности для разделителей/аватаров/полей. */
    val SurfaceVariant = Color(0xFF1B2530)

    /** Тонкие границы/контуры: циан с низкой прозрачностью (rgba(2,215,242,0.18)). */
    val BorderSubtle = Color(0x2E02D7F2)

    /** Цвет ошибки/опасных действий (экстренная очистка, ослабление безопасности). */
    val Danger = Color(0xFFFF003C)
    val OnDanger = Color(0xFF14060A)

    // --- HUD-хром (`docs/ui_hud.md`): рамки панелей, рейки, инверсная выборка ---

    /** Заливка HUD-панели: почти непрозрачная, чтобы текст читался на обоях чата. */
    val PanelFill = Color(0xE60C1116)

    /** Рамка HUD-панели: циан заметнее, чем [BorderSubtle], но не «горит». */
    val PanelBorder = Color(0x6602D7F2)

    /** Трек вертикальной рейки-скроллбара справа от списков. */
    val RailTrack = Color(0x2602D7F2)

    /** Бегунок рейки. */
    val RailThumb = Color(0xCC02D7F2)

    /**
     * Заливка выбранной строки списка: бледный циан «инверсией», как выделение
     * в телефонном меню референса. Текст на ней — [OnSelectionFill].
     */
    val SelectionFill = Color(0xFF9BEFF8)
    val OnSelectionFill = Color(0xFF04161B)

    /** Служебные микро-коды по углам панелей (декоративная маркировка HUD). */
    val CodeLabel = Color(0x99FF2A6D)

    /** «Хвост» двухцветной линейки под заголовком — маджента до правого края. */
    val RuleTail = Color(0xB3FF2A6D)

    /** Линии фоновой сетки под контентом (очень тонкие, почти на пределе видимости). */
    val BackdropGrid = Color(0x1202D7F2)

    /**
     * Подложка панелей поверх видео (экран звонка): затемнение, на котором текст
     * читается независимо от того, что показывает камера собеседника.
     */
    val ScrimOverlay = Color(0xCC080C10)
}

/**
 * Светлая палитра (§14.6). Вспомогательная тема: та же бренд-идентичность
 * (неоновый циан/жёлтый), но на светлом холодном фоне. Акценты затемнены для
 * достаточного контраста текста на кнопках (WCAG AA).
 */
internal object AegisLightPalette {
    /** Основной фон экранов (очень светлый холодный). */
    val Background = Color(0xFFF3F6F8)

    /** Карточки, панели, поля ввода. */
    val Surface = Color(0xFFFFFFFF)

    /** Приподнятые поверхности. */
    val SurfaceElevated = Color(0xFFEAF1F4)

    /** Главный акцент: затемнённый янтарный для контраста на светлом. */
    val AccentPrimary = Color(0xFF8A7A00)

    /** Циан: затемнённый для контраста на светлом. */
    val AccentCyan = Color(0xFF0B7C8C)

    /** Маджента: затемнённая для контраста на светлом. */
    val AccentMagenta = Color(0xFFC01356)

    /** Тёмный/светлый текст поверх акцентов. */
    val OnAccentPrimary = Color(0xFF141200)
    val OnAccentCyan = Color(0xFFFFFFFF)
    val OnAccentMagenta = Color(0xFFFFFFFF)

    /** Основной текст (тёмный графит). */
    val TextPrimary = Color(0xFF0A0E12)

    /** Вторичный текст, подписи, таймстампы, иконки. */
    val TextSecondary = Color(0xFF4C5763)

    /** Вариант поверхности для разделителей/полей. */
    val SurfaceVariant = Color(0xFFDCE4E9)

    /** Тонкие границы/контуры. */
    val BorderSubtle = Color(0x330B7C8C)

    /** Цвет ошибки/опасных действий. */
    val Danger = Color(0xFFC1092E)
    val OnDanger = Color(0xFFFFFFFF)

    // --- HUD-хром (`docs/ui_hud.md`), светлый вариант ---

    /** Заливка HUD-панели — плотный белый, чтобы рамки читались на светлом фоне. */
    val PanelFill = Color(0xFAFFFFFF)

    /** Рамка HUD-панели. */
    val PanelBorder = Color(0x800B7C8C)

    val RailTrack = Color(0x330B7C8C)
    val RailThumb = Color(0xCC0B7C8C)

    /** Выбранная строка: тёмная инверсия на светлом холсте. */
    val SelectionFill = Color(0xFF0B7C8C)
    val OnSelectionFill = Color(0xFFFFFFFF)

    val CodeLabel = Color(0x99C01356)
    val RuleTail = Color(0xB3C01356)
    val BackdropGrid = Color(0x140B7C8C)

    /**
     * Подложка панелей поверх видео. В светлой теме тоже ТЁМНАЯ: панель лежит на
     * видеокадре, а не на фоне приложения, и светлый скрим на кадре не работает.
     */
    val ScrimOverlay = Color(0xCC080C10)
}
