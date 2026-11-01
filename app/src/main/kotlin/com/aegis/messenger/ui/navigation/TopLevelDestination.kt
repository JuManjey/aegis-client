package com.aegis.messenger.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.aegis.core.ui.icons.AegisHudIcons
import com.aegis.messenger.R

/**
 * Разделы нижней навигации (§13): Чаты / Контакты / Настройки / Мой профиль.
 *
 * [labelRes] — полное имя раздела: заголовок шапки и `contentDescription` для
 * TalkBack. [tabLabelRes] — короткая подпись под глифом вкладки.
 *
 * Две строки на раздел нужны из-за ширины: на экране 360dp ячейка вкладки — это
 * ~80dp под текст, а «МОЙ ПРОФИЛЬ» прописным моноширинным занимает больше и
 * усекалось бы до «МОЙ ПРОФИЛ…». В шапке же длинное имя уместно и усекать его не
 * нужно. TalkBack при этом читает полное имя, а не сокращение.
 *
 * Активная вкладка подсвечивается циановым хромом HUD (`securityAccent`),
 * как вкладки в интерфейсе телефона Cyberpunk 2077. Жёлтый бренд-акцент
 * остаётся у заголовка шапки.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    @param:StringRes val tabLabelRes: Int,
    val icon: ImageVector,
) {
    CHATS(
        route = "chats",
        labelRes = R.string.nav_chats,
        tabLabelRes = R.string.nav_chats,
        icon = AegisHudIcons.Chats,
    ),
    CONTACTS(
        route = "contacts",
        labelRes = R.string.nav_contacts,
        tabLabelRes = R.string.nav_contacts,
        icon = AegisHudIcons.Contacts,
    ),
    SETTINGS(
        route = "settings",
        labelRes = R.string.nav_settings,
        tabLabelRes = R.string.nav_settings,
        icon = AegisHudIcons.Settings,
    ),
    PROFILE(
        route = "profile",
        labelRes = R.string.nav_profile,
        tabLabelRes = R.string.nav_profile_tab,
        icon = AegisHudIcons.Profile,
    ),
}
