package com.aegis.messenger.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.aegis.core.common.call.CallMedia
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.model.AttachmentKind
import com.aegis.core.common.model.Message
import com.aegis.core.common.notifications.NotificationSettings
import com.aegis.messenger.MainActivity
import com.aegis.messenger.R
import com.aegis.messenger.appearance.AppIconManager
import com.aegis.messenger.appearance.LocaleManager

/**
 * Локальные уведомления (§14.5).
 *
 * AEGIS не использует FCM/push (zero-trust к серверу и к Google, §7.6): входящие
 * ловит long-poll поверх Tor в [MessageSyncService], а уведомление формируется
 * ЛОКАЛЬНО из уже расшифрованного сообщения. Сервер о факте уведомления не знает.
 *
 * Приватность превью (§14.3): по умолчанию скрываем и отправителя, и текст —
 * показываем нейтральное «Новое сообщение». Раскрытие включается пользователем
 * явно в настройках.
 *
 * Звук/вибрация на Android 8+ задаются на уровне канала при его создании и не
 * меняются потом. Чтобы пользовательские переключатели и персональные звуки
 * контактов (§14.5) реально применялись «на лету», id канала ДЕТЕРМИНИРОВАННО
 * выводится из его конфигурации (звук + вибрация): при смене настроек создаётся
 * канал с новым id, а уведомление публикуется в него.
 */
class AegisNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * Контекст для строковых ресурсов с учётом выбранного языка приложения
     * (§14.6). `appContext` не оборачивается [LocaleManager] (это делает Activity
     * в `attachBaseContext`), поэтому для уведомлений/каналов, живущих вне Activity,
     * локаль применяем здесь, чтобы i18n реально работала в фоне.
     */
    private fun localized(): Context = LocaleManager.wrap(appContext)

    /** Разрешены ли уведомления системой (Android 13+ POST_NOTIFICATIONS). */
    fun notificationsAllowed(): Boolean = manager.areNotificationsEnabled()

    /**
     * Постоянное уведомление foreground-service (§14.5): держит процесс живым,
     * чтобы long-poll поверх Tor продолжал принимать сообщения в фоне. Тихое,
     * низкой важности, без раскрытия чего-либо о переписке.
     */
    fun buildServiceNotification(torEnabled: Boolean): android.app.Notification {
        ensureServiceChannel()
        val loc = localized()
        val disguise = disguised()
        val text = when {
            disguise -> R.string.notif_disguise_service_text
            torEnabled -> R.string.notif_service_text
            else -> R.string.notif_service_text_direct
        }
        return NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(statusIcon())
            .setContentTitle(
                loc.getString(
                    if (disguise) R.string.notif_disguise_service_title else R.string.notif_service_title,
                ),
            )
            .setContentText(loc.getString(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(null))
            .build()
    }

    /**
     * Уведомление о новом входящем сообщении (§14.5). Вызывающий ([AppContainer])
     * уже проверил, что уведомление уместно (включено, не заглушено, чат не открыт).
     * Здесь только формируем и публикуем с учётом настроек приватности превью.
     */
    fun notifyMessage(
        contactId: String,
        displayName: String,
        message: Message,
        settings: NotificationSettings,
        contactSoundUri: String,
        unreadCount: Int,
    ) {
        if (!settings.enabled || !notificationsAllowed()) return

        val soundUri: Uri? = when {
            !settings.messageSound -> null
            contactSoundUri.isNotBlank() -> runCatching { Uri.parse(contactSoundUri) }.getOrNull()
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        val channelId = ensureMessageChannel(soundUri, settings.messageVibrate)

        val appName = visibleAppLabel()
        val title = if (settings.showSender) displayName.ifBlank { appName } else appName
        val text = if (settings.showContent) previewOf(message) else localized().getString(R.string.notif_new_message)

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(statusIcon())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // На экране блокировки прячем содержимое полностью (§14.3).
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(contactId))
        if (unreadCount > 1) builder.setNumber(unreadCount)
        if (soundUri == null) builder.setSilent(true)

        manager.notify(messageNotificationId(contactId), builder.build())
    }

    /** Убрать уведомления о сообщениях контакта (например, при открытии чата). */
    fun cancelMessages(contactId: String) {
        manager.cancel(messageNotificationId(contactId))
    }

    /**
     * Уведомление о ЗАЯВКЕ на добавление в контакты (RCH v1,
     * `docs/contact_requests.md`).
     *
     * Кнопок «принять/отклонить» здесь НАМЕРЕННО нет: решение требует посмотреть
     * `accountId` и отпечаток собеседника, а из строки уведомления этого не
     * увидеть. Тап открывает экран заявок, где показано всё нужное — принять
     * контакт вслепую невозможно.
     *
     * Содержимое подчиняется тем же настройкам, что и сообщения: [
     * NotificationSettings.showSender] управляет показом ника, [
     * NotificationSettings.showContent] — приветствия из заявки.
     */
    fun notifyContactRequest(
        displayName: String,
        greeting: String,
        pendingCount: Int,
        settings: NotificationSettings,
    ) {
        if (!settings.enabled || !notificationsAllowed()) return
        val soundUri: Uri? = if (settings.messageSound) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            null
        }
        val channelId = ensureMessageChannel(soundUri, settings.messageVibrate)
        val loc = localized()
        val appName = visibleAppLabel()
        val who = if (settings.showSender) displayName.ifBlank { appName } else appName
        val text = when {
            !settings.showContent -> loc.getString(R.string.notif_contact_request_text)
            greeting.isNotBlank() -> greeting
            else -> loc.getString(R.string.notif_contact_request_text)
        }

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(statusIcon())
            .setContentTitle(loc.getString(R.string.notif_contact_request_title, who))
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openContactRequestsIntent())
        if (pendingCount > 1) builder.setNumber(pendingCount)
        if (soundUri == null) builder.setSilent(true)

        manager.notify(CONTACT_REQUEST_NOTIFICATION_ID, builder.build())
    }

    /** Убрать уведомление о заявках (все решения приняты / аккаунт заблокирован). */
    fun cancelContactRequests() {
        manager.cancel(CONTACT_REQUEST_NOTIFICATION_ID)
    }

    /**
     * Уведомление о ВХОДЯЩЕМ звонке (§14.5) с action-кнопками «Принять» и
     * «Отклонить». Высокий приоритет + full-screen intent, чтобы система показала
     * полноэкранный вызов при заблокированном экране; тап по телу открывает
     * приложение (оверлей [com.aegis.messenger.ui.call.CallScreen]).
     *
     * Звук/вибрацию ведёт НЕ канал уведомления, а
     * [com.aegis.messenger.call.CallRingtonePlayer] (непрерывный рингтон на всё
     * время дозвона), поэтому само уведомление — тихое.
     *
     * - «Отклонить» → [CallActionReceiver] (сброс в фоне, без вывода приложения).
     * - «Принять»   → открытие приложения ([EXTRA_ACCEPT_CALL]) с последующим
     *   запросом runtime-разрешений и приёмом звонка (permission-flow в UI).
     */
    fun notifyIncomingCall(
        contactId: String,
        callId: String,
        displayName: String,
        media: CallMedia,
        settings: NotificationSettings,
    ) {
        if (!settings.enabled || !notificationsAllowed()) return

        val appName = visibleAppLabel()
        // На экране блокировки имя контакта не показываем НИКОГДА (§14.3), даже
        // если showSender включён: разблокированный экран — другое дело, там имя
        // полезно, поэтому оно уходит в основной заголовок, а публичная версия
        // уведомления ниже несёт только ярлык приложения.
        val title = if (settings.showSender) displayName.ifBlank { appName } else appName
        val text = localized().getString(
            if (media == CallMedia.VIDEO) R.string.notif_incoming_video_call else R.string.notif_incoming_call,
        )
        postCallNotification(
            title = title,
            text = text,
            video = media == CallMedia.VIDEO,
            acceptIntent = acceptCallIntent(contactId, callId),
            declineIntent = declineCallIntent(callId),
        )
    }

    /**
     * Входящий звонок при ЗАКРЫТОМ аккаунте: сигналинг лежит в очереди
     * нерасшифрованным, поэтому ни имени, ни вида звонка здесь нет. «Ответить»
     * открывает экран входа и принимает звонок сразу после него, «Отклонить»
     * только гасит гудок — сообщить звонящему об отказе без ключей нечем.
     */
    fun notifyLockedIncomingCall(settings: NotificationSettings) {
        if (!settings.enabled || !notificationsAllowed()) return
        postCallNotification(
            title = visibleAppLabel(),
            text = localized().getString(R.string.notif_locked_call),
            video = false,
            acceptIntent = answerLockedCallIntent(),
            declineIntent = dismissLockedCallIntent(),
        )
    }

    private fun postCallNotification(
        title: String,
        text: String,
        video: Boolean,
        acceptIntent: PendingIntent,
        declineIntent: PendingIntent,
    ) {
        val channelId = ensureCallChannel()
        val loc = localized()
        // Тап по уведомлению и full-screen intent ведут на ЭКРАН ЗВОНКА, а не в
        // чат: у звонящего уведомления единственное осмысленное продолжение —
        // увидеть «Принять/Отклонить». Открытие чата (как было) уводило поверх
        // оверлея звонка лишний экран.
        val contentIntent = showCallIntent()

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(statusIcon())
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            // Звук/вибро ведёт CallRingtonePlayer — уведомление тихое (иначе двойной звук).
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // VISIBILITY_SECRET прятал уведомление о звонке на блокировке ЦЕЛИКОМ:
            // телефон звонил и вибрировал, а понять, какое приложение это делает и
            // как ответить, было нельзя — приходилось разблокировать и лезть в
            // шторку. Показываем PUBLIC, но подставляем обезличенную версию
            // (publicVersion ниже): на блокировке видно «<приложение> · входящий
            // звонок» и кнопки, и ни имени контакта, ни деталей.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(anonymousCallNotification(channelId, text, contentIntent))
            // Уведомление относится только к этому устройству: незачем зеркалить
            // звонок на часы/планшет с тем же аккаунтом Google.
            .setLocalOnly(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)

        // CallStyle (Android 12+, через compat и ниже) даёт системе понять, что
        // это ЗВОНОК, а не обычное уведомление: собственная вёрстка с крупными
        // «Принять/Отклонить», приоритетное место в шторке и на блокировке,
        // устойчивость к сворачиванию. Без него звонок выглядел рядовой строкой
        // среди прочих уведомлений.
        val person = Person.Builder()
            .setName(title)
            .setImportant(true)
            .build()
        val styledNotification = runCatching {
            builder.setStyle(
                NotificationCompat.CallStyle
                    .forIncomingCall(person, declineIntent, acceptIntent)
                    .setIsVideo(video),
            ).build()
        }

        // Стиль звонка проверяется системой при сборке (например, требуется
        // full-screen intent либо привязка к foreground-сервису). Если проверка
        // не прошла, ПРОПУСТИТЬ уведомление нельзя — это молча съеденный
        // входящий звонок. Собираем обычное уведомление с теми же кнопками.
        val notification = styledNotification.getOrElse { error ->
            DiagnosticLog.e("notify", error) {
                "CallStyle отклонён — публикуем обычное уведомление о звонке"
            }
            builder
                .setStyle(null)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    loc.getString(R.string.notif_call_decline),
                    declineIntent,
                )
                .addAction(
                    android.R.drawable.sym_action_call,
                    loc.getString(R.string.notif_call_accept),
                    acceptIntent,
                )
                .build()
        }

        manager.notify(CALL_NOTIFICATION_ID, notification)
        DiagnosticLog.event(
            "notify", "входящий звонок показан",
            // Без этого разрешения (Android 14+ выдаёт его не всем) full-screen
            // intent деградирует до heads-up: экран звонка сам не поднимется, и
            // пользователю придётся разблокировать телефон вручную. Полезно
            // видеть в журнале, чтобы не искать причину в самом звонке.
            "fullScreen" to fullScreenAllowed(),
            "callStyle" to styledNotification.isSuccess,
        )
    }

    /**
     * Обезличенная версия уведомления для экрана блокировки.
     *
     * Показывает только ярлык приложения (или маскировки) и «входящий звонок»:
     * пользователь понимает, что звонит и как ответить, но кто звонит — нет.
     * Именно это и требует §14.3 от блокировки, в отличие от полного скрытия,
     * которое просто оставляло человека наедине со звенящим телефоном.
     */
    private fun anonymousCallNotification(
        channelId: String,
        text: String,
        contentIntent: PendingIntent,
    ): android.app.Notification =
        NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(statusIcon())
            .setContentTitle(visibleAppLabel())
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build()

    /**
     * Разрешён ли full-screen intent (Android 14+ спрашивает отдельно).
     *
     * Публичный: на этом ответе построен весь сценарий «звонок на заблокированном
     * экране». Когда здесь `false`, экран звонка сам не поднимется, и приложению
     * остаётся будить экран замком ([IncomingCallWaker]) и просить пользователя
     * выдать разрешение ([fullScreenIntentSettings]).
     */
    fun fullScreenAllowed(): Boolean =
        runCatching { manager.canUseFullScreenIntent() }.getOrDefault(true)

    /** Убрать уведомление о звонке (принят/отклонён/завершён). */
    fun cancelCall() {
        manager.cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Сообщения, пришедшие при ЗАКРЫТОМ аккаунте: они лежат в очереди
     * нерасшифрованными, поэтому ни отправителя, ни текста — только факт и число.
     */
    fun notifyLockedMessages(count: Int, settings: NotificationSettings) {
        if (!settings.enabled || !notificationsAllowed()) return
        val soundUri: Uri? = if (settings.messageSound) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            null
        }
        val channelId = ensureMessageChannel(soundUri, settings.messageVibrate)
        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(statusIcon())
            .setContentTitle(visibleAppLabel())
            .setContentText(localized().getString(R.string.notif_locked_messages))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(null))
        if (count > 1) builder.setNumber(count)
        if (soundUri == null) builder.setSilent(true)
        manager.notify(LOCKED_MESSAGES_NOTIFICATION_ID, builder.build())
    }

    fun cancelLockedMessages() {
        manager.cancel(LOCKED_MESSAGES_NOTIFICATION_ID)
    }

    /**
     * Тихое ongoing-уведомление FGS медиазвонка (микрофон/камера). Не путать с
     * входящим звонком [notifyIncomingCall]: это только чтобы Android 14+ разрешил
     * AudioRecord.
     */
    fun buildCallMediaNotification(): android.app.Notification {
        ensureServiceChannel()
        val loc = localized()
        val disguise = disguised()
        return NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(statusIcon())
            .setContentTitle(
                loc.getString(
                    if (disguise) R.string.notif_disguise_call_media_title else R.string.notif_call_media_title,
                ),
            )
            .setContentText(
                loc.getString(
                    if (disguise) R.string.notif_disguise_call_media_text else R.string.notif_call_media_text,
                ),
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent(null))
            .build()
    }

    /** Убрать все уведомления (например, при блокировке аккаунта). */
    fun cancelAll() {
        manager.cancelAll()
    }

    // --- Каналы --------------------------------------------------------------

    private fun ensureServiceChannel() {
        val loc = localized()
        val disguise = disguised()
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_SERVICE,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(
                loc.getString(
                    if (disguise) R.string.notif_disguise_channel_service_name else R.string.notif_channel_service_name,
                ),
            )
            .setDescription(
                loc.getString(
                    if (disguise) R.string.notif_disguise_channel_service_desc else R.string.notif_channel_service_desc,
                ),
            )
            .setShowBadge(false)
            .build()
        manager.createNotificationChannel(channel)
    }

    private fun ensureMessageChannel(soundUri: Uri?, vibrate: Boolean): String {
        val id = "$CHANNEL_MSG_PREFIX${soundToken(soundUri)}_${if (vibrate) "v1" else "v0"}"
        val name = localized().getString(
            if (disguised()) R.string.notif_disguise_channel_messages_name else R.string.notif_channel_messages_name,
        )
        createSoundChannel(id, name, soundUri, vibrate, RingtoneManager.TYPE_NOTIFICATION)
        return id
    }

    /**
     * Канал входящих звонков: высокая важность (heads-up + full-screen), но БЕЗ
     * звука и вибрации — их ведёт [com.aegis.messenger.call.CallRingtonePlayer]
     * (непрерывный рингтон). Так канал фиксирован и не плодит копии под каждый
     * персональный рингтон.
     *
     * `lockscreenVisibility` = PUBLIC, потому что видимость канала — ЖЁСТКИЙ
     * ПОТОЛОК для видимости уведомления: со SECRET-каналом никакой
     * `setVisibility(PUBLIC)` на уведомлении не помог бы, и звонок на блокировке
     * оставался бы невидимым. Приватность обеспечивает не потолок канала, а
     * обезличенная `publicVersion` (см. [notifyIncomingCall]).
     *
     * Параметры канала неизменяемы после создания, поэтому изменение политики
     * требует НОВОГО id ([CHANNEL_CALL]); старый удаляется, иначе в настройках
     * останется мёртвая запись «Звонки» с прежним поведением.
     */
    private fun ensureCallChannel(): String {
        val callChannelName = localized().getString(
            if (disguised()) R.string.notif_disguise_channel_calls_name else R.string.notif_channel_calls_name,
        )
        runCatching { manager.deleteNotificationChannel(CHANNEL_CALL_LEGACY) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_CALL,
                callChannelName,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                // Обход «Не беспокоить» для звонков: пропущенный звонок хуже
                // лишнего heads-up, а звук всё равно решает CallRingtonePlayer,
                // который сам уважает режим звонка устройства.
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        } else {
            val channel = NotificationChannelCompat.Builder(
                CHANNEL_CALL,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            ).setName(callChannelName).setVibrationEnabled(false).build()
            manager.createNotificationChannel(channel)
        }
        return CHANNEL_CALL
    }

    /**
     * Создаёт канал с заданными звуком/вибрацией. На API < 26 каналы игнорируются
     * системой, но [NotificationChannelCompat] безопасно вызывать на любой версии
     * (звук/вибрация там берутся из самого уведомления).
     */
    private fun createSoundChannel(
        id: String,
        name: String,
        soundUri: Uri?,
        vibrate: Boolean,
        usageType: Int,
    ) {
        // Через compat-строитель нельзя задать null-звук (тишину), поэтому для
        // варианта «без звука» на O+ создаём канал напрямую. На API < 26 звук
        // управляется самим уведомлением (setSilent), канал не нужен.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(id, name, importance).apply {
                enableVibration(vibrate)
                if (soundUri != null) {
                    val usage = if (usageType == RingtoneManager.TYPE_RINGTONE) {
                        AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                    } else {
                        AudioAttributes.USAGE_NOTIFICATION
                    }
                    val attrs = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(usage)
                        .build()
                    setSound(soundUri, attrs)
                } else {
                    setSound(null, null)
                }
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        } else {
            val channel = NotificationChannelCompat.Builder(
                id,
                NotificationManagerCompat.IMPORTANCE_HIGH,
            ).setName(name).setVibrationEnabled(vibrate).build()
            manager.createNotificationChannel(channel)
        }
    }

    private fun soundToken(soundUri: Uri?): String =
        if (soundUri == null) "silent" else Integer.toHexString(soundUri.toString().hashCode())

    private fun previewOf(message: Message): String {
        message.attachment?.let { ref ->
            val kind = localized().getString(
                when (ref.kind) {
                    AttachmentKind.IMAGE -> R.string.notif_attachment_image
                    AttachmentKind.VIDEO -> R.string.notif_attachment_video
                    AttachmentKind.FILE -> R.string.notif_attachment_file
                },
            )
            return if (message.body.isNotBlank()) {
                localized().getString(R.string.notif_attachment_with_caption, kind, message.body)
            } else {
                kind
            }
        }
        return message.body.ifBlank { localized().getString(R.string.notif_new_message) }
    }

    /** PendingIntent открытия приложения (опц. с переходом в чат [contactId]). */
    private fun openAppIntent(contactId: String?): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (contactId != null) putExtra(EXTRA_OPEN_CHAT, contactId)
        }
        val requestCode = contactId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * PendingIntent открытия экрана ЗАЯВОК на добавление в контакты.
     *
     * Ведёт именно на экран со списком, а не на «принять» — решение принимается
     * только после просмотра `accountId` и отпечатка собеседника.
     */
    private fun openContactRequestsIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_OPEN_CONTACT_REQUESTS, true)
        }
        return PendingIntent.getActivity(
            appContext,
            CONTACT_REQUESTS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * PendingIntent показа ЭКРАНА звонка (§14.5): просто выводит приложение на
     * передний план. Оверлей звонка появится сам — он привязан к фазе в
     * [com.aegis.messenger.call.CallManager], а не к навигации, поэтому никаких
     * «открыть чат» здесь быть не должно.
     */
    private fun showCallIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_SHOW_CALL, true)
        }
        return PendingIntent.getActivity(
            appContext,
            SHOW_CALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * PendingIntent приёма звонка (§14.5): открывает приложение с
     * [EXTRA_ACCEPT_CALL]; UI запрашивает разрешения и вызывает `acceptIncoming`.
     */
    private fun acceptCallIntent(contactId: String, callId: String): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ACCEPT_CALL, contactId)
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getActivity(
            appContext,
            ACCEPT_CALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** «Ответить» на звонок при закрытом аккаунте: экран входа, затем приём ([EXTRA_ANSWER_LOCKED_CALL]). */
    private fun answerLockedCallIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_ANSWER_LOCKED_CALL, true)
        }
        return PendingIntent.getActivity(
            appContext,
            ANSWER_LOCKED_CALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** «Отклонить» звонок при закрытом аккаунте: только гасит гудок ([CallActionReceiver]). */
    private fun dismissLockedCallIntent(): PendingIntent {
        val intent = Intent(appContext, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DISMISS_LOCKED
        }
        return PendingIntent.getBroadcast(
            appContext,
            DISMISS_LOCKED_CALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** PendingIntent отклонения звонка (§14.5): фоновый сброс через [CallActionReceiver]. */
    private fun declineCallIntent(callId: String): PendingIntent {
        val intent = Intent(appContext, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            DECLINE_CALL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun messageNotificationId(contactId: String): Int =
        MESSAGE_ID_BASE + (contactId.hashCode() and 0x0FFFFFFF)

    private fun disguised(): Boolean = AppIconManager.current(appContext).disguise

    private fun statusIcon(): Int = AppIconManager.current(appContext).statusIconRes

    /** Имя в шторке: бренд AEGIS или ярлык выбранной маскировки. */
    private fun visibleAppLabel(): String {
        val icon = AppIconManager.current(appContext)
        val loc = localized()
        return loc.getString(if (icon.disguise) icon.pickerLabelRes else R.string.app_name)
    }

    companion object {
        /** Extra intent'а MainActivity: id контакта для открытия чата из уведомления. */
        const val EXTRA_OPEN_CHAT = "com.aegis.messenger.OPEN_CHAT"

        /** Extra intent'а MainActivity: id контакта для приёма входящего звонка (§14.5). */
        const val EXTRA_ACCEPT_CALL = "com.aegis.messenger.ACCEPT_CALL"

        /** Extra intent'а MainActivity: показать экран текущего звонка (§14.5). */
        const val EXTRA_SHOW_CALL = "com.aegis.messenger.SHOW_CALL"

        /** Extra: id звонка (для сопоставления действия с текущей сессией). */
        const val EXTRA_CALL_ID = "com.aegis.messenger.CALL_ID"

        /** Extra intent'а MainActivity: открыть экран заявок на добавление. */
        const val EXTRA_OPEN_CONTACT_REQUESTS = "com.aegis.messenger.OPEN_CONTACT_REQUESTS"

        /** Extra intent'а MainActivity: ответить на звонок, пришедший при закрытом аккаунте. */
        const val EXTRA_ANSWER_LOCKED_CALL = "com.aegis.messenger.ANSWER_LOCKED_CALL"

        /** Стабильный id уведомления foreground-service. */
        const val SERVICE_NOTIFICATION_ID = 0x4547_01

        /** Id уведомления FGS медиазвонка (микрофон/камера). */
        const val CALL_MEDIA_NOTIFICATION_ID = 0x4547_03

        private const val CALL_NOTIFICATION_ID = 0x4547_02
        private const val CONTACT_REQUEST_NOTIFICATION_ID = 0x4547_04
        private const val LOCKED_MESSAGES_NOTIFICATION_ID = 0x4547_05
        private const val MESSAGE_ID_BASE = 0x1000_0000

        private const val ACCEPT_CALL_REQUEST_CODE = 0x4547_11
        private const val DECLINE_CALL_REQUEST_CODE = 0x4547_12
        private const val CONTACT_REQUESTS_REQUEST_CODE = 0x4547_13
        private const val SHOW_CALL_REQUEST_CODE = 0x4547_14
        private const val ANSWER_LOCKED_CALL_REQUEST_CODE = 0x4547_15
        private const val DISMISS_LOCKED_CALL_REQUEST_CODE = 0x4547_16

        private const val CHANNEL_SERVICE = "aegis.service.v1"
        private const val CHANNEL_MSG_PREFIX = "aegis.msg."

        /**
         * Канал звонков v3: `lockscreenVisibility=PUBLIC` + обход «Не беспокоить».
         * Версия поднята с v2, потому что параметры канала неизменяемы после
         * создания — на уже установленных сборках старый канал так и остался бы
         * SECRET, и звонок на блокировке снова был бы невидим.
         */
        private const val CHANNEL_CALL = "aegis.call.v3"

        /** Канал звонков предыдущей политики; удаляется при создании v3. */
        private const val CHANNEL_CALL_LEGACY = "aegis.call.v2"
    }
}
