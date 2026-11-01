package com.aegis.messenger.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.messenger.MainActivity

/**
 * Выводит экран звонка на передний план при входящем вызове.
 *
 * ЧТО ИМЕННО ВОСПРОИЗВОДИТСЯ. В Telegram/WhatsApp экран звонка запускается В
 * МОМЕНТ ЗВОНКА, пока телефон ещё заблокирован: карточка вызова показывается
 * поверх экрана блокировки, и когда пользователь вводит PIN, она уже там —
 * никаких «найди уведомление или иконку приложения». Ключ здесь именно в моменте
 * запуска, а не в том, что происходит после разблокировки.
 *
 * Поэтому [present] вызывается СРАЗУ на входящем звонке. Разблокировка
 * (`ACTION_USER_PRESENT`) остаётся второй линией: если запуск в момент звонка не
 * прошёл, попробуем ещё раз тогда, когда пользователь точно взял телефон в руки.
 *
 * ТРИ УРОВНЯ, потому что ни один не даёт гарантии сам:
 *  1. **Full-screen intent** уведомления — штатный путь, но с Android 14
 *     разрешение на него выдаётся не всем ([SystemGrants.canUseFullScreenIntent]).
 *  2. **Прямой запуск Activity отсюда** — работает по документированному
 *     исключению из запрета фоновых запусков: «у приложения есть activity в
 *     стеке задачи на экране недавних». Для мессенджера, которым пользуются,
 *     это почти всегда так; если приложение смахнули из недавних — нет.
 *  3. **Повтор при разблокировке** — последний шанс, когда первые два не
 *     сработали.
 *
 * ПРО ПРОВЕРКУ РЕЗУЛЬТАТА. `startActivity` из фона на Android 10+ при отказе не
 * бросает исключение — система просто молча не запускает Activity, оставляя
 * запись только в своём logcat. Поэтому успех проверяется по факту:
 * [foregroundProbe] через [PROBE_DELAY_MS] спрашивает, появилась ли видимая
 * Activity, и пишет честный ответ в журнал. Без этой проверки в журнале стояло бы
 * «ок=true» там, где на экране ничего не появилось.
 *
 * Приёмник регистрируется ДИНАМИЧЕСКИ и только на время дозвона:
 * `ACTION_USER_PRESENT` с Android 8 не доставляется манифестным приёмникам, да и
 * слушать разблокировки телефона постоянно приложению незачем.
 */
class IncomingCallPresenter(
    context: Context,
    /** Есть ли прямо сейчас видимая Activity приложения (для проверки результата). */
    private val foregroundProbe: () -> Boolean,
) {
    private val appContext = context.applicationContext

    private val handler = Handler(Looper.getMainLooper())

    private var receiver: BroadcastReceiver? = null

    /**
     * Уже работаем для текущего звонка.
     *
     * Отдельный флаг, а не проверка `receiver != null`: подписка на разблокировку
     * может не удаться, и тогда по признаку приёмника [start] перестал бы быть
     * идемпотентным. А зовут его на КАЖДОМ обновлении состояния звонка (их за
     * время дозвона десятки — обновляется лента этапов), то есть мы бы дёргали
     * запуск Activity десятки раз подряд.
     */
    private var active = false

    /**
     * Начинает работу для текущего входящего звонка: пытается показать экран
     * звонка немедленно и подписывается на разблокировку устройства как на
     * вторую попытку. Идемпотентно — повторный вызов на том же звонке ничего не
     * делает.
     *
     * @param onUnlocked выполняется при разблокировке ДО повторной попытки
     *   (используется, чтобы переопубликовать уведомление о звонке — тогда даже
     *   при отказе в запуске пользователь увидит heads-up с кнопками).
     */
    @Synchronized
    fun start(onUnlocked: () -> Unit) {
        if (active) return
        active = true
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_USER_PRESENT) return
                DiagnosticLog.event(DIAG_TAG, "телефон разблокирован во время звонка")
                runCatching { onUnlocked() }
                present("после-разблокировки")
            }
        }
        val subscribed = runCatching {
            appContext.registerReceiver(rec, IntentFilter(Intent.ACTION_USER_PRESENT))
        }.isSuccess
        if (subscribed) {
            receiver = rec
        } else {
            DiagnosticLog.w(DIAG_TAG) { "не удалось подписаться на разблокировку устройства" }
        }
        // Главное действие — здесь, а не в колбэке разблокировки: экран звонка
        // должен существовать ДО того, как пользователь введёт PIN.
        present("входящий-звонок")
    }

    /** Прекращает работу (звонок принят/отклонён/завершён). Идемпотентно. */
    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        val rec = receiver ?: return
        receiver = null
        runCatching { appContext.unregisterReceiver(rec) }
        DiagnosticLog.d(DIAG_TAG) { "перестали слушать разблокировку устройства" }
    }

    /**
     * Пытается вывести экран звонка на передний план.
     *
     * Флаги: `NEW_TASK` обязателен (запуск не из Activity); `SINGLE_TOP` — чтобы
     * не плодить копии, если Activity уже поднял full-screen intent (тогда придёт
     * `onNewIntent`, и это ровно то, что нужно); `NO_USER_ACTION` — чтобы не
     * дёрнуть `onUserInteraction` и не сдвинуть таймер автоблокировки, ведь
     * пользователь экрана не касался.
     */
    fun present(reason: String) {
        if (foregroundProbe()) {
            // Приложение и так на экране — оверлей звонка нарисуется сам.
            DiagnosticLog.d(DIAG_TAG) { "экран звонка не нужен: приложение уже на переднем плане" }
            return
        }
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(AegisNotifier.EXTRA_SHOW_CALL, true)
        }
        val launch = runCatching { appContext.startActivity(intent) }
        DiagnosticLog.event(
            DIAG_TAG, "поднимаем экран звонка",
            "повод" to reason,
            "вызовПрошёл" to launch.isSuccess,
            "ошибка" to launch.exceptionOrNull()?.javaClass?.simpleName,
        )
        if (!launch.isSuccess) return
        // Отказ фонового запуска не бросает исключение — проверяем по факту.
        handler.postDelayed({
            val shown = foregroundProbe()
            if (shown) {
                DiagnosticLog.event(DIAG_TAG, "экран звонка показан", "повод" to reason)
            } else {
                DiagnosticLog.w(DIAG_TAG) {
                    "экран звонка НЕ появился (повод=$reason): система запретила фоновый запуск. " +
                        "Выдайте разрешение на полноэкранные уведомления — тогда звонок будет " +
                        "подниматься штатным путём"
                }
            }
        }, PROBE_DELAY_MS)
    }

    private companion object {
        const val DIAG_TAG = "call-screen"

        /**
         * Через сколько проверять, появилась ли Activity. Секунда с запасом:
         * холодный старт процесса с инициализацией контейнера укладывается в это
         * время, а дольше ждать смысла нет — пользователь уже смотрит на экран.
         */
        const val PROBE_DELAY_MS = 1_500L
    }
}
