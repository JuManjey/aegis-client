package com.aegis.data.storage

import com.aegis.core.common.contact.ContactRequestDirection
import com.aegis.core.common.contact.ContactRequestState
import com.aegis.core.common.contact.ContactRequestView

/**
 * Заявка на добавление в контакты (RCH v1, `docs/contact_requests.md`).
 *
 * ВНИМАНИЕ, СЕКРЕТЫ. [epkSecretB64], [kemSecretB64] и [replyTagB64] — секретный
 * материал раунда. Он ОБЯЗАН жить до получения ответа (собеседник может принять
 * заявку через сутки), поэтому лежит в SQLCipher, а не в памяти, и затирается
 * при завершении рукопожатия ([ContactRequestStore.complete]) либо по истечении
 * [expiresAtMs]. В UI-проекцию ([ContactRequestView]) эти поля не попадают.
 */
data class StoredContactRequest(
    val id: String,
    val direction: ContactRequestDirection,
    val state: ContactRequestState,

    // --- Публичная часть собеседника (из подписанной нагрузки) --------------
    val peerAccountId: String,
    val peerEd25519Hex: String = "",
    val peerX25519Hex: String = "",
    val peerNickname: String = "",
    val peerFingerprint: String = "",
    val peerDeviceId: Int = 1,
    val peerEpkPublicB64: String = "",
    val peerContribB64: String = "",
    val peerNonceB64: String = "",
    /** Тег обнаружения собеседника (из его адреса) — куда писать приглашение. */
    val peerTagB64: String = "",
    /** Эфемерный тег ответа собеседника (из его `INVITE`) — куда писать `ACCEPT`. */
    val peerReplyTagB64: String = "",
    /**
     * Публичный Signal identity-ключ устройства собеседника: у исходящей заявки
     * из проверенной записи каталога, у входящей — из подписанного `INVITE`.
     * Переносится в контакт при активации, чтобы слой B был готов сразу.
     */
    val peerSignalIdentityB64: String = "",
    /** Публичный prekey-бандл устройства собеседника (JSON, §0). */
    val peerBundleJson: String = "",
    val greeting: String = "",

    // --- Своя часть раунда (секреты + публичные значения) -------------------
    val epkSecretB64: String = "",
    val epkPublicB64: String = "",
    val contribB64: String = "",
    val nonceB64: String = "",
    val kemPublicB64: String = "",
    val kemSecretB64: String = "",
    val replyTagB64: String = "",

    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
) {
    /** Небесекретная проекция для UI — без материала раунда. */
    fun toView(): ContactRequestView = ContactRequestView(
        id = id,
        direction = direction,
        state = state,
        peerAccountId = peerAccountId,
        peerNickname = peerNickname,
        peerFingerprint = peerFingerprint,
        greeting = greeting,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}

/** Зашифрованное хранилище заявок на добавление в контакты (SQLCipher). */
interface ContactRequestStore {
    /**
     * Создаёт или обновляет заявку. Уникальность — по паре
     * (направление, accountId собеседника): повторный `INVITE` от того же
     * аккаунта ОБНОВЛЯЕТ запись, а не добавляет вторую (иначе тот, кому выдан
     * адрес, накопил бы у нас список уведомлений).
     */
    fun upsert(request: StoredContactRequest)

    fun get(id: String): StoredContactRequest?

    /** Заявка данного направления с этим собеседником, если есть. */
    fun find(direction: ContactRequestDirection, peerAccountId: String): StoredContactRequest?

    /** Все заявки, свежие сверху. */
    fun all(): List<StoredContactRequest>

    /** Заявки в состоянии ожидания (для бейджа и экрана заявок). */
    fun pending(): List<StoredContactRequest>

    /** Число входящих заявок, ожидающих решения (бейдж на вкладке). */
    fun incomingPendingCount(): Int

    fun setState(id: String, state: ContactRequestState)

    /**
     * Помечает заявку завершённой и ЗАТИРАЕТ секреты раунда: они больше не
     * нужны, а держать их «на всякий случай» — держать лишний секрет на диске.
     */
    fun complete(id: String)

    /**
     * Удаляет просроченные заявки (и их секреты). Возвращает число удалённых.
     */
    fun purgeExpired(nowMs: Long): Int

    fun delete(id: String)
    fun clear()
}

internal class SqlContactRequestStore(
    private val database: AegisDatabase,
) : ContactRequestStore {

    override fun upsert(request: StoredContactRequest) {
        database.database.execSQL(
            """
            INSERT INTO contact_requests(
                id, direction, state, peer_account_id, peer_ed25519_hex, peer_x25519_hex,
                peer_nickname, peer_fingerprint, peer_device_id, peer_epk_public_b64,
                peer_contrib_b64, peer_nonce_b64, peer_tag_b64, peer_reply_tag_b64,
                peer_signal_identity_b64, peer_bundle_json, greeting,
                epk_secret_b64, epk_public_b64, contrib_b64, nonce_b64,
                kem_public_b64, kem_secret_b64, reply_tag_b64,
                created_at_ms, updated_at_ms, expires_at_ms
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(direction, peer_account_id) DO UPDATE SET
                state = excluded.state,
                peer_ed25519_hex = excluded.peer_ed25519_hex,
                peer_x25519_hex = excluded.peer_x25519_hex,
                peer_nickname = excluded.peer_nickname,
                peer_fingerprint = excluded.peer_fingerprint,
                peer_device_id = excluded.peer_device_id,
                peer_epk_public_b64 = excluded.peer_epk_public_b64,
                peer_contrib_b64 = excluded.peer_contrib_b64,
                peer_nonce_b64 = excluded.peer_nonce_b64,
                peer_tag_b64 = excluded.peer_tag_b64,
                peer_reply_tag_b64 = excluded.peer_reply_tag_b64,
                peer_signal_identity_b64 = excluded.peer_signal_identity_b64,
                peer_bundle_json = excluded.peer_bundle_json,
                greeting = excluded.greeting,
                -- Свой материал раунда НЕ перезаписываем пустым: повторный
                -- INVITE от собеседника не должен обнулять наши секреты, иначе
                -- уже отправленную исходящую заявку станет нечем завершить.
                epk_secret_b64 = CASE WHEN excluded.epk_secret_b64 = ''
                    THEN contact_requests.epk_secret_b64 ELSE excluded.epk_secret_b64 END,
                epk_public_b64 = CASE WHEN excluded.epk_public_b64 = ''
                    THEN contact_requests.epk_public_b64 ELSE excluded.epk_public_b64 END,
                contrib_b64 = CASE WHEN excluded.contrib_b64 = ''
                    THEN contact_requests.contrib_b64 ELSE excluded.contrib_b64 END,
                nonce_b64 = CASE WHEN excluded.nonce_b64 = ''
                    THEN contact_requests.nonce_b64 ELSE excluded.nonce_b64 END,
                kem_public_b64 = CASE WHEN excluded.kem_public_b64 = ''
                    THEN contact_requests.kem_public_b64 ELSE excluded.kem_public_b64 END,
                kem_secret_b64 = CASE WHEN excluded.kem_secret_b64 = ''
                    THEN contact_requests.kem_secret_b64 ELSE excluded.kem_secret_b64 END,
                reply_tag_b64 = CASE WHEN excluded.reply_tag_b64 = ''
                    THEN contact_requests.reply_tag_b64 ELSE excluded.reply_tag_b64 END,
                updated_at_ms = excluded.updated_at_ms,
                expires_at_ms = excluded.expires_at_ms
            """.trimIndent(),
            arrayOf<Any?>(
                request.id,
                request.direction.name,
                request.state.name,
                request.peerAccountId,
                request.peerEd25519Hex,
                request.peerX25519Hex,
                request.peerNickname,
                request.peerFingerprint,
                request.peerDeviceId,
                request.peerEpkPublicB64,
                request.peerContribB64,
                request.peerNonceB64,
                request.peerTagB64,
                request.peerReplyTagB64,
                request.peerSignalIdentityB64,
                request.peerBundleJson,
                request.greeting,
                request.epkSecretB64,
                request.epkPublicB64,
                request.contribB64,
                request.nonceB64,
                request.kemPublicB64,
                request.kemSecretB64,
                request.replyTagB64,
                request.createdAtMs,
                request.updatedAtMs,
                request.expiresAtMs,
            ),
        )
        enforceRowCap()
    }

    /**
     * Потолок числа заявок. Ящик приглашений адресуется секретным тегом, поэтому
     * писать в него может только тот, кому владелец сам дал адрес — но если тег
     * утёк (например, был опубликован в алиасе), таблица не должна расти без
     * предела. Вытесняем самые старые НЕ-ожидающие, затем самые старые вообще.
     */
    private fun enforceRowCap() {
        database.database.execSQL(
            """
            DELETE FROM contact_requests WHERE id IN (
                SELECT id FROM contact_requests
                ORDER BY (state IN ('INCOMING_PENDING', 'OUTGOING_PENDING')) DESC,
                         updated_at_ms DESC
                LIMIT -1 OFFSET ?
            )
            """.trimIndent(),
            arrayOf<Any?>(MAX_ROWS),
        )
    }

    override fun get(id: String): StoredContactRequest? =
        database.database.rawQuery("$SELECT_ALL WHERE id = ? LIMIT 1", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.read() else null
        }

    override fun find(
        direction: ContactRequestDirection,
        peerAccountId: String,
    ): StoredContactRequest? =
        database.database.rawQuery(
            "$SELECT_ALL WHERE direction = ? AND peer_account_id = ? LIMIT 1",
            arrayOf(direction.name, peerAccountId),
        ).use { c -> if (c.moveToFirst()) c.read() else null }

    override fun all(): List<StoredContactRequest> =
        database.database.rawQuery("$SELECT_ALL ORDER BY updated_at_ms DESC", null).use { c ->
            val out = ArrayList<StoredContactRequest>(c.count)
            while (c.moveToNext()) out += c.read()
            out
        }

    override fun pending(): List<StoredContactRequest> =
        database.database.rawQuery(
            "$SELECT_ALL WHERE state IN ('INCOMING_PENDING', 'OUTGOING_PENDING') " +
                "ORDER BY updated_at_ms DESC",
            null,
        ).use { c ->
            val out = ArrayList<StoredContactRequest>(c.count)
            while (c.moveToNext()) out += c.read()
            out
        }

    override fun incomingPendingCount(): Int =
        database.database.rawQuery(
            "SELECT COUNT(*) FROM contact_requests WHERE state = 'INCOMING_PENDING'",
            null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun setState(id: String, state: ContactRequestState) {
        database.database.execSQL(
            "UPDATE contact_requests SET state = ?, updated_at_ms = ? WHERE id = ?",
            arrayOf<Any?>(state.name, System.currentTimeMillis(), id),
        )
    }

    override fun complete(id: String) {
        database.database.execSQL(
            """
            UPDATE contact_requests SET
                state = ?, updated_at_ms = ?,
                epk_secret_b64 = '', kem_secret_b64 = '', reply_tag_b64 = ''
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any?>(ContactRequestState.COMPLETED.name, System.currentTimeMillis(), id),
        )
    }

    override fun purgeExpired(nowMs: Long): Int {
        val cursor = database.database.rawQuery(
            "SELECT COUNT(*) FROM contact_requests WHERE expires_at_ms > 0 AND expires_at_ms < ?",
            arrayOf(nowMs.toString()),
        )
        val count = cursor.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count > 0) {
            database.database.execSQL(
                "DELETE FROM contact_requests WHERE expires_at_ms > 0 AND expires_at_ms < ?",
                arrayOf<Any?>(nowMs),
            )
        }
        return count
    }

    override fun delete(id: String) {
        database.database.execSQL("DELETE FROM contact_requests WHERE id = ?", arrayOf(id))
    }

    override fun clear() {
        database.database.execSQL("DELETE FROM contact_requests")
    }

    private fun android.database.Cursor.read(): StoredContactRequest = StoredContactRequest(
        id = getString(0),
        direction = if (getString(1) == ContactRequestDirection.INCOMING.name) {
            ContactRequestDirection.INCOMING
        } else {
            ContactRequestDirection.OUTGOING
        },
        state = ContactRequestState.fromStorage(getString(2)),
        peerAccountId = getString(3),
        peerEd25519Hex = getString(4),
        peerX25519Hex = getString(5),
        peerNickname = getString(6),
        peerFingerprint = getString(7),
        peerDeviceId = getInt(8),
        peerEpkPublicB64 = getString(9),
        peerContribB64 = getString(10),
        peerNonceB64 = getString(11),
        peerTagB64 = getString(12),
        peerReplyTagB64 = getString(13),
        peerSignalIdentityB64 = getString(14),
        peerBundleJson = getString(15),
        greeting = getString(16),
        epkSecretB64 = getString(17),
        epkPublicB64 = getString(18),
        contribB64 = getString(19),
        nonceB64 = getString(20),
        kemPublicB64 = getString(21),
        kemSecretB64 = getString(22),
        replyTagB64 = getString(23),
        createdAtMs = getLong(24),
        updatedAtMs = getLong(25),
        expiresAtMs = getLong(26),
    )

    private companion object {
        /** Потолок числа заявок в таблице (защита от накопления). */
        const val MAX_ROWS = 200

        const val SELECT_ALL =
            "SELECT id, direction, state, peer_account_id, peer_ed25519_hex, peer_x25519_hex, " +
                "peer_nickname, peer_fingerprint, peer_device_id, peer_epk_public_b64, " +
                "peer_contrib_b64, peer_nonce_b64, peer_tag_b64, peer_reply_tag_b64, " +
                "peer_signal_identity_b64, peer_bundle_json, greeting, " +
                "epk_secret_b64, epk_public_b64, contrib_b64, nonce_b64, " +
                "kem_public_b64, kem_secret_b64, reply_tag_b64, " +
                "created_at_ms, updated_at_ms, expires_at_ms FROM contact_requests"
    }
}
