package com.aegis.messenger.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.messenger.AegisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-service фонового приёма (§14.5/§7.6).
 *
 * AEGIS не использует FCM/push (zero-trust к серверу и к Google): доставка
 * входящих держится на long-poll поверх Tor ([com.aegis.messenger.messaging.RelayReceiver],
 * живёт в [com.aegis.messenger.AppContainer.applicationScope]).
 *
 * ЧТО ИМЕННО УДЕРЖИВАЕТ СВЯЗЬ. Тут два РАЗНЫХ механизма, и путать их нельзя —
 * именно на этой путанице соединение и «засыпало»:
 *
 *  1. **Foreground-service** защищает ПРОЦЕСС от выгрузки (ActivityManager/LMK
 *     не убивают приложение с видимым уведомлением). Он НЕ мешает системе
 *     усыплять процессор.
 *  2. **[PowerManager.WakeLock] (PARTIAL_WAKE_LOCK)** удерживает ПРОЦЕССОР. Без
 *     него в Doze / App Standby ядро приостанавливает наши потоки: `delay()`
 *     откладывается (в реальном журнале интервал 20 с растягивался до 47–62 с),
 *     а сокет long-poll висит непрочитанным, пока цепь Tor не отвалится по
 *     таймауту. Снаружи это выглядит как «Tor уснул и больше не проснулся».
 *
 * Поэтому здесь держится и то, и другое.
 *
 * ТИП FGS. На Android 15+ (API 35) у типа `dataSync` есть жёсткий лимит —
 * 6 часов за сутки, после чего система вызывает [onTimeout] и сервис обязан
 * остановиться; процесс становится кэшированным и умирает. Для мессенджера без
 * push это означает «после шести часов приложение глухое». Поэтому с API 34
 * используется `specialUse` (лимита по времени нет), а `dataSync` остаётся
 * только для API 29..33, где ограничений ещё не было. Оба типа объявлены в
 * манифесте; рантайм выбирает один.
 *
 * КОГДА ОСТАНАВЛИВАЕТСЯ. Решение принимает не сервис, а
 * [com.aegis.messenger.AppContainer.receivingAllowedNow]: при открытом аккаунте
 * приём идёт всегда, при закрытом — в очередь без расшифровки, если пользователь
 * это разрешил (§8 `deliverWhileLocked`). Прежнее жёсткое условие «аккаунт не
 * Unlocked» гасило сервис на автоблокировке по бездействию, а вместе с ним замок
 * процессора и цепи Tor: снаружи это и выглядело как «связь засыпает со временем».
 */
class MessageSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var keepAliveJob: Job? = null

    /**
     * Замок процессора. Берётся с ТАЙМАУТОМ и периодически продлевается
     * ([keepAliveJob]) вместо «взять навсегда»: если сервис умрёт способом, при
     * котором [onDestroy] не выполнится (нативный сбой, kill -9), замок сам
     * отпустится через [WAKE_LOCK_TIMEOUT_MS], а не будет держать процессор до
     * перезагрузки телефона.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        DiagnosticLog.event(DIAG_TAG, "сервис приёма создан")
        SystemGrants.logState(this)
        startAsForeground()
        acquireWakeLock()

        val container = (application as? AegisApplication)?.container
        if (container == null) {
            stopSelf()
            return
        }
        // Останавливаемся, когда приём становится невозможен.
        //
        // Раньше условием было «аккаунт не Unlocked», и автоблокировка по
        // бездействию гасила сервис вместе с замком процессора — именно из-за
        // этого связь «засыпала» после того, как телефон полежал заблокированным.
        // Теперь решение принимает контейнер ([AppContainer.receivingAllowedNow]):
        // при закрытом аккаунте приём продолжается в очередь, если пользователь
        // это разрешил (§8 `deliverWhileLocked`), и прекращается, если нет.
        stateJob = scope.launch {
            // Процесс мог подняться БЕЗ интерфейса: система перезапустила сервис
            // после выгрузки (START_STICKY). Тогда контейнер ещё не прочитал ни
            // настроек, ни аккаунта, состояние `None` остановило бы приём, и до
            // телефона нельзя было бы дозвониться, пока его не откроют руками.
            // Если приложение уже открыто, вызов ничего не делает.
            runCatching { container.initialize() }
                .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "контейнер не поднялся из сервиса приёма" } }
            container.accountManager.state.collect {
                if (!container.receivingAllowedNow()) {
                    DiagnosticLog.event(DIAG_TAG, "приём больше не разрешён — остановка сервиса")
                    stopSelfSafely()
                }
            }
        }
        startKeepAlive()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // На случай рестарта системой (START_STICKY) — гарантируем foreground.
        startAsForeground()
        acquireWakeLock()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Пользователь смахнул приложение из списка недавних. Приём при этом
     * останавливать НЕЛЬЗЯ: аккаунт остаётся разблокированным, и пользователь
     * ждёт звонки и сообщения. Просто подтверждаем foreground-состояние заново —
     * некоторые прошивки в этот момент сбрасывают уведомление сервиса.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.event(DIAG_TAG, "задача снята из недавних — приём продолжается")
        startAsForeground()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Android 15+ (API 35): система исчерпала бюджет времени для типа FGS.
     *
     * ЗДЕСЬ НЕЛЬЗЯ ДЕЛАТЬ НИЧЕГО, КРОМЕ ОСТАНОВКИ. К моменту вызова система уже
     * НЕ считает сервис foreground'ом и даёт несколько секунд на [stopSelf]; если
     * не остановиться, процесс падает с внутренним исключением
     * («A foreground service of <type> did not stop within its timeout»). Пытаться
     * переобъявиться другим типом в этом окне — гонка с системным таймером, и
     * проигрыш в ней стоит краха процесса, то есть полной потери приёма вместо
     * временной.
     *
     * По построению сюда попасть не должны: бюджет времени существует только для
     * `dataSync`/`mediaProcessing`, а на всех API, где он есть (35+), мы работаем
     * как `specialUse` — без лимита (см. [foregroundType]). Поэтому вызов этого
     * метода — сигнал, что предположение о типе FGS где-то нарушилось, и он
     * записывается в журнал как ошибка, а не как рядовое событие.
     *
     * Приём возобновится при следующем выводе приложения на передний план:
     * [com.aegis.messenger.AppContainer] запускает сервис по разблокировке
     * аккаунта, а выход в foreground сбрасывает и системный бюджет.
     */
    override fun onTimeout(startId: Int) {
        handleTimeout("onTimeout(startId=$startId)")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout("onTimeout(startId=$startId, тип=$fgsType)")
    }

    private fun handleTimeout(from: String) {
        DiagnosticLog.e(DIAG_TAG) {
            "система исчерпала бюджет FGS ($from) при типе ${foregroundType()} — " +
                "останавливаем приём; он возобновится при следующем открытии приложения"
        }
        stopSelfSafely()
    }

    override fun onDestroy() {
        running = false
        DiagnosticLog.event(DIAG_TAG, "сервис приёма уничтожен")
        keepAliveJob?.cancel()
        stateJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // --- Foreground -----------------------------------------------------------

    private fun startAsForeground() {
        // На Android 12+ старт FGS из фона может быть запрещён — гасим исключение,
        // но не молча: без foreground-статуса прошивка заморозит свёрнутое
        // приложение, и звонки перестанут доходить до его открытия.
        runCatching { promoteToForeground() }
            .onFailure { DiagnosticLog.e(DIAG_TAG, it) { "сервис приёма не переведён в foreground" } }
    }

    /**
     * Система может снять foreground-статус без нашего участия — так она
     * поступает с приложением в режиме батареи «Ограничено». Приём после этого
     * живёт до первой заморозки процесса, и в самих циклах приёма этого не видно.
     */
    private fun warnIfDemoted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE) return
        DiagnosticLog.w(DIAG_TAG) {
            "система сняла с сервиса приёма foreground-статус " +
                "(фонОграничен=${SystemGrants.backgroundRestricted(this)})"
        }
    }

    /** Публикует уведомление и переводит сервис в foreground. */
    private fun promoteToForeground() {
        val torEnabled = (application as? AegisApplication)?.container?.securitySettings?.value?.torEnabled ?: true
        val notification = AegisNotifier(this).buildServiceNotification(torEnabled)
        ServiceCompat.startForeground(
            this,
            AegisNotifier.SERVICE_NOTIFICATION_ID,
            notification,
            foregroundType(),
        )
    }

    /**
     * Тип foreground-сервиса под текущий API.
     *
     * `specialUse` (API 34+) — единственный тип без лимита времени работы,
     * подходящий мессенджеру без push. `dataSync` оставлен для API 29..33: там
     * ограничений ещё нет, а тип точнее описывает происходящее. Оба объявлены в
     * манифесте, поэтому выбор в рантайме допустим.
     */
    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    // --- Замок процессора -----------------------------------------------------

    @Synchronized
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing != null && existing.isHeld) return
        val power = runCatching { getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        if (power == null) {
            DiagnosticLog.w(DIAG_TAG) { "PowerManager недоступен — соединение будет рваться в Doze" }
            return
        }
        val lock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
        if (lock == null) {
            DiagnosticLog.w(DIAG_TAG) { "не удалось взять замок процессора" }
            return
        }
        wakeLock = lock
        DiagnosticLog.event(DIAG_TAG, "замок процессора взят", "таймаутМс" to WAKE_LOCK_TIMEOUT_MS)
    }

    @Synchronized
    private fun renewWakeLock() {
        val lock = wakeLock ?: run { acquireWakeLock(); return }
        // acquire с таймаутом на НЕ-refcounted замке просто сдвигает дедлайн.
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
            .onFailure { DiagnosticLog.w(DIAG_TAG) { "продление замка процессора не удалось: ${it.message}" } }
    }

    @Synchronized
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
        DiagnosticLog.event(DIAG_TAG, "замок процессора отпущен")
    }

    /**
     * Периодически продлевает замок процессора и подтверждает foreground.
     *
     * Заодно это единственная в приложении «живая» точка, по которой в журнале
     * видно, СПАЛ ли процесс: если фактическая пауза между тиками заметно больше
     * запрошенной, значит замок не действует (например, отобран режимом
     * энергосбережения производителя) — и это стоит увидеть до жалоб на
     * пропавшие сообщения.
     */
    private fun startKeepAlive() {
        keepAliveJob = scope.launch {
            var previousTickAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                val drift = now - previousTickAt - KEEP_ALIVE_INTERVAL_MS
                previousTickAt = now
                renewWakeLock()
                warnIfDemoted()
                startAsForeground()
                if (drift > DRIFT_ALERT_MS) {
                    DiagnosticLog.w(DIAG_TAG) {
                        "процесс усыплялся: тик опоздал на ${drift}мс (замок процессора не действует; " +
                            "проверьте «Оптимизация батареи» для приложения)"
                    }
                } else {
                    DiagnosticLog.d(DIAG_TAG) { "приём жив, отклонение тика=${drift}мс" }
                }
            }
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        /**
         * Запустить сервис (вызывать из foreground, напр. после разблокировки —
         * иначе Android 12+ бросит ForegroundServiceStartNotAllowedException).
         */
        fun start(context: Context) {
            DiagnosticLog.event(DIAG_TAG, "запрос старта сервиса")
            val intent = Intent(context, MessageSyncService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                DiagnosticLog.e(DIAG_TAG, it) { "не удалось запустить сервис приёма" }
            }
        }

        /** Остановить сервис (разрешено и из фона). */
        fun stop(context: Context) {
            DiagnosticLog.event(DIAG_TAG, "запрос остановки сервиса")
            runCatching { context.stopService(Intent(context, MessageSyncService::class.java)) }
        }

        @Volatile
        private var running = false

        /**
         * Обновить текст уведомления работающего сервиса (сменился режим сети).
         * Новое уведомление с тем же id заменяет текущее; сервис не перезапускается —
         * из фона это и запрещено, и не нужно.
         */
        fun refreshNotification(context: Context) {
            if (!running) return
            val container = (context.applicationContext as? AegisApplication)?.container ?: return
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(context).notify(
                    AegisNotifier.SERVICE_NOTIFICATION_ID,
                    AegisNotifier(context).buildServiceNotification(container.securitySettings.value.torEnabled),
                )
            }
        }

        private const val DIAG_TAG = "sync-svc"

        /** Тег замка виден в `dumpsys power` — называем честно, без маскировки. */
        private const val WAKE_LOCK_TAG = "aegis:message-sync"

        /**
         * Срок замка процессора. 15 минут с продлением каждые 5: даже если
         * процесс умрёт «жёстко», замок не переживёт его дольше этого времени.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 15 * 60_000L

        /** Как часто продлевать замок и подтверждать foreground. */
        private const val KEEP_ALIVE_INTERVAL_MS = 5 * 60_000L

        /**
         * Насколько тик может опоздать, прежде чем это станет поводом для
         * предупреждения. 30 с — заведомо больше обычного планировочного джиттера
         * и заведомо меньше окон Doze (минуты).
         */
        private const val DRIFT_ALERT_MS = 30_000L
    }
}
