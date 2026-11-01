package com.aegis.messenger.ui.contacts

import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.contact.ContactRequestView
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.messenger.contacts.ContactRequestManager
import com.aegis.messenger.contacts.DirectoryPublisher

/**
 * Всё, что UI нужно для удалённого добавления контактов, одним параметром.
 *
 * ЗАЧЕМ ХОЛДЕР. Функциональность добавляет ~10 состояний и колбэков; протаскивать
 * их по отдельности через `AegisRoot → AegisApp → MainScaffold` означало бы
 * раздуть три сигнатуры и каждый раз править все три. Холдер собирается один раз
 * в [com.aegis.messenger.ui.AegisRoot] и передаётся как одно значение.
 */
data class ContactRequestsUi(
    /** Режим из настроек безопасности: что вообще разрешено пользователю. */
    val mode: ContactRequestMode,
    /** Заявки для экрана и бейджа (без секретов раунда). */
    val requests: List<ContactRequestView>,
    /** Занятый публичный `@ник` или пусто. */
    val alias: String,
    /** Мой адрес контакта; создаёт тег при первом вызове. */
    val myAddress: suspend () -> String?,
    val sendRequest: suspend (String, String) -> ContactRequestManager.SendResult,
    val accept: suspend (String) -> ContactRequestManager.DecisionResult,
    val decline: suspend (String) -> ContactRequestManager.DecisionResult,
    val cancel: suspend (String) -> Unit,
    val claimAlias: suspend (String) -> DirectoryPublisher.AliasResult,
    val releaseAlias: suspend () -> Unit,
    val rotateAddress: suspend () -> String?,
) {
    /** Число входящих заявок, ожидающих решения — бейдж на вкладке «Контакты». */
    val incomingPendingCount: Int
        get() = requests.count { it.state == ContactRequestState.INCOMING_PENDING }

    companion object {
        /**
         * Заглушка для превью Compose и для случая «функция выключена»: ничего не
         * делает и ничего не показывает.
         */
        val DISABLED: ContactRequestsUi = ContactRequestsUi(
            mode = ContactRequestMode.OFF,
            requests = emptyList(),
            alias = "",
            myAddress = { null },
            sendRequest = { _, _ -> ContactRequestManager.SendResult.Unavailable },
            accept = { ContactRequestManager.DecisionResult.NotFound },
            decline = { ContactRequestManager.DecisionResult.NotFound },
            cancel = { },
            claimAlias = { DirectoryPublisher.AliasResult.Unavailable },
            releaseAlias = { },
            rotateAddress = { null },
        )
    }
}
