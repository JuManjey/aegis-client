package com.aegis.messenger.psk

import android.content.Context
import android.util.Base64
import com.aegis.core.common.account.AccountIdentity
import com.aegis.core.common.psk.PskExchangePayload
import com.aegis.core.common.psk.PskMeta
import com.aegis.core.common.psk.PskQrPayload
import com.aegis.core.common.psk.PskRecord
import com.aegis.core.common.psk.PskSource
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.CryptoModule
import com.aegis.crypto.PskCrypto
import com.aegis.crypto.cascade.CascadeCipher
import com.aegis.messenger.R
import com.aegis.messenger.appearance.LocaleManager
import com.aegis.data.storage.PskStore
import com.aegis.data.storage.StorageLockedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** Результат генерации PSK для показа собеседнику (QR / мнемоника / NFC). */
data class PskGenerated(
    val payload: PskExchangePayload,
    /**
     * Полный JSON полезной нагрузки (включая prekey-бандл слоя B) — контент NFC.
     * NFC-транспорт чанкирует данные, поэтому размер бандла (Kyber1024, §6.2) не
     * ограничен.
     */
    val payloadJson: String,
    /**
     * Полезная нагрузка QR: компактный формат v3 с инлайн-бандлом слоя B, если он
     * влезает (~2.9 КБ JSON не помещался, строковый хвост — помещается). Иначе
     * без бандла, как раньше (NFC / keyx / bundleId).
     */
    val qrPayloadJson: String,
    /** Мнемоническая форма PSK (для ручного ввода собеседником). */
    val mnemonic: String,
    val pskFingerprintHex: String,
    /** Есть ли в полной нагрузке prekey-бандл слоя B (для подсказки про NFC). */
    val hasBundle: Boolean,
)

/** Результат импорта PSK (сканирование QR / ввод мнемоники / NFC). */
sealed interface PskImportResult {
    data class Success(val meta: PskMeta) : PskImportResult
    data class Failure(val reason: String) : PskImportResult
}

/**
 * Оркестратор ручного обмена ключом контейнера (PSK), P4 §6.2/§10.3.
 *
 * Соединяет нативные PSK-примитивы ([PskCrypto]), защищённое хранилище
 * ([PskStore], Keystore-обёртка) и слой A ([CryptoModule.createCascadeCipher]).
 *
 * ГАРАНТИЯ P4: PSK обменивается СТРОГО вне сети (QR лично / мнемоника / NFC) и
 * НИКОГДА не уходит в сеть и не копируется в буфер обмена. Здесь нет ни одного
 * пути, отправляющего PSK по сети; сервер видит только контейнер слоя A
 * (см. docs/layer_a_container_format.md, docs/psk_exchange.md).
 */
class PskManager(
    private val appContext: Context,
    private val pskStore: PskStore,
    private val crypto: PskCrypto = CryptoModule.psk,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun str(resId: Int): String = LocaleManager.wrap(appContext).getString(resId)

    private val _entries = MutableStateFlow<List<PskMeta>>(emptyList())
    val entries: StateFlow<List<PskMeta>> = _entries.asStateFlow()

    /**
     * Провайдеры слоя B (§6.2, PSK-привязка сессии): вкладывают публичный identity-ключ и
     * prekey-бандл владельца в код обмена, чтобы собеседник СРАЗУ установил
     * Signal-сессию. Возвращают `null`, если слой B недоступен. Задаются из DI.
     */
    var identityProvider: () -> String? = { null }
    var bundleProvider: () -> String? = { null }

    /**
     * Колбэк для сохранения контакта после импорта кода обмена (identity+бандл
     * собеседника). Задаётся из DI (репозиторий переписки), см. AppContainer.
     */
    var onImportedExchange: (suspend (String, PskExchangePayload) -> Unit)? = null

    /**
     * Публикация ПУБЛИЧНОГО prekey-бандла слоя B на релее при показе QR
     * (`id, bundleJson → ok`). Нужна, потому что бандл (Kyber1024, ~2.9 КБ) не
     * влезает в QR; собеседник тянет его по id ([fetchBundle]). На релей уходит
     * ТОЛЬКО публичный бандл — PSK остаётся в QR (§10.7). Задаётся из DI.
     */
    var uploadBundle: (suspend (String, String) -> Boolean)? = null

    /** Загрузка публичного prekey-бандла собеседника с релея по id. Из DI. */
    var fetchBundle: (suspend (String) -> String?)? = null

    private val secureRandom = SecureRandom()

    /**
     * Половина PSK, сгенерированная ЭТИМ устройством для текущего обмена, живёт в
     * памяти между показом своего QR ([beginExchange]) и сканированием кода
     * собеседника ([completeExchange]). Общий PSK = XOR двух половин — обе стороны
     * получают ОДИН секрет независимо от того, кто «A», кто «B» (коммутативность).
     */
    private class Pending(val half: ByteArray)

    @Volatile
    private var pending: Pending? = null

    /**
     * Контакт, для которого ЭТОТ раунд уже завершён на нашей стороне (мы
     * отсканировали QR собеседника). Половину PSK при этом НЕ затираем — QR
     * должен оставаться тем же, пока собеседник не отсканирует нас; иначе уход
     * на вкладку «Чаты» порождал НОВУЮ половину и общий ключ расходился.
     */
    @Volatile
    private var completedPeerId: String? = null

    /** Id контакта, если текущий раунд обмена у нас уже завершён. */
    fun completedPeerId(): String? = completedPeerId

    /** Готова ли нативная PSK-крипта. */
    val isReady: Boolean get() = crypto.isReady

    /** Перечитать список установленных PSK (без секретов). При закрытом аккаунте список пуст. */
    suspend fun refresh() {
        _entries.value = try {
            pskStore.list()
        } catch (_: StorageLockedException) {
            emptyList()
        }
    }

    /** Забыть прочитанный список (аккаунт закрыт). */
    fun clearCache() {
        _entries.value = emptyList()
    }

    /**
     * Начинает ВЗАИМНЫЙ обмен (§6.2): генерирует нашу ПОЛОВИНУ PSK, удерживает её
     * в памяти ([pending]) и возвращает материал для ПОКАЗА собеседнику лично
     * (QR/NFC). Общий PSK получится как XOR нашей и чужой половин в
     * [completeExchange] — поэтому здесь в хранилище пока НИЧЕГО не пишется
     * (окончательный ключ ещё неизвестен), а показанный код НЕ создаёт контакт.
     *
     * Если раунд уже начат (половина в [pending]) — ПОВТОРНО используем её: уход
     * с экрана / переключение вкладок не должно менять QR, иначе собеседник
     * сканирует старый код, а у нас уже другая половина.
     *
     * Публичный prekey-бандл слоя B кладётся в QR (компактный формат v3). Если
     * вдруг не влез — публикуется на релее под [PskExchangePayload.bundleId].
     * PSK при этом в сеть не уходит — он только в QR.
     */
    suspend fun beginExchange(self: AccountIdentity?, displayLabel: String = ""): PskGenerated {
        val reused = pending
        val half = if (reused != null) {
            DiagnosticLog.event(DIAG_TAG, "повтор того же раунда обмена (половина PSK сохранена)")
            reused.half.copyOf()
        } else {
            completedPeerId = null
            crypto.generate().also { generated ->
                pending = Pending(generated.copyOf())
            }
        }
        try {
            val halfB64 = crypto.toBase64(half)
            val fpHex = crypto.fingerprintHex(half)
            val mnemonic = crypto.toMnemonic(half)
            val fullBundle = runCatching { bundleProvider() }.getOrNull().orEmpty()
            val label = displayLabel.trim().ifBlank { self?.shortId().orEmpty() }
            DiagnosticLog.event(
                DIAG_TAG, "начало обмена: показываю свой код",
                "accountId" to DiagnosticLog.shortId(self?.accountId),
                "метка" to DiagnosticLog.presence(label),
                "identityЕсть" to runCatching { !identityProvider().isNullOrBlank() }.getOrDefault(false),
                "бандлБайт" to fullBundle.length,
                "полуPSK" to DiagnosticLog.fp(half),
            )
            val identityB64 = runCatching { identityProvider() }.getOrNull().orEmpty()
            val payloadWithBundle = PskExchangePayload(
                pskB64 = halfB64,
                fromAccountId = self?.accountId.orEmpty(),
                fromFingerprint = self?.fingerprint.orEmpty(),
                label = label,
                identityB64 = identityB64,
                bundleJson = fullBundle,
            )
            var qrPayload = PskQrPayload.encodeForQr(payloadWithBundle)
            val bundleInQr = fullBundle.isNotBlank() && qrPayload.contains(fullBundle)
            // Релей нужен только если бандл НЕ влез в QR.
            var bundleId = ""
            if (fullBundle.isNotBlank() && !bundleInQr) {
                val id = randomBundleId()
                val ok = runCatching { uploadBundle?.invoke(id, fullBundle) ?: false }
                    .getOrDefault(false)
                if (ok) {
                    bundleId = id
                    qrPayload = PskQrPayload.encodeForQr(payloadWithBundle.copy(bundleId = id))
                } else {
                    DiagnosticLog.w(DIAG_TAG) {
                        "бандл не влез в QR и НЕ опубликован на релее — слой B догонит по NFC/keyx"
                    }
                }
            } else if (fullBundle.isBlank()) {
                DiagnosticLog.w(DIAG_TAG) {
                    "нет своего prekey-бандла (слой B недоступен?) — код обмена уйдёт без него"
                }
            }
            val payload = payloadWithBundle.copy(bundleId = bundleId)
            DiagnosticLog.event(
                DIAG_TAG, "QR закодирован",
                "qrБайт" to qrPayload.length,
                "бандлВQr" to bundleInQr,
            )
            return PskGenerated(
                payload = payload,
                payloadJson = json.encodeToString(PskExchangePayload.serializer(), payload),
                qrPayloadJson = qrPayload,
                mnemonic = mnemonic,
                pskFingerprintHex = fpHex,
                hasBundle = fullBundle.isNotBlank(),
            )
        } finally {
            half.fill(0)
        }
    }

    /**
     * Завершает ВЗАИМНЫЙ обмен по отсканированному/принятому коду собеседника.
     * Общий PSK = XOR(наша половина, половина собеседника); сохраняется под
     * РЕАЛЬНЫМ accountId собеседника (иначе ломается адресация mailbox v2, §4.1) и
     * создаётся контакт/чат — симметрично на ОБОИХ устройствах (оба сканируют).
     *
     * Публичный prekey-бандл собеседника тянется с релея по
     * [PskExchangePayload.bundleId] (если не пришёл инлайн по NFC) и сверяется с
     * закреплённым в QR identity-ключом — сервер не может подменить его незаметно.
     *
     * [pending] очищается ТОЛЬКО при успехе — при ошибке можно пересканировать.
     */
    suspend fun completeExchange(rawJson: String, source: PskSource): PskImportResult {
        DiagnosticLog.event(
            DIAG_TAG, "принят код собеседника",
            "источник" to source.name,
            "байт" to rawJson.length,
            "своя-половина-есть" to (pending != null),
        )
        val mine = pending ?: run {
            DiagnosticLog.w(DIAG_TAG) { "нет своей половины PSK — сначала нужно открыть свой QR" }
            return PskImportResult.Failure(str(R.string.psk_err_show_first))
        }
        val payload = PskQrPayload.decode(rawJson, json) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "код не разобран как обмен AEGIS (ни v3, ни JSON)" }
            return PskImportResult.Failure(str(R.string.psk_err_not_aegis))
        }
        if (payload.t != PskExchangePayload.TYPE) {
            DiagnosticLog.w(DIAG_TAG) { "чужой формат кода: t=${payload.t} v=${payload.v}" }
            return PskImportResult.Failure(str(R.string.psk_err_unknown_format))
        }
        val peerAccountId = payload.fromAccountId.trim()
        DiagnosticLog.event(
            DIAG_TAG, "разбор кода собеседника",
            "версия" to payload.v,
            "accountId" to DiagnosticLog.shortId(peerAccountId),
            "метка" to DiagnosticLog.presence(payload.label),
            "identityЕсть" to payload.identityB64.isNotBlank(),
            "бандлИнлайн" to payload.bundleJson.length,
            "bundleIdЕсть" to payload.bundleId.isNotBlank(),
        )
        if (peerAccountId.isEmpty()) {
            DiagnosticLog.w(DIAG_TAG) { "в коде нет accountId — обмен невозможен (старая сборка?)" }
            return PskImportResult.Failure(str(R.string.psk_err_no_account))
        }
        val peerHalf = runCatching { crypto.fromBase64(payload.pskB64) }
            .getOrElse { return PskImportResult.Failure(str(R.string.psk_err_corrupt)) }
        if (peerHalf.size != mine.half.size) {
            peerHalf.fill(0)
            return PskImportResult.Failure(str(R.string.psk_err_corrupt))
        }
        // XOR половин — коммутативно, обе стороны получают одинаковый секрет.
        val shared = ByteArray(mine.half.size) { i -> (mine.half[i].toInt() xor peerHalf[i].toInt()).toByte() }
        peerHalf.fill(0)
        val label = payload.label.ifBlank { peerAccountId }
        val result = storeImported(
            psk = shared,
            contactId = peerAccountId,
            contactLabel = label,
            source = source,
            peerAccountId = peerAccountId,
            peerFingerprint = payload.fromFingerprint,
        )
        if (result is PskImportResult.Success) {
            DiagnosticLog.event(
                DIAG_TAG, "общий PSK установлен",
                "контакт" to DiagnosticLog.shortId(peerAccountId),
                "отпечатокPSK" to (result.meta.pskFingerprintHex.take(8)),
            )
            val resolvedBundle = resolvePeerBundle(payload)
            if (resolvedBundle.isBlank()) {
                DiagnosticLog.w(DIAG_TAG) {
                    "бандл слоя B НЕ получен при обмене — контакт создаётся без ключей слоя B, " +
                        "отправка будет падать до срабатывания keyx-канала"
                }
            }
            val forContact = payload.copy(bundleJson = resolvedBundle)
            runCatching { onImportedExchange?.invoke(peerAccountId, forContact) }
            // Половину НЕ затираем: QR на экране должен остаться из ЭТОГО раунда,
            // пока собеседник не отсканирует нас (иначе вкладка «Чаты» сбрасывала
            // раунд и общий PSK расходился).
            completedPeerId = peerAccountId
        } else if (result is PskImportResult.Failure) {
            DiagnosticLog.w(DIAG_TAG) { "PSK не сохранён: ${result.reason}" }
        }
        return result
    }

    /**
     * Сбрасывает текущий раунд (явная кнопка «Назад» / «Готово») — затирает
     * половину PSK. НЕ вызывать из onDispose экрана: переключение вкладок не
     * должно ломать идущий обмен.
     */
    fun cancelExchange() {
        pending?.half?.fill(0)
        pending = null
        completedPeerId = null
    }

    /**
     * Разрешает публичный prekey-бандл собеседника: инлайн (NFC) → как есть; иначе
     * тянет с релея по [PskExchangePayload.bundleId] и СВЕРЯЕТ identity-ключ с
     * закреплённым в коде [PskExchangePayload.identityB64] (анти-MITM подмены
     * сервером). Пусто, если бандл недоступен/не сошёлся — тогда слой B догонит
     * позже, а слой A (PSK) уже установлен.
     */
    private suspend fun resolvePeerBundle(payload: PskExchangePayload): String {
        if (payload.bundleJson.isNotBlank()) {
            DiagnosticLog.event(DIAG_TAG, "бандл получен инлайн (QR/NFC)", "байт" to payload.bundleJson.length)
            return payload.bundleJson
        }
        val id = payload.bundleId.trim()
        if (id.isEmpty()) {
            DiagnosticLog.w(DIAG_TAG) {
                "в коде нет ни инлайн-бандла, ни bundleId — у собеседника не вышло " +
                    "опубликовать бандл (нет релея/Tor в момент показа QR)"
            }
            return ""
        }
        val fetched = runCatching { fetchBundle?.invoke(id) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fetched == null) {
            DiagnosticLog.w(DIAG_TAG) {
                "бандл по bundleId=${DiagnosticLog.fp(id)} не скачался с релея " +
                    "(нет сети/Tor или запись просрочена)"
            }
            return ""
        }
        val matches = PrekeyBundle.identityMatches(fetched, payload.identityB64)
        DiagnosticLog.event(
            DIAG_TAG, "бандл скачан с релея",
            "байт" to fetched.length,
            "identityСошлась" to matches,
        )
        if (!matches) {
            DiagnosticLog.w(DIAG_TAG) {
                "бандл ОТБРОШЕН: identity не совпала с закреплённой в QR (возможна подмена сервером)"
            }
        }
        return if (matches) fetched else ""
    }

    /** Случайный опаковый id бандла (256 бит → url-safe base64, как id блоба). */
    private fun randomBundleId(): String {
        val raw = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Удаляет PSK контакта. */
    suspend fun remove(contactId: String) {
        pskStore.remove(contactId)
        refresh()
    }

    /**
     * Создаёт каскадный шифр слоя A на PSK контакта (§6.2 → container_secret).
     * `null`, если PSK для контакта не установлен. Байты PSK затираются сразу
     * после инициализации шифра.
     */
    suspend fun cipherFor(contactId: String): CascadeCipher? {
        val pskB64 = pskStore.getPskB64(contactId) ?: return null
        val psk = crypto.fromBase64(pskB64)
        return try {
            CryptoModule.createCascadeCipher(psk)
        } finally {
            psk.fill(0)
        }
    }

    /**
     * Защита от понижения: отправитель с PSK всегда накладывает слой A, а без PSK
     * конверт контакту и не доставить (адрес ящика выводится из PSK). Значит,
     * конверт без слоя A от контакта с PSK подделан тем, кто подменил
     * Signal-сессию (TOFU), и принимать его содержимое нельзя.
     */
    suspend fun requireLayerA(contactId: String, layerA: Boolean) {
        if (!layerA && pskStore.hasPsk(contactId)) {
            error("Конверт без слоя A при установленном PSK отклонён")
        }
    }

    /**
     * Сырые байты PSK контакта для согласования ключей звонка (§7.5/§10.10,
     * [com.aegis.crypto.CallCrypto]). Раскрывает СЕКРЕТ — вызывающий ОБЯЗАН
     * затереть массив сразу после использования. `null`, если PSK не установлен.
     */
    suspend fun pskBytesFor(contactId: String): ByteArray? {
        val pskB64 = pskStore.getPskB64(contactId) ?: return null
        return crypto.fromBase64(pskB64)
    }

    /**
     * v2 per-device подканал переписки (мультидевайс §7.1.1, `docs/multidevice.md`
     * §4.1). Адресует КОНКРЕТНОЕ устройство `(ownerAccountId, ownerDeviceId)`
     * владельца ящика: secret = общий PSK контакта, scope = accountId владельца.
     * Обе стороны знают PSK, поэтому вычисляют один и тот же id (роль 0/1 не нужна
     * — направленность в scope). Я слушаю `(myAccountId, myDeviceId)`, а пишу в
     * `(contactAccountId, peerDeviceId)`. `null`, если нет PSK/пустой scope.
     */
    suspend fun convMailboxFor(contactId: String, ownerAccountId: String, ownerDeviceId: Int): String? {
        if (ownerAccountId.isBlank()) return null
        val psk = pskBytesFor(contactId) ?: return null
        return try {
            crypto.mailboxIdDevice(psk, PskCrypto.MailboxKind.CONVERSATION, ownerAccountId, ownerDeviceId)
        } catch (_: Exception) {
            null
        } finally {
            psk.fill(0)
        }
    }

    private companion object {
        /** Тег записей обмена PSK в журнале диагностики. */
        const val DIAG_TAG = "psk"
    }

    private suspend fun storeImported(
        psk: ByteArray,
        contactId: String,
        contactLabel: String,
        source: PskSource,
        peerAccountId: String,
        peerFingerprint: String,
    ): PskImportResult {
        return try {
            val record = PskRecord(
                contactId = contactId,
                pskB64 = crypto.toBase64(psk),
                source = source,
                pskFingerprintHex = crypto.fingerprintHex(psk),
                peerAccountId = peerAccountId,
                peerFingerprint = peerFingerprint,
                label = contactLabel,
                createdAtEpochMs = System.currentTimeMillis(),
            )
            pskStore.put(record)
            refresh()
            PskImportResult.Success(record.toMeta())
        } catch (e: Exception) {
            PskImportResult.Failure(e.message ?: str(R.string.psk_err_save))
        } finally {
            psk.fill(0)
        }
    }
}
