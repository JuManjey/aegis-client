package com.aegis.messenger.contacts

import com.aegis.core.common.contact.ContactAddress
import com.aegis.core.common.contact.DirectoryRecord
import com.aegis.core.common.diag.DiagnosticLog
import com.aegis.crypto.DiscoveryCrypto
import com.aegis.messenger.messaging.RelayGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Base64

/**
 * Поиск владельца адреса или публичного `@ника` в каталоге обнаружения
 * (`docs/contact_requests.md`, PROTOCOL.md §4.7/§4.8).
 *
 * Ответ релея здесь НЕ является источником истины: любая найденная запись
 * проходит полную проверку в [DirectoryRecordVerifier] (подпись + привязка
 * `accountId ↔ ed25519` + коммитмент к тегу + сроки), и только прошедшая
 * попадает наружу. Не прошедшая возвращается как [Result.Untrusted] — это
 * сигнал возможной подмены, а не «попробуем ещё раз».
 */
class DirectoryLookup(
    private val relayGateway: RelayGateway,
    /** Ранее принятый `issuedAtMs` записи этого аккаунта (защита от откатов). */
    private val knownIssuedAtProvider: suspend (String) -> Long = { 0L },
    /**
     * Запоминает `issuedAtMs` ПРИНЯТОЙ записи. Без этого вызова защита от
     * откатов была бы мертва: [knownIssuedAtProvider] всегда возвращал бы 0, и
     * сервер мог бы безнаказанно подсунуть старую запись с отозванными ключами.
     */
    private val onRecordAccepted: suspend (String, Long) -> Unit = { _, _ -> },
) {
    /** Итог поиска. */
    sealed interface Result {
        /** Запись найдена и ПОЛНОСТЬЮ проверена. */
        data class Found(
            val record: DirectoryRecord,
            /** Тег, которым запись открыта: нужен для адреса ящика приглашений. */
            val tagB64: String,
        ) : Result

        /** Записи нет: адрес устарел, тег ротирован либо владелец не публиковался. */
        data object NotFound : Result

        /** Каталог выключен на релее либо нет Tor. */
        data object Unavailable : Result

        /** Адрес/ник не разобран (опечатка, чужой формат). */
        data object BadInput : Result

        /** Запись найдена, но проверку НЕ прошла — возможна подмена. */
        data class Untrusted(val reason: DirectoryRecordVerifier.Failure) : Result
    }

    /** Ищет владельца по разобранному адресу контакта. */
    suspend fun byAddress(parsed: ContactAddress.Parsed): Result = withContext(Dispatchers.IO) {
        val tagStandardB64 = runCatching {
            val raw = Base64.decode(
                parsed.tagB64Url,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            Base64.encodeToString(raw, Base64.NO_WRAP)
        }.getOrNull() ?: return@withContext Result.BadInput

        fetchAndVerify(tagStandardB64, parsed.accountId)
    }

    /**
     * Ищет владельца по публичному `@нику`.
     *
     * Два шага: заявка на алиас (открытая, подписанная) → приватная запись
     * каталога по указателю из неё. Доверенным идентификатором остаётся
     * `accountId` из заявки: он коммитится к ключу подписи, и та же привязка
     * перепроверяется у самой записи.
     */
    suspend fun byAlias(alias: String): Result = withContext(Dispatchers.IO) {
        val index = DiscoveryCrypto.aliasIndexOrNull(alias) ?: return@withContext Result.BadInput
        val signed = relayGateway.getAliasClaim(index.index)
            ?: return@withContext Result.NotFound
        val claim = DirectoryRecordVerifier.verifyAliasClaim(
            claimB64 = signed.claimB64,
            sigB64 = signed.sigB64,
            expectedAliasIndex = index.index,
        ) ?: run {
            DiagnosticLog.w(DIAG_TAG) { "заявка на алиас не прошла проверку — поиск отклонён" }
            return@withContext Result.Untrusted(DirectoryRecordVerifier.Failure.BadSignature)
        }
        DiagnosticLog.event(
            DIAG_TAG, "алиас найден",
            "ник" to index.normalized,
            "accountId" to DiagnosticLog.shortId(claim.accountId),
        )
        fetchAndVerify(claim.tagB64, claim.accountId)
    }

    /**
     * Тянет запись по индексу из тега и проверяет её. [expectedAccountId] —
     * единственное, чему доверяем на входе (пришло вне сети либо из подписанной
     * заявки на алиас).
     */
    private suspend fun fetchAndVerify(tagB64: String, expectedAccountId: String): Result {
        val ids = runCatching { DiscoveryCrypto.deriveIds(tagB64) }.getOrNull()
            ?: return Result.BadInput
        val params = relayGateway.relayParams()
        if (params?.directoryEnabled != true) {
            DiagnosticLog.w(DIAG_TAG) {
                "каталог недоступен: выключен на релее либо нет Tor — " +
                    "удалённое добавление невозможно, остаётся обмен по QR/NFC"
            }
            return Result.Unavailable
        }
        val sealed = relayGateway.getDirectoryRecord(ids.index) ?: return Result.NotFound

        return when (
            val verdict = DirectoryRecordVerifier.verify(
                sealed = sealed,
                index = ids.index,
                recordKeyB64 = ids.recordKeyB64,
                tagCommitB64 = ids.tagCommitB64,
                expectedAccountId = expectedAccountId,
                knownIssuedAtMs = knownIssuedAtProvider(expectedAccountId),
            )
        ) {
            is DirectoryRecordVerifier.Result.Ok -> {
                // Фиксируем метку выпуска ПРИНЯТОЙ записи: следующая обязана быть
                // не старее, иначе это откат к отозванным ключам.
                runCatching {
                    onRecordAccepted(verdict.record.accountId, verdict.record.issuedAtMs)
                }
                Result.Found(verdict.record, tagB64)
            }
            is DirectoryRecordVerifier.Result.Rejected -> Result.Untrusted(verdict.reason)
        }
    }

    private companion object {
        const val DIAG_TAG = "directory"
    }
}
