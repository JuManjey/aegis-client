//! RCH v1 — удалённое добавление контакта без QR (§6.2, `docs/contact_requests.md`).
//!
//! ЗАЧЕМ. Личный обмен PSK (QR/NFC, [`crate::psk`]) требует физической встречи:
//! адрес почтового ящика выводится ИЗ PSK ([`crate::psk::mailbox_id_device`]),
//! поэтому до появления PSK доставить собеседнику нельзя ни одного байта, а PSK
//! без канала доставки не согласовать. Замкнутый круг разрывал QR.
//!
//! Этот модуль разрывает его иначе — **тегом обнаружения** (discovery tag): 256
//! бит случайных данных, которые владелец аккаунта раздаёт вместе со своим
//! `accountId` в виде строки-адреса. Из тега выводятся:
//!
//!  * `index`      — опаковый адрес записи в каталоге релея (`/v1/directory/{index}`);
//!  * `record_key` — ключ AEAD, которым запечатана сама запись;
//!  * ящик приглашений — [`crate::psk::MailboxKind::Invite`] на теге вместо PSK.
//!
//! Сервер видит только `index` (256 бит) и шифртекст: ни ника, ни ключей, ни
//! связи «кто-кого искал» (zero-trust §10.9). Перечислить пользователей нельзя —
//! индекс невоспроизводим без тега. Ротация тега мгновенно отзывает все ранее
//! выданные адреса.
//!
//! МОДЕЛЬ ДОВЕРИЯ. `accountId = "aegis:" + hex(SHA-256(ed25519_pub)[..16])`
//! ([`crate::identity`]) — криптографический коммитмент к ключу подписи. Поэтому
//! клиент, получивший `accountId` по доверенному каналу, проверяет запись
//! каталога сам: подпись Ed25519 + равенство `account_id_from(ed25519) ==
//! accountId`. Сервер не может подменить ключи — ему пришлось бы найти второй
//! прообраз SHA-256 (128 бит). Доверия к серверу в цепочке НЕТ; доверие ровно
//! одно — к строке адреса, полученной вне сети (как и к отсканированному QR).
//!
//! СЕКРЕТ СЛОЯ A. PSK больше не передаётся — он **выводится** из рукопожатия
//! RCH, независимого и от libsignal (слой B), и от релея:
//!
//! ```text
//! dh_ee  = X25519(эфемерный_свой, эфемерный_чужой)   // forward secrecy
//! dh_ss  = X25519(статический_свой, статический_чужой) // аутентификация accountId
//! kem_ss = Kyber1024 (инициатор публикует ключ, ответчик инкапсулирует) // PQ
//!
//! container_secret = HKDF-SHA512(
//!     salt = "AEGIS/psk/rch/v1",
//!     ikm  = dh_ee || dh_ss || kem_ss || contrib_lo || contrib_hi,
//!     info = "aegis/psk/rch/v1/" || hex(transcript))
//! ```
//!
//! `transcript` (SHA-512) связывает ВСЕ поля обеих нагрузок в каноничном порядке
//! (см. [`transcript`]), а сами нагрузки подписаны Ed25519-ключами аккаунтов.
//! Отсюда: подмена любого поля ломает вывод у обеих сторон, reflection/replay
//! невозможны, а одновременная компрометация libsignal И релея не даёт MITM —
//! нужно вскрыть ещё X25519 и Ed25519.
//!
//! Свою криптографию НЕ пишем (§1): hkdf/sha2/x25519-dalek (RustCrypto/dalek),
//! Kyber1024 — из уже присутствующей в сборке аудированной libsignal.

use hkdf::Hkdf;
use sha2::{Digest, Sha256, Sha512};
use x25519_dalek::{PublicKey as XPublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::error::AegisError;

/// Длина тега обнаружения — 256 бит (как PSK и mailbox-id).
pub const TAG_LEN: usize = 32;

/// Длина выводимых из тега идентификаторов/ключей.
pub const INDEX_LEN: usize = 32;

/// Длина эфемерного/статического X25519-ключа.
const X25519_LEN: usize = 32;

/// Длина случайного вклада стороны в секрет слоя A.
pub const CONTRIB_LEN: usize = 32;

/// Длина nonce нагрузки (защита от повторов в рамках transcript).
pub const NONCE_LEN: usize = 16;

/// Домен HKDF тега обнаружения. Отделён от identity/PSK/mailbox/каскада.
const DISCOVERY_SALT: &[u8] = b"AEGIS/discovery/v1";
const INFO_INDEX: &[u8] = b"aegis/discovery/v1/index";
const INFO_RECORD_KEY: &[u8] = b"aegis/discovery/v1/record-key";

/// Префикс AAD запечатанной записи каталога — привязывает шифртекст к его
/// индексу, чтобы запись нельзя было переставить на другой индекс.
const RECORD_AAD_PREFIX: &[u8] = b"aegis/discovery/v1/record/";

/// Метка коммитмента к тегу внутри записи (доказывает, что запись — для ЭТОГО
/// тега; иначе владелец другого тега мог бы переиспользовать чужую запись).
const TAG_COMMIT_LABEL: &[u8] = b"aegis/discovery/v1/tag-commit";

/// Домен HKDF публичного алиаса (`@ник`). ОТДЕЛЬНЫЙ от тега: алиас — низко-
/// энтропийный вход, его индекс принципиально перечислим, и смешивать его с
/// доменом тега нельзя.
const ALIAS_SALT: &[u8] = b"AEGIS/alias/v1";
const INFO_ALIAS_INDEX: &[u8] = b"aegis/alias/v1/index";

/// Домен вывода секрета слоя A из рукопожатия RCH.
const RCH_SALT: &[u8] = b"AEGIS/psk/rch/v1";
const RCH_INFO_PREFIX: &str = "aegis/psk/rch/v1/";

/// Метка transcript — фиксирует версию протокола внутри хеша.
const RCH_TRANSCRIPT_LABEL: &[u8] = b"aegis/rch/v1/transcript";

/// Минимальная/максимальная длина публичного алиаса.
pub const ALIAS_MIN_LEN: usize = 3;
pub const ALIAS_MAX_LEN: usize = 32;

// ---------------------------------------------------------------------------
// Тег обнаружения и производные
// ---------------------------------------------------------------------------

/// Выведенные из тега обнаружения адрес каталога и ключ записи.
pub struct DiscoveryIds {
    /// Опаковый индекс записи в каталоге релея (256 бит).
    pub index: [u8; INDEX_LEN],
    /// Ключ AEAD записи каталога (256 бит). Затирается при выходе из скоупа.
    pub record_key: Zeroizing<[u8; 32]>,
}

/// Генерирует новый тег обнаружения из ГСЧ ОС (§6.1).
pub fn generate_tag() -> Result<Zeroizing<Vec<u8>>, AegisError> {
    let mut tag = Zeroizing::new(vec![0u8; TAG_LEN]);
    getrandom::getrandom(tag.as_mut_slice())
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    Ok(tag)
}

/// Выводит из тега индекс каталога и ключ записи (разные `info` — разные домены).
pub fn derive_discovery(tag: &[u8]) -> Result<DiscoveryIds, AegisError> {
    if tag.len() != TAG_LEN {
        return Err(AegisError::InvalidArg(format!(
            "тег обнаружения должен быть {TAG_LEN} байт, получено {}",
            tag.len()
        )));
    }
    let hk = Hkdf::<Sha512>::new(Some(DISCOVERY_SALT), tag);

    let mut index = [0u8; INDEX_LEN];
    hk.expand(INFO_INDEX, &mut index)
        .map_err(|_| AegisError::Crypto("HKDF expand (discovery index) failed".into()))?;

    let mut record_key = Zeroizing::new([0u8; 32]);
    hk.expand(INFO_RECORD_KEY, record_key.as_mut())
        .map_err(|_| AegisError::Crypto("HKDF expand (discovery record key) failed".into()))?;

    Ok(DiscoveryIds { index, record_key })
}

/// Коммитмент к тегу для вложения в запись каталога: `SHA-256(label || tag)`.
/// Необратим — сам тег из записи не восстанавливается, а получатель, знающий
/// тег, убеждается, что запись выпущена именно для него.
pub fn tag_commit(tag: &[u8]) -> Result<[u8; 32], AegisError> {
    if tag.len() != TAG_LEN {
        return Err(AegisError::InvalidArg(format!(
            "тег обнаружения должен быть {TAG_LEN} байт, получено {}",
            tag.len()
        )));
    }
    let mut hasher = Sha256::new();
    hasher.update(TAG_COMMIT_LABEL);
    hasher.update(tag);
    Ok(hasher.finalize().into())
}

/// Запечатывает запись каталога ключом из тега, привязывая её к `index` (AAD).
pub fn seal_record(
    record_key: &[u8],
    index: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, AegisError> {
    crate::seal::seal(record_key, plaintext, &record_aad(index))
}

/// Распечатывает запись каталога. Провал тега AEAD = чужая/битая запись.
pub fn open_record(
    record_key: &[u8],
    index: &[u8],
    blob: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    crate::seal::open(record_key, blob, &record_aad(index))
}

fn record_aad(index: &[u8]) -> Vec<u8> {
    let mut aad = Vec::with_capacity(RECORD_AAD_PREFIX.len() + index.len());
    aad.extend_from_slice(RECORD_AAD_PREFIX);
    aad.extend_from_slice(index);
    aad
}

// ---------------------------------------------------------------------------
// Публичный алиас (`@ник`) — ОПЦИОНАЛЬНЫЙ, перечислимый по построению
// ---------------------------------------------------------------------------

/// Нормализует публичный алиас или возвращает ошибку, если он недопустим.
///
/// Правила намеренно жёсткие: только ASCII `[a-z0-9_.]`, длина
/// [`ALIAS_MIN_LEN`]..=[`ALIAS_MAX_LEN`], без ведущих/замыкающих и сдвоенных
/// разделителей. Не-ASCII запрещён СПЕЦИАЛЬНО: в публичном пространстве имён
/// схожие по виду символы Unicode (homoglyph/confusable) — готовый фишинг
/// («раyраl» вместо «paypal»), а нормализация NFKC его не устраняет.
pub fn normalize_alias(alias: &str) -> Result<String, AegisError> {
    let trimmed = alias.trim().trim_start_matches('@');
    if !trimmed.is_ascii() {
        return Err(AegisError::InvalidArg(
            "алиас: только латиница, цифры, «_» и «.» (не-ASCII запрещён из-за схожих символов)"
                .into(),
        ));
    }
    let lower = trimmed.to_ascii_lowercase();
    if !(ALIAS_MIN_LEN..=ALIAS_MAX_LEN).contains(&lower.len()) {
        return Err(AegisError::InvalidArg(format!(
            "алиас должен быть длиной {ALIAS_MIN_LEN}..{ALIAS_MAX_LEN}, получено {}",
            lower.len()
        )));
    }
    let bytes = lower.as_bytes();
    let is_sep = |b: u8| b == b'_' || b == b'.';
    if is_sep(bytes[0]) || is_sep(bytes[bytes.len() - 1]) {
        return Err(AegisError::InvalidArg(
            "алиас не может начинаться или заканчиваться на «_» или «.»".into(),
        ));
    }
    let mut prev_sep = false;
    for &b in bytes {
        let ok = b.is_ascii_lowercase() || b.is_ascii_digit() || is_sep(b);
        if !ok {
            return Err(AegisError::InvalidArg(
                "алиас: допустимы только латиница, цифры, «_» и «.»".into(),
            ));
        }
        if is_sep(b) && prev_sep {
            return Err(AegisError::InvalidArg(
                "алиас: два разделителя подряд не допускаются".into(),
            ));
        }
        prev_sep = is_sep(b);
    }
    Ok(lower)
}

/// Индекс публичного алиаса в каталоге: `HKDF-SHA512(salt, ikm=normalize(alias))`.
///
/// ЧЕСТНО: алиас низкоэнтропиен, поэтому индекс перечислим словарной атакой —
/// это неотъемлемое свойство ПУБЛИЧНОГО имени, а не дефект. Сервер прикрывает
/// перечисление proof-of-work и лимитами, а сама запись алиаса не содержит
/// ключей — только указатель на приватный каталог (см. `docs/contact_requests.md`).
pub fn alias_index(alias: &str) -> Result<[u8; INDEX_LEN], AegisError> {
    let normalized = normalize_alias(alias)?;
    let hk = Hkdf::<Sha512>::new(Some(ALIAS_SALT), normalized.as_bytes());
    let mut out = [0u8; INDEX_LEN];
    hk.expand(INFO_ALIAS_INDEX, &mut out)
        .map_err(|_| AegisError::Crypto("HKDF expand (alias index) failed".into()))?;
    Ok(out)
}

// ---------------------------------------------------------------------------
// Рукопожатие RCH: transcript и вывод секрета слоя A
// ---------------------------------------------------------------------------

/// Публичная часть нагрузки одной стороны рукопожатия.
///
/// Все поля приходят из ПОДПИСАННОЙ Ed25519 нагрузки (`INVITE`/`ACCEPT`), поэтому
/// подмена любого из них обнаруживается проверкой подписи ещё до вызова [`transcript`].
pub struct Party<'a> {
    /// `aegis:<32 hex>` — коммитмент к [`Party::ed25519_pub`].
    pub account_id: &'a str,
    /// Публичный Ed25519-ключ аккаунта (32 байта).
    pub ed25519_pub: &'a [u8],
    /// Публичный эфемерный X25519-ключ раунда (32 байта).
    pub epk_pub: &'a [u8],
    /// Случайный вклад стороны в секрет (32 байта).
    pub contrib: &'a [u8],
    /// Nonce стороны (16 байт).
    pub nonce: &'a [u8],
}

impl Party<'_> {
    fn validate(&self, who: &str) -> Result<(), AegisError> {
        if self.account_id.is_empty() {
            return Err(AegisError::InvalidArg(format!("{who}: пустой accountId")));
        }
        expect_len(self.ed25519_pub, 32, &format!("{who}: ed25519"))?;
        expect_len(self.epk_pub, X25519_LEN, &format!("{who}: эфемерный X25519"))?;
        expect_len(self.contrib, CONTRIB_LEN, &format!("{who}: contrib"))?;
        expect_len(self.nonce, NONCE_LEN, &format!("{who}: nonce"))?;
        Ok(())
    }
}

fn expect_len(bytes: &[u8], want: usize, what: &str) -> Result<(), AegisError> {
    if bytes.len() != want {
        return Err(AegisError::InvalidArg(format!(
            "{what}: ожидалось {want} байт, получено {}",
            bytes.len()
        )));
    }
    Ok(())
}

/// Каноничный порядок сторон: по `accountId` лексикографически. Обе стороны
/// получают одинаковую пару `(lo, hi)` независимо от того, кто инициатор.
fn order<'p, 'a>(a: &'p Party<'a>, b: &'p Party<'a>) -> (&'p Party<'a>, &'p Party<'a>) {
    if a.account_id <= b.account_id {
        (a, b)
    } else {
        (b, a)
    }
}

/// Хеш transcript рукопожатия (SHA-512, 64 байта).
///
/// Связывает версию протокола, оба `accountId`, оба Ed25519-ключа, оба
/// эфемерных ключа, публичный ключ и шифртекст KEM, оба вклада и оба nonce.
/// Длины фиксированы (кроме `accountId`, который префиксуется своей длиной),
/// поэтому конкатенация однозначна — «сдвинуть» границу поля нельзя.
pub fn transcript(
    a: &Party,
    b: &Party,
    kem_pub: &[u8],
    kem_ct: &[u8],
) -> Result<[u8; 64], AegisError> {
    a.validate("сторона A")?;
    b.validate("сторона B")?;
    if a.account_id == b.account_id {
        return Err(AegisError::InvalidArg(
            "рукопожатие с самим собой: accountId сторон совпадают".into(),
        ));
    }
    let (lo, hi) = order(a, b);

    let mut h = Sha512::new();
    h.update(RCH_TRANSCRIPT_LABEL);
    for party in [lo, hi] {
        // accountId переменной длины — префиксуем длиной (u16 BE), чтобы
        // конкатенация оставалась однозначной.
        let id = party.account_id.as_bytes();
        h.update((id.len() as u16).to_be_bytes());
        h.update(id);
    }
    for party in [lo, hi] {
        h.update(party.ed25519_pub);
    }
    for party in [lo, hi] {
        h.update(party.epk_pub);
    }
    h.update((kem_pub.len() as u32).to_be_bytes());
    h.update(kem_pub);
    h.update((kem_ct.len() as u32).to_be_bytes());
    h.update(kem_ct);
    for party in [lo, hi] {
        h.update(party.contrib);
    }
    for party in [lo, hi] {
        h.update(party.nonce);
    }
    Ok(h.finalize().into())
}

/// Выводит секрет контейнера слоя A (аналог PSK) из рукопожатия RCH.
///
/// `local_x25519_secret` — статический X25519 аккаунта ([`crate::identity`]);
/// `local_epk_secret` — эфемерный секрет раунда; `kem_shared` — общий секрет
/// Kyber1024 (у ответчика из инкапсуляции, у инициатора из декапсуляции).
/// Результат детерминирован и одинаков у обеих сторон.
#[allow(clippy::too_many_arguments)]
pub fn container_secret(
    local: &Party,
    remote: &Party,
    local_x25519_secret: &[u8],
    remote_x25519_pub: &[u8],
    local_epk_secret: &[u8],
    kem_pub: &[u8],
    kem_ct: &[u8],
    kem_shared: &[u8],
) -> Result<Zeroizing<[u8; 32]>, AegisError> {
    expect_len(local_x25519_secret, X25519_LEN, "свой статический X25519")?;
    expect_len(remote_x25519_pub, X25519_LEN, "чужой статический X25519")?;
    expect_len(local_epk_secret, X25519_LEN, "свой эфемерный X25519")?;
    if kem_shared.is_empty() {
        return Err(AegisError::InvalidArg("пустой общий секрет KEM".into()));
    }

    let tr = transcript(local, remote, kem_pub, kem_ct)?;

    // dh_ee — эфемерный↔эфемерный: forward secrecy (секреты раунда удаляются).
    let dh_ee = {
        let sk = StaticSecret::from(to_array32(local_epk_secret)?);
        let pk = XPublicKey::from(to_array32(remote.epk_pub)?);
        Zeroizing::new(*sk.diffie_hellman(&pk).as_bytes())
    };
    // dh_ss — статический↔статический: аутентификация, привязанная к accountId.
    let dh_ss = {
        let sk = StaticSecret::from(to_array32(local_x25519_secret)?);
        let pk = XPublicKey::from(to_array32(remote_x25519_pub)?);
        Zeroizing::new(*sk.diffie_hellman(&pk).as_bytes())
    };

    // Оба DH обязаны быть ненулевыми: нулевой результат = вырожденная точка
    // (порядок ≤ 8) в чужом ключе. Активный противник, подсунувший такой ключ,
    // иначе зафиксировал бы вклад DH в нуль.
    if dh_ee.iter().all(|&b| b == 0) || dh_ss.iter().all(|&b| b == 0) {
        return Err(AegisError::Crypto(
            "X25519 дал нулевой общий секрет (вырожденный публичный ключ) — рукопожатие отклонено"
                .into(),
        ));
    }

    let (lo, hi) = order(local, remote);
    let mut ikm = Zeroizing::new(Vec::with_capacity(
        dh_ee.len() + dh_ss.len() + kem_shared.len() + CONTRIB_LEN * 2,
    ));
    ikm.extend_from_slice(dh_ee.as_ref());
    ikm.extend_from_slice(dh_ss.as_ref());
    ikm.extend_from_slice(kem_shared);
    ikm.extend_from_slice(lo.contrib);
    ikm.extend_from_slice(hi.contrib);

    let info = format!("{RCH_INFO_PREFIX}{}", to_hex(&tr));
    let hk = Hkdf::<Sha512>::new(Some(RCH_SALT), &ikm);
    let mut out = Zeroizing::new([0u8; 32]);
    hk.expand(info.as_bytes(), out.as_mut())
        .map_err(|_| AegisError::Crypto("HKDF expand (RCH container secret) failed".into()))?;
    Ok(out)
}

/// Короткий код рукопожатия из transcript — шесть групп по пять цифр, как
/// safety-number. Одинаков у обеих сторон; удобен для устной сверки сразу после
/// активации контакта, до полноценной верификации отпечатка (§14.3).
pub fn handshake_code(tr: &[u8; 64]) -> String {
    tr.chunks(5)
        .take(6)
        .map(|chunk| {
            let mut acc: u64 = 0;
            for &byte in chunk {
                acc = (acc << 8) | byte as u64;
            }
            format!("{:05}", acc % 100_000)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Генерирует эфемерную пару X25519 для одного раунда рукопожатия.
/// Секрет ОБЯЗАН быть сохранён инициатором до получения ответа (собеседник
/// может принять приглашение через сутки) и затёрт сразу после вывода секрета.
pub fn generate_ephemeral() -> Result<(Zeroizing<[u8; 32]>, [u8; 32]), AegisError> {
    let mut seed = Zeroizing::new([0u8; X25519_LEN]);
    getrandom::getrandom(seed.as_mut())
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    let sk = StaticSecret::from(*seed);
    let pk = XPublicKey::from(&sk);
    Ok((Zeroizing::new(sk.to_bytes()), pk.to_bytes()))
}

/// Генерирует случайные `contrib` (32 байта) и `nonce` (16 байт) нагрузки.
pub fn generate_contrib_nonce() -> Result<(Zeroizing<Vec<u8>>, Vec<u8>), AegisError> {
    let mut contrib = Zeroizing::new(vec![0u8; CONTRIB_LEN]);
    getrandom::getrandom(contrib.as_mut_slice())
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    let mut nonce = vec![0u8; NONCE_LEN];
    getrandom::getrandom(&mut nonce)
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    Ok((contrib, nonce))
}

fn to_array32(bytes: &[u8]) -> Result<[u8; 32], AegisError> {
    expect_len(bytes, 32, "X25519-ключ")?;
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    Ok(out)
}

fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        let _ = write!(s, "{b:02x}");
    }
    s
}

// ---------------------------------------------------------------------------
// Kyber1024 (PQ-составляющая) — из аудированной libsignal, уже в графе сборки
// ---------------------------------------------------------------------------

/// Пара ключей KEM инициатора. Публичный ключ едет в `INVITE`, секретный
/// сохраняется до получения `ACCEPT` (в SQLCipher) и затирается после вывода.
#[cfg(feature = "signal")]
pub struct KemKeyPair {
    pub public: Vec<u8>,
    pub secret: Zeroizing<Vec<u8>>,
}

/// Генерирует пару Kyber1024 для раунда рукопожатия.
#[cfg(feature = "signal")]
pub fn kem_generate() -> Result<KemKeyPair, AegisError> {
    use libsignal_protocol::kem;
    use rand::TryRngCore as _;

    let mut rng = rand::rngs::OsRng.unwrap_err();
    let pair = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
    Ok(KemKeyPair {
        public: pair.public_key.serialize().into_vec(),
        secret: Zeroizing::new(pair.secret_key.serialize().into_vec()),
    })
}

/// Инкапсулирует общий секрет в публичный ключ инициатора (сторона-ответчик).
/// Возвращает `(общий_секрет, шифртекст)`; шифртекст едет в `ACCEPT`.
#[cfg(feature = "signal")]
pub fn kem_encapsulate(public: &[u8]) -> Result<(Zeroizing<Vec<u8>>, Vec<u8>), AegisError> {
    use libsignal_protocol::kem;
    use rand::TryRngCore as _;

    let key = kem::PublicKey::deserialize(public)
        .map_err(|e| AegisError::InvalidArg(format!("некорректный публичный ключ KEM: {e}")))?;
    let mut rng = rand::rngs::OsRng.unwrap_err();
    let (shared, ciphertext) = key
        .encapsulate(&mut rng)
        .map_err(|e| AegisError::Crypto(format!("сбой инкапсуляции KEM: {e}")))?;
    Ok((
        Zeroizing::new(shared.into_vec()),
        ciphertext.into_vec(),
    ))
}

/// Декапсулирует общий секрет из шифртекста ответчика (сторона-инициатор).
#[cfg(feature = "signal")]
pub fn kem_decapsulate(secret: &[u8], ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    use libsignal_protocol::kem;

    let key = kem::SecretKey::deserialize(secret)
        .map_err(|e| AegisError::InvalidArg(format!("некорректный секретный ключ KEM: {e}")))?;
    let shared = key
        .decapsulate(&ciphertext.to_vec().into_boxed_slice())
        .map_err(|e| AegisError::Crypto(format!("сбой декапсуляции KEM: {e}")))?;
    Ok(Zeroizing::new(shared.into_vec()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tag() -> Vec<u8> {
        generate_tag().unwrap().to_vec()
    }

    #[test]
    fn discovery_is_deterministic_and_domain_separated() {
        let t = tag();
        let a = derive_discovery(&t).unwrap();
        let b = derive_discovery(&t).unwrap();
        assert_eq!(a.index, b.index);
        assert_eq!(a.record_key.as_ref(), b.record_key.as_ref());
        // Индекс и ключ записи — разные домены.
        assert_ne!(&a.index[..], a.record_key.as_ref());
        // Другой тег → другой индекс (перебор без тега невозможен).
        let other = derive_discovery(&tag()).unwrap();
        assert_ne!(a.index, other.index);
        // Отделено от PSK-доменов (v1/v2 mailbox и отпечаток).
        assert_ne!(&a.index[..], &crate::psk::mailbox_id(&t, 0).unwrap()[..]);
        assert_ne!(&a.index[..], &crate::psk::fingerprint(&t).unwrap()[..]);
    }

    #[test]
    fn discovery_rejects_wrong_tag_length() {
        assert!(derive_discovery(&[0u8; 16]).is_err());
        assert!(derive_discovery(&[0u8; 33]).is_err());
        assert!(tag_commit(b"short").is_err());
    }

    #[test]
    fn tag_commit_is_deterministic_and_hides_tag() {
        let t = tag();
        assert_eq!(tag_commit(&t).unwrap(), tag_commit(&t).unwrap());
        assert_ne!(&tag_commit(&t).unwrap()[..], &t[..]);
        assert_ne!(tag_commit(&t).unwrap(), tag_commit(&tag()).unwrap());
    }

    #[test]
    fn record_seals_and_binds_to_index() {
        let t = tag();
        let ids = derive_discovery(&t).unwrap();
        let payload = br#"{"v":1,"accountId":"aegis:dead"}"#;
        let blob = seal_record(ids.record_key.as_ref(), &ids.index, payload).unwrap();
        let out = open_record(ids.record_key.as_ref(), &ids.index, &blob).unwrap();
        assert_eq!(out.as_slice(), payload);

        // Чужой ключ (другой тег) не откроет.
        let other = derive_discovery(&tag()).unwrap();
        assert!(open_record(other.record_key.as_ref(), &ids.index, &blob).is_err());
        // Перестановка на другой индекс не проходит (AAD привязан к индексу).
        assert!(open_record(ids.record_key.as_ref(), &other.index, &blob).is_err());
    }

    #[test]
    fn alias_normalization_rules() {
        assert_eq!(normalize_alias("  @Alice  ").unwrap(), "alice");
        assert_eq!(normalize_alias("a_b.c1").unwrap(), "a_b.c1");
        // Слишком короткий/длинный.
        assert!(normalize_alias("ab").is_err());
        assert!(normalize_alias(&"a".repeat(33)).is_err());
        // Не-ASCII запрещён (защита от схожих символов).
        assert!(normalize_alias("алиса").is_err());
        assert!(normalize_alias("pаypal").is_err());
        // Разделители по краям и сдвоенные.
        assert!(normalize_alias("_abc").is_err());
        assert!(normalize_alias("abc.").is_err());
        assert!(normalize_alias("a..b").is_err());
        assert!(normalize_alias("a_.b").is_err());
        // Прочие символы.
        assert!(normalize_alias("a-b").is_err());
        assert!(normalize_alias("a b").is_err());
        assert!(normalize_alias("a@b").is_err());
    }

    #[test]
    fn alias_index_is_deterministic_and_case_insensitive() {
        assert_eq!(alias_index("Alice").unwrap(), alias_index("@alice").unwrap());
        assert_ne!(alias_index("alice").unwrap(), alias_index("alice2").unwrap());
        // Отделён от домена тега: индекс алиаса ≠ индекс тега тех же байт.
        let t = tag();
        let ids = derive_discovery(&t).unwrap();
        assert_ne!(alias_index("alice").unwrap(), ids.index);
    }

    /// Полный раунд RCH: обе стороны выводят ОДИН секрет слоя A.
    #[test]
    #[cfg(feature = "signal")]
    fn rch_both_sides_derive_same_secret() {
        // Статические ключи аккаунтов.
        let (a_static, a_static_pub) = generate_ephemeral().unwrap();
        let (b_static, b_static_pub) = generate_ephemeral().unwrap();
        // Эфемерные ключи раунда.
        let (a_epk, a_epk_pub) = generate_ephemeral().unwrap();
        let (b_epk, b_epk_pub) = generate_ephemeral().unwrap();
        let (a_contrib, a_nonce) = generate_contrib_nonce().unwrap();
        let (b_contrib, b_nonce) = generate_contrib_nonce().unwrap();
        let a_ed = [0xAAu8; 32];
        let b_ed = [0xBBu8; 32];

        // Инициатор публикует ключ KEM, ответчик инкапсулирует.
        let kem = kem_generate().unwrap();
        let (b_kem_shared, kem_ct) = kem_encapsulate(&kem.public).unwrap();
        let a_kem_shared = kem_decapsulate(&kem.secret, &kem_ct).unwrap();
        assert_eq!(a_kem_shared.as_slice(), b_kem_shared.as_slice());

        let party_a = Party {
            account_id: "aegis:00000000000000000000000000000001",
            ed25519_pub: &a_ed,
            epk_pub: &a_epk_pub,
            contrib: &a_contrib,
            nonce: &a_nonce,
        };
        let party_b = Party {
            account_id: "aegis:00000000000000000000000000000002",
            ed25519_pub: &b_ed,
            epk_pub: &b_epk_pub,
            contrib: &b_contrib,
            nonce: &b_nonce,
        };

        let secret_a = container_secret(
            &party_a, &party_b, a_static.as_ref(), &b_static_pub, a_epk.as_ref(),
            &kem.public, &kem_ct, &a_kem_shared,
        )
        .unwrap();
        let secret_b = container_secret(
            &party_b, &party_a, b_static.as_ref(), &a_static_pub, b_epk.as_ref(),
            &kem.public, &kem_ct, &b_kem_shared,
        )
        .unwrap();

        assert_eq!(secret_a.as_ref(), secret_b.as_ref(), "секрет слоя A должен совпасть");
        assert_ne!(secret_a.as_ref(), &[0u8; 32], "секрет не может быть нулевым");

        // Секрет годится как ключ каскада слоя A (§6.2 → container_secret).
        let container =
            crate::cascade::seal(secret_a.as_ref(), b"remote contact works", b"aegis/packet/v1")
                .unwrap();
        let out =
            crate::cascade::open(secret_b.as_ref(), &container, b"aegis/packet/v1").unwrap();
        assert_eq!(out.as_slice(), b"remote contact works");
    }

    /// Transcript симметричен (порядок аргументов не важен) и код совпадает.
    #[test]
    fn transcript_is_symmetric() {
        let (_, a_epk_pub) = generate_ephemeral().unwrap();
        let (_, b_epk_pub) = generate_ephemeral().unwrap();
        let a_ed = [1u8; 32];
        let b_ed = [2u8; 32];
        let a = Party {
            account_id: "aegis:aaaa",
            ed25519_pub: &a_ed,
            epk_pub: &a_epk_pub,
            contrib: &[3u8; CONTRIB_LEN],
            nonce: &[4u8; NONCE_LEN],
        };
        let b = Party {
            account_id: "aegis:bbbb",
            ed25519_pub: &b_ed,
            epk_pub: &b_epk_pub,
            contrib: &[5u8; CONTRIB_LEN],
            nonce: &[6u8; NONCE_LEN],
        };
        let t1 = transcript(&a, &b, b"kempub", b"kemct").unwrap();
        let t2 = transcript(&b, &a, b"kempub", b"kemct").unwrap();
        assert_eq!(t1, t2);
        assert_eq!(handshake_code(&t1), handshake_code(&t2));
        assert_eq!(handshake_code(&t1).split(' ').count(), 6);
    }

    /// Подмена ЛЮБОГО поля меняет transcript ⇒ стороны не сойдутся по секрету.
    #[test]
    fn transcript_binds_every_field() {
        let (_, a_epk_pub) = generate_ephemeral().unwrap();
        let (_, b_epk_pub) = generate_ephemeral().unwrap();
        let (_, evil_epk_pub) = generate_ephemeral().unwrap();
        let a_ed = [1u8; 32];
        let b_ed = [2u8; 32];
        let mk = |epk: &[u8; 32], contrib: u8, nonce: u8, kem_ct: &[u8]| {
            let a = Party {
                account_id: "aegis:aaaa",
                ed25519_pub: &a_ed,
                epk_pub: &a_epk_pub,
                contrib: &[3u8; CONTRIB_LEN],
                nonce: &[4u8; NONCE_LEN],
            };
            let b_contrib = [contrib; CONTRIB_LEN];
            let b_nonce = [nonce; NONCE_LEN];
            let b = Party {
                account_id: "aegis:bbbb",
                ed25519_pub: &b_ed,
                epk_pub: epk,
                contrib: &b_contrib,
                nonce: &b_nonce,
            };
            transcript(&a, &b, b"kempub", kem_ct).unwrap()
        };
        let base = mk(&b_epk_pub, 5, 6, b"kemct");
        assert_ne!(base, mk(&evil_epk_pub, 5, 6, b"kemct"), "подмена epk");
        assert_ne!(base, mk(&b_epk_pub, 9, 6, b"kemct"), "подмена contrib");
        assert_ne!(base, mk(&b_epk_pub, 5, 9, b"kemct"), "подмена nonce");
        assert_ne!(base, mk(&b_epk_pub, 5, 6, b"evilct"), "подмена шифртекста KEM");
    }

    #[test]
    fn transcript_rejects_self_handshake_and_bad_lengths() {
        let (_, epk) = generate_ephemeral().unwrap();
        let ed = [1u8; 32];
        let same = || Party {
            account_id: "aegis:aaaa",
            ed25519_pub: &ed,
            epk_pub: &epk,
            contrib: &[3u8; CONTRIB_LEN],
            nonce: &[4u8; NONCE_LEN],
        };
        assert!(transcript(&same(), &same(), b"k", b"c").is_err());

        let short = Party {
            account_id: "aegis:bbbb",
            ed25519_pub: &ed,
            epk_pub: &epk,
            contrib: &[3u8; 8], // неверная длина
            nonce: &[4u8; NONCE_LEN],
        };
        assert!(transcript(&same(), &short, b"k", b"c").is_err());
    }

    /// Вырожденный (low-order) чужой X25519-ключ должен отвергаться, а не
    /// давать предсказуемый нулевой вклад в ikm.
    #[test]
    fn rejects_degenerate_x25519_public_key() {
        let (a_static, _) = generate_ephemeral().unwrap();
        let (a_epk, a_epk_pub) = generate_ephemeral().unwrap();
        let zero = [0u8; 32];
        let a_ed = [1u8; 32];
        let b_ed = [2u8; 32];
        let a = Party {
            account_id: "aegis:aaaa",
            ed25519_pub: &a_ed,
            epk_pub: &a_epk_pub,
            contrib: &[3u8; CONTRIB_LEN],
            nonce: &[4u8; NONCE_LEN],
        };
        let b = Party {
            account_id: "aegis:bbbb",
            ed25519_pub: &b_ed,
            epk_pub: &zero, // вырожденная точка
            contrib: &[5u8; CONTRIB_LEN],
            nonce: &[6u8; NONCE_LEN],
        };
        let err = container_secret(
            &a, &b, a_static.as_ref(), &zero, a_epk.as_ref(), b"kempub", b"kemct", b"kemshared",
        );
        assert!(err.is_err(), "нулевой общий секрет X25519 должен быть отвергнут");
    }

    #[test]
    #[cfg(feature = "signal")]
    fn kem_rejects_tampered_ciphertext_and_bad_keys() {
        let kem = kem_generate().unwrap();
        let (_, mut ct) = kem_encapsulate(&kem.public).unwrap();
        let last = ct.len() - 1;
        ct[last] ^= 0xff;
        // Kyber неявно отвергает: либо ошибка, либо ДРУГОЙ общий секрет.
        match kem_decapsulate(&kem.secret, &ct) {
            Err(_) => {}
            Ok(shared) => {
                let (orig, orig_ct) = kem_encapsulate(&kem.public).unwrap();
                assert_ne!(shared.as_slice(), orig.as_slice());
                let _ = orig_ct;
            }
        }
        assert!(kem_encapsulate(b"not a key").is_err());
        assert!(kem_decapsulate(b"not a key", &[0u8; 16]).is_err());
    }
}
