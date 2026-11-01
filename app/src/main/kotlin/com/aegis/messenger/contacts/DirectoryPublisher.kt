package com.aegis.messenger.contacts

import com.aegis.core.common.contact.AliasClaim
import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.contact.DirectoryDevice
import com.aegis.core.common.contact.DirectoryRecord
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.core.common.security.ContactRequestMode
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.DiscoveryCrypto
import com.aegis.messenger.messaging.RelayGateway
import com.aegis.messenger.messaging.SignalSessionManager
import com.aegis.network.tor.AliasPutResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Публикация СВОЕЙ записи в каталоге обнаружения и (опционально) заявки на
 * публичный `@ник` (`docs/contact_requests.md`, PROTOCOL.md §4.7/§4.8).
 *
 * ЧТО УХОДИТ НА РЕЛЕЙ. Только запечатанная запись (AEAD на ключе из тега, у
 * сервера тега нет) под опаковым индексом. Внутри — исключительно ПУБЛИЧНЫЕ
 * ключи: identity аккаунта (Ed25519/X25519) и prekey-бандлы слоя B по
 * устройствам. Приватного материала в записи нет и быть не может: она доступна
 * каждому, кому владелец дал свой адрес.
 *
 * ЗАЧЕМ ПЕРИОДИЧЕСКИ. Запись подписана и имеет срок; prekey-бандл однократного
 * использования расходуется, а срок публикации истекает. Поэтому запись
 * обновляется при разблокировке и далее раз в [REFRESH_INTERVAL_MS] — иначе
 * через несколько недель контакт «по адресу» перестал бы устанавливаться, и
 * причину было бы не видно.
 */
class DirectoryPublisher(
    private val relayGateway: RelayGateway,
    private val sessionManager: SignalSessionManager,
    private val scope: CoroutineScope,
    /** Тег обнаружения (base64) — свой приватный. `null`, если ещё не создан. */
    private val tagProvider: suspend () -> String?,
    /** Публичный тег для алиаса (base64) — ротируется независимо. */
    private val publicTagProvider: suspend () -> String?,
    /** Мнемоника разблокированного аккаунта (для подписи). `null` — заблокировано. */
    private val mnemonicProvider: () -> String?,
    /** Публичная идентичность аккаунта: accountId + ed25519/x25519 + отпечаток. */
    private val identityProvider: suspend () -> AccountPublicIdentity?,
    /** Ник для показа собеседнику (логин профиля). */
    private val nicknameProvider: suspend () -> String,
    /** Мой device id и его назначение (мультидевайс §4.1). */
    private val localDeviceIdProvider: () -> Int,
    /** Режим удалённого добавления из настроек безопасности. */
    private val modeProvider: () -> ContactRequestMode,
    /** Желаемый публичный алиас (без `@`) или пусто, если не задан. */
    private val aliasProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /** Минимальная публичная идентичность аккаунта для записи каталога. */
    data class AccountPublicIdentity(
        val accountId: String,
        val ed25519Hex: String,
        val x25519Hex: String,
        val fingerprint: String,
    )

    /** Итог попытки занять публичный алиас — для показа в настройках профиля. */
    sealed interface AliasResult {
        data class Ok(val normalized: String) : AliasResult
        data object Taken : AliasResult
        data object Unavailable : AliasResult
        data class Invalid(val reason: String) : AliasResult
        data class Failed(val reason: String) : AliasResult
    }

    private val publishMutex = Mutex()

    @Volatile
    private var loop: Job? = null

    /** Когда запись последний раз успешно опубликована (0 — ни разу). */
    @Volatile
    var lastPublishedAtMs: Long = 0L
        private set

    /** Периодическое обновление записи. Идемпотентно. */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                runCatching { publish() }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * Собирает, подписывает, запечатывает и публикует свою запись каталога.
     * `false` — публиковать нечего либо релей/Tor недоступны (best-effort:
     * удалённое добавление просто не сработает, E2EE от этого не страдает).
     */
    suspend fun publish(): Boolean = publishMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!modeProvider().enabled) {
                DiagnosticLog.d(DIAG_TAG) { "публикация пропущена: удалённое добавление выключено" }
                return@withContext false
            }
            if (!DiscoveryCrypto.isAvailable) {
                DiagnosticLog.w(DIAG_TAG) { "публикация невозможна: нет слоя B в сборке" }
                return@withContext false
            }
            val mnemonic = mnemonicProvider() ?: return@withContext false
            val identity = identityProvider() ?: return@withContext false
            val tagB64 = tagProvider() ?: run {
                DiagnosticLog.w(DIAG_TAG) { "публикация невозможна: тег обнаружения не создан" }
                return@withContext false
            }
            val params = relayGateway.relayParams()
            if (params?.directoryEnabled != true) {
                DiagnosticLog.w(DIAG_TAG) {
                    "публикация невозможна: каталог выключен на релее (или релей недоступен)"
                }
                return@withContext false
            }

            val record = buildRecord(mnemonic, identity, tagB64) ?: return@withContext false
            val ids = DiscoveryCrypto.deriveIds(tagB64)
            val sealed = DiscoveryCrypto.sealRecord(
                ids.recordKeyB64,
                ids.index,
                json.encodeToString(DirectoryRecord.serializer(), record),
            )
            if (params.directoryMaxBytes in 1..<sealed.size) {
                DiagnosticLog.w(DIAG_TAG) {
                    "запись каталога ${sealed.size} Б превышает лимит релея " +
                        "${params.directoryMaxBytes} Б — публикация отменена"
                }
                return@withContext false
            }
            val ttl = params.directoryMaxTtlSec.takeIf { it > 0 } ?: DEFAULT_TTL_SEC
            val ok = relayGateway.putDirectoryRecord(ids.index, sealed, ttl)
            if (ok) lastPublishedAtMs = System.currentTimeMillis()
            DiagnosticLog.event(
                DIAG_TAG, "своя запись каталога",
                "устройств" to record.devices.size,
                "байт" to sealed.size,
                "ок" to ok,
            )
            ok
        }
    }

    /**
     * Собирает и подписывает запись. `null`, если нет ключей слоя B — публиковать
     * запись без prekey-бандла бессмысленно: собеседник не смог бы отправить
     * приглашение (оно едет внутри Signal PreKeyMessage).
     */
    private suspend fun buildRecord(
        mnemonic: String,
        identity: AccountPublicIdentity,
        tagB64: String,
    ): DirectoryRecord? {
        val signalIdentity = runCatching { sessionManager.identityKeyB64() }.getOrNull().orEmpty()
        val bundle = runCatching { sessionManager.publishBundleJson() }.getOrNull().orEmpty()
        if (signalIdentity.isBlank() || bundle.isBlank()) {
            DiagnosticLog.w(DIAG_TAG) {
                "публикация отменена: нет своего prekey-бандла слоя B"
            }
            return null
        }
        val now = System.currentTimeMillis()
        val unsigned = DirectoryRecord(
            accountId = identity.accountId,
            ed25519Hex = identity.ed25519Hex,
            x25519Hex = identity.x25519Hex,
            fingerprint = identity.fingerprint,
            nickname = runCatching { nicknameProvider() }.getOrDefault(""),
            devices = listOf(
                DirectoryDevice(
                    deviceId = localDeviceIdProvider(),
                    signalIdentityB64 = signalIdentity,
                    bundleJson = bundle,
                ),
            ),
            tagCommitB64 = DiscoveryCrypto.deriveIds(tagB64).tagCommitB64,
            issuedAtMs = now,
            expiresAtMs = now + DEFAULT_TTL_SEC * 1000,
        )
        val signature = runCatching {
            AccountCrypto.sign(mnemonic, unsigned.canonicalBytes())
        }.getOrNull() ?: return null
        return unsigned.copy(
            sigB64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP),
        )
    }

    /**
     * Занимает или продлевает публичный `@ник`.
     *
     * Заявка подписывается ключом аккаунта и содержит только указатель:
     * accountId, ключ подписи и адрес приватной записи каталога — ни бандлов, ни
     * списка устройств. Раскрывается ПУБЛИЧНЫЙ тег (отдельный от приватного),
     * поэтому его ротация закрывает канал «пишут по нику», не задевая адреса,
     * выданные доверенным людям.
     */
    suspend fun claimAlias(alias: String): AliasResult = withContext(Dispatchers.IO) {
        if (!modeProvider().aliasAllowed) return@withContext AliasResult.Unavailable
        val normalized = DiscoveryCrypto.aliasIndexOrNull(alias)
            ?: return@withContext AliasResult.Invalid(
                DiscoveryCrypto.aliasErrorOrNull(alias) ?: "некорректный ник",
            )
        val mnemonic = mnemonicProvider() ?: return@withContext AliasResult.Failed("аккаунт заблокирован")
        val identity = identityProvider() ?: return@withContext AliasResult.Failed("нет идентичности")
        val publicTag = publicTagProvider()
            ?: return@withContext AliasResult.Failed("публичный тег не создан")
        val discoveryIndex = DiscoveryCrypto.deriveIds(publicTag).index

        val now = System.currentTimeMillis()
        val claim = AliasClaim(
            aliasIndex = normalized.index,
            accountId = identity.accountId,
            ed25519Hex = identity.ed25519Hex,
            discoveryIndex = discoveryIndex,
            tagB64 = publicTag,
            nickname = normalized.normalized,
            issuedAtMs = now,
            expiresAtMs = now + ALIAS_TTL_MS,
        )
        // Байты заявки считаем каноничной сериализацией и ИМЕННО ИХ отправляем:
        // релей и клиент-получатель проверяют подпись над принятыми байтами, не
        // пересобирая JSON, поэтому расхождений быть не может.
        val claimBytes = claim.canonicalBytes()
        val signature = runCatching { AccountCrypto.sign(mnemonic, claimBytes) }.getOrNull()
            ?: return@withContext AliasResult.Failed("не удалось подписать заявку")

        val b64 = android.util.Base64.NO_WRAP
        val result = relayGateway.putAliasClaim(
            normalized.index,
            android.util.Base64.encodeToString(claimBytes, b64),
            android.util.Base64.encodeToString(signature, b64),
        )
        when (result) {
            AliasPutResult.Ok -> {
                // Публичный тег теперь достижим по нику ⇒ запись каталога под ним
                // тоже должна существовать, иначе поиск найдёт указатель в пустоту.
                runCatching { publishUnderPublicTag(mnemonic, identity, publicTag) }
                AliasResult.Ok(normalized.normalized)
            }
            AliasPutResult.Taken -> AliasResult.Taken
            AliasPutResult.Unavailable -> AliasResult.Unavailable
            AliasPutResult.PowRequired -> AliasResult.Failed("не удалось выполнить proof-of-work")
            is AliasPutResult.Rejected -> AliasResult.Failed(result.reason)
        }
    }

    /**
     * Публикует ВТОРУЮ копию записи под публичным тегом. Нужна, потому что
     * приватный и публичный теги дают разные индексы и разные ключи записи:
     * нашедший нас по нику должен уметь открыть запись публичным тегом, не зная
     * приватного.
     */
    private suspend fun publishUnderPublicTag(
        mnemonic: String,
        identity: AccountPublicIdentity,
        publicTagB64: String,
    ): Boolean {
        val record = buildRecord(mnemonic, identity, publicTagB64) ?: return false
        val ids = DiscoveryCrypto.deriveIds(publicTagB64)
        val sealed = DiscoveryCrypto.sealRecord(
            ids.recordKeyB64,
            ids.index,
            json.encodeToString(DirectoryRecord.serializer(), record),
        )
        return relayGateway.putDirectoryRecord(ids.index, sealed, DEFAULT_TTL_SEC)
    }

    /** Снимает записи каталога с публикации (обе копии). Best-effort. */
    suspend fun unpublish(): Unit = withContext(Dispatchers.IO) {
        listOfNotNull(tagProvider(), publicTagProvider()).forEach { tag ->
            runCatching { relayGateway.deleteDirectoryRecord(DiscoveryCrypto.deriveIds(tag).index) }
        }
        lastPublishedAtMs = 0L
        DiagnosticLog.event(DIAG_TAG, "записи каталога сняты с публикации")
    }

    /**
     * Мой адрес контакта для показа/копирования. `null`, если тега ещё нет.
     *
     * Тег в адресе едет в URL-safe base64 без паддинга — как и индексы ящиков,
     * чтобы адрес можно было безопасно пересылать любым каналом и вставлять в
     * ссылку без экранирования.
     */
    suspend fun myAddress(): String? {
        val identity = identityProvider() ?: return null
        val tag = tagProvider() ?: return null
        val raw = runCatching { android.util.Base64.decode(tag, android.util.Base64.NO_WRAP) }
            .getOrNull() ?: return null
        val urlSafeTag = android.util.Base64.encodeToString(
            raw,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
        return runCatching { ContactAddress.format(identity.accountId, urlSafeTag) }.getOrNull()
    }

    /** Желаемый алиас из настроек (для экрана профиля). */
    suspend fun currentAlias(): String = runCatching { aliasProvider() }.getOrDefault("")

    private companion object {
        const val DIAG_TAG = "directory"

        /** Как часто обновлять свою запись (срок и одноразовые prekeys истекают). */
        const val REFRESH_INTERVAL_MS = 12 * 3600_000L

        /** Срок публикации записи по умолчанию (клемпится лимитом релея). */
        const val DEFAULT_TTL_SEC = 30L * 24 * 3600

        /** Срок заявки на алиас (продлевается автоматически при обновлении). */
        const val ALIAS_TTL_MS = 60L * 24 * 3600 * 1000
    }
}
