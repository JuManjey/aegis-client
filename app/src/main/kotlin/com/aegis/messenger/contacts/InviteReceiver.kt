package com.aegis.messenger.contacts

import com.aegis.core.common.contact.ContactAccept
import com.aegis.core.common.contact.ContactDecline
import com.aegis.core.common.contact.ContactInvite
import com.aegis.core.common.contact.ContactRequestDirection
import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.data.storage.ContactRequestStore
import com.aegis.network.tor.RelayClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Приём заявок на добавление в контакты (RCH v1, `docs/contact_requests.md`).
 *
 * ПОЧЕМУ ОТДЕЛЬНО ОТ [com.aegis.messenger.messaging.RelayReceiver]. Тот поднимает
 * long-poll по СУЩЕСТВУЮЩИМ контактам: адреса ящиков он выводит из PSK контакта.
 * Здесь всё наоборот — заявка приходит от того, с кем контакта ещё нет, а адрес
 * ящика выводится из СВОЕГО тега обнаружения. Поэтому этот цикл обязан работать
 * даже когда контактов ноль (иначе первую заявку принять было бы невозможно).
 *
 * Слушаем два вида ящиков:
 *  * ящик ПРИГЛАШЕНИЙ (свой тег) — сюда приходят `INVITE`;
 *  * ящики ОТВЕТА (эфемерные `replyTag` наших исходящих заявок) — `ACCEPT`/`DECLINE`.
 *
 * Набор ящиков ответа пересчитывается вместе с набором ожидающих заявок: как
 * только заявка завершена или просрочена, её цикл останавливается.
 */
class InviteReceiver(
    private val transport: ContactRequestTransport,
    private val manager: ContactRequestManager,
    private val requestStore: ContactRequestStore,
    private val clientProvider: () -> RelayClient?,
    /** Мой тег обнаружения (base64). `null`, если ещё не создан. */
    private val tagProvider: suspend () -> String?,
    /** Мой публичный тег для алиаса (base64) — тоже принимает заявки. */
    private val publicTagProvider: suspend () -> String?,
    private val accountIdProvider: suspend () -> String?,
    private val localDeviceIdProvider: () -> Int,
    private val modeProvider: () -> ContactRequestMode,
    /** Разблокирован ли аккаунт и доступен ли слой B. */
    private val enabledProvider: () -> Boolean,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Volatile
    private var supervisor: Job? = null

    /** Активные циклы: ключ — адрес ящика (он уникален). */
    private val loops = HashMap<String, Job>()

    /** «Пересобери циклы сейчас» (смена режима сети, перезапуск Tor), не дожидаясь таймера. */
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    @Synchronized
    fun start() {
        if (supervisor?.isActive == true) return
        DiagnosticLog.event(DIAG_TAG, "надзор приёма заявок запущен")
        supervisor = scope.launch {
            while (isActive) {
                runCatching { if (enabledProvider()) reconcile() else stopLoops() }
                runCatching { manager.purgeExpired() }
                withTimeoutOrNull(RECONCILE_INTERVAL_MS) { wake.receive() }
            }
        }
    }

    /**
     * Гасит циклы и немедленно пересобирает их на текущем клиенте релея. Пока
     * идёт смена режима сети, клиента нет, и циклы просто не поднимутся.
     */
    fun restartLoops() {
        stopLoops()
        wake.trySend(Unit)
    }

    @Synchronized
    fun stop() {
        DiagnosticLog.event(DIAG_TAG, "надзор приёма заявок остановлен")
        supervisor?.cancel()
        supervisor = null
        stopLoops()
    }

    @Synchronized
    private fun stopLoops() {
        loops.values.forEach { it.cancel() }
        loops.clear()
    }

    /** Сводит набор циклов к текущему набору ящиков (приглашения + ответы). */
    private suspend fun reconcile() {
        if (!modeProvider().enabled) {
            stopLoops()
            return
        }
        if (clientProvider() == null) return
        val accountId = accountIdProvider() ?: return
        val myDevice = localDeviceIdProvider()

        val wanted = LinkedHashMap<String, Kind>()
        // Ящики приглашений: приватный тег и (если алиас разрешён) публичный.
        tagProvider()?.let { tag ->
            transport.inviteMailbox(tag, accountId, myDevice)?.let { wanted[it] = Kind.INVITE }
        }
        if (modeProvider().aliasAllowed) {
            publicTagProvider()?.let { tag ->
                transport.inviteMailbox(tag, accountId, myDevice)?.let { wanted[it] = Kind.INVITE }
            }
        }
        // Ящики ответа по нашим ОЖИДАЮЩИМ исходящим заявкам.
        requestStore.pending()
            .filter {
                it.direction == ContactRequestDirection.OUTGOING &&
                    it.state == ContactRequestState.OUTGOING_PENDING &&
                    it.replyTagB64.isNotBlank()
            }
            .forEach { request ->
                transport.replyMailbox(request.replyTagB64, accountId, myDevice)
                    ?.let { wanted[it] = Kind.REPLY }
            }

        synchronized(this) {
            val stale = loops.keys.filter { it !in wanted || loops[it]?.isActive != true }
            stale.forEach { loops.remove(it)?.cancel() }
            for ((mailbox, kind) in wanted) {
                if (loops[mailbox]?.isActive != true) {
                    loops[mailbox] = scope.launch(Dispatchers.IO) { pollLoop(mailbox, kind) }
                }
            }
        }
        DiagnosticLog.d(DIAG_TAG) { "reconcile заявок: ящиков=${wanted.size}" }
    }

    private enum class Kind { INVITE, REPLY }

    /**
     * Долгий цикл приёма одного ящика.
     *
     * Курсор двигается и `ack` отправляется ТОЛЬКО по успешно обработанным
     * конвертам: деструктивный ack на конверте, который не удалось разобрать,
     * потерял бы заявку безвозвратно. Неразбираемый конверт (мусор/чужой формат)
     * подтверждается отдельно — иначе он навсегда заблокировал бы очередь.
     */
    private suspend fun pollLoop(mailbox: String, kind: Kind) {
        var after = 0L
        DiagnosticLog.event(
            DIAG_TAG, "poll заявок старт",
            "вид" to kind.name,
            "ящик" to DiagnosticLog.mailbox(mailbox),
        )
        while (coroutineContext.isActive) {
            try {
                val client = clientProvider() ?: run { delay(IDLE_BACKOFF_MS); return }
                val result = client.poll(mailbox, after, WAIT_SECONDS)
                var ackUpTo = after
                for (item in result.items) {
                    val handled = runCatching {
                        dispatch(String(item.data, Charsets.UTF_8), kind)
                    }.getOrElse { e ->
                        DiagnosticLog.e(DIAG_TAG, e) { "обработка конверта заявки упала" }
                        Outcome.RETRY
                    }
                    when (handled) {
                        // Обработано либо заведомо мусор — подтверждаем и идём дальше.
                        Outcome.DONE, Outcome.DISCARD -> ackUpTo = item.seq
                        // Временная ошибка (нет мнемоники/крипты): НЕ подтверждаем,
                        // конверт останется в очереди до следующей попытки.
                        Outcome.RETRY -> {
                            DiagnosticLog.w(DIAG_TAG) {
                                "конверт заявки оставлен в очереди для повторной попытки"
                            }
                            break
                        }
                    }
                }
                if (ackUpTo > after) {
                    after = ackUpTo
                    runCatching { client.ack(mailbox, after) }
                } else if (result.items.isNotEmpty()) {
                    // Ничего не продвинулось — не крутим цикл вплотную.
                    delay(ERROR_BACKOFF_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.e(DIAG_TAG, e) {
                    "poll заявок ошибка ящик=${DiagnosticLog.mailbox(mailbox)}"
                }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    /** Итог обработки одного конверта. */
    private enum class Outcome {
        /** Обработано успешно. */
        DONE,

        /** Заведомо непригодный конверт — подтвердить и забыть. */
        DISCARD,

        /** Временная ошибка — оставить в очереди и повторить позже. */
        RETRY,
    }

    private suspend fun dispatch(wireJson: String, kind: Kind): Outcome {
        val opened = transport.openWire(wireJson) ?: return Outcome.DISCARD
        val type = runCatching {
            json.parseToJsonElement(opened.payloadJson).jsonObject["t"]?.jsonPrimitive?.content
        }.getOrNull() ?: return Outcome.DISCARD

        DiagnosticLog.event(
            DIAG_TAG, "конверт заявки разобран",
            "вид" to kind.name,
            "тип" to type,
            "отправитель" to DiagnosticLog.shortId(opened.senderAccountId),
        )
        return when (type) {
            ContactInvite.TYPE -> {
                manager.onInvite(opened.payloadJson, opened.senderDeviceId)
                Outcome.DONE
            }
            ContactAccept.TYPE -> {
                manager.onAccept(opened.payloadJson)
                Outcome.DONE
            }
            ContactDecline.TYPE -> {
                manager.onDecline(opened.payloadJson)
                Outcome.DONE
            }
            else -> {
                DiagnosticLog.w(DIAG_TAG) { "неизвестный тип нагрузки заявки: $type" }
                Outcome.DISCARD
            }
        }
    }

    private companion object {
        const val DIAG_TAG = "contact-req"
        const val WAIT_SECONDS = 30
        const val RECONCILE_INTERVAL_MS = 20_000L
        const val IDLE_BACKOFF_MS = 5_000L
        const val ERROR_BACKOFF_MS = 8_000L
    }
}
