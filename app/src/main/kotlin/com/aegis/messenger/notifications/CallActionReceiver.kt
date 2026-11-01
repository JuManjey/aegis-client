package com.aegis.messenger.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aegis.messenger.AegisApplication

/**
 * Обрабатывает действия из уведомления о ВХОДЯЩЕМ звонке (§14.5).
 *
 * «Отклонить» безопасно выполнять в фоне — сразу сбрасываем звонок ([onReceive]
 * → [com.aegis.messenger.call.CallManager.declineIncoming]) и убираем уведомление;
 * непрерывный рингтон гасится реактивно ([com.aegis.messenger.AppContainer] по
 * смене фазы звонка на ENDED).
 *
 * «Принять» требует вывода приложения на передний план (диалог runtime-
 * разрешений микрофона/камеры + оверлей звонка), поэтому идёт НЕ сюда, а через
 * Activity-интент (см. [AegisNotifier.EXTRA_ACCEPT_CALL]).
 *
 * Не экспортируется (см. AndroidManifest): триггерится только PendingIntent'ами
 * самого приложения.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? AegisApplication)?.container ?: return
        when (intent.action) {
            // Гасит рингтон/вибрацию и убирает уведомление синхронно, не
            // дожидаясь реактивной реакции на смену фазы.
            ACTION_DECLINE -> container.declineIncomingCall()
            ACTION_DISMISS_LOCKED -> container.dismissLockedCall()
        }
    }

    companion object {
        /** Действие «Отклонить» из уведомления о входящем звонке. */
        const val ACTION_DECLINE = "com.aegis.messenger.action.CALL_DECLINE"

        /** «Отклонить» звонок, пришедший при закрытом аккаунте: только тишина. */
        const val ACTION_DISMISS_LOCKED = "com.aegis.messenger.action.LOCKED_CALL_DISMISS"
    }
}
