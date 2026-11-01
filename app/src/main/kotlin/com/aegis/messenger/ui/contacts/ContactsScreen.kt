package com.aegis.messenger.ui.contacts

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.common.model.ContactCard
import com.aegis.core.ui.hud.AegisHudSectionHeader
import com.aegis.core.ui.hud.AegisHudToggleChip
import com.aegis.core.ui.icons.AegisHudIcons
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.messenger.R
import com.aegis.messenger.psk.PskManager
import com.aegis.messenger.ui.psk.PskExchangeScreen
import kotlinx.coroutines.launch

/**
 * Раздел «Контакты» (§14.1) — хаб из трёх равноправных частей, между которыми
 * переключает явная линейка вкладок:
 *
 *  * [ContactsTab.LIST] — уже добавленные контакты: поиск, подробности, удаление;
 *  * [ContactsTab.ADD] — добавление нового (`docs/contact_requests.md`);
 *  * [ContactsTab.REQUESTS] — входящие и исходящие заявки.
 *
 * ПОЧЕМУ ВКЛАДКИ, А НЕ «НАЗАД». Раньше «добавить» и «заявки» переключались
 * стрелкой «назад» в шапке каждого из них: стрелка вела не назад, а в соседний
 * раздел, скрывала сам факт его существования и не показывала, сколько заявок
 * ждёт решения. Линейка вкладок делает набор разделов и счётчик заявок видимыми
 * без единого нажатия.
 *
 * Поверх вкладок открываются два подпотока — подробности контакта и личный
 * обмен (QR/NFC): у них своя шапка с «назад», и там стрелка означает ровно то,
 * что должна.
 *
 * Стартовая вкладка зависит от того, есть ли что решать: при ожидающих ВХОДЯЩИХ
 * заявках сразу открывается их список — иначе заявка могла бы висеть незамеченной.
 */
@Composable
fun ContactsScreen(
    pskManager: PskManager,
    selfIdentity: AccountIdentity?,
    modifier: Modifier = Modifier,
    selfLogin: String = "",
    relayConfigured: Boolean = true,
    contacts: List<ContactCard> = emptyList(),
    contactRequests: ContactRequestsUi = ContactRequestsUi.DISABLED,
    openRequestsSignal: Boolean = false,
    onConsumeOpenRequests: () -> Unit = {},
    onOpenChat: (String) -> Unit = {},
    onVerifyContact: (String) -> Unit = {},
    /** Полное локальное удаление контакта (см. `AppContainer.deleteContact`). */
    onDeleteContact: suspend (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paneStates = rememberSaveableStateHolder()

    var tab by rememberSaveable {
        mutableStateOf(
            if (contactRequests.incomingPendingCount > 0) ContactsTab.REQUESTS else ContactsTab.LIST,
        )
    }
    // Подпотоки поверх вкладок. Хранятся примитивами, а не sealed-классом:
    // `rememberSaveable` переживает поворот экрана только для того, что кладётся
    // в Bundle, а пользователь не должен терять открытую карточку контакта.
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var inPersonOpen by rememberSaveable { mutableStateOf(false) }

    // Удаление запускается ЗДЕСЬ, а не в карточке контакта: карточка
    // закрывается ровно посреди операции — как только контакт пропал из
    // списка, — и её корутина не дожила бы до конца.
    //
    // Состояние операции намеренно НЕ `rememberSaveable`: корутина не переживёт
    // смерть процесса, а восстановленный из Bundle флаг «удаляю» навсегда
    // запер бы кнопки в выключенном состоянии.
    var deletingId by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    val details = detailsId?.let { id -> contacts.firstOrNull { it.id == id } }
    // Контакта больше нет (удалён здесь либо из переписки) — закрываем карточку.
    LaunchedEffect(detailsId, details) {
        if (detailsId != null && details == null) detailsId = null
    }

    // Переход из уведомления. Побочный эффект в эффекте, а не в теле composable:
    // запись состояния прямо в композиции ведёт к бесконечной рекомпозиции.
    LaunchedEffect(openRequestsSignal) {
        if (!openRequestsSignal) return@LaunchedEffect
        detailsId = null
        inPersonOpen = false
        tab = ContactsTab.REQUESTS
        onConsumeOpenRequests()
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            details != null -> ContactDetailsScreen(
                contact = details,
                onOpenChat = onOpenChat,
                onVerify = onVerifyContact,
                onDelete = {
                    val id = details.id
                    deletingId = id
                    deleteError = null
                    scope.launch {
                        runCatching { onDeleteContact(id) }.onFailure { error ->
                            deleteError = context.getString(
                                R.string.contacts_delete_failed,
                                error.message ?: error::class.simpleName.orEmpty(),
                            )
                        }
                        deletingId = null
                    }
                },
                onBack = { detailsId = null },
                modifier = Modifier.weight(1f),
                deleting = deletingId == details.id,
                errorMessage = deleteError,
            )

            inPersonOpen -> PskExchangeScreen(
                pskManager = pskManager,
                selfIdentity = selfIdentity,
                modifier = Modifier.weight(1f),
                selfLogin = selfLogin,
                relayConfigured = relayConfigured,
                onOpenChat = onOpenChat,
                onExit = { inPersonOpen = false },
            )

            else -> {
                AegisHudSectionHeader(
                    title = stringResource(tab.titleRes),
                    icon = tab.icon,
                )
                ContactsTabRow(
                    current = tab,
                    incomingPending = contactRequests.incomingPendingCount,
                    onSelect = { selected ->
                        deleteError = null
                        tab = selected
                    },
                )
                // Состояние вкладок (запрос поиска, позиция прокрутки) переживает
                // переключение: без этого возврат к списку после «добавить»
                // сбрасывал бы набранный поиск.
                val paneModifier = Modifier.weight(1f)
                paneStates.SaveableStateProvider(tab) {
                    when (tab) {
                        ContactsTab.LIST -> ContactListScreen(
                            contacts = contacts,
                            onOpenDetails = { id -> detailsId = id },
                            modifier = paneModifier,
                        )

                        ContactsTab.ADD -> AddContactScreen(
                            mode = contactRequests.mode,
                            myAddressProvider = contactRequests.myAddress,
                            currentAlias = contactRequests.alias,
                            onSendRequest = contactRequests.sendRequest,
                            onClaimAlias = contactRequests.claimAlias,
                            onReleaseAlias = contactRequests.releaseAlias,
                            onRotateAddress = contactRequests.rotateAddress,
                            onOpenInPerson = { inPersonOpen = true },
                            modifier = paneModifier,
                        )

                        ContactsTab.REQUESTS -> ContactRequestsScreen(
                            requests = contactRequests.requests,
                            onAccept = contactRequests.accept,
                            onDecline = contactRequests.decline,
                            onCancel = contactRequests.cancel,
                            onOpenChat = onOpenChat,
                            modifier = paneModifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Линейка вкладок раздела. Горизонтально прокручиваемая: на узких экранах
 * (проект держит 320dp) три подписи со счётчиком заявок не влезают в строку, а
 * сжимать их до многоточия значит превратить навигацию в ребус.
 */
@Composable
private fun ContactsTabRow(
    current: ContactsTab,
    incomingPending: Int,
    onSelect: (ContactsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AegisTheme.extendedColors
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .selectableGroup()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (tab in ContactsTab.entries) {
            val waiting = tab == ContactsTab.REQUESTS && incomingPending > 0
            AegisHudToggleChip(
                text = if (waiting) {
                    stringResource(R.string.contacts_tab_requests_badge, incomingPending)
                } else {
                    stringResource(tab.tabLabelRes)
                },
                selected = tab == current,
                onClick = { onSelect(tab) },
                // Жёлтый акцент = «ждёт решения», как у входящей заявки в списке.
                accent = if (waiting) colors.accentPrimary else colors.securityAccent,
            )
        }
    }
}

/** Часть раздела «Контакты», между которыми переключает линейка вкладок. */
private enum class ContactsTab(
    /** Заголовок в шапке раздела: полная формулировка. */
    @param:StringRes val titleRes: Int,
    /** Подпись на вкладке: короткая, чтобы линейка влезала в узкий экран. */
    @param:StringRes val tabLabelRes: Int,
    val icon: ImageVector,
) {
    LIST(
        titleRes = R.string.contacts_search_title,
        tabLabelRes = R.string.contacts_tab_list,
        icon = AegisHudIcons.Contacts,
    ),
    ADD(
        titleRes = R.string.contact_add_title,
        tabLabelRes = R.string.contacts_tab_add,
        icon = Icons.Filled.PersonAdd,
    ),
    REQUESTS(
        titleRes = R.string.contact_req_title,
        tabLabelRes = R.string.contacts_tab_requests,
        icon = Icons.Outlined.Inbox,
    ),
}
