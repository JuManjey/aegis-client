package com.aegis.messenger.messaging

import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.TorNetworkSettings
import com.aegis.network.tor.SignedConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64

/**
 * Авто-обнаружение адресов релея (§12.2) — onion для режима Tor и HTTPS для
 * режима без Tor, — чтобы пользователь НЕ вводил их вручную и подхватывал
 * ротацию/резерв без пересборки приложения.
 *
 * Двухслойная модель:
 *  1) **Зашитые в сборку дефолты** ([defaultRelayOnion], [defaultRelayHttps],
 *     BuildConfig): свежая установка сразу знает релей — ноль ручного ввода.
 *  2) **Подписанный конфиг с сервера** (`GET /v1/config`): периодически тянется
 *     ТЕКУЩИМ транспортом (через Tor или по HTTPS) и обновляет адреса. Документ
 *     подписан ОФЛАЙН-ключом Ed25519, публичная часть которого зашита в APK
 *     ([relayConfigPubkeyHex]); подпись проверяется ДО применения. Приватного
 *     ключа нет ни на сервере, ни в клиенте → даже полностью скомпрометированный
 *     сервер не подменит адрес (и пины SPKI) — какой бы транспорт ни доставил документ.
 *
 * Инварианты безопасности:
 *  - **fail-closed**: нет pubkey / не прошла подпись / битый JSON → адреса НЕ
 *    меняются (остаются зашитые дефолты или ранее принятые).
 *  - **защита от отката**: документ со СТАРОЙ или равной версией игнорируется
 *    ([TorNetworkSettings.configVersion]).
 *  - **уважение ручного выбора**: если пользователь задал адреса вручную
 *    ([TorNetworkSettings.relayManual]) — авто-обновление их НЕ трогает.
 *  - E2EE (слои A/B) от этого механизма НЕ зависит.
 *
 * Класс намеренно без прямых зависимостей от Android BuildConfig/крипто-фасада —
 * они прокидываются из [com.aegis.messenger.AppContainer], что упрощает тесты.
 */
class ConfigSync(
    /** Публичный ключ проверки подписи (64 hex, зашит в сборку). Пусто → авто-обновление выкл. */
    private val relayConfigPubkeyHex: String,
    /** Зашитый в сборку onion-адрес релея по умолчанию. Пусто → нет дефолта. */
    private val defaultRelayOnion: String,
    /** Зашитый в сборку HTTPS-адрес того же релея. Пусто → нет дефолта. */
    private val defaultRelayHttps: String,
    /** Зашитые в сборку пины SPKI к [defaultRelayHttps]. */
    private val defaultHttpsPins: List<String>,
    /** versionCode приложения (для проверки minAppVersion документа). */
    private val appVersionCode: Long,
    /** Текущие настройки сети/Tor (читаются «на лету»). */
    private val settings: () -> TorNetworkSettings,
    /** Атомарно сохранить обновлённые настройки (поток + защищённое хранилище). */
    private val persist: suspend (TorNetworkSettings) -> Unit,
    /** Best-effort загрузка подписанного конфига текущим транспортом (или null). */
    private val fetchConfig: suspend () -> SignedConfig?,
    /** Проверка Ed25519-подписи над (docBytes, sigBytes) зашитым pubkey. */
    private val verify: (ByteArray, ByteArray) -> Boolean,
    /** Есть ли сейчас клиент релея в текущем режиме — иначе опрос откладывается. */
    private val transportReady: () -> Boolean,
    /** Долгоживущий scope приложения. */
    private val scope: CoroutineScope,
    /** Интервал периодического опроса (мс). */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    @Volatile
    private var started = false

    /** Внеочередной опрос (смена режима сети): не ждать [intervalMs]. */
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    @Volatile
    private var waitingForTransport = false

    /**
     * Ставит зашитые в сборку адреса, если пользователь ещё НЕ задал свои и поля
     * пусты. Не помечает [TorNetworkSettings.relayManual] — адреса остаются под
     * управлением авто-обновления.
     */
    suspend fun applyBakedDefaultIfNeeded() {
        val s = settings()
        val next = s.withBakedRelayIfNeeded(defaultRelayOnion, defaultRelayHttps, defaultHttpsPins)
        if (next == s) return
        DiagnosticLog.event(DIAG_TAG, "поставлены зашитые адреса релея")
        persist(next)
    }

    /**
     * Один цикл синхронизации: тянет подписанный конфиг, проверяет подпись/версию
     * и применяет новые адреса (если не ручные). Возвращает true, если что-то
     * обновилось. Любой сбой → false и адреса не меняются (fail-closed).
     */
    suspend fun syncNow(): Boolean {
        if (relayConfigPubkeyHex.isBlank()) {
            DiagnosticLog.d(DIAG_TAG) { "sync пропущен: нет pubkey в сборке" }
            return false
        }
        waitingForTransport = !transportReady()
        if (waitingForTransport) {
            DiagnosticLog.d(DIAG_TAG) { "sync пропущен: нет клиента релея в текущем режиме" }
            return false
        }
        val signed = fetchConfig() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "sync: конфиг не получен" }
            return false
        }
        val docBytes = decodeB64(signed.docB64) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "sync: docB64 не разобран" }
            return false
        }
        val sigBytes = decodeB64(signed.sigB64) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "sync: sigB64 не разобран" }
            return false
        }
        if (!verify(docBytes, sigBytes)) {
            DiagnosticLog.w(DIAG_TAG) { "sync: подпись НЕ прошла — адреса не меняем" }
            return false
        }
        val doc = ConfigDoc.parse(String(docBytes, Charsets.UTF_8)) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "sync: JSON документа битый" }
            return false
        }
        val current = settings()
        val updated = doc.applyTo(current, appVersionCode) ?: run {
            DiagnosticLog.d(DIAG_TAG) {
                "sync: не применён (версия ${doc.version} при текущей ${current.configVersion}, " +
                    "minAppVersion=${doc.minAppVersion}, у нас $appVersionCode)"
            }
            return false
        }
        if (updated == current) return false
        persist(updated)
        DiagnosticLog.event(
            DIAG_TAG, "конфиг применён",
            "версия" to doc.version,
            "onionЗадан" to (doc.onion != null),
            "httpsЗадан" to (doc.https != null),
            "пинов" to doc.httpsPins?.size,
            "вручную" to current.relayManual,
        )
        return true
    }

    /**
     * Запускает фоновый периодический опрос (идемпотентно). Сначала ставит
     * зашитые дефолты, затем в цикле раз в [intervalMs] пробует обновить адреса.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { applyBakedDefaultIfNeeded() }
            // Небольшая начальная задержка — дать транспорту подняться после старта.
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { syncNow() }
                // Транспорт ещё не готов (Tor поднимается после старта или смены
                // режима) — повторяем скоро, а не через [intervalMs].
                val wait = if (waitingForTransport) TRANSPORT_RETRY_MS else intervalMs
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Попросить внеочередную синхронизацию (после смены режима сети). */
    fun syncSoon() {
        wake.trySend(Unit)
    }

    private fun decodeB64(s: String): ByteArray? =
        runCatching { Base64.getMimeDecoder().decode(s.trim()) }.getOrNull()

    private companion object {
        /** По умолчанию — раз в 6 часов (баланс свежести и трафика). */
        const val DEFAULT_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val INITIAL_DELAY_MS = 15_000L
        const val TRANSPORT_RETRY_MS = 30_000L
        const val DIAG_TAG = "config-sync"
    }
}
