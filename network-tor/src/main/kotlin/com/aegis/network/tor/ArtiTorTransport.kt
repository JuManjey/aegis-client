package com.aegis.network.tor

import android.content.Context
import android.os.SystemClock
import com.aegis.core.common.security.BuiltInBridge
import com.aegis.core.common.security.BuiltInBridges
import com.aegis.core.common.security.PtBinaryNames
import com.aegis.core.common.security.PtTransportSpec
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.TorMode
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.crypto.CryptoModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Реальный транспорт поверх встроенного Tor (Arti, §7.2/§10.5/§10.11).
 *
 * Оборачивает нативное ядро (`libaegis_core.so`, фича `tor`) через фасад
 * [CryptoModule.tor]: поднимает Tor, доводит bootstrap и открывает локальный
 * SOCKS5-прокси, через который идёт весь трафик клиента. Статус bootstrap
 * периодически опрашивается и транслируется в [status] для индикатора (§13).
 *
 * Мосты / pluggable transports (obfs4/snowflake/meek, §14.7) берутся из
 * [networkSettingsProvider] в момент применения режима — смена мостов «на лету»
 * выполняется повторным [applyMode] (Rust перезапускает клиент с новым конфигом).
 *
 * Если нативная фича `tor` не собрана — использовать [FakeTorTransport]
 * (выбор делает DI-контейнер приложения). Здесь предполагается, что
 * [CryptoModule.hasTorSupport] == true.
 */
class ArtiTorTransport(
    appContext: Context,
    private val networkSettingsProvider: () -> TorNetworkSettings,
    private val scope: CoroutineScope,
) : TorTransport {

    private val appContext = appContext.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<TorStatus>(TorStatus.Disabled)
    override val status: StateFlow<TorStatus> = _status.asStateFlow()

    @Volatile
    private var currentSocksPort: Int? = null
    override val socksPort: Int?
        get() = currentSocksPort

    @Volatile
    private var statusChangedAt = 0L
    override val statusChangedAtElapsed: Long
        get() = statusChangedAt

    /** Задача опроса статуса bootstrap; отменяется при остановке/смене режима. */
    private var pollJob: Job? = null

    /** Отпечаток мостов/PT последнего успешного старта — чтобы не рвать живой Tor. */
    @Volatile
    private var lastClientConfigKey: String? = null

    /**
     * Последний применённый режим. Нужен [restart]: сторож знает только «надо
     * перезапустить», а какой режим при этом восстанавливать — знаем мы. Без
     * этого перезапуск в режиме OFF молча поднимал бы Tor, который пользователь
     * выключил.
     */
    @Volatile
    private var lastMode: TorMode = TorMode.OFF

    /** Сериализует старт/стоп: сторож и UI дёргают транспорт из разных корутин. */
    private val lifecycleLock = Any()

    override suspend fun applyMode(mode: TorMode) = withContext(Dispatchers.IO) {
        lastMode = mode
        synchronized(lifecycleLock) {
            when (mode) {
                // Режим без Tor: Arti не работает вообще, а не «простаивает».
                TorMode.OFF -> stopInternal()
                TorMode.ALWAYS -> startInternal()
            }
        }
    }

    override suspend fun restart(reason: String) = withContext(Dispatchers.IO) {
        val mode = lastMode
        if (mode == TorMode.OFF) {
            DiagnosticLog.event(DIAG_TAG, "перезапуск пропущен: Tor выключен", "причина" to reason)
            return@withContext
        }
        synchronized(lifecycleLock) {
            DiagnosticLog.event(
                DIAG_TAG, "принудительный перезапуск Tor",
                "причина" to reason,
                "было" to _status.value.toString(),
                "socks" to currentSocksPort,
            )
            // Гасим ПОЛНОСТЬЮ: stopInternal чистит lastClientConfigKey, поэтому
            // следующий startInternal не сработает «пропущен: тот же конфиг».
            stopInternal()
            startInternal()
        }
    }

    override suspend fun shutdown() = withContext(Dispatchers.IO) {
        lastMode = TorMode.OFF
        synchronized(lifecycleLock) { stopInternal() }
    }

    private fun stopInternal() {
        DiagnosticLog.event(DIAG_TAG, "Tor остановлен", "было" to _status.value.toString())
        pollJob?.cancel()
        pollJob = null
        currentSocksPort = null
        lastClientConfigKey = null
        CryptoModule.tor.stop()
        publish(TorStatus.Disabled)
    }

    private fun startInternal() {
        val net = networkSettingsProvider()
        val configKey = net.torClientRestartToken()
        val alreadyUp = currentSocksPort != null &&
            lastClientConfigKey == configKey &&
            _status.value !is TorStatus.Disabled &&
            _status.value !is TorStatus.Error &&
            _status.value !is TorStatus.Unavailable
        if (alreadyUp) {
            DiagnosticLog.event(DIAG_TAG, "Tor старт пропущен: тот же конфиг", "socks" to currentSocksPort)
            return
        }

        // Останавливаем предыдущий опрос перед перезапуском (смена мостов/режима).
        pollJob?.cancel()
        publish(TorStatus.Starting)

        val configJson = buildConfigJson()
        DiagnosticLog.event(
            DIAG_TAG, "Tor старт",
            "мосты" to net.bridgesEnabled,
            "встроенныйМост" to net.builtInBridge.name,
            "строкМостов" to net.bridgeLines.size,
        )
        val result = CryptoModule.tor.start(configJson)
        if (result == null) {
            currentSocksPort = null
            lastClientConfigKey = null
            DiagnosticLog.e(DIAG_TAG) { "Tor недоступен в сборке" }
            publish(TorStatus.Error("Встроенный Tor недоступен в сборке"))
            return
        }
        if (result.startsWith("+")) {
            currentSocksPort = result.drop(1).toIntOrNull()
            lastClientConfigKey = configKey
            DiagnosticLog.event(DIAG_TAG, "Tor поднят", "socks" to currentSocksPort)
            startPolling()
        } else {
            currentSocksPort = null
            lastClientConfigKey = null
            val err = result.removePrefix("-").ifBlank { "неизвестная ошибка Tor" }
            DiagnosticLog.e(DIAG_TAG) { "Tor не стартовал: $err" }
            publish(TorStatus.Error(err))
        }
    }

    private fun startPolling() {
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val statusJson = CryptoModule.tor.statusJson()
                if (statusJson != null) {
                    val next = parseStatus(statusJson)
                    val prev = _status.value
                    if (shouldLogTorChange(prev, next)) {
                        DiagnosticLog.event(
                            "tor", "статус",
                            "было" to prev.toString(),
                            "стало" to next.toString(),
                            "socks" to currentSocksPort,
                        )
                    }
                    publish(next)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Публикует статус и обновляет [statusChangedAtElapsed] ТОЛЬКО при реальном
     * изменении.
     *
     * Штамп времени обязан двигаться на прогресс bootstrap (40 % → 45 %), но НЕ
     * на повторную публикацию того же значения: иначе сторож, который ищет
     * «висит на одном месте дольше N», никогда бы не сработал — опрос идёт каждые
     * 700 мс и обновлял бы штамп вечно.
     */
    private fun publish(next: TorStatus) {
        if (_status.value == next) return
        statusChangedAt = SystemClock.elapsedRealtime()
        _status.value = next
    }

    /** Собирает JSON-конфиг для нативного `nativeTorStart`. */
    private fun buildConfigJson(): String {
        val stateDir = File(appContext.filesDir, "tor/state").apply { mkdirs() }
        val cacheDir = File(appContext.cacheDir, "tor/cache").apply { mkdirs() }
        val net = networkSettingsProvider()

        // Источник строк мостов: встроенный набор (§14.7) ИЛИ свои строки.
        val rawLines: List<String> =
            if (net.builtInBridge != BuiltInBridge.NONE) BuiltInBridges.linesFor(net.builtInBridge)
            else net.bridgeLines

        // Отбрасываем мосты с управляемым PT, бинарь которого не вложен в сборку
        // под текущее ABI (иначе Arti не сможет запустить транспорт). «Ванильные»
        // строки (первый токен — не известный нам PT) пропускаем в Arti как есть.
        val usableLines = rawLines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { line ->
                val proto = PtBinaryNames.protocolOf(line) ?: return@filter true
                if (PtBinaryNames.binaryFor(proto) == null) true // не наш PT — пусть решает Arti
                else PtBinaries.binaryPathForProtocol(appContext, proto) != null
            }

        val useBridges = net.bridgesEnabled && usableLines.isNotEmpty()

        // Автовывод transports: группируем нужные протоколы по бинарю и
        // подставляем реальный путь к вложенному PT из nativeLibraryDir. Явно
        // заданные net.transports имеют приоритет для перечисленных протоколов.
        val explicit: Map<String, PtTransportSpec> = net.transports
            .flatMap { spec -> spec.protocols.map { it.lowercase() to spec } }
            .toMap()
        val byBinaryPath = LinkedHashMap<String, MutableList<String>>()
        val explicitByPath = LinkedHashMap<String, PtTransportSpec>()
        if (useBridges) {
            for (line in usableLines) {
                val proto = PtBinaryNames.protocolOf(line) ?: continue
                val custom = explicit[proto]
                if (custom != null && custom.path.isNotBlank()) {
                    byBinaryPath.getOrPut(custom.path) { mutableListOf() }
                    if (proto !in byBinaryPath[custom.path]!!) byBinaryPath[custom.path]!!.add(proto)
                    explicitByPath[custom.path] = custom
                    continue
                }
                val path = PtBinaries.binaryPathForProtocol(appContext, proto) ?: continue
                byBinaryPath.getOrPut(path) { mutableListOf() }
                if (proto !in byBinaryPath[path]!!) byBinaryPath[path]!!.add(proto)
            }
        }

        val obj: JsonObject = buildJsonObject {
            put("stateDir", stateDir.absolutePath)
            put("cacheDir", cacheDir.absolutePath)
            put("socksPort", 0) // 0 = ОС назначит свободный локальный порт
            putJsonArray("bridges") {
                if (useBridges) usableLines.forEach { add(it) }
            }
            putJsonArray("transports") {
                if (useBridges) {
                    byBinaryPath.forEach { (path, protocols) ->
                        add(buildJsonObject {
                            putJsonArray("protocols") { protocols.forEach { add(it) } }
                            put("path", path)
                            putJsonArray("arguments") {
                                explicitByPath[path]?.arguments?.forEach { add(it) }
                            }
                        })
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** Маппит JSON-статус нативного слоя в [TorStatus] для UI. */
    private fun parseStatus(statusJson: String): TorStatus = runCatching {
        val obj = json.parseToJsonElement(statusJson).jsonObject
        val state = obj["state"]?.jsonPrimitive?.content ?: "disabled"
        val progress = obj["progress"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val error = obj["error"]?.jsonPrimitive?.content.orEmpty()
        obj["socksPort"]?.jsonPrimitive?.content?.toIntOrNull()?.let { p ->
            if (p > 0) currentSocksPort = p
        }
        when (state) {
            "connected" -> TorStatus.Connected
            "bootstrapping" -> TorStatus.Bootstrapping(progress.coerceIn(0, 100))
            "starting" -> TorStatus.Starting
            "error" -> TorStatus.Error(error.ifBlank { "ошибка Tor" })
            else -> TorStatus.Disabled
        }
    }.getOrElse { TorStatus.Error("не удалось прочитать статус Tor") }

    /** Bootstrap пишем редко (каждый 25%), остальные смены — всегда. */
    private fun shouldLogTorChange(prev: TorStatus, next: TorStatus): Boolean {
        if (prev == next) return false
        val prevBoot = prev as? TorStatus.Bootstrapping
        val nextBoot = next as? TorStatus.Bootstrapping
        if (prevBoot != null && nextBoot != null) {
            return nextBoot.progress / 25 != prevBoot.progress / 25
        }
        return true
    }

    private companion object {
        const val POLL_INTERVAL_MS = 700L
        const val DIAG_TAG = "tor"
    }
}
