package com.aegis.messenger.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.aegis.core.common.security.TorMode
import com.aegis.messenger.messaging.RelayUnavailableReason

/**
 * Режим сети для экранов — одним холдером, чтобы не протаскивать через три
 * сигнатуры полдюжины параметров (как [com.aegis.messenger.ui.contacts.ContactRequestsUi]).
 *
 * @param switching идёт переключение транспорта (старые соединения закрываются).
 * @param relayUnavailable почему сейчас нет клиента релея (`null` — есть).
 * @param setTorEnabled включить/выключить Tor; выключать — только после диалога.
 */
data class NetworkUi(
    val mode: TorMode = TorMode.ALWAYS,
    val switching: Boolean = false,
    val relayUnavailable: RelayUnavailableReason? = null,
    val setTorEnabled: (Boolean) -> Unit = {},
    val testOnion: suspend (String) -> String = { "" },
    val testHttps: suspend (String) -> String = { "" },
) {
    val torEnabled: Boolean get() = mode == TorMode.ALWAYS
}

/**
 * Включён ли Tor — для баннеров и подсказок в глубине дерева экранов (тексты
 * «адрес не задан» и подобные различаются по режиму). Задаётся в корне приложения.
 */
val LocalTorEnabled = staticCompositionLocalOf { true }
