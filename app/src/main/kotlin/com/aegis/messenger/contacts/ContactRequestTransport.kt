package com.aegis.messenger.contacts

import android.util.Base64
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.wire.WireChannel
import com.aegis.crypto.PskCrypto
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.messenger.messaging.WireJson
import com.aegis.network.tor.RelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Транспорт рукопожатия удалённого добавления (RCH v1, `docs/contact_requests.md`).
 *
 * ЧЕМ ОТЛИЧАЕТСЯ ОТ [com.aegis.messenger.messaging.RelayGateway]. Обычная
 * доставка адресует ящики, выведенные ИЗ PSK контакта — а здесь контакта и PSK
 * ещё нет. Адрес берётся из ТЕГА ОБНАРУЖЕНИЯ:
 *
 * ```
 * ящик приглашений = HKDF(salt="AEGIS/mailbox/v2", ikm=тег_собеседника,
 *                         info="aegis/mailbox/v2/invite/<его accountId>/<его deviceId>")
 * ящик ответа      = HKDF(…, ikm=replyTag из INVITE, info=".../reply/…")
 * ```
 *
 * Именно это разрывает круг «нет PSK ⇒ нет адреса ⇒ нечем согласовать PSK».
 * Знание тега — capability написать заявку, и ничего больше; ротация тега её
 * отзывает.
 *
 * ШИФРОВАНИЕ. Нагрузка едет ВНУТРИ Signal-сообщения (слой B): инициатор берёт
 * prekey-бандл из проверенной записи каталога и отправляет полноценный PQXDH
 * `PreKeyMessage`. Слоя A здесь нет и быть не может — его секрет как раз и
 * согласуется этим рукопожатием. Для сервера конверт неотличим от обычного.
 */
class ContactRequestTransport(
    private val sessionManager: SignalSessionManager,
    /** Построитель клиента релея (тот же, что у обычной доставки). */
    private val clientProvider: () -> RelayClient?,
    /** Мой accountId — локальное имя в адресации libsignal. */
    private val accountIdProvider: suspend () -> String?,
    /** device id ЭТОГО устройства — уходит в конверт как `senderDevice`. */
    private val localDeviceIdProvider: () -> Int = { 1 },
    private val json: Json = WireJson,
) {
    /**
     * Отправляет `INVITE` во все известные ящики приглашений собеседника.
     *
     * Сессия слоя B устанавливается по [peerBundleJson] — бандлу из ПРОВЕРЕННОЙ
     * записи каталога (проверку выполняет вызывающий до этого вызова, иначе
     * сессия завелась бы на чужих ключах). [peerIdentityB64] из той же записи
     * закрепляется в ядре: ответ на заявку с другим identity не расшифруется.
     */
    @Suppress("LongParameterList")
    suspend fun sendInvite(
        peerAccountId: String,
        peerDeviceId: Int,
        peerBundleJson: String,
        peerIdentityB64: String,
        peerTagB64: String,
        payloadJson: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val client = clientProvider() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "заявка не отправлена: нет клиента релея (Tor/адрес)" }
            return@withContext false
        }
        val mailbox = inviteMailbox(peerTagB64, peerAccountId, peerDeviceId) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "заявка не отправлена: не вывести адрес ящика приглашений" }
            return@withContext false
        }
        // Бандл из записи каталога аутентифицирован подписью аккаунта, а его
        // внутренняя согласованность (`identity_key` == объявленный) проверена
        // в [DirectoryRecordVerifier] — дополнительная сверка здесь была бы
        // повтором той же проверки в менее подходящем месте.
        val envelope = sealLayerB(
            peerAccountId = peerAccountId,
            peerDeviceId = peerDeviceId,
            peerBundleJson = peerBundleJson,
            peerIdentityB64 = peerIdentityB64,
            payloadJson = payloadJson,
        ) ?: return@withContext false

        val ok = runCatching { client.post(mailbox, envelope) != null }.getOrDefault(false)
        DiagnosticLog.event(
            DIAG_TAG, "дроп заявки",
            "ящик" to DiagnosticLog.mailbox(mailbox),
            "байт" to envelope.size,
            "ок" to ok,
        )
        ok
    }

    /**
     * Отправляет `ACCEPT`/`DECLINE` в ящик ответа инициатора, адрес которого
     * выведен из эфемерного `replyTag` его заявки.
     *
     * Сессия слоя B к этому моменту уже есть (её завёл входящий `PreKeyMessage`),
     * поэтому бандл не нужен — уходит обычный `Whisper`. [peerIdentityB64] —
     * identity из ПОДПИСАННОЙ заявки: сессия на другом ключе ответа не получит.
     */
    suspend fun sendToReplyBox(
        peerAccountId: String,
        peerDeviceId: Int,
        peerIdentityB64: String,
        replyTagB64: String,
        payloadJson: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val client = clientProvider() ?: return@withContext false
        val mailbox = replyMailbox(replyTagB64, peerAccountId, peerDeviceId) ?: return@withContext false
        val envelope = sealLayerB(
            peerAccountId = peerAccountId,
            peerDeviceId = peerDeviceId,
            peerBundleJson = "",
            peerIdentityB64 = peerIdentityB64,
            payloadJson = payloadJson,
        ) ?: return@withContext false

        val ok = runCatching { client.post(mailbox, envelope) != null }.getOrDefault(false)
        DiagnosticLog.event(
            DIAG_TAG, "дроп ответа на заявку",
            "ящик" to DiagnosticLog.mailbox(mailbox),
            "байт" to envelope.size,
            "ок" to ok,
        )
        ok
    }

    /**
     * Расшифровывает конверт канала `invite`. Возвращает plaintext нагрузки и
     * device id отправителя, либо `null`, если конверт не разобран/не расшифрован.
     *
     * Отдельный путь от конвейера переписки: сообщение приходит от того, с кем
     * контакта ЕЩЁ НЕТ, поэтому `StoredContact` для него не существует, а слоя A
     * в конверте нет.
     */
    suspend fun openWire(wireJson: String): Opened? = withContext(Dispatchers.IO) {
        val wire = runCatching { json.decodeFromString(InviteWireEnvelope.serializer(), wireJson) }
            .getOrNull() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "конверт заявки не разобран" }
            return@withContext null
        }
        if (wire.senderAccountId.isBlank()) {
            DiagnosticLog.w(DIAG_TAG) { "конверт заявки без accountId отправителя" }
            return@withContext null
        }
        val ciphertext = runCatching { Base64.decode(wire.ciphertextB64, Base64.NO_WRAP) }
            .getOrNull() ?: return@withContext null
        // libsignal умеет принять `PreKeyMessage` от НЕИЗВЕСТНОГО отправителя —
        // в этом и смысл X3DH, поэтому `StoredContact` здесь не нужен. Identity
        // отправителя приходит внутри подписанной нагрузки, а ответ на НАШУ
        // заявку защищает ключ, закреплённый при её отправке ([sendInvite]).
        val plaintext = runCatching {
            sessionManager.decryptFrom(
                remoteAccountId = wire.senderAccountId,
                senderDevice = wire.senderDevice.coerceIn(1, 255),
                ciphertext = ciphertext,
                messageType = wire.type,
                expectedIdentityB64 = null,
            )
        }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) { "слой B не снялся с конверта заявки" }
            return@withContext null
        }
        Opened(
            payloadJson = String(plaintext, Charsets.UTF_8),
            senderAccountId = wire.senderAccountId,
            senderDeviceId = wire.senderDevice,
        )
    }

    /** Расшифрованный конверт канала `invite`. */
    data class Opened(
        val payloadJson: String,
        val senderAccountId: String,
        val senderDeviceId: Int,
    )

    /** Адрес ящика приглашений собеседника из его тега обнаружения. */
    fun inviteMailbox(tagB64: String, ownerAccountId: String, ownerDeviceId: Int): String? =
        mailbox(tagB64, PskCrypto.MailboxKind.INVITE, ownerAccountId, ownerDeviceId)

    /** Адрес ящика ответа из эфемерного `replyTag` раунда. */
    fun replyMailbox(replyTagB64: String, ownerAccountId: String, ownerDeviceId: Int): String? =
        mailbox(replyTagB64, PskCrypto.MailboxKind.REPLY, ownerAccountId, ownerDeviceId)

    private fun mailbox(
        secretB64: String,
        kind: PskCrypto.MailboxKind,
        ownerAccountId: String,
        ownerDeviceId: Int,
    ): String? {
        if (secretB64.isBlank() || ownerAccountId.isBlank()) return null
        if (ownerDeviceId !in 1..255) return null
        val secret = runCatching { PskCrypto.fromBase64(secretB64) }.getOrNull() ?: return null
        return try {
            PskCrypto.mailboxIdDevice(secret, kind, ownerAccountId, ownerDeviceId)
        } catch (_: Exception) {
            null
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Шифрует нагрузку слоем B и собирает внешний конверт.
     *
     * `senderAccountId` едет СНАРУЖИ шифртекста — иначе получатель не смог бы
     * выбрать нужную libsignal-сессию для расшифровки (контакта ещё нет, и
     * сопоставить конверт с кем-то по ящику невозможно: ящик выведен из тега).
     * Это осознанная, локализованная уступка: сервер видит accountId только в
     * конвертах КАНАЛА ЗАЯВОК, а не в переписке. Тег — capability, поэтому
     * положить такой конверт может лишь тот, кому владелец дал адрес.
     */
    private suspend fun sealLayerB(
        peerAccountId: String,
        peerDeviceId: Int,
        peerBundleJson: String,
        peerIdentityB64: String,
        payloadJson: String,
    ): ByteArray? {
        val myAccountId = accountIdProvider() ?: return null
        val encrypted = runCatching {
            sessionManager.encryptTo(
                remoteAccountId = peerAccountId,
                remoteDevice = peerDeviceId,
                bundleJson = peerBundleJson.takeIf { it.isNotBlank() },
                plaintext = payloadJson.toByteArray(Charsets.UTF_8),
                expectedIdentityB64 = peerIdentityB64.takeIf { it.isNotBlank() },
            )
        }.getOrElse { e ->
            DiagnosticLog.e(DIAG_TAG, e) { "слой B не зашифровал нагрузку заявки" }
            return null
        }
        return json.encodeToString(
            InviteWireEnvelope.serializer(),
            InviteWireEnvelope(
                type = encrypted.type,
                ciphertextB64 = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP),
                senderAccountId = myAccountId,
                senderDevice = localDeviceIdProvider().coerceIn(1, 255),
            ),
        ).toByteArray(Charsets.UTF_8)
    }

    /** Внешний конверт канала заявок. Слоя A нет — он этим рукопожатием и создаётся. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class InviteWireEnvelope(
        /** Тип сообщения libsignal: "PreKey" (INVITE) либо "Whisper" (ответ). */
        val type: String,
        val ciphertextB64: String,
        /** accountId отправителя — снаружи шифртекста, чтобы выбрать сессию. */
        val senderAccountId: String,
        val senderDevice: Int = 1,
        @EncodeDefault
        val channel: String = WireChannel.INVITE,
        val v: Int = 1,
    )

    private companion object {
        const val DIAG_TAG = "contact-req"
    }
}
