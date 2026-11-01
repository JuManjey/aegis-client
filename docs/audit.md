# AEGIS — материалы для независимого аудита безопасности

> P9 (§9, §11: «независимый аудит + open source + reproducible builds»).
> Единая точка входа для внешнего аудитора: что смотреть, где границы доверия,
> как собрать и проверить. Относится к продукту в целом (клиент `aegis-client` +
> сервер `aegis-server`).

---

## 1. Как начать (сборка + воспроизводимость + тесты)

```bash
# Окружение (пример для macOS arm64; Linux — см. README.md, «Подготовка окружения»):
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.15729638"
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$JAVA_HOME/bin:$PATH"

# Тесты крипто-ядра (слой A + слой B + транспорт):
cargo test --locked --manifest-path rust/aegis-core/Cargo.toml --features signal,tor   # 99/99

# Воспроизводимость нативного ядра (двойная сборка + сравнение SHA-256):
scripts/verify-reproducible-build.sh

# SBOM / инвентарь зависимостей:
scripts/generate-sbom.sh   # → docs/sbom/

# Backend:
cd ../aegis-server && cargo test --locked --features tools   # 76/76 (72 релей + 4 aegis-config-sign)
scripts/verify-reproducible-build.sh
```

---

## 2. Криптографический инвентарь (что и чем реализовано)

**Принцип §1: свою криптографию не пишем — только аудированные библиотеки.**
Собственными остаются композиции этих примитивов — каскад слоя A, PSK-обёртка
A1S1, рукопожатие RCH v1 (`docs/contact_requests.md`), вывод ключей звонка; им
нужен профильный криптографический ревью (§4).

| Функция | Примитив / протокол | Библиотека | Где в коде |
|---------|---------------------|------------|------------|
| Слой B (E2EE канала) | Signal Protocol: X3DH/PQXDH + Double Ratchet | libsignal `v0.94.5` (git-пин) | `rust/aegis-core/src/signal*.rs` |
| Слой A (контейнер) | XChaCha20-Poly1305 → AES-256-GCM → Camellia-256/EAX → Twofish-256/EAX | RustCrypto (`chacha20poly1305`, `aes-gcm`, `eax`, `camellia`, `twofish`, `aead`) | `rust/aegis-core/src/cascade.rs` |
| KDF ключей | HKDF-SHA512, разделение доменов | `hkdf`, `sha2` | `cascade.rs`, `identity.rs`, `psk.rs`, `discovery.rs`, `calls.rs` |
| KDF пароля | Argon2id: 64 МиБ / 3 прохода / 1 поток (и для пакетов переноса/привязки) | `argon2` | `kdf.rs`, `seal.rs`, `cascade.rs` (парольный ключ контейнера); параметры — `core-common/.../AccountModels.kt` |
| Подписи / ECDH | Ed25519 / X25519 | `ed25519-dalek`, `x25519-dalek` | `identity.rs`, `discovery.rs` |
| Мнемоника | BIP-39 | `bip39` | `identity.rs` (восстановление аккаунта), `psk.rs` (мнемоника PSK — в интерфейс не выведена) |
| Транспорт (Tor ВКЛ, по умолчанию) | Tor (Arti), Onion v3, мосты/PT | `arti-client` `0.43` (rustls/ring) | `rust/aegis-core/src/tor.rs` |
| Транспорт (Tor ВЫКЛ, явный выбор) | TLS ≥ 1.2, системная проверка цепочки и имени, необязательные пины SPKI (SHA-256) | платформенный `HttpsURLConnection` (Conscrypt) | `network-tor/.../HttpsRelayTransport.kt`, `RelayTls.kt` |
| PT (обход цензуры) | obfs4/meek (lyrebird), snowflake | официальные PT Tor Project (Go) | `pt/build_pt.sh` |
| Звонки (транспорт медиа) | DTLS-SRTP (на проводе согласуется `AES_CM_128_HMAC_SHA1_80`) + кадровый AES-128-GCM на ключах Signal+PSK (webrtc-sdk сводит 256-битный материал к 128 битам через PBKDF2), SAS | libwebrtc (`webrtc-sdk` M125) `FrameCryptor` | `docs/calls.md`, `call/CallFrameCryptor.kt` |
| Звонки (эталон AEAD кадра, §1.14) | ChaCha20-Poly1305 (тесты C-ABI); на проводе WebRTC M125 — FrameCryptor той же .so | RustCrypto `chacha20poly1305` | `rust/.../frame.rs` |
| Хранилище | SQLCipher (AES-256) + Android Keystore/StrongBox | `sqlcipher-android`, Keystore | `:data-storage` |
| Затирание секретов | zeroize | `zeroize` | по всему ядру; на JNI-границе секреты (состояние Signal, PSK, ключи звонка) передаются base64-строками Java и в куче JVM не затираются |
| ГСЧ | криптографический ГСЧ ОС | `getrandom` | §6.1 |

Полные версии — `rust/aegis-core/Cargo.lock`, `gradle/libs.versions.toml`,
`docs/sbom/`.

### Ключевые обоснования крипто-решений (для ревью)

- **Camellia-256 вместо Serpent-256**: аудированный крейт `serpent` жёстко
  фиксирует 128-битный ключ (недостижим настоящий Serpent-256). Camellia-256 —
  стандартизованный (RFC 3713, ISO/IEC 18033-3, NESSIE, CRYPTREC) 256-битный
  шифр из отдельного семейства → идея каскада «4 независимых сильных слоя»
  сохраняется. Обоснование — `rust/aegis-core/Cargo.toml`.
- **SHA-1 только внутри HMAC** для TURN REST-кредов coturn (требование
  протокола); HMAC-SHA1 остаётся стойким. См. backend `Cargo.toml`.
- **rustls + ring CryptoProvider** ставится явно (`tor.rs`) — иначе паника на
  первом TLS-хендшейке (§1.13).
- **Кадровый слой звонка (§1.14)** — AES-GCM через `org.webrtc.FrameCryptor`
  (та же `libjingle_peerconnection_so.so`, что PeerConnection). C++-шим
  `FrameEncryptorInterface` из отдельного `.so` не навешивается: prebuilt
  WebRTC падает SIGSEGV по чужому vtable. Корневой материал — 256 бит
  (HKDF-SHA512 от `call_secret` из Signal-канала, PSK и DTLS-отпечатков), но на
  проводе кадровый ключ 128-битный: `KeyProvider` webrtc-sdk сводит его к 128
  битам через PBKDF2 (AES-128-GCM). AEAD на кадрах, fail-closed при сбое
  навески (`requireFrameEncryption` не используется: это другой API).
  Эталон ChaCha20-Poly1305 остаётся в `frame.rs` (юнит-тесты C-ABI).

---

## 3. Границы доверия (trust boundaries)

```
[UI/Kotlin] --JNI--> [Rust ядро: слой A + слой B] --> [Arti/Tor] ------> [сеть]  (Tor ВКЛ)
                                                 \--> [HTTPS, TLS] --> [сеть]  (Tor ВЫКЛ)
                                                                        |
                                                              [релей: Onion v3 или
                                                               HTTPS-фронт → тот же релей]
                                                              видит: шифртекст,
                                                              mailbox_id, время,
                                                              открытые поля
                                                              конверта, Tor-цепочку
```

- **JNI-граница** Kotlin↔Rust: вся крипта — в Rust; Kotlin — «клей». Проверять
  корректность (де)сериализации и обработку ошибок на границе (`:crypto`).
  Секреты пересекают границу base64-строками Java (см. §2, «Затирание секретов»).
- **Сеть**: сервер недоверенный. Любой байт от сервера — потенциально
  враждебный ввод (парсинг конвертов, blob-ссылок, сигналинга).
- **Устройство**: ключи в Keystore/StrongBox; plaintext только в защищённой БД.

---

## 4. Приоритетные области ревью

1. **Каскад слоя A** (`cascade.rs`): корректность вложенности AEAD, уникальность
   nonce, разделение доменов ключей, обработка провала тега (fail-closed).
1a. **Кадровый слой звонка** (`CallFrameCryptor` + `org.webrtc.FrameCryptor`
   AES-GCM в той же `libjingle_peerconnection_so.so`, что PeerConnection;
   ключи из `calls.rs` / Signal+PSK). C++-шим `libaegis_frame.so` /
   `frame.rs` **не навешивается** на медиаконвейер (чужой vtable → SIGSEGV у
   prebuilt WebRTC). Ревью: fail-closed при сбое навески, стабильные id
   направления, отсутствие silent-downgrade на один DTLS-SRTP.
2. **PSK и слой B** (`docs/psk_exchange.md`, `signal.rs`): HKDF-биндинг
   (safety-code) и сессионная AEAD-обёртка plaintext. PSK **не** входит в KDF
   PQXDH (API libsignal хука не даёт). Утечки PSK в сеть быть не должно.
3. **Границы ввода от сервера**: конверты (`aegis-server/PROTOCOL.md`), вложения
   (§14.4), сигналинг звонков — устойчивость к malformed/adversarial данным.
4. **Хранение ключей**: обёртка ключа SQLCipher StrongBox, crypto-shredding,
   duress (§7.4).
5. **Zero-trust сервера** (`aegis-server`): не логирует чувствительное,
   mailbox_id непрозрачен. Связь «кто-кому» сейчас выводима через общую
   Tor-цепочку и открытые поля конверта (§5) — ревью исправлений.
6. **Транспорт**: отсутствие утечек в обход Tor в режиме Tor ВКЛ (DNS, прямые
   сокеты, STUN, HTTPS), корректный запуск PT; порядок смены режима без
   перекрёстных запросов (`NetworkModeController`); в режиме Tor ВЫКЛ — проверка
   TLS (версия, цепочка, имя хоста, пины) до первого байта HTTP
   (`GuardedTrustManager`), отсутствие откатов на незашифрованный HTTP.

---

## 5. Известные ограничения и нереализованное (честно)

- **Кадровый слой поверх DTLS-SRTP** реализован (`docs/calls.md §5`):
  `CallFrameCryptor` навешивает `org.webrtc.FrameCryptor` (AES-GCM) из той
  же native-библиотеки, что WebRTC. Ключи — HKDF-SHA512(call_secret, PSK,
  DTLS-отпечатки). Сбой навески на реальном движке **завершает звонок**.
  `requireFrameEncryption` выключен (это API `FrameEncryptorInterface`, не
  transformer). C++-шим `libaegis_frame.so` не используется. Обе стороны
  должны быть на этой версии клиента.
- **PSK без ротации** — см. `docs/threat-model.md §4`.
- Полная i18n UI-литералов не завершена (не влияет на безопасность).
- Проверка PT на реально цензурируемой сети — требует полевых устройств.

### 5.1 Найдено аудитом кода 01.11.2026 (клиент `531cb1c`, сервер `ea07016`)

Критических проблем (прямой доступ к содержимому или ключам) не найдено.
Шифрование содержимого реализовано на реальных библиотеках и проходит тесты;
ниже — места, где код не дотягивает до заявленного.

**Высокая критичность**

- **Связывание ящиков и граф «кто с кем» на стороне сервера.** SOCKS5 Arti без
  аутентификации, `TorClient.connect` без изоляции (`tor.rs`), новое
  SOCKS-соединение на каждый запрос, отдельный long-poll на каждый контакт
  (`RelayReceiver`), статичные id ящиков (`psk.rs`). Все потоки идут через одну
  rendezvous-цепочку, и оператор релея может связать ящики пользователя.
- **Отзыв устройства обходится.** `DeviceAnnouncer` записывает объявление с
  `revoked = false`, upsert в `PeerDeviceStore` перезаписывает флаг; номер
  устройства берётся из тела сообщения; `SelfSyncManager` принимает анонс с любым
  новым номером. Мнемоника и PSK при отзыве не меняются.
- **Знание PSK позволяет добавить «устройство-призрак» или заменить закреплённый
  identity** через канал `keyx` (только слой A): `PrekeyBundle.identityMatches`
  пропускает бандл без `identity_key`, `ContactStore.setPeerKeys` перезаписывает
  ключ через COALESCE, для нового номера устройства закрепления нет, fan-out идёт
  на все неотозванные устройства.

**Средняя критичность**

- Открытые поля внешнего конверта (`type`, `layerA`, `msgId`, `senderDevice`,
  `channel`, `kind`) и отсутствие выравнивания длины.
- `CoverTraffic` отличим от настоящих дропов: случайные байты (`kotlin.random`)
  вместо JSON, decoy-ящики никто не опрашивает.
- Зависимости ядра с бюллетенями RustSec: rustls 0.23.41, rustls-webpki 0.103.13,
  zstd-safe 7.2.4 / zstd 0.13.3, libcrux-secrets 0.0.5 (aarch64); cargo-audit или
  cargo-deny в CI нет. На сервере — только tokio 1.52.3 (API, который релей не
  использует).
- DoS глобальным вытеснением: при переполнении сервер удаляет самые старые
  конверты, блобы и записи каталога по всей БД; ограничения частоты нет.
- Пакеты переноса/привязки: Argon2id 64 МиБ / 3 / 1 и пароль от 8 символов;
  параметры 256 МиБ / 4 / 2 из `CascadeSpec` нигде не используются.
- Релизная сборка может тихо потерять `tor`/`signal`/PT (best-effort в
  `crypto/build.gradle.kts`, `cargo ndk build` без `--locked`).
- EXIF и имя файла камеры (`cap_<мс>.jpg`) уходят собеседнику.
- Экспортированные ярлыки + экстра `EXTRA_ACCEPT_CALL`: стороннее приложение
  может принять входящий звонок.
- Проверка «duress ≠ основной пароль» не срабатывает (разные соли).
- RCH: `accountId` отправителя и ответившего открыты; ACCEPT/DECLINE не привязаны
  к INVITE (повтор → DoS); отпечаток в заявке не пересчитывается из ключей;
  запись каталога с `expiresAtMs ≤ 0` не истекает.
- Неподписанный QR молча перезаписывает существующий контакт; «проверен»
  переживает смену ключа; «обязательная верификация» — только баннер.
- NFC: HCE раздаёт половину PSK открытым текстом; приёмная сторона
  (`PskNfcReader`) нигде не создаётся.

**Не работает / выключено**

- Обмен PSK мнемоникой: кодирование есть в `psk.rs`, экрана и вызова импорта нет.
- Приём PSK по NFC (см. выше).
- Публикация `@ника`: клиент кладёт ключ в поле `ed25519Hex`
  (`DirectoryRecord.kt`), сервер требует `ed25519` — заявка отвергается.
- Пины SPKI и авто-обнаружение релея по подписанному конфигу: код корректен, но
  `aegis.relayHttpsPins` и `aegis.relayConfigPubkey` пусты.

**Низкая критичность и неточности**

- Сервер: `mailbox_seq` не чистится никогда; `GET /v1/health` отдаёт глобальное
  число конвертов; `PUT /v1/prekeys/{id}` перезаписывает кто угодно, знающий id;
  нет `HiddenServicePoWDefensesEnabled`; образы не закреплены по digest; секрет
  TURN — в аргументах командной строки coturn; `aegis-config-sign` принимает
  закрытый ключ аргументом.
- Аккаунт без пароля защищён только Keystore и блокировкой телефона;
  автоблокировка — только на переднем плане.
- Блокировка контакта не останавливает звонки и служебные сигналы до перезапуска
  циклов приёма; «удалить у всех» не проверяет авторство.
- Нет `IME_FLAG_NO_PERSONALIZED_LEARNING`; loopback SOCKS5 и TURN-форвардер без
  аутентификации; уникальный NFC AID выдаёт приложение.
- Ed25519 `verify`, а не `verify_strict`; PoW 20 бит, решение можно повторно
  использовать для того же пути 10–20 минут.
- Размер вложения при приёме не проверяется; первое устройство удаляет блоб для
  остальных; signed prekey и Kyber-prekey не ротируются.
- Перенос аккаунта по умолчанию клонирует состояние Signal; экспорт, показ
  фразы и сборка пакета привязки — без повторного ввода пароля; параметры
  Argon2id берутся из пакета без ограничения.
- Вторая ступень очистки — не более 4 ГиБ, без fsync, отчёт всегда успешный.
- Журнал диагностики (выключен по умолчанию) пишет ники контактов открытым
  текстом; вызовы `android.util.Log` не вырезаются из release.
- Состояние Signal сохраняется до проверки слоя A; половина PSK переиспользуется
  между раундами обмена; чёрный список чувствителен к регистру hex `accountId`;
  экран сканера QR (ZXing) без `FLAG_SECURE`.
- SRTP на проводе — `AES_CM_128_HMAC_SHA1_80`, кадровый слой — AES-128-GCM;
  комментарий `calls.rs` о «SRTP-ключах AES-256 с солью» неточен (соль не
  используется).
- Нет статуса «доставлено»; отчёты о прочтении не взаимны (вопреки тексту
  интерфейса); нет автозапуска после перезагрузки; `panic = "abort"` без
  `catch_unwind` на JNI-границе.
- 7 полей `SecuritySettings` ни на что не влияют; обнаружения root нет;
  уровень доверия `IN_PERSON` новым контактам не присваивается; код рукопожатия
  RCH не показывается; Signal-сессия переживает удаление контакта.
- После звонка флаги «поверх блокировки» снимаются через 3 с — возможно окно
  ~2 с, когда открытое приложение видно поверх экрана блокировки (по коду, на
  устройстве не проверялось).

---

## 6. Материалы

- Модель угроз: `docs/threat-model.md`.
- Форматы/протоколы: `docs/layer_a_container_format.md`, `docs/psk_exchange.md`,
  `docs/tor_transport.md`, `docs/relay_api.md`, `docs/calls.md`,
  `../aegis-server/PROTOCOL.md`.
- Воспроизводимость: `docs/reproducible-builds.md`.
- Сборка, статус и известные ограничения: `README.md`.
- SBOM: `docs/sbom/`.
