package com.aegis.crypto

/**
 * Тонкий JNI-мост к нативному ядру `libaegis_core.so` (Rust, крейт `aegis-core`).
 *
 * Все криптографические операции выполняются в аудированных Rust-крейтах (§1):
 * ключи, KDF и AEAD НЕ реализуются на Kotlin. Граница намеренно узкая — обмен
 * строками (hex / base64 / JSON); секреты живут в нативной памяти и затираются
 * там же ([zeroize]).
 *
 * Если библиотека не собрана/не загрузилась (каркас без Rust-тулчейна),
 * [isAvailable] = false, а нативные методы НЕ вызываются — фасад [AccountCrypto]
 * бросает понятную ошибку вместо тихого фейкового результата.
 */
object NativeAegisCore {
    private const val LIBRARY_NAME = "aegis_core"

    @Volatile
    var isAvailable: Boolean = false
        private set

    init {
        isAvailable = try {
            System.loadLibrary(LIBRARY_NAME)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    // --- Диагностика ---
    external fun nativeVersion(): String
    external fun nativeHasSignalSupport(): Boolean
    external fun nativeHasTorSupport(): Boolean

    // --- Слой транспорта: встроенный Tor (Arti, P5, §7.2/§10.5) ---
    // Символы существуют только в сборке с фичей `tor`. Вызывать ЛИШЬ после
    // проверки hasTorSupport(), иначе UnsatisfiedLinkError.

    /**
     * Запускает/перезапускает встроенный Tor по JSON-конфигу
     * (`{stateDir,cacheDir,socksPort,bridges,transports}`).
     * Ответ: `+<port>` — успех (SOCKS-порт), `-<текст>` — ошибка.
     */
    external fun nativeTorStart(configJson: String): String

    /** Останавливает встроенный Tor и освобождает ресурсы. */
    external fun nativeTorStop()

    /** JSON-статус транспорта: `{state,progress,socksPort,error}` (для индикатора). */
    external fun nativeTorStatusJson(): String

    /** Диагностика достижимости адреса (напр. onion-релея) через Tor. `+ok`/`-<текст>`. */
    external fun nativeTorTestConnect(host: String, port: Int, timeoutMs: Int): String

    // --- Слой B: Signal Protocol (P2, §6.1) ---
    // Символы существуют только в сборке с фичей `signal`. Вызывать ЛИШЬ после
    // проверки hasSignalSupport(), иначе UnsatisfiedLinkError.

    /**
     * Нативный самотест слоя B: два клиента X3DH/PQXDH + Double Ratchet.
     * [pskB64] — опциональный ручной PSK (base64) для проверки биндинга §6.2;
     * пустая строка = без PSK. Возвращает JSON-отчёт или {"error":...}.
     */
    external fun nativeSignalSelfTest(pskB64: String): String

    /** Эталонный публичный prekey-бандл (JSON) — формат обмена с backend (§0). */
    external fun nativeSignalDemoPreKeyBundleJson(): String

    // --- Слой B: stateful-сессии (P6, §6.1/§6.3) ---
    // Состояние аккаунта — непрозрачный blob (base64), который Kotlin хранит в
    // SQLCipher. Каждая операция принимает старый blob и возвращает новый.

    /** Создаёт свежий Signal-аккаунт. Возвращает blob состояния (base64) или "". */
    external fun nativeSignalCreateAccount(): String

    /** Публичный identity-ключ аккаунта (base64) для PSK-биндинга/отпечатка. "" при ошибке. */
    external fun nativeSignalIdentityKeyB64(stateB64: String): String

    /**
     * Генерирует комплект prekeys для локального устройства [localDevice]
     * (мультидевайс §7.1.1: бандл несёт device id).
     * JSON `{"state":..,"bundle":{..}}` или `{"error":..}`.
     */
    external fun nativeSignalPublishPrekeys(stateB64: String, localDevice: Int): String

    /**
     * Устанавливает исходящую сессию по публичному бандлу (X3DH/PQXDH).
     * [localDevice] — device id ЭТОГО устройства (мультидевайс §7.1.1).
     * Ответ: `+<новый blob>` — успех, `-<ошибка>`.
     *
     * [expectedIdentityB64] здесь и в [nativeSignalHasSession],
     * [nativeSignalEncrypt], [nativeSignalDecrypt] — закреплённый identity
     * удалённого устройства ("" — неизвестен, TOFU). Он стоит ПОСЛЕДНИМ
     * аргументом: `.so`, собранная до него, лишний аргумент просто не читает.
     */
    external fun nativeSignalProcessBundle(
        stateB64: String,
        localName: String,
        localDevice: Int,
        remoteName: String,
        remoteDevice: Int,
        bundleJson: String,
        expectedIdentityB64: String,
    ): String

    /**
     * Есть ли сессия с удалённым адресом. С непустым [expectedIdentityB64]
     * сессия на другом identity считается отсутствующей.
     */
    external fun nativeSignalHasSession(
        stateB64: String,
        remoteName: String,
        remoteDevice: Int,
        expectedIdentityB64: String,
    ): Boolean

    /**
     * Шифрует сообщение слоя B. JSON `{"state":..,"type":"PreKey|Whisper",
     * "ciphertext":"<b64>"}` или `{"error":..}`. `plaintextB64` — base64 plaintext.
     */
    external fun nativeSignalEncrypt(
        stateB64: String,
        localName: String,
        localDevice: Int,
        remoteName: String,
        remoteDevice: Int,
        plaintextB64: String,
        expectedIdentityB64: String,
    ): String

    /**
     * Расшифровывает сообщение слоя B. JSON `{"state":..,"plaintext":"<b64>"}`
     * или `{"error":..}`. `messageType`: "PreKey" | "Whisper".
     * [localDevice] — device id ЭТОГО устройства (мультидевайс §7.1.1).
     */
    external fun nativeSignalDecrypt(
        stateB64: String,
        localName: String,
        localDevice: Int,
        remoteName: String,
        remoteDevice: Int,
        ciphertextB64: String,
        messageType: String,
        expectedIdentityB64: String,
    ): String

    /**
     * PSK-биндинг (§6.2) из локального и удалённого identity-ключей + ручного PSK.
     * JSON `{"binding":"<b64>","safety":".."}` или `{"error":".."}`.
     */
    external fun nativeSignalPskBinding(
        localIdentityB64: String,
        remoteIdentityB64: String,
        pskB64: String,
    ): String

    /**
     * Сессионная обёртка PSK поверх plaintext слоя B (`+<b64>` / `-<ошибка>`).
     * Ключ = HKDF-SHA256(salt=PSK, ikm=sort(idA‖idB), info=psk-session-seal/v1),
     * AEAD = ChaCha20-Poly1305. Не хук в KDF PQXDH — публичный API libsignal
     * его не даёт; это отдельный слой, который нельзя снять без PSK и без тех
     * identity, которые сторона реально увидела.
     */
    external fun nativeSignalPskSealB64(
        localIdentityB64: String,
        remoteIdentityB64: String,
        pskB64: String,
        plaintextB64: String,
    ): String

    /** Снятие сессионной обёртки PSK. Legacy без метки возвращается как есть. */
    external fun nativeSignalPskOpenB64(
        localIdentityB64: String,
        remoteIdentityB64: String,
        pskB64: String,
        sealedB64: String,
    ): String

    // --- P8 (§7.5/§10.10): согласование ключей звонка ---
    // Символ существует только в сборке с фичей `signal`. Вызывать ЛИШЬ после
    // проверки hasSignalSupport(), иначе UnsatisfiedLinkError.

    /**
     * Согласует ключи медиапотока звонка для одной стороны. Возвращает JSON
     * `{"sendKey":..,"sendSalt":..,"recvKey":..,"recvSalt":..,"sas":..}` (ключи
     * base64) или `{"error":".."}`. [initiator] — эта сторона инициировала звонок.
     */
    external fun nativeCallDeriveKeys(
        callSecretB64: String,
        pskB64: String,
        callId: String,
        initiator: Boolean,
        contextB64: String,
    ): String

    // --- Аккаунт (P1, §7.1) ---
    /** Генерирует BIP-39-мнемонику (12/15/18/21/24 слова). Пустая строка = ошибка. */
    external fun nativeGenerateMnemonic(wordCount: Int): String

    /** Проверяет контрольную сумму BIP-39-мнемоники. */
    external fun nativeValidateMnemonic(phrase: String): Boolean

    /** JSON публичной информации аккаунта (accountId, публичные ключи, отпечаток). */
    external fun nativeDeriveAccountInfo(phrase: String, passphrase: String): String

    /**
     * Проверяет Ed25519-подпись (§12.2, авто-обнаружение адреса релея).
     * [publicHex] — публичный ключ (64 hex, зашит в сборку), [messageB64] —
     * подписанные байты (base64), [signatureB64] — подпись (base64, 64 байта).
     * true только при корректной подписи; любой сбой разбора → false.
     */
    external fun nativeVerifyEd25519(
        publicHex: String,
        messageB64: String,
        signatureB64: String,
    ): Boolean

    // --- KDF пароля (Argon2id) ---
    /** Выводит ключ из пароля. base64-результат; пустая строка = ошибка. */
    external fun nativeArgon2idDeriveB64(
        password: String,
        saltB64: String,
        memKib: Int,
        iters: Int,
        parallelism: Int,
        outLen: Int,
    ): String

    // --- Запечатывание мнемоники (XChaCha20-Poly1305) ---
    /** Запечатывает мнемонику ключом (base64). Возвращает base64 блоба (nonce||ct). */
    external fun nativeSealMnemonicB64(mnemonic: String, keyB64: String): String

    /** Распечатывает блоб ключом (base64). Возвращает мнемонику; пустая строка = ошибка. */
    external fun nativeOpenMnemonicB64(blobB64: String, keyB64: String): String

    // --- Слой A: каскадный контейнер (P3, §5) ---
    // Формат ответа: "+<base64>" — успех (данные, могут быть пустыми),
    // "-<текст>" — ошибка (провал тега слоя / неверный секрет / заголовок).
    // Все бинарные аргументы — base64 (NO_WRAP).

    /** Шифрует plaintext в каскадный контейнер (XChaCha20→AES-256→Camellia-256→Twofish-256). */
    external fun nativeCascadeSealB64(secretB64: String, plaintextB64: String, aadB64: String): String

    /** Расшифровывает каскадный контейнер (обратный порядок слоёв). */
    external fun nativeCascadeOpenB64(secretB64: String, containerB64: String, aadB64: String): String

    // --- P4 (§6.2, §10.3): ручной обмен ключом контейнера (PSK) ---
    // PSK НИКОГДА не уходит в сеть; эти методы обслуживают только локальный
    // (out-of-band) обмен. Все бинарные значения — base64; "" = ошибка.

    /** Генерирует новый 256-битный PSK (ОС-ГСЧ). base64; "" = ошибка. */
    external fun nativeGeneratePskB64(): String

    /** Отпечаток PSK (необратимый, HKDF-SHA512) для повторной сверки. base64. */
    external fun nativePskFingerprintB64(pskB64: String): String

    /** Кодирует PSK (base64) в BIP-39-подобную мнемонику для ручного ввода. */
    external fun nativePskToMnemonic(pskB64: String): String

    /** Восстанавливает PSK (base64) из мнемонической фразы. "" = неверная фраза. */
    external fun nativePskFromMnemonicB64(phrase: String): String

    /**
     * Выводит непрозрачный идентификатор mailbox релея из PSK и направления
     * (§7.6/§12.2): `HKDF-SHA512(salt="AEGIS/mailbox/v1", ikm=PSK, info=role)`.
     * [role] — 0/1 (направленный подканал). base64 32 байт; "" = ошибка.
     * PSK по-прежнему в сеть НЕ уходит — наружу идёт только производный id.
     */
    external fun nativeMailboxIdB64(pskB64: String, role: Int): String

    /**
     * Per-device идентификатор mailbox v2 (мультидевайс §7.1.1,
     * `docs/multidevice.md` §4). [secretB64] — секрет соответствующего домена,
     * [kind]: `0` — переписка (PSK контакта), `1` — self-sync аккаунта
     * (см. [nativeDeriveSelfSyncSecretB64]), `2` — ящик приглашений (тег
     * обнаружения), `3` — ящик ответа на приглашение (эфемерный `replyTag`).
     * [scope] — accountId владельца ящика (`aegis:<hex>`); [deviceId] — 1..=255.
     * base64 32 байт; "" = ошибка. Секрет в сеть НЕ уходит — наружу только id.
     */
    external fun nativeMailboxIdDeviceB64(
        secretB64: String,
        kind: Int,
        scope: String,
        deviceId: Int,
    ): String

    // --- RCH v1 (§6.2, `docs/contact_requests.md`): удалённое добавление ---
    // Канонический порядок полей, transcript и вывод секрета слоя A целиком
    // живут в Rust (`discovery.rs`) и покрыты тестами: через границу ходят
    // только JSON/base64, чтобы Kotlin не мог перепутать порядок аргументов.

    /** Новый тег обнаружения (256 бит) для адреса контакта. base64; "" = ошибка. */
    external fun nativeDiscoveryGenerateTagB64(): String

    /**
     * Выводит из тега адрес записи каталога и ключ её запечатывания.
     * Ответ: `{"indexB64","recordKeyB64","tagCommitB64"}` либо `{"error"}`.
     */
    external fun nativeDiscoveryDeriveJson(tagB64: String): String

    /**
     * Индекс каталога для ПУБЛИЧНОГО алиаса (`@ник`) после нормализации.
     * Ответ: `{"aliasNormalized","indexB64"}` либо `{"error"}`.
     */
    external fun nativeAliasIndexJson(alias: String): String

    /** Запечатывает запись каталога (AAD привязан к индексу). base64; "" = ошибка. */
    external fun nativeDiscoverySealRecordB64(
        recordKeyB64: String,
        indexB64: String,
        plaintext: String,
    ): String

    /**
     * Распечатывает запись каталога. Возвращает plaintext (UTF-8) либо "" при
     * провале AEAD-тега (чужой тег / битая или переставленная запись).
     */
    external fun nativeDiscoveryOpenRecord(
        recordKeyB64: String,
        indexB64: String,
        blobB64: String,
    ): String

    /**
     * Генерирует эфемерный материал одного раунда рукопожатия RCH. Ответ:
     * `{"epkSecretB64","epkPublicB64","contribB64","nonceB64","kemPublicB64","kemSecretB64"}`
     * либо `{"error"}`. Секреты хранить ТОЛЬКО в SQLCipher до получения ответа.
     */
    external fun nativeRchGenerateRoundJson(): String

    /**
     * Завершает рукопожатие RCH и выводит секрет контейнера слоя A. Формат
     * запроса/ответа — см. `lib.rs::rch_derive` и [DiscoveryCrypto.derive].
     * Ответ: `{"pskB64","transcriptB64","handshakeCode","kemCiphertextB64",`
     * `"localAccountId"}` либо `{"error"}`.
     *
     * Свои `accountId`/Ed25519 и статический X25519-секрет НЕ передаются: они
     * выводятся внутри Rust из мнемоники, поэтому приватные ключи не покидают
     * нативный слой, а подложить чужую личность в запросе невозможно.
     */
    external fun nativeRchDeriveJson(requestJson: String): String

    /**
     * Подписывает [messageB64] долгосрочным Ed25519-ключом аккаунта, выведенным
     * из мнемоники внутри Rust (приватный ключ наружу не отдаётся). base64
     * подписи; "" = ошибка. Нужна для записи каталога и нагрузок RCH.
     */
    external fun nativeAccountSignB64(
        phrase: String,
        passphrase: String,
        messageB64: String,
    ): String

    /**
     * `accountId` из публичного Ed25519-ключа (hex). Единственная реализация
     * привязки на клиенте — проверка «accountId коммитится к этому ключу» это
     * линчпин доверия при удалённом добавлении, дублировать её в Kotlin нельзя.
     * "" = некорректный ключ.
     */
    external fun nativeAccountIdFromEd25519(ed25519Hex: String): String

    /**
     * Секрет синхронизации СВОИХ устройств из мнемоники (мультидевайс §7.1.1,
     * `docs/multidevice.md` §4.2). Общий всем линкованным устройствам,
     * неизвестен контактам. base64 32 байт; "" = ошибка. В сеть НЕ уходит.
     */
    external fun nativeDeriveSelfSyncSecretB64(phrase: String, passphrase: String): String

    /**
     * Ключ локальных хранилищ из мнемоники: второй слой поверх Keystore для
     * ключа SQLCipher и файла PSK. base64 32 байт; "" = ошибка. В сеть НЕ уходит.
     */
    external fun nativeDeriveStorageKeyB64(phrase: String, passphrase: String): String

    /** Версия ядра или null, если нативная библиотека недоступна. */
    fun versionOrNull(): String? =
        if (isAvailable) runCatching { nativeVersion() }.getOrNull() else null

    /** Скомпилирована ли поддержка libsignal (слой B). */
    fun hasSignalSupport(): Boolean =
        isAvailable && runCatching { nativeHasSignalSupport() }.getOrDefault(false)

    /** Скомпилирована ли поддержка встроенного Tor (Arti, слой транспорта, P5). */
    fun hasTorSupport(): Boolean =
        isAvailable && runCatching { nativeHasTorSupport() }.getOrDefault(false)
}
