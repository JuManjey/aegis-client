package com.aegis.data.storage

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * Зашифрованное локальное хранилище (§6.3) поверх SQLCipher (AES-256).
 *
 * Ключ БД (случайная 256-битная парольная фраза) НА ДИСКЕ лежит запечатанным
 * дважды: ключом аккаунта ([StorageSealer]) и поверх него аппаратным ключом
 * Keystore/StrongBox. Открыть БД можно только при открытом аккаунте; после
 * открытия соединения сырые байты фразы немедленно затираются.
 *
 * Уничтожение мастер-ключа ([KeyStoreManager.destroyAllKeys]) делает файл БД
 * нечитаемым мгновенно (crypto-shredding, §7.4), без необходимости перезаписи.
 *
 * Ленивое открытие: соединение создаётся при первом обращении к [database] и
 * закрывается при блокировке аккаунта ([close]). Обращение при закрытом
 * аккаунте бросает [StorageLockedException].
 */
class AegisDatabase(
    appContext: Context,
    private val keyStoreManager: KeyStoreManager,
    private val sealer: StorageSealer,
) {
    private val appContext = appContext.applicationContext

    @Volatile
    private var db: SQLiteDatabase? = null

    /** Открытое (при необходимости) соединение с БД. Потокобезопасно. */
    val database: SQLiteDatabase
        get() = db ?: synchronized(this) { db ?: open().also { db = it } }

    /** Хранилище состояния Signal-аккаунта/сессий (§6.1, stateful blob). */
    val signalStateStore: SignalStateStore by lazy { SqlSignalStateStore(this) }

    /** Хранилище контактов и параметров их E2EE-сессий (§14.1). */
    val contactStore: ContactStore by lazy { SqlContactStore(this) }

    /** Хранилище расшифрованных сообщений для показа в UI (§13). */
    val messageStore: MessageStore by lazy { SqlMessageStore(this) }

    /** Реестр устройств контактов (мультидевайс §7.1.1, `docs/multidevice.md` §7). */
    val peerDeviceStore: PeerDeviceStore by lazy { SqlPeerDeviceStore(this) }

    /**
     * Заявки на добавление в контакты (RCH v1, `docs/contact_requests.md`).
     * Содержит СЕКРЕТЫ раунда рукопожатия, поэтому живёт только здесь, внутри
     * SQLCipher, и очищается вместе с БД при crypto-shredding (§7.4).
     */
    val contactRequestStore: ContactRequestStore by lazy { SqlContactRequestStore(this) }

    private fun open(): SQLiteDatabase {
        // Нативное ядро SQLCipher грузим явно (требование библиотеки, идемпотентно).
        System.loadLibrary("sqlcipher")
        val file = File(appContext.filesDir, DB_NAME)
        val passphrase = loadOrCreatePassphrase()
        try {
            val connection =
                SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null, null)
            createSchema(connection)
            return connection
        } finally {
            // Не держим ключ в куче дольше, чем нужно для открытия (§6.3).
            passphrase.fill(0)
        }
    }

    private fun createSchema(connection: SQLiteDatabase) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS signal_state (
                account_id TEXT PRIMARY KEY NOT NULL,
                state_blob TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT PRIMARY KEY NOT NULL,
                nickname TEXT NOT NULL,
                local_name TEXT NOT NULL DEFAULT '',
                about TEXT NOT NULL DEFAULT '',
                fingerprint TEXT NOT NULL DEFAULT '',
                verified INTEGER NOT NULL DEFAULT 0,
                remote_identity_b64 TEXT,
                remote_device INTEGER NOT NULL DEFAULT 1,
                bundle_json TEXT,
                session_ready INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                blocked INTEGER NOT NULL DEFAULT 0,
                muted_until_ms INTEGER NOT NULL DEFAULT 0,
                notification_sound_uri TEXT NOT NULL DEFAULT '',
                call_ringtone_uri TEXT NOT NULL DEFAULT '',
                trust TEXT NOT NULL DEFAULT 'UNKNOWN'
            )
            """.trimIndent(),
        )
        // Миграция для БД, созданных до §14.2 (чёрный список): добавляем колонку
        // best-effort — если она уже есть, ALTER бросит исключение, которое гасим.
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        }
        // Миграция для БД, созданных до §14.5 (уведомления): персональное mute и
        // персональные звуки сообщений/звонков. Best-effort ALTER (см. выше).
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN muted_until_ms INTEGER NOT NULL DEFAULT 0")
        }
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN notification_sound_uri TEXT NOT NULL DEFAULT ''")
        }
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN call_ringtone_uri TEXT NOT NULL DEFAULT ''")
        }
        // Миграция: локальный алиас чата (переименование в списке). Best-effort
        // ALTER — на свежей БД колонка уже есть в CREATE TABLE.
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN local_name TEXT NOT NULL DEFAULT ''")
        }
        // Миграция: способ добавления контакта (`docs/contact_requests.md`).
        // Все существующие контакты могли появиться ТОЛЬКО личным обменом QR/NFC
        // (удалённого добавления до этой версии не было), поэтому бэкфилл —
        // IN_PERSON. Оставить им UNKNOWN было бы неверно: UI показал бы падение
        // доверия там, где его не происходило. ALTER best-effort — на свежей БД
        // колонка уже есть в CREATE TABLE, и тогда UPDATE не выполняется.
        runCatching {
            connection.execSQL("ALTER TABLE contacts ADD COLUMN trust TEXT NOT NULL DEFAULT 'UNKNOWN'")
            connection.execSQL("UPDATE contacts SET trust = 'IN_PERSON'")
        }
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                contact_id TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                status TEXT NOT NULL,
                attachment_json TEXT,
                attachment_local INTEGER NOT NULL DEFAULT 0,
                crypto_json TEXT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_contact ON messages(contact_id, timestamp_ms)",
        )
        // Миграция для БД, созданных до §14.4 (вложения): добавляем колонки
        // best-effort — на свежей БД (где они уже есть) ALTER бросит исключение,
        // которое гасим (тот же приём, что и для contacts.blocked).
        runCatching {
            connection.execSQL("ALTER TABLE messages ADD COLUMN attachment_json TEXT")
        }
        runCatching {
            connection.execSQL(
                "ALTER TABLE messages ADD COLUMN attachment_local INTEGER NOT NULL DEFAULT 0",
            )
        }
        runCatching {
            connection.execSQL("ALTER TABLE messages ADD COLUMN crypto_json TEXT")
        }
        // Байты вложений (§14.4): отдельный BLOB в той же зашифрованной БД, чтобы
        // не раздувать выборки сообщений и не хранить plaintext-файлы на диске (§6.3).
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
                message_id TEXT PRIMARY KEY NOT NULL,
                data BLOB NOT NULL
            )
            """.trimIndent(),
        )
        // Реестр устройств контактов (§7.1.1, `docs/multidevice.md` §7): ДОП.
        // устройства (обычно device_id 2..255), обнаруженные через device-announce
        // или самоанонс. Первичное устройство (device_id=1) остаётся в contacts;
        // объединение — в StoredContact.devices. CREATE IF NOT EXISTS = миграция
        // для БД, созданных до мультидевайса (пустая таблица ⇒ поведение как было).
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS peer_devices (
                contact_id TEXT NOT NULL,
                device_id INTEGER NOT NULL,
                identity_b64 TEXT,
                bundle_json TEXT,
                session_ready INTEGER NOT NULL DEFAULT 0,
                verified INTEGER NOT NULL DEFAULT 0,
                revoked INTEGER NOT NULL DEFAULT 0,
                added_at_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(contact_id, device_id)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_peer_devices_contact ON peer_devices(contact_id)",
        )
        // Заявки на добавление в контакты (RCH v1, `docs/contact_requests.md`).
        //
        // ЗДЕСЬ ЛЕЖАТ СЕКРЕТЫ РАУНДА: `epk_secret_b64`, `kem_secret_b64` и
        // `reply_tag_b64` инициатора. Они обязаны жить до получения ответа
        // (собеседник может принять заявку через сутки), поэтому хранятся в
        // SQLCipher и затираются в момент завершения рукопожатия
        // (`ContactRequestStore.complete`) либо по истечении срока.
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contact_requests (
                id TEXT PRIMARY KEY NOT NULL,
                direction TEXT NOT NULL,
                state TEXT NOT NULL,
                peer_account_id TEXT NOT NULL,
                peer_ed25519_hex TEXT NOT NULL DEFAULT '',
                peer_x25519_hex TEXT NOT NULL DEFAULT '',
                peer_nickname TEXT NOT NULL DEFAULT '',
                peer_fingerprint TEXT NOT NULL DEFAULT '',
                peer_device_id INTEGER NOT NULL DEFAULT 1,
                peer_epk_public_b64 TEXT NOT NULL DEFAULT '',
                peer_contrib_b64 TEXT NOT NULL DEFAULT '',
                peer_nonce_b64 TEXT NOT NULL DEFAULT '',
                peer_tag_b64 TEXT NOT NULL DEFAULT '',
                peer_reply_tag_b64 TEXT NOT NULL DEFAULT '',
                peer_signal_identity_b64 TEXT NOT NULL DEFAULT '',
                peer_bundle_json TEXT NOT NULL DEFAULT '',
                greeting TEXT NOT NULL DEFAULT '',
                epk_secret_b64 TEXT NOT NULL DEFAULT '',
                epk_public_b64 TEXT NOT NULL DEFAULT '',
                contrib_b64 TEXT NOT NULL DEFAULT '',
                nonce_b64 TEXT NOT NULL DEFAULT '',
                kem_public_b64 TEXT NOT NULL DEFAULT '',
                kem_secret_b64 TEXT NOT NULL DEFAULT '',
                reply_tag_b64 TEXT NOT NULL DEFAULT '',
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_contact_requests_peer ON contact_requests(peer_account_id)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_contact_requests_state ON contact_requests(state, updated_at_ms)",
        )
        // Одна заявка на (направление, собеседник): повторный INVITE от того же
        // аккаунта ОБНОВЛЯЕТ запись, а не создаёт вторую. Иначе собеседник, знающий
        // тег, накопил бы у нас список уведомлений — таблица теперь ограничена
        // числом аккаунтов, которым владелец сам выдал адрес (плюс потолок в
        // `SqlContactRequestStore.MAX_ROWS`).
        connection.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_contact_requests_peer_dir
                ON contact_requests(direction, peer_account_id)
            """.trimIndent(),
        )
    }

    /** Закрыть соединение (например, при блокировке приложения). */
    fun close() {
        synchronized(this) {
            db?.close()
            db = null
        }
    }

    /**
     * Экстренная очистка (§7.4): закрыть соединение и удалить файлы БД. После
     * уничтожения мастер-ключа Keystore содержимое уже нечитаемо (crypto-
     * shredding) — удаление файлов здесь best-effort на уровне ФС.
     */
    fun deleteDatabaseFiles() {
        close()
        listOf(
            DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm", "$DB_NAME-journal",
            KEY_FILE, "$KEY_FILE.tmp", LEGACY_KEY_FILE,
        ).forEach { name ->
            runCatching { File(appContext.filesDir, name).delete() }
        }
    }

    /**
     * Фраза SQLCipher: `db_key.v2.aegis = Keystore.wrap(seal_аккаунта(фраза))`.
     * Файл прежнего формата (только Keystore) переносится в новый при первом
     * открытии аккаунта и удаляется.
     *
     * Сбой распечатывания пробрасывается: новую фразу при живом файле БД
     * генерировать нельзя — старая база стала бы нечитаемой навсегда.
     */
    private fun loadOrCreatePassphrase(): ByteArray {
        if (!sealer.isUnlocked) throw StorageLockedException()
        val dir = appContext.filesDir
        val keyFile = File(dir, KEY_FILE)
        val legacyFile = File(dir, LEGACY_KEY_FILE)
        if (keyFile.isFile) {
            // Хвост переноса, прерванного между записью нового файла и удалением старого.
            if (legacyFile.exists()) legacyFile.delete()
            val sealed = keyStoreManager.unwrap(keyFile.readBytes())
            return sealer.open(sealed, StoragePurpose.DATABASE_KEY)
        }
        val passphrase = if (legacyFile.isFile) {
            keyStoreManager.unwrap(legacyFile.readBytes())
        } else {
            ByteArray(KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        }
        val wrapped = keyStoreManager.wrap(sealer.seal(passphrase, StoragePurpose.DATABASE_KEY))
        val tmp = File(dir, "$KEY_FILE.tmp")
        tmp.writeBytes(wrapped)
        if (!tmp.renameTo(keyFile)) {
            keyFile.writeBytes(wrapped)
            tmp.delete()
        }
        legacyFile.delete()
        return passphrase
    }

    private companion object {
        const val DB_NAME = "aegis.db"
        const val KEY_FILE = "db_key.v2.aegis"
        const val LEGACY_KEY_FILE = "db_key.aegis"
        const val KEY_LENGTH = 32
    }
}
