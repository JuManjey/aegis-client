//! Запечатывание секретов (мнемоники) через XChaCha20-Poly1305 AEAD.
//!
//! Используется для хранения мнемоники аккаунта под ключом, выведенным из
//! пароля (Argon2id, [`crate::kdf`]). XChaCha20 выбран из-за 192-битного nonce:
//! случайный nonce не имеет практического риска повтора (§5). Всегда AEAD:
//! при провале тега расшифровка возвращает ошибку (никакого «просто шифра»).

use chacha20poly1305::{
    aead::{Aead, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use zeroize::Zeroizing;

use crate::error::AegisError;

const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 24;

/// Запечатывает `plaintext` ключом `key` (32 байта). Возвращает `nonce || ct`.
pub fn seal(key: &[u8], plaintext: &[u8], associated_data: &[u8]) -> Result<Vec<u8>, AegisError> {
    if key.len() != KEY_LEN {
        return Err(AegisError::InvalidArg("ключ XChaCha20 должен быть 32 байта".into()));
    }
    let cipher = XChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| AegisError::Crypto(format!("init XChaCha20: {e}")))?;

    let mut nonce_bytes = [0u8; NONCE_LEN];
    getrandom::getrandom(&mut nonce_bytes)
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    let nonce = XNonce::from_slice(&nonce_bytes);

    let payload = chacha20poly1305::aead::Payload {
        msg: plaintext,
        aad: associated_data,
    };
    let ciphertext = cipher
        .encrypt(nonce, payload)
        .map_err(|_| AegisError::Crypto("сбой AEAD-шифрования".into()))?;

    let mut out = Vec::with_capacity(NONCE_LEN + ciphertext.len());
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Распечатывает блоб `nonce || ct`. При провале тега — ошибка.
pub fn open(
    key: &[u8],
    blob: &[u8],
    associated_data: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    if key.len() != KEY_LEN {
        return Err(AegisError::InvalidArg("ключ XChaCha20 должен быть 32 байта".into()));
    }
    if blob.len() < NONCE_LEN {
        return Err(AegisError::InvalidArg("блоб короче nonce".into()));
    }
    let cipher = XChaCha20Poly1305::new_from_slice(key)
        .map_err(|e| AegisError::Crypto(format!("init XChaCha20: {e}")))?;

    let (nonce_bytes, ciphertext) = blob.split_at(NONCE_LEN);
    let nonce = XNonce::from_slice(nonce_bytes);
    let payload = chacha20poly1305::aead::Payload {
        msg: ciphertext,
        aad: associated_data,
    };
    let plaintext = cipher
        .decrypt(nonce, payload)
        .map_err(|_| AegisError::Crypto("сбой проверки AEAD-тега (неверный ключ или данные)".into()))?;
    Ok(Zeroizing::new(plaintext))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seal_open_roundtrip() {
        let key = [7u8; 32];
        let msg = b"twelve word mnemonic goes here";
        let blob = seal(&key, msg, b"aegis").unwrap();
        assert_ne!(&blob[NONCE_LEN..], msg);
        let out = open(&key, &blob, b"aegis").unwrap();
        assert_eq!(out.as_slice(), msg);
    }

    #[test]
    fn wrong_key_fails() {
        let blob = seal(&[1u8; 32], b"secret", b"").unwrap();
        assert!(open(&[2u8; 32], &blob, b"").is_err());
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let key = [3u8; 32];
        let mut blob = seal(&key, b"secret", b"").unwrap();
        let last = blob.len() - 1;
        blob[last] ^= 0xff;
        assert!(open(&key, &blob, b"").is_err());
    }

    #[test]
    fn aad_mismatch_fails() {
        let key = [4u8; 32];
        let blob = seal(&key, b"secret", b"ctx-a").unwrap();
        assert!(open(&key, &blob, b"ctx-b").is_err());
    }
}
