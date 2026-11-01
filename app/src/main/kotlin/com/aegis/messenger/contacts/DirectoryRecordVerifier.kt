package com.aegis.messenger.contacts

import com.aegis.core.common.contact.AliasClaim
import com.aegis.core.common.contact.DirectoryRecord
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.AccountCrypto
import com.aegis.crypto.DiscoveryCrypto
import com.aegis.messenger.psk.PrekeyBundle
import kotlinx.serialization.json.Json
import android.util.Base64

/**
 * Проверка записи каталога обнаружения и заявки на публичный алиас
 * (`docs/contact_requests.md`, PROTOCOL.md §4.7/§4.8).
 *
 * ЗДЕСЬ ЖИВЁТ ВСЯ МОДЕЛЬ ДОВЕРИЯ УДАЛЁННОГО ДОБАВЛЕНИЯ, поэтому логика
 * **fail-closed**: любое сомнение → отказ, а не «предупредим и продолжим».
 * Единственное, чему клиент доверяет, — `accountId` из адреса, полученного вне
 * сети. Всё остальное проверяется криптографически, и ответ релея в этой цепочке
 * не участвует как источник истины.
 */
object DirectoryRecordVerifier {

    private const val DIAG_TAG = "directory"

    /**
     * Допуск на расхождение часов: запись, выпущенная «в будущем» в пределах
     * суток, — это разошедшиеся часы, а не атака.
     */
    private const val CLOCK_SKEW_MS = 24L * 3600 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    /** Причина отказа — для журнала и текста в UI. */
    sealed interface Failure {
        /** AEAD не открылся: тег неверен либо запись переставлена/повреждена. */
        data object SealBroken : Failure

        /** JSON не разобран или версия формата не поддерживается. */
        data object Malformed : Failure

        /** Подпись записи не сходится с заявленным ключом. */
        data object BadSignature : Failure

        /** `accountId` не коммитится к `ed25519` — подделка привязки. */
        data object AccountMismatch : Failure

        /** Запись принадлежит ДРУГОМУ аккаунту, чем указан в адресе. */
        data class WrongAccount(val expected: String, val actual: String) : Failure

        /** Запись выпущена не для этого тега (попытка переиспользовать чужую). */
        data object TagMismatch : Failure

        /** Срок записи истёк либо она старше уже известной (откат). */
        data object Stale : Failure

        /** В записи нет ни одного пригодного устройства со ключами слоя B. */
        data object NoUsableDevice : Failure
    }

    /** Итог проверки. */
    sealed interface Result {
        data class Ok(val record: DirectoryRecord) : Result
        data class Rejected(val reason: Failure) : Result
    }

    /**
     * Проверяет запечатанную запись каталога целиком.
     *
     * @param sealed байты из `GET /v1/directory/{index}`.
     * @param index индекс, по которому запись получена (входит в AAD).
     * @param recordKeyB64 ключ записи, выведенный из тега из адреса.
     * @param tagCommitB64 ожидаемый коммитмент к тегу.
     * @param expectedAccountId `accountId` из адреса — единственное, чему доверяем.
     * @param knownIssuedAtMs `issuedAtMs` ранее принятой записи (0, если первая):
     *   защита от подсовывания старой записи с отозванными ключами.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun verify(
        sealed: ByteArray,
        index: String,
        recordKeyB64: String,
        tagCommitB64: String,
        expectedAccountId: String,
        knownIssuedAtMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        val plaintext = DiscoveryCrypto.openRecordOrNull(recordKeyB64, index, sealed)
            ?: return reject(Failure.SealBroken)

        val record = runCatching { json.decodeFromString(DirectoryRecord.serializer(), plaintext) }
            .getOrNull()
            ?: return reject(Failure.Malformed)
        if (record.v != DirectoryRecord.VERSION) return reject(Failure.Malformed)
        if (record.ed25519Hex.isBlank() || record.x25519Hex.isBlank()) {
            return reject(Failure.Malformed)
        }

        // 1. Подпись под заявленным ключом. Каноничные байты считает сама модель
        //    фиксированной сериализацией — именно расхождение конфигураций
        //    (`encodeDefaults`) между публикатором и проверяющим давало ложный
        //    `BadSignature` на подлинных записях.
        val signature = runCatching { Base64.decode(record.sigB64, Base64.NO_WRAP) }.getOrNull()
            ?: return reject(Failure.Malformed)
        if (!AccountCrypto.verify(record.ed25519Hex, record.canonicalBytes(), signature)) {
            return reject(Failure.BadSignature)
        }

        // 2. Привязка accountId к ключу подписи — суть всей модели: подменить
        //    ключи можно было бы только найдя второй прообраз SHA-256.
        val derived = AccountCrypto.accountIdFromEd25519(record.ed25519Hex)
            ?: return reject(Failure.Malformed)
        if (!derived.equals(record.accountId, ignoreCase = true)) {
            return reject(Failure.AccountMismatch)
        }

        // 3. Это тот аккаунт, чей адрес нам дали вне сети.
        if (!record.accountId.equals(expectedAccountId, ignoreCase = true)) {
            return reject(Failure.WrongAccount(expectedAccountId, record.accountId))
        }

        // 4. Запись выпущена именно для ЭТОГО тега.
        if (tagCommitB64.isNotBlank() && record.tagCommitB64 != tagCommitB64) {
            return reject(Failure.TagMismatch)
        }

        // 5. Срок и защита от откатов.
        if (record.expiresAtMs in 1..<nowMs) return reject(Failure.Stale)
        if (record.issuedAtMs > nowMs + CLOCK_SKEW_MS) return reject(Failure.Stale)
        if (knownIssuedAtMs > 0 && record.issuedAtMs < knownIssuedAtMs) return reject(Failure.Stale)

        // 6. Есть хотя бы одно устройство с ключами слоя B — иначе установить
        //    сессию и отправить приглашение всё равно не получится. Заодно
        //    отбрасываем внутренне противоречивые устройства: `identity_key`
        //    внутри бандла обязан совпадать с объявленным рядом. Подпись записи
        //    такое расхождение не поймала бы (подписано-то целиком), а сессия
        //    завелась бы на ключе, отличном от показанного пользователю.
        val usable = record.devices.filter { device ->
            device.deviceId in 1..255 &&
                device.signalIdentityB64.isNotBlank() &&
                device.bundleJson.isNotBlank() &&
                PrekeyBundle.identityMatches(device.bundleJson, device.signalIdentityB64)
        }
        if (usable.isEmpty()) return reject(Failure.NoUsableDevice)

        DiagnosticLog.event(
            DIAG_TAG, "запись каталога принята",
            "accountId" to DiagnosticLog.shortId(record.accountId),
            "устройств" to usable.size,
            "ник" to DiagnosticLog.presence(record.nickname),
        )
        return Result.Ok(record.copy(devices = usable.take(DirectoryRecord.MAX_DEVICES)))
    }

    /**
     * Проверяет заявку на ПУБЛИЧНЫЙ алиас. Релей её тоже проверяет, но лишь
     * чтобы алиас нельзя было угнать перезаписью — доверять его ответу не нужно,
     * поэтому здесь всё считается заново.
     *
     * @param expectedAliasIndex индекс, по которому заявка получена.
     * @return разобранная заявка либо `null` (fail-closed).
     */
    fun verifyAliasClaim(
        claimB64: String,
        sigB64: String,
        expectedAliasIndex: String,
        nowMs: Long = System.currentTimeMillis(),
    ): AliasClaim? {
        val claimBytes = runCatching { Base64.decode(claimB64, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val signature = runCatching { Base64.decode(sigB64, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val claim = runCatching {
            json.decodeFromString(AliasClaim.serializer(), String(claimBytes, Charsets.UTF_8))
        }.getOrNull() ?: return null

        if (claim.v != AliasClaim.VERSION) return null
        if (claim.aliasIndex != expectedAliasIndex) return null
        // Подпись проверяется над ТЕМИ ЖЕ байтами, что пришли: перекодировать
        // JSON нельзя — иначе байты разойдутся с подписанными.
        if (!AccountCrypto.verify(claim.ed25519Hex, claimBytes, signature)) return null
        val derived = AccountCrypto.accountIdFromEd25519(claim.ed25519Hex) ?: return null
        if (!derived.equals(claim.accountId, ignoreCase = true)) return null
        if (claim.expiresAtMs <= nowMs) return null
        if (claim.issuedAtMs > nowMs + CLOCK_SKEW_MS) return null
        if (claim.tagB64.isBlank() || claim.discoveryIndex.isBlank()) return null

        DiagnosticLog.event(
            DIAG_TAG, "заявка на алиас принята",
            "accountId" to DiagnosticLog.shortId(claim.accountId),
            "ник" to DiagnosticLog.presence(claim.nickname),
        )
        return claim
    }

    private fun reject(reason: Failure): Result.Rejected {
        DiagnosticLog.w(DIAG_TAG) { "запись каталога ОТБРОШЕНА: $reason" }
        return Result.Rejected(reason)
    }
}
