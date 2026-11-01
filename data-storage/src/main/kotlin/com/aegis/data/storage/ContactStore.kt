package com.aegis.data.storage

import com.aegis.core.common.contact.ContactTrust
import com.aegis.core.common.model.Contact

/**
 * Контакт вместе с параметрами его E2EE-сессии (§6.1/§14.1).
 *
 * [remoteIdentityB64] и [bundleJson] нужны для установления Signal-сессии
 * (X3DH/PQXDH) и вычисления PSK-биндинга (§6.2). [sessionReady] отмечает, что
 * исходящая сессия уже установлена по бандлу. Эти поля описывают ПЕРВИЧНОЕ
 * устройство контакта (`deviceId = remoteDevice`, обычно 1) — обратная
 * совместимость до мультидевайса.
 *
 * [devices] (мультидевайс §7.1.1, `docs/multidevice.md` §7) — ПОЛНЫЙ набор
 * устройств контакта для fan-out (Этап 4): первичное (синтезированное из полей
 * выше) плюс дополнительные из таблицы `peer_devices`. Никогда не пусто —
 * содержит хотя бы первичное устройство, поэтому одноустройственная логика
 * (fan-out по [devices]) совпадает с прежним поведением.
 */
data class StoredContact(
    val contact: Contact,
    val remoteIdentityB64: String? = null,
    val remoteDevice: Int = 1,
    val bundleJson: String? = null,
    val sessionReady: Boolean = false,
    val devices: List<PeerDevice> = emptyList(),
) {
    /** Первичное устройство контакта, синтезированное из legacy-колонок. */
    fun primaryDevice(): PeerDevice = PeerDevice(
        contactId = contact.id,
        deviceId = remoteDevice,
        identityB64 = remoteIdentityB64,
        bundleJson = bundleJson,
        sessionReady = sessionReady,
        verified = contact.verified,
        revoked = false,
    )

    /** Устройства для fan-out: неотозванные (первичное + доп. из `peer_devices`). */
    fun activeDevices(): List<PeerDevice> =
        devices.ifEmpty { listOf(primaryDevice()) }.filterNot { it.revoked }

    /**
     * Закреплённый Signal identity устройства [deviceId]: у первичного — из
     * личного обмена или подписанной заявки, у остальных — из их объявления под
     * слоем A. `null`, если устройство неизвестно или ключ ещё не получен.
     */
    fun identityFor(deviceId: Int): String? =
        devices.ifEmpty { listOf(primaryDevice()) }.firstOrNull { it.deviceId == deviceId }?.identityB64
}

/** Зашифрованное хранилище контактов (SQLCipher). */
interface ContactStore {
    fun upsert(contact: StoredContact)
    fun get(id: String): StoredContact?
    fun all(): List<StoredContact>
    fun setSessionReady(id: String, ready: Boolean)

    /**
     * Догоняющее сохранение ПУБЛИЧНЫХ ключей слоя B первичного устройства
     * контакта (§6.2): бандл не всегда доступен в момент личного обмена (в QR он
     * не влезает, а релей может быть недоступен), поэтому приходит позже — по
     * keyx-каналу поверх слоя A или с релея по `bundleId`.
     *
     * Точечный UPDATE вместо [upsert]: остальные поля контакта не трогаются,
     * поэтому параллельная запись из UI (никнейм, mute, блокировка) не затирается
     * и наоборот. `null` в аргументе означает «не менять это поле».
     */
    fun setPeerKeys(id: String, identityB64: String?, bundleJson: String?)

    /**
     * Ручная верификация отпечатка контакта (§14.3): отмечает, что safety number
     * контакта сверен вне сети (лично / по доверенному каналу). Не трогает
     * остальные поля контакта, включая [Contact.trust] — это независимый факт
     * (способ добавления), и снятие галочки не должно его терять.
     */
    fun setVerified(id: String, verified: Boolean)

    /**
     * Способ добавления контакта (`docs/contact_requests.md`). Точечный UPDATE.
     * [ContactTrust.UNKNOWN] игнорируется: «не знаю способ» не должно затирать
     * уже записанный (`ensureContact` вызывается и из синхронизации).
     */
    fun setTrust(id: String, trust: ContactTrust)

    /**
     * Чёрный список (§14.2): помечает контакт заблокированным/разблокированным.
     * Не трогает остальные поля. Блокировка локальна и незаметна собеседнику.
     */
    fun setBlocked(id: String, blocked: Boolean)

    /**
     * Персональное отключение уведомлений (§14.5): `0` — снять mute,
     * [com.aegis.core.common.model.Contact.MUTED_FOREVER] — навсегда, иначе
     * Unix-время (мс), до которого уведомления от контакта заглушены. Не трогает
     * прочие поля.
     */
    fun setMuted(id: String, untilMs: Long)

    /**
     * Персональные звуки уведомлений контакта (§14.5): URI звука сообщения и
     * мелодии звонка (пустая строка — использовать значение по умолчанию). Не
     * трогает прочие поля.
     */
    fun setNotificationSounds(id: String, messageSoundUri: String, callRingtoneUri: String)

    /**
     * Локальное имя чата на этом устройстве. Пустая строка сбрасывает отображение
     * к публичному никнейму контакта. Точечный UPDATE: остальные поля не трогает,
     * поэтому параллельная запись (mute, ключи слоя B) не затирается.
     */
    fun setLocalName(id: String, localName: String)

    fun delete(id: String)
    fun clear()
}

internal class SqlContactStore(
    private val database: AegisDatabase,
) : ContactStore {

    override fun upsert(contact: StoredContact) {
        val c = contact.contact
        database.database.execSQL(
            """
            INSERT INTO contacts(
                id, nickname, local_name, about, fingerprint, verified,
                remote_identity_b64, remote_device, bundle_json, session_ready, created_at, blocked,
                muted_until_ms, notification_sound_uri, call_ringtone_uri, trust
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                nickname = excluded.nickname,
                -- local_name намеренно НЕ обновляем: его пишет setLocalName / INSERT.
                -- Иначе ensureContact (синхронизация PSK) затирал бы алиас в гонке с UI.
                about = excluded.about,
                fingerprint = excluded.fingerprint,
                verified = excluded.verified,
                remote_identity_b64 = excluded.remote_identity_b64,
                remote_device = excluded.remote_device,
                bundle_json = excluded.bundle_json,
                session_ready = excluded.session_ready,
                blocked = excluded.blocked,
                muted_until_ms = excluded.muted_until_ms,
                notification_sound_uri = excluded.notification_sound_uri,
                call_ringtone_uri = excluded.call_ringtone_uri,
                -- «Способ неизвестен» НЕ затирает уже записанный: ensureContact
                -- вызывается и из синхронизации, где способ недоступен.
                trust = CASE
                    WHEN excluded.trust = 'UNKNOWN' THEN contacts.trust
                    ELSE excluded.trust
                END
            """.trimIndent(),
            arrayOf<Any?>(
                c.id,
                c.nickname,
                c.localName,
                c.about,
                c.fingerprint,
                if (c.verified) 1 else 0,
                contact.remoteIdentityB64,
                contact.remoteDevice,
                contact.bundleJson,
                if (contact.sessionReady) 1 else 0,
                System.currentTimeMillis(),
                if (c.blocked) 1 else 0,
                c.mutedUntilMs,
                c.notificationSoundUri,
                c.callRingtoneUri,
                c.trust.name,
            ),
        )
    }

    override fun get(id: String): StoredContact? {
        val base = database.database.rawQuery("$SELECT_ALL WHERE id = ? LIMIT 1", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.readContact() else null
        } ?: return null
        return base.withDevices(peerDevices(id))
    }

    override fun all(): List<StoredContact> {
        val extras = allPeerDevices()
        database.database.rawQuery("$SELECT_ALL ORDER BY nickname COLLATE NOCASE", null).use { cursor ->
            val result = ArrayList<StoredContact>(cursor.count)
            while (cursor.moveToNext()) {
                val base = cursor.readContact()
                result += base.withDevices(extras[base.contact.id].orEmpty())
            }
            return result
        }
    }

    /**
     * Объединяет первичное устройство (legacy-колонки) с дополнительными из
     * `peer_devices` (дедуп по `deviceId` — первичное каноническое). Результат
     * никогда не пуст: содержит хотя бы первичное устройство.
     */
    private fun StoredContact.withDevices(extras: List<PeerDevice>): StoredContact {
        val primary = primaryDevice()
        val merged = ArrayList<PeerDevice>(extras.size + 1)
        merged += primary
        for (d in extras) if (d.deviceId != primary.deviceId) merged += d
        return copy(devices = merged)
    }

    private fun peerDevices(contactId: String): List<PeerDevice> =
        database.peerDeviceStore.listFor(contactId)

    private fun allPeerDevices(): Map<String, List<PeerDevice>> {
        database.database.rawQuery(
            "SELECT contact_id, device_id, identity_b64, bundle_json, " +
                "session_ready, verified, revoked, added_at_ms FROM peer_devices ORDER BY contact_id, device_id",
            null,
        ).use { cursor ->
            val map = HashMap<String, MutableList<PeerDevice>>()
            while (cursor.moveToNext()) {
                val cid = cursor.getString(0)
                map.getOrPut(cid) { ArrayList() } += PeerDevice(
                    contactId = cid,
                    deviceId = cursor.getInt(1),
                    identityB64 = if (cursor.isNull(2)) null else cursor.getString(2),
                    bundleJson = if (cursor.isNull(3)) null else cursor.getString(3),
                    sessionReady = cursor.getInt(4) != 0,
                    verified = cursor.getInt(5) != 0,
                    revoked = cursor.getInt(6) != 0,
                    addedAtMs = cursor.getLong(7),
                )
            }
            return map
        }
    }

    override fun setSessionReady(id: String, ready: Boolean) {
        database.database.execSQL(
            "UPDATE contacts SET session_ready = ? WHERE id = ?",
            arrayOf<Any?>(if (ready) 1 else 0, id),
        )
    }

    override fun setPeerKeys(id: String, identityB64: String?, bundleJson: String?) {
        database.database.execSQL(
            """
            UPDATE contacts SET
                remote_identity_b64 = COALESCE(?, remote_identity_b64),
                bundle_json = COALESCE(?, bundle_json)
            WHERE id = ?
            """.trimIndent(),
            arrayOf<Any?>(identityB64, bundleJson, id),
        )
    }

    override fun setVerified(id: String, verified: Boolean) {
        database.database.execSQL(
            "UPDATE contacts SET verified = ? WHERE id = ?",
            arrayOf<Any?>(if (verified) 1 else 0, id),
        )
    }

    override fun setTrust(id: String, trust: ContactTrust) {
        if (trust == ContactTrust.UNKNOWN) return
        database.database.execSQL(
            "UPDATE contacts SET trust = ? WHERE id = ?",
            arrayOf<Any?>(trust.name, id),
        )
    }

    override fun setBlocked(id: String, blocked: Boolean) {
        database.database.execSQL(
            "UPDATE contacts SET blocked = ? WHERE id = ?",
            arrayOf<Any?>(if (blocked) 1 else 0, id),
        )
    }

    override fun setMuted(id: String, untilMs: Long) {
        database.database.execSQL(
            "UPDATE contacts SET muted_until_ms = ? WHERE id = ?",
            arrayOf<Any?>(untilMs, id),
        )
    }

    override fun setNotificationSounds(id: String, messageSoundUri: String, callRingtoneUri: String) {
        database.database.execSQL(
            "UPDATE contacts SET notification_sound_uri = ?, call_ringtone_uri = ? WHERE id = ?",
            arrayOf<Any?>(messageSoundUri, callRingtoneUri, id),
        )
    }

    override fun setLocalName(id: String, localName: String) {
        database.database.execSQL(
            "UPDATE contacts SET local_name = ? WHERE id = ?",
            arrayOf<Any?>(localName, id),
        )
    }

    override fun delete(id: String) {
        database.database.execSQL("DELETE FROM contacts WHERE id = ?", arrayOf(id))
        database.database.execSQL("DELETE FROM peer_devices WHERE contact_id = ?", arrayOf(id))
    }

    override fun clear() {
        database.database.execSQL("DELETE FROM contacts")
        database.database.execSQL("DELETE FROM peer_devices")
    }

    private fun android.database.Cursor.readContact(): StoredContact = StoredContact(
        contact = Contact(
            id = getString(0),
            nickname = getString(1),
            localName = getString(2).orEmpty(),
            about = getString(3).orEmpty(),
            fingerprint = getString(4),
            verified = getInt(5) != 0,
            blocked = getInt(10) != 0,
            mutedUntilMs = getLong(11),
            notificationSoundUri = getString(12),
            callRingtoneUri = getString(13),
            trust = ContactTrust.fromStorage(if (isNull(14)) null else getString(14)),
        ),
        remoteIdentityB64 = if (isNull(6)) null else getString(6),
        remoteDevice = getInt(7),
        bundleJson = if (isNull(8)) null else getString(8),
        sessionReady = getInt(9) != 0,
    )

    private companion object {
        const val SELECT_ALL =
            "SELECT id, nickname, local_name, about, fingerprint, verified, " +
                "remote_identity_b64, remote_device, bundle_json, session_ready, blocked, " +
                "muted_until_ms, notification_sound_uri, call_ringtone_uri, trust FROM contacts"
    }
}
