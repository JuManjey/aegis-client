package com.aegis.messenger.messaging

import android.util.Base64
import com.aegis.core.common.device.DeviceSettings
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.device.LinkedDevice
import com.aegis.core.common.model.AttachmentRef
import com.aegis.core.common.model.MessageCryptoInfo
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.PskCrypto
import com.aegis.network.tor.RelayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aegis.core.common.wire.WireChannel
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Синхронизация СВОИХ устройств одного аккаунта (мультидевайс §7.1.1,
 * `docs/multidevice.md` §4.2/§6/§6.2). Устройства делят мнемонику →
 * детерминированный `selfSyncSecret` (недоступный контактам), из которого
 * выводится self-sync mailbox каждого устройства ([mailboxFor]).
 *
 * Через self-sync устройства:
 *  1) АНОНСИРУЮТ себя (свой Signal identity + prekey-бандл), чтобы остальные мои
 *     устройства могли установить per-device Signal-сессии (для копий/отзывов);
 *  2) шлют друг другу копии входящих/исходящих ([mirrorMessage]), чтобы история
 *     сходилась без общего ratchet (инвариант MD-1);
 *  3) распространяют отзыв устройства ([revokeDevice], §10).
 *
 * Многослойность (§4) и forward secrecy self-sync (follow-up №2.3, §6.2 док):
 *  - `announce` — единственный BOOTSTRAP-пакет: запечатан ТОЛЬКО каскадом слоя A
 *    на `selfSyncSecret` (Signal-сессии между моими устройствами ещё нет — её как
 *    раз и поднимает анонс, доставляя identity+bundle). Несёт лишь ПУБЛИЧНЫЕ
 *    ключи, поэтому forward secrecy для него не требуется.
 *  - `msg`/`revoke` — идут двойным конвейером, как переписка: слой A (каскад на
 *    `selfSyncSecret`, один раз) → слой B (СВОЯ Signal-сессия `(myAccount,
 *    myDevice) → (myAccount, targetDevice)`, MD-1). Double Ratchet даёт forward
 *    secrecy: компрометация `selfSyncSecret` больше НЕ раскрывает историю self-
 *    sync — она дополнительно защищена эфемерным ratchet-состоянием слоя B.
 *
 * Транспорт — тот же onion-релей (§7.6): сервер видит только непрозрачный
 * mailbox-id (HKDF из secret) и шифртекст; связь устройств ему недоступна
 * (zero-trust, MD-2). Всё best-effort: без Tor/релея — тихий no-op.
 */
class SelfSyncManager(
    private val accountIdProvider: () -> String?,
    private val mnemonicProvider: () -> String?,
    private val deviceSettingsProvider: () -> DeviceSettings,
    private val onDeviceSettingsUpdate: suspend (DeviceSettings) -> Unit,
    private val sessionManager: SignalSessionManager,
    private val relayGateway: RelayGateway,
    /** Разрешён ли мультидевайс (§8): если нет — self-sync полностью инертен. */
    private val multiDeviceAllowedProvider: () -> Boolean = { true },
    /**
     * Смягчение тайминг-корреляции self-sync fan-out (§12, `meta.minimize` §8):
     * рандомизация порядка + джиттер per-device дропов моих устройств. По
     * умолчанию инертен (BASIC ⇒ прежнее поведение).
     */
    private val fanoutJitter: FanoutJitter =
        FanoutJitter({ com.aegis.core.common.security.MetadataMinimization.BASIC }),
    /**
     * Прикрытие факта/объёма self-sync отправки (§12, §3a): подмешивает фиктивные
     * дропы к per-device батчу своих устройств. По умолчанию инертен (BASIC ⇒ 0
     * паддинга, прежнее поведение) — реальный инстанс прокидывается из [AppContainer].
     */
    private val coverTraffic: CoverTraffic =
        CoverTraffic({ com.aegis.core.common.security.MetadataMinimization.BASIC }),
    private val psk: PskCrypto = CryptoModule.psk,
    private val json: Json = WireJson,
) {
    /**
     * Приём копии сообщения с другого моего устройства (§6) → в хранилище.
     * Задаётся из DI ([AppContainer]) после построения [MessagePipeline]. По
     * умолчанию no-op (копия отбрасывается).
     */
    var onMessageCopy: suspend (MessagePipeline.SelfSyncCopy) -> Unit = {}

    /**
     * Готовы ли крипто-фичи И разрешён ли мультидевайс (§8). Если пользователь
     * запретил мультидевайс — self-sync/анонсы отключаются (enforcement).
     */
    val isAvailable: Boolean
        get() = psk.isReady && sessionManager.isAvailable && multiDeviceAllowedProvider()

    /** self-sync секрет аккаунта (32 байта) или `null`. Вызывающий затирает сам. */
    private fun selfSyncSecret(): ByteArray? {
        val mnemonic = mnemonicProvider()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { psk.deriveSelfSyncSecret(mnemonic) }.getOrNull()
    }

    /** self-sync mailbox устройства [deviceId] моего аккаунта (§4.2) или `null`. */
    private fun mailboxFor(accountId: String, secret: ByteArray, deviceId: Int): String? =
        runCatching {
            psk.mailboxIdDevice(secret, PskCrypto.MailboxKind.SELF_SYNC, accountId, deviceId)
        }.getOrNull()

    /** Мой входящий self-sync подканал (для long-poll в [RelayReceiver], §6). */
    suspend fun myInbox(): String? = withContext(Dispatchers.IO) {
        if (!multiDeviceAllowedProvider()) return@withContext null
        val accountId = accountIdProvider() ?: return@withContext null
        val secret = selfSyncSecret() ?: return@withContext null
        try {
            mailboxFor(accountId, secret, deviceSettingsProvider().localDeviceId)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Анонсирует ЭТО устройство (свой identity + prekey-бандл) всем остальным моим
     * НЕотозванным устройствам по их self-sync mailbox. BOOTSTRAP-пакет: запечатан
     * каскадом слоя A на `selfSyncSecret` (Signal-сессию для анонса не поднять — её
     * как раз и бутстрапит анонс; несёт лишь публичные ключи). Best-effort.
     */
    suspend fun announce() = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            DiagnosticLog.d(DIAG_TAG) { "анонс пропущен: self-sync недоступен" }
            return@withContext
        }
        val accountId = accountIdProvider() ?: return@withContext
        val device = deviceSettingsProvider()
        val targets = device.activeLinkedDevices()
        if (targets.isEmpty()) {
            DiagnosticLog.d(DIAG_TAG) { "анонс пропущен: нет связанных устройств" }
            return@withContext
        }
        val client = relayGateway.clientOrNull() ?: run {
            DiagnosticLog.w(DIAG_TAG) { "анонс пропущен: нет клиента релея" }
            return@withContext
        }
        DiagnosticLog.event(
            DIAG_TAG, "анонс себя",
            "своёУстройство" to device.localDeviceId,
            "целей" to targets.size,
        )
        val bundleJson = runCatching { sessionManager.publishBundleJson() }.getOrNull() ?: return@withContext
        val identityB64 = runCatching { sessionManager.identityKeyB64() }.getOrNull() ?: return@withContext

        val secret = selfSyncSecret() ?: return@withContext
        try {
            val announce = SelfSyncAnnounce(
                deviceId = device.localDeviceId,
                label = device.label,
                identityB64 = identityB64,
                bundleJson = bundleJson,
            )
            val sealed = sealAnnounce(secret, announce) ?: return@withContext
            val envelope = SelfSyncEnvelope(
                kind = KIND_ANNOUNCE,
                senderDevice = device.localDeviceId,
                ciphertextB64 = sealed,
            )
            val payload = json.encodeToString(SelfSyncEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
            val realDrops = targets.mapNotNull { target ->
                mailboxFor(accountId, secret, target.deviceId)?.let { it to payload }
            }
            postDrops(client, realDrops)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Зеркалирует одну запись переписки на ВСЕ мои остальные НЕотозванные
     * устройства (self-sync §6): контент уже расшифрован локально. Слой A (каскад
     * на `selfSyncSecret`) применяется ОДИН раз, затем на каждое устройство — своя
     * Signal-сессия между моими устройствами (слой B, forward secrecy, §6.2/№2.3).
     * Signal-состояние переписки НЕ передаётся (MD-1). Best-effort — сбой не влияет
     * на доставку контакту.
     */
    suspend fun mirrorMessage(copy: MessagePipeline.SelfSyncCopy) = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext
        val accountId = accountIdProvider() ?: return@withContext
        val device = deviceSettingsProvider()
        val targets = device.activeLinkedDevices()
        if (targets.isEmpty()) return@withContext
        val client = relayGateway.clientOrNull() ?: return@withContext
        val secret = selfSyncSecret() ?: return@withContext
        try {
            val wireCopy = SelfSyncMessageCopy(
                contactId = copy.contactId,
                msgId = copy.msgId,
                outgoing = copy.outgoing,
                text = copy.text,
                timestampMs = copy.timestampMs,
                attachment = copy.attachment,
                crypto = copy.crypto,
            )
            val plain = json.encodeToString(SelfSyncMessageCopy.serializer(), wireCopy)
                .toByteArray(Charsets.UTF_8)
            val inner = cascadeEncrypt(secret, plain) ?: return@withContext
            fanOut(accountId, device, secret, targets, KIND_MSG, inner, client)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Отзывает МОЁ устройство [deviceId] у остальных моих устройств по self-sync
     * (§10): они пометят его `revoked=true` в СВОЁМ реестре и перестанут слать/
     * анонсировать на него. Дополняет [DeviceAnnouncer.revokeMyDeviceToContacts]
     * (отзыв у КОНТАКТОВ). Пакет идёт тем же двойным конвейером слой A→B, что и
     * копии сообщений (forward secrecy, §6.2/№2.3). Отзываемое устройство в
     * адресаты не попадает (исключается явно и из `activeLinkedDevices()`), поэтому
     * будим только «живые» устройства; авто-crypto-shred самого отзываемого
     * устройства осознанно НЕ делаем (деструктивно, требует локального
     * подтверждения — честная граница §10). Best-effort.
     */
    suspend fun revokeDevice(deviceId: Int) = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext
        val accountId = accountIdProvider() ?: return@withContext
        val device = deviceSettingsProvider()
        val targets = device.activeLinkedDevices()
        if (targets.isEmpty()) return@withContext
        val client = relayGateway.clientOrNull() ?: return@withContext
        val secret = selfSyncSecret() ?: return@withContext
        try {
            val plain = json.encodeToString(SelfSyncRevoke.serializer(), SelfSyncRevoke(deviceId = deviceId))
                .toByteArray(Charsets.UTF_8)
            val inner = cascadeEncrypt(secret, plain) ?: return@withContext
            fanOut(accountId, device, secret, targets, KIND_REVOKE, inner, client, excludeDeviceId = deviceId)
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Общий per-device fan-out служебного пакета self-sync (§6/§10, №2.3): [inner]
     * — уже запечатанный слоем A контент; для КАЖДОГО целевого устройства
     * оборачиваем его в СВОЮ Signal-сессию между моими устройствами (слой B,
     * MD-1, forward secrecy) и постим в его self-sync mailbox. Устройства без
     * анонса (нет bundle/сессии) пропускаем — синхронизируются после device-
     * announce (тот бутстрапит их Signal-сессию).
     */
    private suspend fun fanOut(
        accountId: String,
        device: DeviceSettings,
        secret: ByteArray,
        targets: List<LinkedDevice>,
        kind: String,
        inner: ByteArray,
        client: RelayClient,
        excludeDeviceId: Int? = null,
    ) {
        val realDrops = ArrayList<Pair<String, ByteArray>>(targets.size)
        for (target in targets) {
            if (target.deviceId == excludeDeviceId) continue
            val mailbox = mailboxFor(accountId, secret, target.deviceId) ?: continue
            // Слой B: своя Signal-сессия (myAccount,myDevice)→(myAccount,targetDevice).
            val env = try {
                sessionManager.encryptTo(accountId, target.deviceId, target.bundleJson, inner, target.identityB64)
            } catch (_: Exception) {
                continue
            }
            val envelope = SelfSyncEnvelope(
                kind = kind,
                senderDevice = device.localDeviceId,
                ciphertextB64 = Base64.encodeToString(env.ciphertext, Base64.NO_WRAP),
                sigType = env.type,
            )
            val payload = json.encodeToString(SelfSyncEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
            realDrops += mailbox to payload
        }
        postDrops(client, realDrops)
    }

    /**
     * Постит батч реальных дропов, разбавляя его фиктивными (§3a, §12): №0 —
     * рандомизируем ПОРЯДОК реальных и decoy-дропов и джиттерим каждый, чтобы
     * N per-device POST-ов self-sync не уходили детерминированной «пачкой» и число
     * дропов не раскрывало число моих устройств. При MAXIMUM padCount()>0; иначе
     * decoy пуст ⇒ прежнее поведение. Best-effort.
     */
    private suspend fun postDrops(client: RelayClient, realDrops: List<Pair<String, ByteArray>>) {
        if (realDrops.isEmpty()) return
        val decoyDrops = coverTraffic.decoyDrops(coverTraffic.padCount())
        for ((mailbox, payload) in fanoutJitter.order(realDrops + decoyDrops)) {
            fanoutJitter.awaitJitter()
            runCatching { client.post(mailbox, payload) }
        }
    }

    /**
     * Обрабатывает входящий self-sync конверт (из моего self-sync mailbox):
     *  - `kind=announce` (§5): sender-устройство регистрируется/уточняется в моём
     *    реестре ([DeviceSettings.linkedDevices]) — так узнаём identity+bundle
     *    (bootstrap, каскад слоя A);
     *  - `kind=msg` (§6): копия записи переписки отражается в моё хранилище
     *    ([onMessageCopy]) — расшифровка слой B (Signal) → слой A (каскад);
     *  - `kind=revoke` (§10): другое моё устройство сообщило об отзыве устройства —
     *    помечаем его `revoked=true` в моём реестре (тот же двойной конвейер).
     * `true`, если конверт распознан.
     */
    suspend fun onWire(wireJson: String): Boolean = withContext(Dispatchers.IO) {
        // §8: мультидевайс запрещён ⇒ игнорируем self-sync от собственного аккаунта.
        if (!multiDeviceAllowedProvider()) return@withContext false
        val envelope = runCatching {
            json.decodeFromString(SelfSyncEnvelope.serializer(), wireJson)
        }.getOrNull() ?: return@withContext false
        // Свои же дропы не обрабатываем (fan-out мог задеть собственный ящик).
        if (envelope.senderDevice == deviceSettingsProvider().localDeviceId) return@withContext true
        DiagnosticLog.event(
            DIAG_TAG, "вход",
            "вид" to envelope.kind,
            "от" to envelope.senderDevice,
            "байт" to wireJson.length,
        )
        when (envelope.kind) {
            KIND_ANNOUNCE -> handleAnnounce(envelope)
            KIND_MSG -> handleMessage(envelope)
            KIND_REVOKE -> handleRevoke(envelope)
            else -> {
                DiagnosticLog.w(DIAG_TAG) { "неизвестный kind=${envelope.kind}" }
                false
            }
        }
    }

    private suspend fun handleMessage(envelope: SelfSyncEnvelope): Boolean {
        val inner = openLayerB(envelope) ?: return false
        val copy = openContent(inner) { plain ->
            json.decodeFromString(SelfSyncMessageCopy.serializer(), plain)
        } ?: return false
        onMessageCopy(
            MessagePipeline.SelfSyncCopy(
                contactId = copy.contactId,
                msgId = copy.msgId,
                outgoing = copy.outgoing,
                text = copy.text,
                timestampMs = copy.timestampMs,
                attachment = copy.attachment,
                crypto = copy.crypto,
            ),
        )
        return true
    }

    private suspend fun handleAnnounce(envelope: SelfSyncEnvelope): Boolean {
        val secret = selfSyncSecret() ?: return false
        val announce = try {
            openAnnounce(secret, envelope.ciphertextB64)
        } finally {
            secret.fill(0)
        } ?: return false
        // Не регистрируем самого себя (эхо/петля).
        if (announce.deviceId == deviceSettingsProvider().localDeviceId) return true
        registerDevice(announce)
        return true
    }

    private suspend fun handleRevoke(envelope: SelfSyncEnvelope): Boolean {
        val inner = openLayerB(envelope) ?: return false
        val revoke = openContent(inner) { plain ->
            json.decodeFromString(SelfSyncRevoke.serializer(), plain)
        } ?: return false
        val device = deviceSettingsProvider()
        // Отзыв самого себя не отражаем: записи о СЕБЕ в linkedDevices нет, а
        // авто-crypto-shred при удалённом отзыве осознанно не делаем (§10, честная
        // граница — деструктивно, требует явного локального подтверждения).
        if (revoke.deviceId == device.localDeviceId) return true
        markRevoked(device, revoke.deviceId)
        return true
    }

    /**
     * Снимает слой B (Signal, forward secrecy) с `msg`/`revoke`-конверта: выбирает
     * ratchet по `senderDevice` (сессия с моим устройством-отправителем, MD-1).
     * Требует `sigType` (тип Signal-конверта): без него это не двойной конвейер
     * №2.3, конверт отвергаем. Возвращает контент слоя A либо `null`.
     */
    private fun openLayerB(envelope: SelfSyncEnvelope): ByteArray? {
        if (envelope.sigType.isBlank()) return null
        val accountId = accountIdProvider() ?: return null
        val ciphertext = runCatching { Base64.decode(envelope.ciphertextB64, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val senderIdentity = deviceSettingsProvider().linkedDevices
            .firstOrNull { it.deviceId == envelope.senderDevice }?.identityB64
        return runCatching {
            sessionManager.decryptFrom(accountId, envelope.senderDevice, ciphertext, envelope.sigType, senderIdentity)
        }.getOrNull()
    }

    /** Снимает слой A (каскад на `selfSyncSecret`) и разбирает контент через [parse]. */
    private inline fun <T> openContent(inner: ByteArray, parse: (String) -> T): T? {
        val secret = selfSyncSecret() ?: return null
        return try {
            val plain = cascadeDecrypt(secret, inner) ?: return null
            runCatching { parse(plain.decodeToString()) }.getOrNull()
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Помечает моё устройство [deviceId] отозванным в реестре (§10). Идемпотентно;
     * если устройство ещё не известно (анонс не дошёл), создаёт tombstone-запись,
     * чтобы поздний device-announce не «воскресил» его ([registerDevice] сохраняет
     * `revoked` существующей записи).
     */
    private suspend fun markRevoked(device: DeviceSettings, deviceId: Int) {
        val existing = device.linkedDevices.firstOrNull { it.deviceId == deviceId }
        if (existing?.revoked == true) return
        val entry = (existing ?: LinkedDevice(deviceId = deviceId, addedAtMs = System.currentTimeMillis()))
            .copy(revoked = true)
        val merged = device.linkedDevices.filterNot { it.deviceId == deviceId } + entry
        onDeviceSettingsUpdate(device.copy(linkedDevices = merged))
    }

    /** Добавляет/уточняет устройство в моём реестре (identity+bundle из анонса). */
    private suspend fun registerDevice(announce: SelfSyncAnnounce) {
        val device = deviceSettingsProvider()
        val existing = device.linkedDevices.firstOrNull { it.deviceId == announce.deviceId }
        val updatedEntry = (existing ?: LinkedDevice(deviceId = announce.deviceId)).copy(
            label = announce.label.ifBlank { existing?.label ?: "" },
            identityB64 = announce.identityB64,
            bundleJson = announce.bundleJson,
            addedAtMs = existing?.addedAtMs?.takeIf { it != 0L } ?: System.currentTimeMillis(),
            revoked = existing?.revoked ?: false,
        )
        val nextAssignable = maxOf(device.nextAssignableDeviceId, announce.deviceId + 1)
        val merged = device.linkedDevices.filterNot { it.deviceId == announce.deviceId } + updatedEntry
        onDeviceSettingsUpdate(
            device.copy(linkedDevices = merged, nextAssignableDeviceId = nextAssignable),
        )
    }

    // --- Слой A (каскад на selfSyncSecret) ------------------------------------

    private fun cascadeEncrypt(secret: ByteArray, plain: ByteArray): ByteArray? =
        runCatching { CryptoModule.createCascadeCipher(secret).encrypt(plain) }.getOrNull()

    private fun cascadeDecrypt(secret: ByteArray, ciphertext: ByteArray): ByteArray? =
        runCatching { CryptoModule.createCascadeCipher(secret).decrypt(ciphertext) }.getOrNull()

    private fun sealAnnounce(secret: ByteArray, announce: SelfSyncAnnounce): String? = runCatching {
        val plain = json.encodeToString(SelfSyncAnnounce.serializer(), announce).toByteArray(Charsets.UTF_8)
        Base64.encodeToString(CryptoModule.createCascadeCipher(secret).encrypt(plain), Base64.NO_WRAP)
    }.getOrNull()

    private fun openAnnounce(secret: ByteArray, ciphertextB64: String): SelfSyncAnnounce? = runCatching {
        val plain = CryptoModule.createCascadeCipher(secret).decrypt(Base64.decode(ciphertextB64, Base64.NO_WRAP))
        json.decodeFromString(SelfSyncAnnounce.serializer(), plain.decodeToString())
    }.getOrNull()

    /**
     * Служебный конверт self-sync, уходящий по сети (только шифртекст).
     * Для `announce` [ciphertextB64] — каскад слоя A (bootstrap); для `msg`/`revoke`
     * — Signal-конверт слоя B поверх каскада, с типом в [sigType] (№2.3).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class SelfSyncEnvelope(
        @EncodeDefault
        val channel: String = CHANNEL,
        val kind: String,
        val senderDevice: Int,
        val ciphertextB64: String,
        /** Тип Signal-конверта слоя B для `msg`/`revoke`: "PreKey"/"Whisper" (№2.3). */
        val sigType: String = "",
        val v: Int = 1,
    )

    /** Device-announce: публичная идентичность моего устройства (§5, шаг 2). */
    @Serializable
    private data class SelfSyncAnnounce(
        val deviceId: Int,
        val label: String = "",
        val identityB64: String,
        val bundleJson: String,
    )

    /** Device-revoke для моих устройств (§10): какое устройство помечено отозванным. */
    @Serializable
    private data class SelfSyncRevoke(
        val deviceId: Int,
    )

    /** Копия записи переписки для моих устройств (§6): контент уже расшифрован. */
    @Serializable
    private data class SelfSyncMessageCopy(
        val contactId: String,
        val msgId: String,
        val outgoing: Boolean,
        val text: String = "",
        val timestampMs: Long,
        val attachment: AttachmentRef? = null,
        val crypto: MessageCryptoInfo? = null,
    )

    companion object {
        /** Маркер канала self-sync в конверте (для диспетчеризации). */
        const val CHANNEL: String = WireChannel.SELF_SYNC
        const val KIND_ANNOUNCE: String = "announce"
        const val KIND_MSG: String = "msg"
        const val KIND_REVOKE: String = "revoke"
        const val DIAG_TAG = "self-sync"
    }
}
