package com.aegis.messenger.messaging

import android.util.Base64
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.MetadataMinimization
import com.aegis.network.tor.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Прикрытие факта отправки — cover-traffic / dummy-дропы (`docs/multidevice.md`
 * §12, вариант №3a; `meta.minimize` §8 ТЗ). Дополняет [FanoutJitter]:
 * тот УБИРАЕТ тайминг-корреляцию между реальными дропами одного сообщения, а этот
 * ПРЯЧЕТ сам факт/объём отправки в фоновом шуме.
 *
 * Две меры (обе — только при `meta.minimize=MAXIMUM`, пресет «Параноик»):
 *  1. **Фоновые decoy-дропы** ([start]): планировщик через случайные интервалы
 *     постит фиктивный конверт в mailbox из бул decoy-адресов ([decoyPool]).
 *     Реальная отправка тонет в этом потоке — наблюдатель за релеем не отличает
 *     «пользователь молчит» от «пользователь пишет».
 *  2. **Паддинг fan-out** ([padCount]/[decoyDrops]): реальный per-device батч
 *     (§6) разбавляется M фиктивными дропами, так что число дропов больше не
 *     раскрывает число устройств-адресатов (утечка «N устройств → N дропов»).
 *
 * Неотличимость от реального (MD-2): decoy идёт тем же `POST /v1/mailbox/<id>`
 * (`application/octet-stream`), что и настоящий дроп; [randomMailboxId] выдаёт
 * 32-байтовый id в том же url-safe base64 без паддинга, что и per-device HKDF-
 * mailbox (§4.1), проходящий серверный `validate_id`; [randomDecoyPayload] — шум
 * правдоподобного размера. Сервер видит только очередной opaque-дроп.
 *
 * Честные границы (§12):
 *  - Это СМЯГЧЕНИЕ, а не устранение: постоянный constant-rate padding на мобильном
 *    непрактичен (батарея/трафик, §7.4), поэтому шум вероятностный и включён лишь в
 *    «Параноике». Глобальный пассивный наблюдатель с долгой статистикой всё ещё
 *    может вести трафик-анализ (напр. отличить одноразовые mailbox-паддинга от
 *    рекуррентных реальных — decoy-пул это лишь ослабляет). Основную защиту даёт Tor.
 *  - E2EE (слои A/B) от cover-traffic НЕ зависит; decoy НЕ несёт реального контента
 *    (случайные байты), его никто не слушает, он истекает по TTL (MD-7). Своя
 *    криптография не вводится — только ГСЧ ([Random], MD-5). Крипто-ядро/backend не
 *    трогаются (MD-6).
 */
class CoverTraffic(
    private val levelProvider: () -> MetadataMinimization,
    /** Источник [RelayClient] «на лету» (обычно `relayGateway::clientOrNull`). */
    private val clientProvider: () -> RelayClient? = { null },
    /** Активен ли аккаунт (разблокирован + слой B доступен): вне этого шум не шлём. */
    private val activeProvider: () -> Boolean = { false },
    private val scope: CoroutineScope? = null,
    private val random: Random = Random.Default,
    /** Приостановка на [ms] мс (инъекция для тестов; по умолчанию корутинный delay). */
    private val sleep: suspend (ms: Long) -> Unit = { ms -> if (ms > 0L) delay(ms) },
) {
    /** Включён ли cover-traffic (только пресет «Параноик»). */
    val enabled: Boolean
        get() = levelProvider() == MetadataMinimization.MAXIMUM

    /**
     * Стабильный на время сессии пул decoy-mailbox: фоновые дропы шлются в
     * рекуррентные адреса, чтобы наблюдатель не отличал их от реальных
     * conversation-mailbox'ов (которые тоже получают повторный трафик). Ленивый —
     * пул не создаётся, пока cover-traffic ни разу не понадобился.
     */
    private val decoyPool: List<String> by lazy { List(DECOY_POOL_SIZE) { randomMailboxId() } }

    /**
     * Случайный mailbox-id, неотличимый по формату от реального per-device HKDF-
     * адреса (§4.1): 32 байта → url-safe base64 без паддинга (43 симв.), проходит
     * серверный `validate_id` (8..64 url-safe base64).
     */
    fun randomMailboxId(): String {
        val bytes = ByteArray(MAILBOX_ID_BYTES).also { random.nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Фиктивный шифртекст-шум правдоподобного размера (мимикрия под конверт). */
    fun randomDecoyPayload(): ByteArray {
        val span = DECOY_MAX_BYTES - DECOY_MIN_BYTES + 1
        val size = DECOY_MIN_BYTES + random.nextInt(span)
        return ByteArray(size).also { random.nextBytes(it) }
    }

    /**
     * Сколько decoy-дропов подмешать к одному реальному fan-out батчу (§6). При
     * выключенном cover-traffic — 0 (прежнее поведение); иначе `[0..MAX_PAD_DROPS]`
     * (переменное, чтобы размер батча не был предсказуем).
     */
    fun padCount(): Int = if (!enabled) 0 else random.nextInt(MAX_PAD_DROPS + 1)

    /**
     * [n] свежих decoy-дропов `(mailbox, payload)` для паддинга реального батча.
     * Свежие (не из пула) id уместны: они уходят В СОСТАВЕ реального всплеска fan-
     * out, где и настоящая доставка на новое устройство даёт впервые виденный id.
     */
    fun decoyDrops(n: Int): List<Pair<String, ByteArray>> =
        if (n <= 0) emptyList() else List(n) { randomMailboxId() to randomDecoyPayload() }

    /** Случайный интервал между фоновыми decoy-дропами (мс), в границах пресета. */
    fun nextIntervalMs(): Long =
        COVER_MIN_INTERVAL_MS + random.nextLong(COVER_MAX_INTERVAL_MS - COVER_MIN_INTERVAL_MS + 1L)

    @Volatile
    private var job: Job? = null

    /** Запускает фоновый планировщик decoy-дропов (идемпотентно). No-op без scope. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val s = scope ?: return
        DiagnosticLog.event(DIAG_TAG, "планировщик запущен", "включён" to enabled)
        job = s.launch(Dispatchers.IO) { loop() }
    }

    /** Останавливает планировщик. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (coroutineContext.isActive) {
            val client = clientProvider()
            if (enabled && activeProvider() && client != null) {
                emitOnce(client)
                sleep(nextIntervalMs())
            } else {
                // Выкл / заблокировано / нет Tor — простаиваем и переоцениваем.
                sleep(IDLE_BACKOFF_MS)
            }
        }
    }

    /**
     * Постит один фоновый decoy: mailbox из рекуррентного пула + шумовой payload.
     * Best-effort — сбой POST игнорируется (это и есть «фиктивный» трафик).
     */
    private suspend fun emitOnce(client: RelayClient) {
        val mailbox = decoyPool[random.nextInt(decoyPool.size)]
        val payload = randomDecoyPayload()
        val ok = runCatching { withContext(Dispatchers.IO) { client.post(mailbox, payload) } }.isSuccess
        DiagnosticLog.d(DIAG_TAG) { "decoy байт=${payload.size} ок=$ok" }
    }

    companion object {
        /** Длина случайного mailbox-id в байтах (как у реального HKDF-адреса). */
        const val MAILBOX_ID_BYTES = 32

        /** Границы размера шумового payload (правдоподобная мимикрия под конверт). */
        const val DECOY_MIN_BYTES = 256
        const val DECOY_MAX_BYTES = 1_280

        /** Максимум decoy-дропов на один реальный fan-out батч. */
        const val MAX_PAD_DROPS = 3

        /** Границы интервала между фоновыми decoy-дропами (Параноик). */
        const val COVER_MIN_INTERVAL_MS = 45_000L
        const val COVER_MAX_INTERVAL_MS = 180_000L

        /** Простой планировщика, когда cover-traffic неактивен. */
        const val IDLE_BACKOFF_MS = 15_000L

        /** Размер рекуррентного пула decoy-mailbox для фоновых дропов. */
        const val DECOY_POOL_SIZE = 8
        const val DIAG_TAG = "cover"
    }
}
