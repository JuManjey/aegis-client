//! Слой A — каскадный контейнер максимальной криптостойкости (P3, §4–§5, §10.2).
//!
//! Каждое сообщение AEGIS шифруется как ВЛОЖЕННЫЙ контейнер из четырёх
//! НЕЗАВИСИМЫХ AEAD-шифров. Компрометация (или гипотетическая слабость) одного
//! шифра не раскрывает контент — атакующему пришлось бы вскрыть все четыре слоя
//! на четырёх независимых ключах.
//!
//! Порядок шифрования (изнутри наружу):
//!   1) XChaCha20-Poly1305  (ключ K1)                — 192-бит nonce, AEAD
//!   2) AES-256-GCM         (ключ K2)                — 96-бит nonce, AEAD
//!   3) Camellia-256 в EAX  (ключ K3)                — 128-бит nonce, AEAD
//!   4) Twofish-256 в EAX   (ключ K4)                — 128-бит nonce, AEAD
//! Расшифровка — строго в обратном порядке (Twofish → Camellia → AES → XChaCha20).
//!
//! Требования ТЗ, реализованные здесь:
//!  * Свою криптографию НЕ пишем (§1): только аудированные крейты RustCrypto
//!    (`chacha20poly1305`, `aes-gcm`, `eax` + `camellia`/`twofish`).
//!  * Каждый шаг обрабатывает выход предыдущего (вложенное шифрование), AEAD-тег
//!    на КАЖДОМ слое; при провале любого тега расшифровка немедленно прекращается.
//!  * НЕ сжимаем данные между слоями (риск CRIME, §4) — просто конкатенация.
//!  * Ключи K1..K4 — независимые 256-бит, выводятся HKDF-SHA512 из общего
//!    секрета контейнера с РАЗДЕЛЕНИЕМ ДОМЕНОВ (разные info-метки; они совпадают
//!    с Kotlin-моделью `CascadeSpec`).
//!  * Nonce каждого слоя — случайный (ОС-ГСЧ), генерируется на каждое сообщение
//!    и НИКОГДА не переиспользуется.
//!  * Ключ контейнера из парольной/мнемонической фразы выводится через Argon2id
//!    ([`crate::kdf`]) — см. [`derive_keys_from_passphrase`].
//!
//! Обоснование выбора EAX (§5 разрешает «AEAD-конструкцию» для сторонних
//! шифров): у Camellia и Twofish нет «родного» AEAD-режима в экосистеме
//! RustCrypto, а GCM-SIV/GCM реализованы только для AES. EAX (RustCrypto `eax`,
//! аудированная two-pass AEAD-конструкция на базе OMAC/CMAC) обобщён над любым
//! 128-битным блочным шифром и даёт настоящий тег целостности на каждом слое.
//!
//! ЗАМЕНА Serpent-256 → Camellia-256 (обоснование, §5 «или обоснуй замену»):
//! крейт RustCrypto `serpent` во всех версиях фиксирует `KeySize = U16` (ключ
//! только 128 бит), поэтому настоящий Serpent-256 через аудированный RustCrypto
//! недостижим. Camellia-256 (RFC 3713 / ISO-IEC 18033-3 / NESSIE / CRYPTREC) —
//! 256-битный ключ, отдельное семейство от AES/Twofish; каскад остаётся «4×256
//! бит независимых слоёв». Решение согласовано с владельцем.

use aead::generic_array::typenum::Unsigned;
use aead::{Aead, AeadCore, KeyInit, Nonce, Payload};
use hkdf::Hkdf;
use sha2::Sha512;
use zeroize::Zeroizing;

use crate::error::AegisError;

/// Типы четырёх AEAD-слоёв каскада (все через единый трейт `aead::Aead`).
type Layer1 = chacha20poly1305::XChaCha20Poly1305; // K1, nonce 24 байта
type Layer2 = aes_gcm::Aes256Gcm; // K2, nonce 12 байт
type Layer3 = eax::Eax<camellia::Camellia256>; // K3, nonce 16 байт
type Layer4 = eax::Eax<twofish::Twofish>; // K4, nonce 16 байт

/// Магия формата контейнера (см. модульную документацию `wire`-формата).
const MAGIC: [u8; 4] = *b"AGSC"; // AeGiS Cascade
/// Версия формата контейнера — для согласованной эволюции клиент/сервер (§0).
pub const FORMAT_VERSION: u8 = 1;
/// Длина заголовка контейнера: MAGIC(4) || VERSION(1).
const HEADER_LEN: usize = 5;

/// Длина ключа каждого слоя (256 бит, §5).
pub const KEY_LEN: usize = 32;

// Метки разделения доменов HKDF-SHA512 — по одной на слой (§5). ДОЛЖНЫ совпадать
// с `CascadeSpec.hkdfInfoLabels` на стороне Kotlin (единый контракт вывода ключей).
const HKDF_SALT: &[u8] = b"AEGIS/cascade/v1";
const INFO_K1: &[u8] = b"aegis/cascade/v1/k1-xchacha20";
const INFO_K2: &[u8] = b"aegis/cascade/v1/k2-aes256";
const INFO_K3: &[u8] = b"aegis/cascade/v1/k3-camellia256";
const INFO_K4: &[u8] = b"aegis/cascade/v1/k4-twofish256";

/// Заголовок контейнера (MAGIC || VERSION). Аутентифицируется на КАЖДОМ слое
/// (входит в AAD), чтобы подмена версии/магии ломала проверку тегов.
fn header() -> [u8; HEADER_LEN] {
    [MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], FORMAT_VERSION]
}

/// Четыре независимых 256-битных ключа слоёв каскада. Все затираются из памяти
/// при выходе из скоупа ([`Zeroizing`]).
pub struct CascadeKeys {
    k1: Zeroizing<[u8; KEY_LEN]>,
    k2: Zeroizing<[u8; KEY_LEN]>,
    k3: Zeroizing<[u8; KEY_LEN]>,
    k4: Zeroizing<[u8; KEY_LEN]>,
}

impl CascadeKeys {
    /// Выводит K1..K4 из общего секрета контейнера через HKDF-SHA512 с
    /// разделением доменов (разные info-метки → некоррелированные ключи, §5).
    ///
    /// `container_secret` — общий секрет (обычно 32-байтовый PSK, обменянный
    /// вручную §6.2, ЛИБО выход Argon2id из парольной фразы).
    pub fn derive(container_secret: &[u8]) -> Result<Self, AegisError> {
        if container_secret.len() < 16 {
            return Err(AegisError::InvalidArg(
                "секрет контейнера должен быть >= 16 байт".into(),
            ));
        }
        let hk = Hkdf::<Sha512>::new(Some(HKDF_SALT), container_secret);
        let expand = |info: &[u8]| -> Result<Zeroizing<[u8; KEY_LEN]>, AegisError> {
            let mut key = Zeroizing::new([0u8; KEY_LEN]);
            hk.expand(info, key.as_mut())
                .map_err(|_| AegisError::Crypto("HKDF expand (cascade key) failed".into()))?;
            Ok(key)
        };
        Ok(Self {
            k1: expand(INFO_K1)?,
            k2: expand(INFO_K2)?,
            k3: expand(INFO_K3)?,
            k4: expand(INFO_K4)?,
        })
    }
}

// ---------------------------------------------------------------------------
// Обобщённые операции одного AEAD-слоя.
//
// На выходе шифрования — `nonce || ciphertext_with_tag`. Nonce случайный,
// нужной для конкретного шифра длины, и хранится рядом с шифртекстом (nonce не
// секретен, но обязан быть уникальным — это гарантирует ОС-ГСЧ на каждое
// сообщение). AAD аутентифицируется, но не шифруется.
// ---------------------------------------------------------------------------

fn seal_layer<C>(key: &[u8], plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>, AegisError>
where
    C: Aead + KeyInit,
{
    let cipher =
        C::new_from_slice(key).map_err(|_| AegisError::Crypto("некорректная длина ключа слоя".into()))?;

    let nonce_len = <C as AeadCore>::NonceSize::USIZE;
    let mut nonce = vec![0u8; nonce_len];
    getrandom::getrandom(&mut nonce).map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    let nonce_ga = Nonce::<C>::from_slice(&nonce);

    let ciphertext = cipher
        .encrypt(nonce_ga, Payload { msg: plaintext, aad })
        .map_err(|_| AegisError::Crypto("сбой AEAD-шифрования слоя".into()))?;

    let mut out = Vec::with_capacity(nonce_len + ciphertext.len());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

fn open_layer<C>(key: &[u8], blob: &[u8], aad: &[u8]) -> Result<Vec<u8>, AegisError>
where
    C: Aead + KeyInit,
{
    let cipher =
        C::new_from_slice(key).map_err(|_| AegisError::Crypto("некорректная длина ключа слоя".into()))?;

    let nonce_len = <C as AeadCore>::NonceSize::USIZE;
    if blob.len() < nonce_len {
        return Err(AegisError::InvalidArg("слой короче nonce".into()));
    }
    let (nonce, ciphertext) = blob.split_at(nonce_len);
    let nonce_ga = Nonce::<C>::from_slice(nonce);

    cipher
        .decrypt(nonce_ga, Payload { msg: ciphertext, aad })
        // При провале тега ЛЮБОГО слоя расшифровка прекращается (§5).
        .map_err(|_| AegisError::Crypto("сбой проверки AEAD-тега слоя (данные/ключ повреждены)".into()))
}

// ---------------------------------------------------------------------------
// Публичный API каскада.
// ---------------------------------------------------------------------------

/// Шифрует `plaintext` в каскадный контейнер на ключах, выведенных из
/// `container_secret`. `associated_data` аутентифицируется на всех слоях, но не
/// шифруется. Возвращает байты контейнера (`MAGIC || VERSION || вложенный AEAD`).
pub fn seal(
    container_secret: &[u8],
    plaintext: &[u8],
    associated_data: &[u8],
) -> Result<Vec<u8>, AegisError> {
    let keys = CascadeKeys::derive(container_secret)?;
    seal_with_keys(&keys, plaintext, associated_data)
}

/// Расшифровывает каскадный контейнер. При провале проверки тега любого слоя
/// либо несовпадении заголовка/версии — [`AegisError::Crypto`]/`InvalidArg`.
/// Результат — в [`Zeroizing`] (затирается при выходе из скоупа).
pub fn open(
    container_secret: &[u8],
    container: &[u8],
    associated_data: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    let keys = CascadeKeys::derive(container_secret)?;
    open_with_keys(&keys, container, associated_data)
}

/// Как [`seal`], но принимает уже выведенные ключи (для тестов/повторного
/// использования расписания ключей без повторного HKDF).
pub fn seal_with_keys(
    keys: &CascadeKeys,
    plaintext: &[u8],
    associated_data: &[u8],
) -> Result<Vec<u8>, AegisError> {
    // Эффективный AAD = заголовок контейнера || пользовательский AAD.
    let mut aad = Vec::with_capacity(HEADER_LEN + associated_data.len());
    aad.extend_from_slice(&header());
    aad.extend_from_slice(associated_data);

    // Вложенное шифрование: выход слоя N — вход слоя N+1. Без сжатия (§4).
    let b1 = seal_layer::<Layer1>(keys.k1.as_ref(), plaintext, &aad)?;
    let b2 = seal_layer::<Layer2>(keys.k2.as_ref(), &b1, &aad)?;
    let b3 = seal_layer::<Layer3>(keys.k3.as_ref(), &b2, &aad)?;
    let b4 = seal_layer::<Layer4>(keys.k4.as_ref(), &b3, &aad)?;

    let mut out = Vec::with_capacity(HEADER_LEN + b4.len());
    out.extend_from_slice(&header());
    out.extend_from_slice(&b4);
    Ok(out)
}

/// Как [`open`], но принимает уже выведенные ключи.
pub fn open_with_keys(
    keys: &CascadeKeys,
    container: &[u8],
    associated_data: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    if container.len() < HEADER_LEN {
        return Err(AegisError::InvalidArg("контейнер короче заголовка".into()));
    }
    if container[..4] != MAGIC {
        return Err(AegisError::InvalidArg("неверная магия контейнера каскада".into()));
    }
    if container[4] != FORMAT_VERSION {
        return Err(AegisError::InvalidArg(format!(
            "неподдерживаемая версия контейнера: {}",
            container[4]
        )));
    }

    let mut aad = Vec::with_capacity(HEADER_LEN + associated_data.len());
    aad.extend_from_slice(&header());
    aad.extend_from_slice(associated_data);

    // Снимаем слои в обратном порядке (Twofish → Camellia → AES → XChaCha20).
    let b4 = &container[HEADER_LEN..];
    let b3 = open_layer::<Layer4>(keys.k4.as_ref(), b4, &aad)?;
    let b2 = open_layer::<Layer3>(keys.k3.as_ref(), &b3, &aad)?;
    let b1 = open_layer::<Layer2>(keys.k2.as_ref(), &b2, &aad)?;
    let pt = open_layer::<Layer1>(keys.k1.as_ref(), &b1, &aad)?;
    Ok(Zeroizing::new(pt))
}

/// Выводит ключи каскада из парольной/мнемонической фразы через Argon2id (§5).
///
/// `salt` уникальна на контейнер и хранится рядом с ним. Параметры Argon2id —
/// «высокие» (память/итерации), задаются вызывающим (Kotlin-`CascadeSpec`).
pub fn derive_keys_from_passphrase(
    passphrase: &[u8],
    salt: &[u8],
    mem_kib: u32,
    iters: u32,
    parallelism: u32,
) -> Result<CascadeKeys, AegisError> {
    // Argon2id → 32-байтовый секрет контейнера, из него HKDF-SHA512 → K1..K4.
    let secret = crate::kdf::argon2id_derive(passphrase, salt, mem_kib, iters, parallelism, KEY_LEN)?;
    CascadeKeys::derive(secret.as_slice())
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &[u8] = b"aegis-cascade-container-shared-secret-32b";

    // -- Независимость и раздельность ключей (разные info → разные K1..K4) -----

    #[test]
    fn derived_keys_are_all_distinct() {
        let k = CascadeKeys::derive(SECRET).unwrap();
        let keys = [k.k1.as_ref(), k.k2.as_ref(), k.k3.as_ref(), k.k4.as_ref()];
        for i in 0..keys.len() {
            for j in (i + 1)..keys.len() {
                assert_ne!(keys[i], keys[j], "ключи слоёв {i} и {j} обязаны различаться");
            }
        }
    }

    #[test]
    fn different_secret_yields_different_keys() {
        let a = CascadeKeys::derive(b"secret-alpha-0000000000000000").unwrap();
        let b = CascadeKeys::derive(b"secret-bravo-0000000000000000").unwrap();
        assert_ne!(a.k1.as_ref(), b.k1.as_ref());
        assert_ne!(a.k2.as_ref(), b.k2.as_ref());
        assert_ne!(a.k3.as_ref(), b.k3.as_ref());
        assert_ne!(a.k4.as_ref(), b.k4.as_ref());
    }

    #[test]
    fn key_derivation_is_deterministic() {
        let a = CascadeKeys::derive(SECRET).unwrap();
        let b = CascadeKeys::derive(SECRET).unwrap();
        assert_eq!(a.k1.as_ref(), b.k1.as_ref());
        assert_eq!(a.k4.as_ref(), b.k4.as_ref());
    }

    #[test]
    fn rejects_too_short_secret() {
        assert!(CascadeKeys::derive(b"short").is_err());
    }

    // -- Roundtrip каждого слоя по отдельности --------------------------------

    #[test]
    fn each_layer_roundtrips_individually() {
        let key = [9u8; KEY_LEN];
        let msg = b"per-layer roundtrip payload";
        let aad = b"ctx";

        let b1 = seal_layer::<Layer1>(&key, msg, aad).unwrap();
        assert_eq!(open_layer::<Layer1>(&key, &b1, aad).unwrap(), msg);

        let b2 = seal_layer::<Layer2>(&key, msg, aad).unwrap();
        assert_eq!(open_layer::<Layer2>(&key, &b2, aad).unwrap(), msg);

        let b3 = seal_layer::<Layer3>(&key, msg, aad).unwrap();
        assert_eq!(open_layer::<Layer3>(&key, &b3, aad).unwrap(), msg);

        let b4 = seal_layer::<Layer4>(&key, msg, aad).unwrap();
        assert_eq!(open_layer::<Layer4>(&key, &b4, aad).unwrap(), msg);
    }

    /// Подмена тега/шифртекста на КАЖДОМ слое по отдельности отвергается.
    #[test]
    fn each_layer_rejects_tamper() {
        let key = [5u8; KEY_LEN];
        let msg = b"tamper me";

        macro_rules! check {
            ($layer:ty) => {{
                let mut blob = seal_layer::<$layer>(&key, msg, b"").unwrap();
                let last = blob.len() - 1; // последний байт = хвост тега
                blob[last] ^= 0xff;
                assert!(open_layer::<$layer>(&key, &blob, b"").is_err());
            }};
        }
        check!(Layer1);
        check!(Layer2);
        check!(Layer3);
        check!(Layer4);
    }

    // -- Roundtrip всего каскада ----------------------------------------------

    #[test]
    fn full_cascade_roundtrips() {
        let msg = b"AEGIS: XChaCha20 -> AES-256 -> Camellia -> Twofish";
        let aad = b"aegis/packet/v1";
        let container = seal(SECRET, msg, aad).unwrap();

        // Заголовок присутствует и корректен.
        assert_eq!(&container[..4], b"AGSC");
        assert_eq!(container[4], FORMAT_VERSION);
        // Контейнер не содержит открытого текста.
        assert!(!container.windows(msg.len()).any(|w| w == msg));

        let out = open(SECRET, &container, aad).unwrap();
        assert_eq!(out.as_slice(), msg);
    }

    #[test]
    fn empty_plaintext_roundtrips() {
        let container = seal(SECRET, b"", b"").unwrap();
        assert_eq!(open(SECRET, &container, b"").unwrap().as_slice(), b"");
    }

    #[test]
    fn wrong_secret_fails() {
        let container = seal(SECRET, b"secret payload", b"").unwrap();
        assert!(open(b"another-container-secret-000000", &container, b"").is_err());
    }

    #[test]
    fn aad_mismatch_fails() {
        let container = seal(SECRET, b"payload", b"ctx-a").unwrap();
        assert!(open(SECRET, &container, b"ctx-b").is_err());
    }

    /// Провал при подмене тега на любом слое — на уровне всего контейнера.
    /// Внешний слой (Twofish) ловит подмену последнего байта напрямую;
    /// прогон по нескольким позициям гарантирует отсутствие «дыр».
    #[test]
    fn cascade_rejects_tampered_container() {
        let container = seal(SECRET, b"integrity matters", b"").unwrap();
        for pos in [HEADER_LEN, container.len() / 2, container.len() - 1] {
            let mut bad = container.clone();
            bad[pos] ^= 0x01;
            assert!(open(SECRET, &bad, b"").is_err(), "подмена байта {pos} должна ломать тег");
        }
    }

    #[test]
    fn rejects_bad_header() {
        let mut container = seal(SECRET, b"x", b"").unwrap();
        container[0] ^= 0xff; // портим магию
        assert!(open(SECRET, &container, b"").is_err());
    }

    // -- Ключ контейнера из парольной фразы (Argon2id) ------------------------

    #[test]
    fn passphrase_derived_cascade_roundtrips() {
        // Лёгкие параметры Argon2id для скорости теста.
        let keys = derive_keys_from_passphrase(b"correct horse battery staple", b"salt-salt", 8 * 1024, 2, 1)
            .unwrap();
        let msg = b"passphrase-keyed container";
        let container = seal_with_keys(&keys, msg, b"").unwrap();

        let keys2 = derive_keys_from_passphrase(b"correct horse battery staple", b"salt-salt", 8 * 1024, 2, 1)
            .unwrap();
        assert_eq!(open_with_keys(&keys2, &container, b"").unwrap().as_slice(), msg);

        // Другой пароль → другой ключ → провал.
        let wrong = derive_keys_from_passphrase(b"wrong password", b"salt-salt", 8 * 1024, 2, 1).unwrap();
        assert!(open_with_keys(&wrong, &container, b"").is_err());
    }
}
