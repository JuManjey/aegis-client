package com.aegis.data.storage

/**
 * Устройство контакта в реестре мультидевайса (§7.1.1, `docs/multidevice.md` §7).
 *
 * Каждый контакт (аккаунт `contactId`) может работать с нескольких устройств
 * параллельно; у каждого устройства СВОЙ Signal identity и СВОЙ ratchet
 * (инвариант MD-1). Клиент держит per-contact список устройств, по которому идёт
 * sender-side fan-out (Этап 4): отдельный шифртекст на каждое `revoked=false`
 * устройство в его per-device mailbox v2.
 *
 * Первичное устройство (`deviceId=1`) исторически хранится в самих колонках
 * `contacts` (обратная совместимость); [PeerDevice] описывает КАЖДОЕ устройство
 * единообразно (включая синтезированное первичное — см. `StoredContact.devices`).
 */
data class PeerDevice(
    /** accountId контакта-владельца устройства (`aegis:<hex>`), FK на `contacts.id`. */
    val contactId: String,
    /** device id устройства контакта (1..255). */
    val deviceId: Int,
    /** Signal identity-ключ устройства (base64); null, пока не получен. */
    val identityB64: String? = null,
    /** prekey-бандл устройства для X3DH/PQXDH; null, пока не получен. */
    val bundleJson: String? = null,
    /** Установлена ли исходящая Signal-сессия именно с этим устройством. */
    val sessionReady: Boolean = false,
    /** Сверен ли отпечаток этого устройства вне сети (§14.3, UX Этапа 5). */
    val verified: Boolean = false,
    /** Отозвано ли устройство (device-revoke, §10): fan-out на него прекращается. */
    val revoked: Boolean = false,
    /** Когда устройство добавлено в реестр (Unix-мс). */
    val addedAtMs: Long = 0L,
)

/**
 * Зашифрованное хранилище устройств контактов (SQLCipher, таблица `peer_devices`).
 *
 * Хранит ДОПОЛНИТЕЛЬНЫЕ устройства контактов (обычно `deviceId ∈ 2..255`),
 * обнаруженные через device-announce по control-каналу или «ленивый» самоанонс
 * (§7). Первичное устройство (`deviceId=1`) остаётся в колонках `contacts` ради
 * обратной совместимости и объединяется в `StoredContact.devices` при чтении.
 */
interface PeerDeviceStore {
    /** Добавить/обновить устройство контакта (ключ — `contactId`+`deviceId`). */
    fun upsert(device: PeerDevice)

    /** Все устройства контакта (в порядке `deviceId`). */
    fun listFor(contactId: String): List<PeerDevice>

    /** Отметить, что исходящая сессия с устройством установлена. */
    fun setSessionReady(contactId: String, deviceId: Int, ready: Boolean)

    /** Пометить устройство отозванным/восстановленным (device-revoke, §10). */
    fun setRevoked(contactId: String, deviceId: Int, revoked: Boolean)

    /** Ручная верификация отпечатка устройства (§14.3, Этап 5). */
    fun setVerified(contactId: String, deviceId: Int, verified: Boolean)

    /** Удалить конкретное устройство контакта. */
    fun delete(contactId: String, deviceId: Int)

    /** Удалить все устройства контакта (при удалении контакта). */
    fun deleteAllFor(contactId: String)
}

internal class SqlPeerDeviceStore(
    private val database: AegisDatabase,
) : PeerDeviceStore {

    override fun upsert(device: PeerDevice) {
        database.database.execSQL(
            """
            INSERT INTO peer_devices(
                contact_id, device_id, identity_b64, bundle_json,
                session_ready, verified, revoked, added_at_ms
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(contact_id, device_id) DO UPDATE SET
                identity_b64 = excluded.identity_b64,
                bundle_json = excluded.bundle_json,
                session_ready = excluded.session_ready,
                verified = excluded.verified,
                revoked = excluded.revoked
            """.trimIndent(),
            arrayOf<Any?>(
                device.contactId,
                device.deviceId,
                device.identityB64,
                device.bundleJson,
                if (device.sessionReady) 1 else 0,
                if (device.verified) 1 else 0,
                if (device.revoked) 1 else 0,
                if (device.addedAtMs != 0L) device.addedAtMs else System.currentTimeMillis(),
            ),
        )
    }

    override fun listFor(contactId: String): List<PeerDevice> {
        database.database.rawQuery(
            "$SELECT_ALL WHERE contact_id = ? ORDER BY device_id",
            arrayOf(contactId),
        ).use { cursor ->
            val result = ArrayList<PeerDevice>(cursor.count)
            while (cursor.moveToNext()) result += cursor.readDevice()
            return result
        }
    }

    override fun setSessionReady(contactId: String, deviceId: Int, ready: Boolean) {
        database.database.execSQL(
            "UPDATE peer_devices SET session_ready = ? WHERE contact_id = ? AND device_id = ?",
            arrayOf<Any?>(if (ready) 1 else 0, contactId, deviceId),
        )
    }

    override fun setRevoked(contactId: String, deviceId: Int, revoked: Boolean) {
        database.database.execSQL(
            "UPDATE peer_devices SET revoked = ? WHERE contact_id = ? AND device_id = ?",
            arrayOf<Any?>(if (revoked) 1 else 0, contactId, deviceId),
        )
    }

    override fun setVerified(contactId: String, deviceId: Int, verified: Boolean) {
        database.database.execSQL(
            "UPDATE peer_devices SET verified = ? WHERE contact_id = ? AND device_id = ?",
            arrayOf<Any?>(if (verified) 1 else 0, contactId, deviceId),
        )
    }

    override fun delete(contactId: String, deviceId: Int) {
        database.database.execSQL(
            "DELETE FROM peer_devices WHERE contact_id = ? AND device_id = ?",
            arrayOf<Any?>(contactId, deviceId),
        )
    }

    override fun deleteAllFor(contactId: String) {
        database.database.execSQL("DELETE FROM peer_devices WHERE contact_id = ?", arrayOf(contactId))
    }

    private fun android.database.Cursor.readDevice(): PeerDevice = PeerDevice(
        contactId = getString(0),
        deviceId = getInt(1),
        identityB64 = if (isNull(2)) null else getString(2),
        bundleJson = if (isNull(3)) null else getString(3),
        sessionReady = getInt(4) != 0,
        verified = getInt(5) != 0,
        revoked = getInt(6) != 0,
        addedAtMs = getLong(7),
    )

    private companion object {
        const val SELECT_ALL =
            "SELECT contact_id, device_id, identity_b64, bundle_json, " +
                "session_ready, verified, revoked, added_at_ms FROM peer_devices"
    }
}
