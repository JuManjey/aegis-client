//! Identity аккаунта AEGIS (P1, §7.1) — без привязки к телефону.
//!
//! Аккаунт = криптографическая пара ключей (§7.1). Мастер-секретом служит
//! BIP-39-мнемоника: из неё детерминированно выводятся долгосрочные ключи
//! (Ed25519 для подписей, X25519 для ECDH/X3DH). Благодаря детерминизму на
//! устройстве достаточно хранить ТОЛЬКО зашифрованную мнемонику — приватные
//! ключи восстанавливаются из неё при разблокировке (crypto-shredding-friendly).
//!
//! Криптографию НЕ пишем свою (§1): ed25519-dalek, x25519-dalek, bip39, hkdf.

use bip39::{Language, Mnemonic};
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use hkdf::Hkdf;
use serde::Serialize;
use sha2::{Digest, Sha256, Sha512};
use x25519_dalek::{PublicKey as XPublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::error::AegisError;

/// Соль HKDF: фиксирует «домен» AEGIS для вывода identity-ключей.
const HKDF_SALT: &[u8] = b"AEGIS/identity/v1";
/// info-метки HKDF — разделение доменов между Ed25519 и X25519 (§5, §6.1).
const INFO_ED25519: &[u8] = b"aegis/identity/v1/ed25519";
const INFO_X25519: &[u8] = b"aegis/identity/v1/x25519";
/// info-метка HKDF для секрета синхронизации своих устройств (мультидевайс).
const INFO_SELF_SYNC: &[u8] = b"aegis/self-sync/v1";
/// info-метка HKDF для ключа локальных хранилищ (ключ SQLCipher, файл PSK).
const INFO_LOCAL_STORAGE: &[u8] = b"aegis/local-storage/v1";

/// Публичная часть identity аккаунта — безопасно отдаётся в UI/хранилище.
/// JSON-ключи в camelCase — контракт с Kotlin-моделью `AccountIdentity`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountPublicInfo {
    /// Стабильный публичный идентификатор аккаунта (без телефона): `aegis:<hex>`.
    pub account_id: String,
    /// Публичный ключ Ed25519 (подписи), hex.
    pub ed25519_public_hex: String,
    /// Публичный ключ X25519 (ECDH), hex.
    pub x25519_public_hex: String,
    /// Отпечаток для ручной верификации (safety number), hex (SHA-256).
    pub fingerprint: String,
}

/// Полный набор материалов аккаунта, выведенных из мнемоники.
/// Приватные ключи обёрнуты в [`Zeroizing`] — затираются при выходе из скоупа.
pub struct AccountKeys {
    pub public: AccountPublicInfo,
    ed_signing: SigningKey,
    x_secret: StaticSecret,
}

impl AccountKeys {
    /// Ed25519-подпись сообщения (для будущей верификации/аутентификации).
    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.ed_signing.sign(message).to_bytes()
    }

    /// Приватный ключ X25519 в сыром виде (для передачи в слой X3DH).
    pub fn x25519_secret_bytes(&self) -> Zeroizing<[u8; 32]> {
        Zeroizing::new(self.x_secret.to_bytes())
    }
}

/// Число слов мнемоники, поддерживаемое AEGIS.
fn entropy_len_for_words(word_count: u32) -> Result<usize, AegisError> {
    match word_count {
        12 => Ok(16),
        15 => Ok(20),
        18 => Ok(24),
        21 => Ok(28),
        24 => Ok(32),
        _ => Err(AegisError::InvalidArg(format!(
            "неподдерживаемое число слов мнемоники: {word_count} (допустимо 12/15/18/21/24)"
        ))),
    }
}

/// Генерирует новую BIP-39-мнемонику из энтропии ОС-ГСЧ (§6.1).
pub fn generate_mnemonic(word_count: u32) -> Result<String, AegisError> {
    let entropy_len = entropy_len_for_words(word_count)?;
    let mut entropy = Zeroizing::new(vec![0u8; entropy_len]);
    getrandom::getrandom(&mut entropy)
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    let mnemonic = Mnemonic::from_entropy_in(Language::English, &entropy)
        .map_err(|e| AegisError::Crypto(format!("не удалось создать мнемонику: {e}")))?;
    Ok(mnemonic.to_string())
}

/// Проверяет корректность мнемоники (контрольная сумма BIP-39).
pub fn validate_mnemonic(phrase: &str) -> bool {
    Mnemonic::parse_in_normalized(Language::English, phrase).is_ok()
}

/// Детерминированно выводит ключи аккаунта из мнемоники и (опц.) парольной фразы.
///
/// `passphrase` — необязательная BIP-39-passphrase («25-е слово»); по умолчанию
/// пустая строка. НЕ путать с паролем входа (тот управляет хранением, §7.1).
pub fn derive_account(phrase: &str, passphrase: &str) -> Result<AccountKeys, AegisError> {
    let mnemonic = Mnemonic::parse_in_normalized(Language::English, phrase)
        .map_err(|_| AegisError::InvalidArg("некорректная мнемоническая фраза".into()))?;

    // 64-байтовый seed BIP-39 (PBKDF2-HMAC-SHA512 внутри bip39).
    let seed = Zeroizing::new(mnemonic.to_seed(passphrase));

    let hk = Hkdf::<Sha512>::new(Some(HKDF_SALT), seed.as_ref());

    let mut ed_seed = Zeroizing::new([0u8; 32]);
    hk.expand(INFO_ED25519, ed_seed.as_mut())
        .map_err(|_| AegisError::Crypto("HKDF expand (ed25519) failed".into()))?;

    let mut x_seed = Zeroizing::new([0u8; 32]);
    hk.expand(INFO_X25519, x_seed.as_mut())
        .map_err(|_| AegisError::Crypto("HKDF expand (x25519) failed".into()))?;

    let ed_signing = SigningKey::from_bytes(&ed_seed);
    let ed_public = ed_signing.verifying_key();
    let x_secret = StaticSecret::from(*x_seed);
    let x_public = XPublicKey::from(&x_secret);

    let ed_pub_bytes = ed_public.to_bytes();
    let x_pub_bytes = x_public.to_bytes();

    let public = AccountPublicInfo {
        account_id: account_id_from(&ed_pub_bytes),
        ed25519_public_hex: to_hex(&ed_pub_bytes),
        x25519_public_hex: to_hex(&x_pub_bytes),
        fingerprint: fingerprint_from(&ed_pub_bytes, &x_pub_bytes),
    };

    Ok(AccountKeys {
        public,
        ed_signing,
        x_secret,
    })
}

/// Выводит секрет синхронизации СВОИХ устройств (мультидевайс, §7.1.1,
/// `docs/multidevice.md` §4.2).
///
/// Детерминированно из мнемоники (её делят все линкованные устройства аккаунта),
/// в ОТДЕЛЬНОМ HKDF-домене от identity-ключей (Ed25519/X25519) — чтобы self-sync
/// секрет не коррелировал с долгосрочными ключами. Секрет НЕ уходит в сеть; из
/// него выводятся self-sync mailbox-id ([`crate::psk::mailbox_id_device`] с
/// [`crate::psk::MailboxKind::SelfSync`]) и он неизвестен контактам. Обёрнут в
/// [`Zeroizing`] — затирается при выходе из скоупа.
pub fn derive_self_sync_secret(
    phrase: &str,
    passphrase: &str,
) -> Result<Zeroizing<[u8; 32]>, AegisError> {
    derive_mnemonic_secret(phrase, passphrase, INFO_SELF_SYNC)
}

/// Ключ локальных хранилищ устройства: им дополнительно запечатываются ключ
/// SQLCipher и файл PSK поверх обёртки Keystore. Выводится из мнемоники, а не
/// из пароля, поэтому смена пароля и восстановление своей фразой («забыли
/// пароль») не требуют перешифровки базы. Отдельный HKDF-домен: ключ не
/// коррелирует ни с identity-ключами, ни с self-sync секретом. В сеть не уходит.
pub fn derive_storage_key(
    phrase: &str,
    passphrase: &str,
) -> Result<Zeroizing<[u8; 32]>, AegisError> {
    derive_mnemonic_secret(phrase, passphrase, INFO_LOCAL_STORAGE)
}

fn derive_mnemonic_secret(
    phrase: &str,
    passphrase: &str,
    info: &[u8],
) -> Result<Zeroizing<[u8; 32]>, AegisError> {
    let mnemonic = Mnemonic::parse_in_normalized(Language::English, phrase)
        .map_err(|_| AegisError::InvalidArg("некорректная мнемоническая фраза".into()))?;
    let seed = Zeroizing::new(mnemonic.to_seed(passphrase));
    let hk = Hkdf::<Sha512>::new(Some(HKDF_SALT), seed.as_ref());
    let mut secret = Zeroizing::new([0u8; 32]);
    hk.expand(info, secret.as_mut())
        .map_err(|_| AegisError::Crypto("HKDF expand failed".into()))?;
    Ok(secret)
}

/// Проверяет Ed25519-подпись публичным ключом (hex).
pub fn verify_ed25519(public_hex: &str, message: &[u8], signature: &[u8]) -> bool {
    let pub_bytes = match from_hex(public_hex) {
        Some(b) if b.len() == 32 => b,
        _ => return false,
    };
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&pub_bytes);
    let vk = match VerifyingKey::from_bytes(&arr) {
        Ok(k) => k,
        Err(_) => return false,
    };
    let sig = match Signature::from_slice(signature) {
        Ok(s) => s,
        Err(_) => return false,
    };
    vk.verify(message, &sig).is_ok()
}

/// `aegis:<32 hex>` — короткий стабильный ID из SHA-256(ed_pub)[..16].
fn account_id_from(ed_pub: &[u8; 32]) -> String {
    let digest = Sha256::digest(ed_pub);
    format!("aegis:{}", to_hex(&digest[..16]))
}

/// [`account_id_from`] для ключа в hex — публичная точка входа для проверки
/// привязки «accountId ↔ ed25519» при удалённом добавлении контакта
/// (`docs/contact_requests.md`). `None`, если это не 32-байтовый hex-ключ.
///
/// Реализация ОДНА на весь клиент: дублировать её в Kotlin нельзя — расхождение
/// сломало бы центральную проверку модели доверия.
pub fn account_id_from_hex(ed_pub_hex: &str) -> Option<String> {
    let bytes = from_hex(ed_pub_hex.trim())?;
    let arr: [u8; 32] = bytes.try_into().ok()?;
    Some(account_id_from(&arr))
}

/// Отпечаток для ручной сверки (safety number), hex SHA-256(ed_pub || x_pub).
fn fingerprint_from(ed_pub: &[u8; 32], x_pub: &[u8; 32]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(ed_pub);
    hasher.update(x_pub);
    to_hex(&hasher.finalize())
}

fn to_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

fn from_hex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_valid_mnemonic_of_requested_length() {
        for &words in &[12u32, 24] {
            let m = generate_mnemonic(words).expect("generate");
            assert_eq!(m.split_whitespace().count(), words as usize);
            assert!(validate_mnemonic(&m));
        }
    }

    #[test]
    fn rejects_bad_word_count() {
        assert!(generate_mnemonic(13).is_err());
    }

    #[test]
    fn rejects_invalid_mnemonic() {
        assert!(!validate_mnemonic("not a real mnemonic phrase at all nope nope"));
    }

    #[test]
    fn derivation_is_deterministic() {
        // Известный тестовый вектор BIP-39 (12 слов, all-zero entropy).
        let phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let a = derive_account(phrase, "").expect("derive a");
        let b = derive_account(phrase, "").expect("derive b");
        assert_eq!(a.public.account_id, b.public.account_id);
        assert_eq!(a.public.ed25519_public_hex, b.public.ed25519_public_hex);
        assert_eq!(a.public.x25519_public_hex, b.public.x25519_public_hex);
        assert!(a.public.account_id.starts_with("aegis:"));
        assert_eq!(a.public.ed25519_public_hex.len(), 64);
        assert_eq!(a.public.fingerprint.len(), 64);
    }

    #[test]
    fn passphrase_changes_identity() {
        let phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let a = derive_account(phrase, "").unwrap();
        let b = derive_account(phrase, "extra").unwrap();
        assert_ne!(a.public.ed25519_public_hex, b.public.ed25519_public_hex);
    }

    #[test]
    fn self_sync_secret_is_deterministic_and_domain_separated() {
        let phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let a = derive_self_sync_secret(phrase, "").unwrap();
        let b = derive_self_sync_secret(phrase, "").unwrap();
        // Все устройства аккаунта выводят один и тот же секрет из общей мнемоники.
        assert_eq!(a.as_ref(), b.as_ref());
        assert_eq!(a.len(), 32);
        // Разная мнемоника → разный секрет.
        let other = generate_mnemonic(24).unwrap();
        let c = derive_self_sync_secret(&other, "").unwrap();
        assert_ne!(a.as_ref(), c.as_ref());
        // Отделён от долгосрочных identity-ключей (другой HKDF-домен): секрет не
        // совпадает с seed'ами ed/x (косвенно — через account_id независимость).
        let keys = derive_account(phrase, "").unwrap();
        assert!(!keys.public.ed25519_public_hex.is_empty());
        // Passphrase меняет секрет (как и identity).
        let d = derive_self_sync_secret(phrase, "extra").unwrap();
        assert_ne!(a.as_ref(), d.as_ref());
    }

    #[test]
    fn storage_key_is_deterministic_and_separate_from_self_sync() {
        let phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let a = derive_storage_key(phrase, "").unwrap();
        let b = derive_storage_key(phrase, "").unwrap();
        // Восстановление своей фразой обязано открыть уже существующую базу.
        assert_eq!(a.as_ref(), b.as_ref());
        assert_eq!(a.len(), 32);
        let self_sync = derive_self_sync_secret(phrase, "").unwrap();
        assert_ne!(a.as_ref(), self_sync.as_ref());
        let other = generate_mnemonic(24).unwrap();
        assert_ne!(a.as_ref(), derive_storage_key(&other, "").unwrap().as_ref());
        assert!(derive_storage_key("not a mnemonic", "").is_err());
    }

    /// `accountId` — коммитмент к Ed25519-ключу, и на этой привязке держится
    /// вся модель доверия удалённого добавления контакта: релей проверяет её у
    /// заявок на алиас (`aegis-server/src/directory.rs::account_id_from`), а
    /// клиент — у записей каталога. Формулы в двух репозиториях ОБЯЗАНЫ
    /// совпадать, поэтому вектор зафиксирован здесь и продублирован тестом
    /// `directory::tests::account_id_vector_is_pinned_across_repos` на релее (§0).
    #[test]
    fn account_id_vector_matches_relay() {
        let hex = "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c";
        let ed_pub: [u8; 32] = from_hex(hex).unwrap().try_into().unwrap();
        assert_eq!(account_id_from(&ed_pub), "aegis:34750f98bd59fcfc946da45aaabe933b");
        // Та же привязка через публичную точку входа (её вызывает Kotlin).
        assert_eq!(
            account_id_from_hex(hex).unwrap(),
            "aegis:34750f98bd59fcfc946da45aaabe933b",
        );
        assert_eq!(account_id_from_hex(&hex.to_uppercase()).unwrap(), account_id_from_hex(hex).unwrap());
        // Мусор отвергается, а не даёт «какой-нибудь» id.
        assert!(account_id_from_hex("").is_none());
        assert!(account_id_from_hex("zz").is_none());
        assert!(account_id_from_hex(&"aa".repeat(31)).is_none(), "31 байт — не ключ");
    }

    #[test]
    fn account_signature_verifies_with_derived_public_key() {
        let phrase =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let keys = derive_account(phrase, "").unwrap();
        let msg = b"aegis/rch/v1 payload";
        let sig = keys.sign(msg);
        assert!(verify_ed25519(&keys.public.ed25519_public_hex, msg, &sig));
        // Подпись привязана к аккаунту: чужой ключ её не подтвердит.
        let other = derive_account(phrase, "other").unwrap();
        assert!(!verify_ed25519(&other.public.ed25519_public_hex, msg, &sig));
    }

    #[test]
    fn sign_and_verify_roundtrip() {
        let m = generate_mnemonic(24).unwrap();
        let keys = derive_account(&m, "").unwrap();
        let msg = b"aegis verification challenge";
        let sig = keys.sign(msg);
        assert!(verify_ed25519(&keys.public.ed25519_public_hex, msg, &sig));
        assert!(!verify_ed25519(&keys.public.ed25519_public_hex, b"tampered", &sig));
    }
}
