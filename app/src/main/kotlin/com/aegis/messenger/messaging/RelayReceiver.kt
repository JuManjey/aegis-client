package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.wire.WireChannel
import com.aegis.data.storage.LockedInboxStore
import com.aegis.data.storage.StoredContact
import com.aegis.network.tor.RelayTransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Живой приём из релея (§7.6/§12.2): для каждого активного PSK-контакта держит
 * long-poll цикл на СВОЁМ per-device подканале переписки v2 (mailbox из PSK +
 * accountId+deviceId, `docs/multidevice.md` §4.1), а также цикл на моём self-sync
 * подканале (§4.2). Принятые конверты демультиплексируются по полю `channel`
 * ([WireChannel.resolve]; нет маркера — эвристика для старых keyx-конвертов).
 * Control/звонок без `channel` внешне как сообщение: после расшифровки их
 * отводит [MessagePipeline.receive], в историю не пишутся.
 *  - сигналинг звонка (`channel="call"`) → [onCall];
 *  - control-канал (`channel="control"`, §14.2/§7.3) → [onControl];
 *  - обмен ключами слоя B (`channel="keyx"`, §6.2) → [onKeyExchange];
 *  - обычное сообщение → [onMessage];
 * после успешной обработки конверт подтверждается (ack), чтобы сервер его удалил.
 *
 * Мультидевайс (follow-up №2.2): переписка, control и звонки едут в ОДИН и тот же
 * per-device подканал v2 адресата (отправка — [RelayGateway.postToPeerDevices]);
 * у каждого устройства свой ящик и свой ack, поэтому деструктивный ack одного
 * устройства не «съедает» сообщения у других (общего v1-mailbox с ролью 0/1 и
 * гонкой ack больше нет).
 *
 * Приём — best-effort и полностью независим от E2EE: сервер отдаёт только
 * шифртекст, расшифровка B→A идёт локально ([MessagePipeline.receive] /
 * [com.aegis.messenger.call.CallSignaling.onWire]). Живые циклы работают, когда
 * [enabledProvider] истинно (аккаунт разблокирован и слой B доступен) и Tor/релей
 * подняты.
 *
 * ЗАКРЫТЫЙ АККАУНТ ([lockedModeProvider]). Ключа хранилищ нет — расшифровать и
 * сохранить входящее нельзя, а адреса ящиков выводятся из PSK, который тоже
 * закрыт. Поэтому живой режим запоминает свои ящики в [lockedInbox], закрытый
 * слушает их, кладёт конверты в очередь как есть, подтверждает и сообщает только
 * канал ([onLockedArrival]). После входа очередь разбирается тем же [dispatch]
 * ДО запуска живых циклов — порядок конвертов важен для храповика.
 */
class RelayReceiver(
    private val gateway: RelayGateway,
    private val contactsProvider: suspend () -> List<StoredContact>,
    /** Сообщение переписки; третий аргумент — транспорт, которым конверт пришёл (`null` — неизвестен). */
    private val onMessage: suspend (StoredContact, String, RelayTransportKind?) -> Unit,
    /** Сигналинг звонка; третий аргумент — когда конверт пришёл (мс эпохи), для очереди — давно. */
    private val onCall: suspend (StoredContact, String, Long) -> Unit,
    private val onControl: suspend (StoredContact, String) -> Unit,
    /**
     * Догоняющий обмен публичными ключами слоя B (§6.2, [KeyExchangeChannel]).
     * Единственный канал БЕЗ слоя B — он его и бутстрапит, поэтому приходит и
     * обрабатывается, когда Signal-сессии ещё нет.
     */
    private val onKeyExchange: suspend (StoredContact, String) -> Unit = { _, _ -> },
    private val enabledProvider: () -> Boolean,
    private val scope: CoroutineScope,
    /** device id ЭТОГО устройства — слушаю СВОЙ per-device подканал v2 (§4.1). */
    private val localDeviceIdProvider: () -> Int = { 1 },
    /** Мой self-sync входящий подканал (§4.2) или `null`, если мультидевайса нет. */
    private val selfSyncInboxProvider: suspend () -> String? = { null },
    /** Обработчик self-sync конверта (announce/копии сообщений, §5/§6). */
    private val onSelfSync: suspend (String) -> Unit = {},
    /**
     * Обмен с релеем прошёл успешно. Сигнал нужен сторожу Tor
     * ([com.aegis.network.tor.TorSupervisor]): «зелёный статус транспорта при
     * недостижимом релее» иначе никак не отличить от нормальной работы.
     */
    private val onRelayOk: () -> Unit = {},
    /** Обмен с релеем не удался (таймаут/SOCKS-отказ/разрыв). */
    private val onRelayFail: () -> Unit = {},
    /** Ящики и очередь приёма при закрытом аккаунте; `null` — режим недоступен. */
    private val lockedInbox: LockedInboxStore? = null,
    /** Аккаунт закрыт, а приём при закрытом аккаунте разрешён. */
    private val lockedModeProvider: () -> Boolean = { false },
    /** В очередь закрытого режима лёг конверт канала `channel` ([WireChannel]). */
    private val onLockedArrival: (String) -> Unit = {},
) {
    @Volatile
    private var supervisor: Job? = null

    /**
     * Активные циклы приёма: по contactId (живой режим), [SELF_SYNC_KEY] и
     * [LOCKED_PREFIX] + ящик (закрытый режим).
     */
    private val loops = HashMap<String, Job>()

    /** Ящик каждого живого цикла — из них складывается набор для закрытого режима. */
    private val loopInboxes = HashMap<String, String>()
    private var savedInboxes: Set<String>? = null

    private enum class Mode { IDLE, LIVE, LOCKED }

    /** Смена режима гасит циклы прежнего: живые и закрытые не должны слушать одни ящики. */
    private var mode = Mode.IDLE

    /**
     * Сигнал «пересобери циклы прямо сейчас», не дожидаясь [RECONCILE_INTERVAL_MS].
     *
     * `CONFLATED`: подряд идущие запросы (перезапуск Tor + смена сети) сливаются в
     * один — пересобирать циклы дважды бессмысленно.
     */
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    /** Запускает надзорный цикл (идемпотентно). */
    @Synchronized
    fun start() {
        if (supervisor?.isActive == true) return
        DiagnosticLog.event(DIAG_TAG, "надзор приёма запущен")
        supervisor = scope.launch {
            while (isActive) {
                runCatching {
                    when {
                        enabledProvider() -> {
                            switchMode(Mode.LIVE)
                            drainLockedInbox()
                            reconcile()
                        }
                        lockedInbox != null && lockedModeProvider() -> {
                            switchMode(Mode.LOCKED)
                            reconcileLocked(lockedInbox)
                        }
                        else -> switchMode(Mode.IDLE)
                    }
                }
                // Просыпаемся либо по таймеру, либо по внешнему толчку
                // ([restartLoops]) — что раньше.
                withTimeoutOrNull(RECONCILE_INTERVAL_MS) { wake.receive() }
            }
        }
    }

    @Synchronized
    private fun switchMode(next: Mode) {
        if (mode == next) return
        DiagnosticLog.event(DIAG_TAG, "режим приёма", "было" to mode.name, "стало" to next.name)
        stopLoops()
        mode = next
    }

    /** Останавливает надзор и все циклы приёма. */
    @Synchronized
    fun stop() {
        DiagnosticLog.event(DIAG_TAG, "надзор приёма остановлен")
        supervisor?.cancel()
        supervisor = null
        stopLoops()
    }

    /**
     * Гасит все циклы приёма и немедленно пересобирает их.
     *
     * Вызывается после ПЕРЕЗАПУСКА Tor (каждый цикл держит клиента с прежним
     * портом SOCKS, и после перезапуска этот порт закрыт) и при смене режима
     * сети: до переключения — чтобы остановить циклы (новых клиентов шлюз в это
     * время не выдаёт), после — чтобы поднять их на транспорте нового режима.
     */
    fun restartLoops() {
        DiagnosticLog.event(DIAG_TAG, "пересборка циклов приёма")
        stopLoops()
        wake.trySend(Unit)
    }

    @Synchronized
    private fun stopLoops() {
        loops.values.forEach { it.cancel() }
        loops.clear()
        loopInboxes.clear()
    }

    /** Синхронизирует набор циклов с текущим набором PSK-контактов. */
    private suspend fun reconcile() {
        // Нет клиента релея (Tor не поднят, адрес не задан, идёт смена режима) —
        // циклы поднимать бессмысленно, ждём следующей итерации.
        if (gateway.clientOrNull() == null) return
        val contacts = contactsProvider().associateBy { it.contact.id }
        val myDevice = localDeviceIdProvider()
        DiagnosticLog.d(DIAG_TAG) {
            "reconcile контактов=${contacts.size} циклов=${loops.size} устройство=$myDevice"
        }

        // Останавливаем циклы для исчезнувших/завершённых контактов (self-sync
        // цикл — служебный ключ [SELF_SYNC_KEY], его не трогаем здесь).
        val stale = loops.keys.filter { key ->
            if (key == SELF_SYNC_KEY) return@filter false
            key !in contacts || loops[key]?.isActive != true
        }
        stale.forEach {
            loops.remove(it)?.cancel()
            loopInboxes.remove(it)
        }

        // Запускаем недостающий per-device подканал переписки v2 (§4.1): в него
        // едут и сообщения, и control (§14.2), и сигналинг звонков (§7.5) —
        // демультиплексирование по полю `channel` в [dispatch]. У устройства свой
        // ящик ⇒ деструктивный ack не задевает другие мои устройства (§9 док).
        for ((id, contact) in contacts) {
            if (loops[id]?.isActive != true) {
                val inboxV2 = gateway.conversationInboxesForMe(contact, listOf(myDevice)).firstOrNull()
                if (inboxV2 != null) {
                    loops[id] = scope.launch(Dispatchers.IO) { pollLoop(contact, inboxV2) }
                    loopInboxes[id] = inboxV2
                }
            }
        }

        // self-sync подканал МОЕГО устройства (§4.2): announce + копии сообщений.
        if (loops[SELF_SYNC_KEY]?.isActive != true) {
            val selfInbox = selfSyncInboxProvider()
            if (selfInbox != null) {
                loops[SELF_SYNC_KEY] = scope.launch(Dispatchers.IO) { selfSyncLoop(selfInbox) }
                loopInboxes[SELF_SYNC_KEY] = selfInbox
            }
        }
        rememberInboxesForLockedMode()
    }

    /** Сохраняет набор ящиков для закрытого режима, когда он изменился. */
    private suspend fun rememberInboxesForLockedMode() {
        val store = lockedInbox ?: return
        val current = loopInboxes.values.toSet()
        if (current == savedInboxes) return
        runCatching { withContext(Dispatchers.IO) { store.saveInboxes(current) } }
            .onSuccess { savedInboxes = current }
            .onFailure { e -> DiagnosticLog.e(DIAG_TAG, e) { "не удалось сохранить ящики закрытого режима" } }
    }

    /** Закрытый режим: по циклу на каждый сохранённый ящик, без контактов и ключей. */
    private suspend fun reconcileLocked(store: LockedInboxStore) {
        if (gateway.clientOrNull() == null) return
        val inboxes = withContext(Dispatchers.IO) { store.inboxes() }
        val stale = loops.keys.filter { key ->
            !key.startsWith(LOCKED_PREFIX) ||
                key.removePrefix(LOCKED_PREFIX) !in inboxes ||
                loops[key]?.isActive != true
        }
        stale.forEach { loops.remove(it)?.cancel() }
        for (inbox in inboxes) {
            val key = LOCKED_PREFIX + inbox
            if (loops[key]?.isActive != true) {
                loops[key] = scope.launch(Dispatchers.IO) { lockedPollLoop(store, inbox) }
            }
        }
    }

    /**
     * Цикл ящика при закрытом аккаунте: конверт — в очередь, затем ack. Расшифровки
     * нет. Переполненная очередь не подтверждается: конверт дождётся входа на релее.
     */
    private suspend fun lockedPollLoop(store: LockedInboxStore, inbox: String) {
        var after = 0L
        while (coroutineContext.isActive) {
            try {
                val client = gateway.clientOrNull() ?: run { delay(IDLE_BACKOFF_MS); return }
                val result = client.poll(inbox, after, WAIT_SECONDS)
                // Блокирующий poll отмену не замечает. Если аккаунт за это время
                // открыли, конверт оставляем на релее — его заберёт живой цикл.
                if (!coroutineContext.isActive) return
                runCatching { onRelayOk() }
                var ackUpTo = after
                var full = false
                for (item in result.items) {
                    val payload = String(item.data, Charsets.UTF_8)
                    if (!store.enqueue(inbox, payload, transport = client.transportKind.name)) {
                        full = true
                        break
                    }
                    ackUpTo = item.seq
                    runCatching { onLockedArrival(WireChannel.resolve(payload)) }
                }
                if (ackUpTo > after) {
                    after = ackUpTo
                    runCatching { client.ack(inbox, after) }
                }
                if (full) {
                    DiagnosticLog.w(DIAG_TAG) { "очередь закрытого режима заполнена — ждём входа" }
                    delay(QUEUE_FULL_BACKOFF_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { onRelayFail() }
                DiagnosticLog.e(DIAG_TAG, e) { "poll закрытого режима ошибка ящик=${DiagnosticLog.mailbox(inbox)}" }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    /**
     * Разбирает очередь закрытого режима после входа: ящик сопоставляется с
     * контактом заново (из PSK), конверт идёт тем же путём, что живой. Одна
     * попытка: копии на релее уже нет, а повтор не исправит рассинхрон храповика.
     * Конверты ящиков, которых больше нет (контакт удалён), отбрасываются.
     */
    internal suspend fun drainLockedInbox() {
        val store = lockedInbox ?: return
        val pending = withContext(Dispatchers.IO) { store.pending() }
        if (pending.isEmpty()) return
        val myDevice = localDeviceIdProvider()
        val byInbox = HashMap<String, StoredContact>()
        for (contact in contactsProvider()) {
            gateway.conversationInboxesForMe(contact, listOf(myDevice)).firstOrNull()?.let { byInbox[it] = contact }
        }
        val selfInbox = selfSyncInboxProvider()
        DiagnosticLog.event(DIAG_TAG, "разбор очереди закрытого режима", "штук" to pending.size)
        for (entry in pending) {
            runCatching {
                val contact = byInbox[entry.inbox]
                val transport = RelayTransportKind.entries.firstOrNull { it.name == entry.transport }
                when {
                    contact != null -> dispatch(contact, entry.payload, entry.receivedAtMs, transport)
                    entry.inbox == selfInbox -> onSelfSync(entry.payload)
                    else -> DiagnosticLog.w(DIAG_TAG) { "конверт очереди без контакта — отброшен" }
                }
            }.onFailure { e -> DiagnosticLog.e(DIAG_TAG, e) { "конверт очереди не обработан" } }
            withContext(Dispatchers.IO) { store.remove(entry.id) }
        }
    }

    /** Долгий цикл приёма моего self-sync подканала (§4.2/§6). */
    private suspend fun selfSyncLoop(inbox: String) {
        var after = 0L
        while (coroutineContext.isActive) {
            try {
                val client = gateway.clientOrNull() ?: run { delay(IDLE_BACKOFF_MS); return }
                val result = client.poll(inbox, after, WAIT_SECONDS)
                runCatching { onRelayOk() }
                for (item in result.items) {
                    runCatching { onSelfSync(String(item.data, Charsets.UTF_8)) }
                    after = item.seq
                }
                if (result.items.isNotEmpty()) {
                    runCatching { client.ack(inbox, after) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { onRelayFail() }
                DiagnosticLog.e(DIAG_TAG, e) { "self-sync poll ошибка ящик=${DiagnosticLog.mailbox(inbox)}" }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    /**
     * Долгий цикл приёма одного входящего подканала (на IO — блокирующий poll).
     *
     * ACK ТОЛЬКО ПО УСПЕШНО ОБРАБОТАННОМУ. Раньше курсор двигался и `ack`
     * отправлялся даже когда `dispatch` падал (например, слой A/B не снялся из-за
     * рассинхронизации храповика) — сервер удалял конверт, и сообщение
     * терялось безвозвратно. Теперь при сбое курсор останавливается, конверт
     * остаётся в очереди и обрабатывается снова.
     *
     * Чтобы один «отравленный» конверт не блокировал очередь навсегда, попытки
     * считаются: после [MAX_DISPATCH_ATTEMPTS] неудач конверт подтверждается с
     * явной записью в журнал (лучше потерять один нерасшифровываемый конверт,
     * чем остановить переписку).
     */
    private suspend fun pollLoop(contact: StoredContact, inbox: String) {
        var after = 0L
        val failures = HashMap<Long, Int>()
        DiagnosticLog.event(
            DIAG_TAG, "poll старт",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "ящик" to DiagnosticLog.mailbox(inbox),
        )
        while (coroutineContext.isActive) {
            try {
                val client = gateway.clientOrNull() ?: run { delay(IDLE_BACKOFF_MS); return }
                val result = client.poll(inbox, after, WAIT_SECONDS)
                // Ответ релея получен — цепь до onion живая. Отмечаем ДО разбора
                // конвертов: сбой расшифровки к состоянию сети не относится.
                runCatching { onRelayOk() }
                if (result.items.isNotEmpty()) {
                    DiagnosticLog.event(
                        DIAG_TAG, "poll получено",
                        "контакт" to DiagnosticLog.shortId(contact.contact.id),
                        "штук" to result.items.size,
                    )
                }
                var ackUpTo = after
                var stalled = false
                for (item in result.items) {
                    val ok = runCatching {
                        dispatch(contact, String(item.data, Charsets.UTF_8), transport = client.transportKind)
                    }
                        .onFailure { e ->
                            DiagnosticLog.e(DIAG_TAG, e) {
                                "dispatch ошибка контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                                    "seq=${item.seq}"
                            }
                        }
                        .isSuccess
                    if (ok) {
                        failures.remove(item.seq)
                        ackUpTo = item.seq
                        continue
                    }
                    val attempts = (failures[item.seq] ?: 0) + 1
                    failures[item.seq] = attempts
                    if (attempts >= MAX_DISPATCH_ATTEMPTS) {
                        DiagnosticLog.w(DIAG_TAG) {
                            "конверт seq=${item.seq} не обработан за $attempts попыток — " +
                                "подтверждаем и пропускаем, иначе очередь встанет навсегда " +
                                "(контакт=${DiagnosticLog.shortId(contact.contact.id)})"
                        }
                        failures.remove(item.seq)
                        ackUpTo = item.seq
                        continue
                    }
                    // Курсор дальше не двигаем: порядок конвертов важен для храповика.
                    stalled = true
                    break
                }
                if (ackUpTo > after) {
                    after = ackUpTo
                    runCatching { client.ack(inbox, after) }
                }
                if (stalled) delay(ERROR_BACKOFF_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { onRelayFail() }
                DiagnosticLog.e(DIAG_TAG, e) {
                    "poll ошибка контакт=${DiagnosticLog.shortId(contact.contact.id)} " +
                        "ящик=${DiagnosticLog.mailbox(inbox)}"
                }
                delay(ERROR_BACKOFF_MS)
            }
        }
    }

    private suspend fun dispatch(
        contact: StoredContact,
        json: String,
        receivedAtMs: Long = System.currentTimeMillis(),
        transport: RelayTransportKind? = null,
    ) {
        val channel = WireChannel.resolve(json)
        DiagnosticLog.event(
            DIAG_TAG, "вход",
            "контакт" to DiagnosticLog.shortId(contact.contact.id),
            "канал" to channel.ifBlank { WireChannel.MESSAGE },
            "байт" to json.length,
            "транспорт" to transport?.name,
        )
        when (channel) {
            WireChannel.CALL -> onCall(contact, json, receivedAtMs)
            WireChannel.CONTROL -> onControl(contact, json)
            WireChannel.KEYX -> onKeyExchange(contact, json)
            else -> onMessage(contact, json, transport)
        }
    }

    private companion object {
        const val DIAG_TAG = "relay-rx"
        const val WAIT_SECONDS = 30
        const val RECONCILE_INTERVAL_MS = 20_000L
        const val IDLE_BACKOFF_MS = 5_000L
        const val ERROR_BACKOFF_MS = 8_000L
        const val QUEUE_FULL_BACKOFF_MS = 60_000L

        /**
         * Сколько раз пытаться обработать один конверт до того, как признать его
         * безнадёжным и подтвердить. Компромисс: не терять сообщения из-за
         * временных сбоев, но и не давать одному битому конверту остановить
         * очередь навсегда.
         */
        const val MAX_DISPATCH_ATTEMPTS = 5

        /** Служебный ключ self-sync цикла в [loops] (не конфликтует с accountId). */
        const val SELF_SYNC_KEY = "__self_sync__"

        /** Префикс ключа цикла закрытого режима в [loops]: за ним — id ящика. */
        const val LOCKED_PREFIX = "__locked__:"
    }
}
