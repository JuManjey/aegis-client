package com.aegis.messenger

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aegis.core.common.appearance.AppThemeMode
import com.aegis.core.common.call.CallInfo
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.call.CallPhase
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.ui.theme.AegisTheme
import com.aegis.crypto.CryptoModule
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.messenger.notifications.AegisNotifier
import com.aegis.messenger.ui.AegisRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Единственная Activity приложения (single-activity + Compose Navigation).
 *
 * Наследует [FragmentActivity] (не `ComponentActivity`), т.к. `BiometricPrompt`
 * (§14.3) требует хост на базе FragmentActivity.
 */
class MainActivity : FragmentActivity() {

    /** Применяем выбранный язык (§14.6) как можно раньше — до создания UI. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        // FLAG_SECURE включён ПРИНУДИТЕЛЬНО и ГЛОБАЛЬНО (§14.3): запрет
        // скриншотов и записи экрана для всех экранов. Отключить нельзя.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as AegisApplication).container
        // Синхронно, до первого кадра: окно, поднятое full-screen intent'ом с
        // нуля, должно сразу встать поверх блокировки, а открытое без звонка —
        // сразу перестать (флаги манифеста действуют только до этой строки).
        applyLockScreenFlags(container)
        // Переход в чат / приём звонка из уведомления (§14.5), если приложение
        // стартовало по тапу или нажатию action-кнопки.
        handleNotificationIntent(intent)
        DiagnosticLog.event(
            "ui", "MainActivity.onCreate",
            "ядроГотово" to CryptoModule.isNativeReady,
            "ядроВерсия" to CryptoModule.nativeVersion(),
            "tor" to CryptoModule.hasTorSupport,
            "интент" to (intent?.action ?: "null"),
        )
        val nativeCryptoVersion = CryptoModule.nativeVersion()
        val nativeReady = CryptoModule.isNativeReady
        observeCallWindow(container)

        setContent {
            // Тема (§14.6): тёмная (база §13) / светлая / по системе.
            val appearance by container.appearanceSettings.collectAsStateWithLifecycle()
            val darkTheme = when (appearance.theme) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            // Иконки системных баров: светлые бары ⇒ тёмные иконки, и наоборот.
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            AegisTheme(darkTheme = darkTheme) {
                AegisRoot(
                    container = container,
                    nativeCryptoVersion = nativeCryptoVersion,
                    nativeReady = nativeReady,
                )
            }
        }
    }

    /** Приложение уже запущено (singleTop): обрабатываем новый интент уведомления. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Обрабатывает интенты уведомлений (§14.5): открытие чата ([EXTRA_OPEN_CHAT]),
     * приём входящего звонка ([EXTRA_ACCEPT_CALL]) и переход к заявкам на
     * добавление в контакты ([EXTRA_OPEN_CONTACT_REQUESTS]).
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val container = (application as AegisApplication).container
        val contactId = intent?.getStringExtra(AegisNotifier.EXTRA_OPEN_CHAT)
        if (!contactId.isNullOrBlank()) {
            DiagnosticLog.event("ui", "интент: открыть чат", "контакт" to DiagnosticLog.shortId(contactId))
            container.requestOpenChat(contactId)
        }
        val acceptContactId = intent?.getStringExtra(AegisNotifier.EXTRA_ACCEPT_CALL)
        if (!acceptContactId.isNullOrBlank()) {
            DiagnosticLog.event("ui", "интент: принять звонок", "контакт" to DiagnosticLog.shortId(acceptContactId))
            container.requestCallAccept()
        }
        if (intent?.getBooleanExtra(AegisNotifier.EXTRA_ANSWER_LOCKED_CALL, false) == true) {
            DiagnosticLog.event("ui", "интент: ответить на звонок при закрытом аккаунте")
            container.answerLockedCallAfterUnlock()
        }
        // Уведомление о заявке ведёт на СПИСОК, а не на «принять»: решение
        // требует посмотреть accountId и отпечаток собеседника.
        if (intent?.getBooleanExtra(AegisNotifier.EXTRA_OPEN_CONTACT_REQUESTS, false) == true) {
            DiagnosticLog.event("ui", "интент: открыть заявки в контакты")
            container.requestContactRequestsOpen()
        }
        // «Показать звонок» не требует действий: оверлей звонка привязан к фазе
        // в CallManager и появится сам, как только Activity выйдет на передний
        // план. Фиксируем только факт — по нему видно, сработал ли full-screen
        // intent или пользователь пришёл тапом.
        if (intent?.getBooleanExtra(AegisNotifier.EXTRA_SHOW_CALL, false) == true) {
            DiagnosticLog.event("ui", "интент: показать экран звонка")
        }
    }

    /**
     * Ведёт окно поверх блокировки на время звонка (§7.5/§14.3).
     *
     * Зачем: принять звонок с заблокированного экрана нужно БЕЗ предварительной
     * разблокировки. Пока этого не было, «Принять» нажималось уже после
     * разблокировки, и старт FGS микрофона попадал в окно, когда система ещё не
     * считает приложение видимым, — отказ в `startForeground(microphone)` и
     * смерть процесса по таймауту контракта FGS.
     *
     * Почему это не ослабляет §14.3:
     *  * флаги стоят ТОЛЬКО пока есть звонок — живой или звонящий при закрытом
     *    аккаунте — и снимаются сразу после. Раньше до первого звонка действовали
     *    флаги манифеста, и оставленное открытым приложение вставало поверх
     *    блокировки при каждом включении экрана: без PIN телефона была видна
     *    переписка, а через минуту автоблокировка закрывала аккаунт и тем самым
     *    останавливала приём звонков;
     *  * пока телефон заблокирован, экран звонка сам рисует обезличенную
     *    карточку (без имени контакта, кода сверки и деталей этапов) — см.
     *    `CallScreen`; `FLAG_SECURE` остаётся включённым глобально;
     *  * аккаунт не разблокируется: доступ к чатам по-прежнему за auth-gate.
     *
     * Снятие флагов откладывается на [LOCK_SCREEN_GRACE_MS]. Звонок при закрытом
     * аккаунте после входа становится живым не сразу — сначала разбирается
     * очередь, — и без паузы окно на этом стыке пряталось бы за блокировку и
     * тут же возвращалось. Тот же запас оставляет на экране «звонок завершён».
     */
    private fun observeCallWindow(container: AppContainer) {
        lifecycleScope.launch {
            combine(container.callManager.state, container.lockedIncomingCall, ::Pair)
                .collectLatest { (call, lockedRinging) ->
                    applyKeepAwake(call)
                    val overKeyguard = call.phase.isLive || lockedRinging
                    if (!overKeyguard) delay(LOCK_SCREEN_GRACE_MS)
                    setLockScreenFlags(
                        overKeyguard = overKeyguard,
                        turnScreenOn = call.phase == CallPhase.RINGING || lockedRinging,
                    )
                }
        }
    }

    /**
     * Синхронная установка флагов по текущему состоянию — для [onCreate], где
     * ждать первой эмиссии [observeCallWindow] нельзя: окно вот-вот покажется.
     *
     * Интент звонка отдельно не учитывается: full-screen intent публикует тот же
     * живой процесс, поэтому к [onCreate] звонок уже в состоянии. Зато устаревший
     * интент (восстановление из недавних, уведомление, пережившее процесс) не
     * поднимет окно поверх блокировки без звонка.
     */
    private fun applyLockScreenFlags(container: AppContainer) {
        val call = container.callManager.state.value
        val lockedRinging = container.lockedIncomingCall.value
        setLockScreenFlags(
            overKeyguard = call.phase.isLive || lockedRinging,
            turnScreenOn = call.phase == CallPhase.RINGING || lockedRinging,
        )
    }

    /**
     * @param turnScreenOn будить экран: имеет смысл только на входящем вызове —
     *   у исходящего и у идущего разговора экран уже включён пользователем.
     */
    private fun setLockScreenFlags(overKeyguard: Boolean, turnScreenOn: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        setShowWhenLocked(overKeyguard)
        setTurnScreenOn(overKeyguard && turnScreenOn)
    }

    private fun applyKeepAwake(call: CallInfo) {
        // Экран держим включённым узко: на входящем вызове (его нужно успеть
        // принять) и на видеозвонке (там картинка — смысл звонка). Голосовой
        // разговор при этом гаснет как обычно: и батарея, и §14.3 — открытый
        // экран звонка тоже видно со стороны.
        val keepAwake = call.phase == CallPhase.RINGING ||
            (call.phase == CallPhase.ACTIVE && call.media == CallMedia.VIDEO)
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Хук фреймворка на любое взаимодействие пользователя (касание/клавиша).
     * Сбрасывает таймер автоблокировки по бездействию (§14.3).
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as AegisApplication).container.idleLockController.onUserInteraction()
    }

    private companion object {
        /** Запас перед снятием флагов «поверх блокировки» (см. [observeCallWindow]). */
        const val LOCK_SCREEN_GRACE_MS = 3_000L
    }
}
