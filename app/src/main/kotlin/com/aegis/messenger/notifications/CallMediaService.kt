package com.aegis.messenger.notifications

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aegis.core.common.diag.DiagnosticLog

/**
 * Foreground-service микрофона (и камеры для видео) на время активного звонка.
 *
 * Android 14+ запрещает AudioRecord без FGS типа `microphone`, если Activity
 * считается фоном. Принятие звонка с уведомления и смена аудиофокуса на Samsung
 * легко попадают в этот случай — без сервиса процесс убивается на старте медиа.
 *
 * ## Почему сервис обязан завершать себя при неудаче
 *
 * `startForegroundService()` даёт процессу контракт: в течение нескольких секунд
 * обязан пройти `startForeground()`. Если `startForeground()` бросил исключение
 * (на Android 14+ это штатный `SecurityException`/`ForegroundServiceStartNotAllowedException`,
 * когда приложение в этот момент не «видимо» и потому не имеет while-in-use
 * доступа к микрофону), контракт остаётся невыполненным и система через
 * несколько секунд убивает процесс
 * `RemoteServiceException$ForegroundServiceDidNotStartInTimeException` — снаружи
 * это выглядит как «приложение просто закрылось сразу после нажатия Принять».
 * Поэтому неудача ОБЯЗАНА заканчиваться [stopSelf]: контракт снимается, процесс
 * живёт, а звонок честно докладывает об отказе ([Result.DENIED]) и не поднимает
 * AudioRecord, который всё равно был бы убит.
 */
class CallMediaService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        if (!startAsForeground(video)) {
            // Снимаем контракт startForegroundService() — иначе система убьёт
            // процесс за «не вызвал startForeground».
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLog.event(DIAG_TAG, "сервис медиазвонка уничтожен")
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    /** @return `true`, если FGS реально поднялся. */
    private fun startAsForeground(video: Boolean): Boolean {
        val notifier = AegisNotifier(this)
        val notification = notifier.buildCallMediaNotification()
        val micGranted = granted(Manifest.permission.RECORD_AUDIO)
        val cameraGranted = granted(Manifest.permission.CAMERA)
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Тип объявляем только под выданное разрешение: `startForeground` с
            // типом microphone без RECORD_AUDIO — гарантированный SecurityException
            // на Android 14+, а с ним и смерть процесса по таймауту контракта.
            if (micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (video && cameraGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        // Android 14+ требует НЕПУСТОЙ тип: с `type = 0` прилетит
        // MissingForegroundServiceTypeException. Без микрофона поднимать сервис
        // незачем — звонка всё равно не будет.
        val typeOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || type != 0
        val failure = if (typeOk) {
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    AegisNotifier.CALL_MEDIA_NOTIFICATION_ID,
                    notification,
                    type,
                )
            }.exceptionOrNull()
        } else {
            IllegalStateException("нет разрешения микрофона для FGS типа microphone")
        }
        val ok = failure == null
        if (!ok) {
            DiagnosticLog.e(DIAG_TAG, failure) { "не удалось поднять FGS медиазвонка" }
        }
        DiagnosticLog.sync(
            DIAG_TAG, "сервис медиазвонка",
            "видео" to video,
            "fgs" to ok,
            "микрофон" to micGranted,
            "камера" to cameraGranted,
            "тип" to type,
            "отказ" to failure?.javaClass?.simpleName,
        )
        publish(if (ok) Result.READY else Result.DENIED)
        return ok
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Итог попытки поднять FGS звонка. */
    enum class Result {
        /** FGS поднят: AudioRecord стартовать можно. */
        READY,

        /** Система отказала (нет while-in-use доступа / нет разрешения). */
        DENIED,

        /** Сервис не ответил за отведённое время. */
        TIMEOUT,
    }

    companion object {
        private const val EXTRA_VIDEO = "video"
        private const val DIAG_TAG = "call-svc"

        private val readyLock = Object()

        @Volatile
        private var result: Result? = null

        /** Поднят ли FGS звонка прямо сейчас (для повторных попыток). */
        val isForeground: Boolean get() = result == Result.READY

        fun start(context: Context, video: Boolean) {
            synchronized(readyLock) { result = null }
            val intent = Intent(context, CallMediaService::class.java)
                .putExtra(EXTRA_VIDEO, video)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                // На Android 12+ старт FGS из фона запрещён и бросает
                // ForegroundServiceStartNotAllowedException. Процесс при этом
                // цел — сервис просто не создан, контракта нет.
                DiagnosticLog.e(DIAG_TAG, it) { "старт CallMediaService отклонён" }
                publish(Result.DENIED)
            }
        }

        /**
         * Ждёт итог [startForeground]. WebRTC AudioRecord на Android 14+/Samsung
         * нельзя стартовать раньше FGS типа `microphone` — система убивает
         * процесс без Java-стека, поэтому решение о старте медиа принимается
         * ТОЛЬКО по [Result.READY].
         */
        fun awaitForeground(timeoutMs: Long): Result {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(readyLock) {
                while (result == null) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0L) break
                    runCatching { readyLock.wait(left) }
                }
                return result ?: Result.TIMEOUT
            }
        }

        fun stop(context: Context) {
            synchronized(readyLock) { result = null }
            runCatching { context.stopService(Intent(context, CallMediaService::class.java)) }
        }

        private fun publish(value: Result) {
            synchronized(readyLock) {
                result = value
                readyLock.notifyAll()
            }
        }
    }
}
