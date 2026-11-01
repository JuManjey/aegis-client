//! KDF пароля — Argon2id (§6.1, §7.1). Не пишем свою криптографию (§1).
//!
//! Используется для вывода ключа-обёртки из пароля входа пользователя. Этим
//! ключом запечатывается мнемоника аккаунта ([`crate::seal`]) перед хранением.

use argon2::{Algorithm, Argon2, Params, Version};
use zeroize::Zeroizing;

use crate::error::AegisError;

/// Выводит ключ из пароля через Argon2id с заданными параметрами.
///
/// * `password` — байты пароля (UTF-8).
/// * `salt` — соль (>= 8 байт), уникальна на аккаунт, хранится рядом с блобом.
/// * `mem_kib` — память в КиБ, `iters` — число проходов, `parallelism` — потоки.
/// * `out_len` — длина выходного ключа в байтах (обычно 32).
pub fn argon2id_derive(
    password: &[u8],
    salt: &[u8],
    mem_kib: u32,
    iters: u32,
    parallelism: u32,
    out_len: usize,
) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    if salt.len() < 8 {
        return Err(AegisError::InvalidArg("соль Argon2 должна быть >= 8 байт".into()));
    }
    if out_len < 16 || out_len > 64 {
        return Err(AegisError::InvalidArg("out_len вне диапазона 16..=64".into()));
    }
    let params = Params::new(mem_kib, iters, parallelism, Some(out_len))
        .map_err(|e| AegisError::InvalidArg(format!("некорректные параметры Argon2: {e}")))?;
    let argon2 = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);

    let mut out = Zeroizing::new(vec![0u8; out_len]);
    argon2
        .hash_password_into(password, salt, &mut out)
        .map_err(|e| AegisError::Crypto(format!("Argon2id: {e}")))?;
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Лёгкие параметры для тестов (быстро, детерминированно).
    const MEM: u32 = 8 * 1024;
    const ITERS: u32 = 2;
    const PAR: u32 = 1;

    #[test]
    fn deterministic_for_same_inputs() {
        let a = argon2id_derive(b"correct horse", b"salt-salt", MEM, ITERS, PAR, 32).unwrap();
        let b = argon2id_derive(b"correct horse", b"salt-salt", MEM, ITERS, PAR, 32).unwrap();
        assert_eq!(a.as_slice(), b.as_slice());
        assert_eq!(a.len(), 32);
    }

    #[test]
    fn different_password_differs() {
        let a = argon2id_derive(b"password1", b"salt-salt", MEM, ITERS, PAR, 32).unwrap();
        let b = argon2id_derive(b"password2", b"salt-salt", MEM, ITERS, PAR, 32).unwrap();
        assert_ne!(a.as_slice(), b.as_slice());
    }

    #[test]
    fn different_salt_differs() {
        let a = argon2id_derive(b"password", b"salt-aaaa", MEM, ITERS, PAR, 32).unwrap();
        let b = argon2id_derive(b"password", b"salt-bbbb", MEM, ITERS, PAR, 32).unwrap();
        assert_ne!(a.as_slice(), b.as_slice());
    }

    #[test]
    fn rejects_short_salt() {
        assert!(argon2id_derive(b"p", b"short", MEM, ITERS, PAR, 32).is_err());
    }
}
