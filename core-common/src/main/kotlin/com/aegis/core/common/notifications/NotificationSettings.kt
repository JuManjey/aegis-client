package com.aegis.core.common.notifications

import kotlinx.serialization.Serializable

/**
 * Глобальные настройки уведомлений (§14.5).
 *
 * Как и [com.aegis.core.common.privacy.PrivacySettings], это независимые
 * пользовательские переключатели (не входят в сравнение пресетов §8), поэтому
 * хранятся отдельным обёрнутым файлом (см. SecureStore).
 *
 * Приватность превью (§14.3): по умолчанию в уведомлении НЕ показываются ни имя
 * отправителя, ни текст — только нейтральное «Новое сообщение». Раскрытие
 * содержимого — осознанное ослабление, включается пользователем явно.
 *
 * Доставка уведомлений в AEGIS не использует FCM/push (zero-trust к серверу и к
 * Google, §7.6): входящие ловит long-poll поверх Tor в foreground-service, а
 * само уведомление формируется ЛОКАЛЬНО из уже расшифрованного сообщения.
 */
@Serializable
data class NotificationSettings(
    /** Главный выключатель уведомлений. Выкл ⇒ ничего не показываем. */
    val enabled: Boolean = true,
    /** Показывать имя отправителя в уведомлении (§14.3, по умолчанию скрыто). */
    val showSender: Boolean = false,
    /** Показывать текст/тип сообщения в уведомлении (§14.3, по умолчанию скрыто). */
    val showContent: Boolean = false,
    /** Звук для уведомлений о сообщениях (глобально). */
    val messageSound: Boolean = true,
    /** Вибрация для уведомлений о сообщениях (глобально). */
    val messageVibrate: Boolean = true,
    /** Рингтон входящего звонка (глобально). */
    val callSound: Boolean = true,
    /** Вибрация входящего звонка (глобально). */
    val callVibrate: Boolean = true,
    /**
     * Встроенный синтезированный рингтон в стиле приложения
     * ([com.aegis.core.common.call.tone.CallTone.INCOMING_RING]) вместо
     * системной мелодии. Требует [callSound].
     *
     * Персональная мелодия контакта (§14.5, `Contact.callRingtoneUri`) всегда
     * важнее этого переключателя: явный выбор для конкретного человека не
     * должен отменяться глобальной настройкой стиля.
     */
    val callSynthRingtone: Boolean = true,
    /**
     * Тоны хода звонка: «гудки» исходящего вызова, пинги согласования, звук
     * установки соединения, потери пути и завершения (см.
     * [com.aegis.core.common.call.tone.CallTone]).
     *
     * Идут по каналу РАЗГОВОРА (в ухо, громкостью вызова), а не по каналу
     * рингтона, поэтому от [callSound] и от режима звонка устройства НЕ зависят:
     * «без звука» глушит звонок телефона, но не звук уже идущего разговора.
     */
    val callProgressTones: Boolean = true,
    /**
     * Показывать уведомления, когда приложение на переднем плане («внутренние
     * уведомления», §14.5). Выкл ⇒ в foreground системные уведомления
     * подавляются (открытый чат и так виден на экране).
     */
    val inAppNotifications: Boolean = true,
    /**
     * Глобальная приостановка всех уведомлений до указанного момента (§14.5,
     * «отключить на время»): `0` — не приостановлены, [MUTE_FOREVER] — навсегда,
     * иначе — Unix-время (мс), до которого уведомления заглушены.
     */
    val muteAllUntilMs: Long = 0L,
) {
    /** Активна ли сейчас глобальная приостановка (§14.5). */
    fun isMutedNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        muteAllUntilMs == MUTE_FOREVER || muteAllUntilMs > nowMs

    companion object {
        /** Метка «заглушить навсегда» для [muteAllUntilMs]. */
        const val MUTE_FOREVER: Long = Long.MAX_VALUE

        val DEFAULT: NotificationSettings = NotificationSettings()
    }
}
