//! Кадровый слой поверх DTLS-SRTP (§1.14, §7.5/§8/§10.10) — НЕЗАВИСИМЫЙ слой
//! шифрования медиа звонка (insertable streams / SFrame-подобный).
//!
//! Медиа звонка уже защищает WebRTC (DTLS-SRTP) — аудированный стандарт (§1).
//! Здесь мы добавляем ВТОРОЙ независимый слой (defense-in-depth, §4): каждый
//! медиа-кадр дополнительно шифруется AEAD на ключах, выведенных из
//! Signal-сессии + ручного PSK (см. [`crate::calls`]). Ключа кадрового слоя
//! НЕТ в DTLS-рукопожатии: компрометация DTLS-SRTP (или подмена сервером
//! согласования) не раскрывает медиа, пока цел out-of-band корень (call_secret
//! из Signal-канала) и PSK.
//!
//! Свою криптографию НЕ пишем (§1): AEAD — аудированный `ChaCha20-Poly1305`
//! (RustCrypto, IETF-вариант, 96-битный nonce), тот же крейт, что и слой A
//! (`seal`/каскад K1). Вызывается из нативного C++-шима WebRTC
//! (`app/src/main/cpp/aegis_frame.cpp`) по стабильному C-ABI ниже — сама
//! реализация `FrameEncryptorInterface`/`FrameDecryptorInterface` на C++ только
//! маршалит буферы, вся крипта здесь.
//!
//! ── Вывод nonce (уникальность на каждый кадр) ─────────────────────────────
//! IETF ChaCha20-Poly1305 требует УНИКАЛЬНЫЙ 96-битный nonce на каждое
//! шифрование одним ключом (повтор (ключ, nonce) катастрофичен). Кадровый слой
//! получает 14-байтовую соль направления ([`crate::calls`] `DirectionKeys.salt`)
//! и монотонный 64-битный счётчик кадра (ведёт C++-шим, по одному на sender).
//! nonce = salt[0..12], где младшие 8 байт XOR-ятся с big-endian(счётчик).
//! Так nonce уникален в пределах направления/ключа (ключи направлений и солей
//! различны — см. `calls::direction_keys`), а счётчик передаётся рядом с
//! шифртекстом, чтобы приёмник восстановил nonce при потере/переупорядочивании.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};

/// Длина ключа AEAD кадрового слоя (совпадает с `DirectionKeys.key`, §calls).
const KEY_LEN: usize = 32;
/// Длина nonce IETF ChaCha20-Poly1305.
const NONCE_LEN: usize = 12;
/// Длина тега Poly1305.
pub const TAG_LEN: usize = 16;
/// Минимальная длина соли направления (`DirectionKeys.salt` = 14 байт).
const MIN_SALT_LEN: usize = NONCE_LEN;

/// Коды возврата C-ABI (0 — успех, отрицательные — ошибки; C++ трактует < 0
/// как «не шифровать/не пропустить кадр»).
const OK: i32 = 0;
const ERR_ARG: i32 = -1;
const ERR_CAP: i32 = -2;
const ERR_CRYPTO: i32 = -3;

/// Строит 96-битный nonce из соли направления и счётчика кадра.
///
/// nonce = salt[0..12]; младшие 8 байт (индексы 4..12) XOR big-endian(counter).
/// Соль постоянна на направление, счётчик монотонен → nonce не повторяется.
fn derive_nonce(salt: &[u8], counter: u64) -> [u8; NONCE_LEN] {
    let mut nonce = [0u8; NONCE_LEN];
    nonce.copy_from_slice(&salt[..NONCE_LEN]);
    let ctr = counter.to_be_bytes();
    for i in 0..8 {
        nonce[NONCE_LEN - 8 + i] ^= ctr[i];
    }
    nonce
}

/// Шифрует один медиа-кадр. Возвращает `ct || tag` (без счётчика — его пишет и
/// читает C++-шим отдельным префиксом на проводе).
fn seal_frame(
    key: &[u8],
    salt: &[u8],
    counter: u64,
    aad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, i32> {
    if key.len() != KEY_LEN || salt.len() < MIN_SALT_LEN {
        return Err(ERR_ARG);
    }
    let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|_| ERR_ARG)?;
    let nonce_bytes = derive_nonce(salt, counter);
    let nonce = Nonce::from_slice(&nonce_bytes);
    cipher
        .encrypt(nonce, Payload { msg: plaintext, aad })
        .map_err(|_| ERR_CRYPTO)
}

/// Расшифровывает один медиа-кадр (`ct || tag`). При провале тега — ошибка.
fn open_frame(
    key: &[u8],
    salt: &[u8],
    counter: u64,
    aad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, i32> {
    if key.len() != KEY_LEN || salt.len() < MIN_SALT_LEN {
        return Err(ERR_ARG);
    }
    if ciphertext.len() < TAG_LEN {
        return Err(ERR_ARG);
    }
    let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|_| ERR_ARG)?;
    let nonce_bytes = derive_nonce(salt, counter);
    let nonce = Nonce::from_slice(&nonce_bytes);
    cipher
        .decrypt(nonce, Payload { msg: ciphertext, aad })
        .map_err(|_| ERR_CRYPTO)
}

// ---------------------------------------------------------------------------
// Стабильный C-ABI для нативного C++-шима WebRTC (dlopen из libaegis_core.so).
//
// Функции самодостаточны (без глобального состояния): весь ключевой материал и
// счётчик передаёт вызывающий. Все указатели допускают null только при нулевой
// длине; при any-null с ненулевой длиной возвращается ERR_ARG. Буферы не
// пересекаются. `out_len` заполняется числом записанных байт при OK.
// ---------------------------------------------------------------------------

/// # Safety
/// Все указатели должны быть валидны на указанную длину (или null при длине 0);
/// `out` — на `out_cap` байт; `out_len` — валидный указатель на `usize`.
unsafe fn slice<'a>(ptr: *const u8, len: usize) -> Option<&'a [u8]> {
    if len == 0 {
        Some(&[])
    } else if ptr.is_null() {
        None
    } else {
        Some(std::slice::from_raw_parts(ptr, len))
    }
}

/// Шифрует кадр. `out` должен вмещать `pt_len + TAG_LEN` байт
/// ([`aegis_frame_max_ciphertext`]). См. модульную документацию.
///
/// # Safety
/// Указатели/длины должны удовлетворять контракту C-ABI (см. выше).
#[no_mangle]
pub unsafe extern "C" fn aegis_frame_seal(
    key: *const u8,
    key_len: usize,
    salt: *const u8,
    salt_len: usize,
    counter: u64,
    aad: *const u8,
    aad_len: usize,
    pt: *const u8,
    pt_len: usize,
    out: *mut u8,
    out_cap: usize,
    out_len: *mut usize,
) -> i32 {
    let (key, salt, aad, pt) = match (
        slice(key, key_len),
        slice(salt, salt_len),
        slice(aad, aad_len),
        slice(pt, pt_len),
    ) {
        (Some(k), Some(s), Some(a), Some(p)) => (k, s, a, p),
        _ => return ERR_ARG,
    };
    if out.is_null() || out_len.is_null() {
        return ERR_ARG;
    }
    match seal_frame(key, salt, counter, aad, pt) {
        Ok(ct) => {
            if ct.len() > out_cap {
                return ERR_CAP;
            }
            std::ptr::copy_nonoverlapping(ct.as_ptr(), out, ct.len());
            *out_len = ct.len();
            OK
        }
        Err(code) => code,
    }
}

/// Расшифровывает кадр (`ct || tag`). `out` должен вмещать `ct_len - TAG_LEN`
/// байт. При провале тега — ERR_CRYPTO (кадр отбрасывается).
///
/// # Safety
/// Указатели/длины должны удовлетворять контракту C-ABI (см. выше).
#[no_mangle]
pub unsafe extern "C" fn aegis_frame_open(
    key: *const u8,
    key_len: usize,
    salt: *const u8,
    salt_len: usize,
    counter: u64,
    aad: *const u8,
    aad_len: usize,
    ct: *const u8,
    ct_len: usize,
    out: *mut u8,
    out_cap: usize,
    out_len: *mut usize,
) -> i32 {
    let (key, salt, aad, ct) = match (
        slice(key, key_len),
        slice(salt, salt_len),
        slice(aad, aad_len),
        slice(ct, ct_len),
    ) {
        (Some(k), Some(s), Some(a), Some(c)) => (k, s, a, c),
        _ => return ERR_ARG,
    };
    if out.is_null() || out_len.is_null() {
        return ERR_ARG;
    }
    match open_frame(key, salt, counter, aad, ct) {
        Ok(pt) => {
            if pt.len() > out_cap {
                return ERR_CAP;
            }
            std::ptr::copy_nonoverlapping(pt.as_ptr(), out, pt.len());
            *out_len = pt.len();
            OK
        }
        Err(code) => code,
    }
}

/// Максимальный размер шифртекста кадрового слоя для данного размера открытого
/// текста (`pt_len + TAG_LEN`). Пригодно для `GetMaxCiphertextByteSize`.
#[no_mangle]
pub extern "C" fn aegis_frame_max_ciphertext(pt_len: usize) -> usize {
    pt_len + TAG_LEN
}

/// Маркер наличия символов кадрового слоя в .so (для dlsym-диагностики шима).
/// Возвращает 1 всегда, когда символ разрешился.
#[no_mangle]
pub extern "C" fn aegis_frame_available() -> i32 {
    1
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; KEY_LEN] = [0x11; KEY_LEN];
    const SALT: [u8; 14] = [0x22; 14];

    #[test]
    fn roundtrip_same_counter() {
        let aad = b"generic-frame-header";
        let pt = b"one encoded media frame payload";
        let ct = seal_frame(&KEY, &SALT, 7, aad, pt).unwrap();
        assert_eq!(ct.len(), pt.len() + TAG_LEN);
        // Шифртекст не содержит открытого кадра.
        assert!(!ct.windows(pt.len()).any(|w| w == pt));
        let out = open_frame(&KEY, &SALT, 7, aad, &ct).unwrap();
        assert_eq!(out, pt);
    }

    #[test]
    fn empty_frame_roundtrips() {
        let ct = seal_frame(&KEY, &SALT, 0, b"", b"").unwrap();
        assert_eq!(ct.len(), TAG_LEN);
        assert_eq!(open_frame(&KEY, &SALT, 0, b"", &ct).unwrap(), b"");
    }

    #[test]
    fn wrong_counter_fails() {
        let ct = seal_frame(&KEY, &SALT, 5, b"h", b"payload").unwrap();
        // Иной счётчик → иной nonce → тег не сойдётся.
        assert!(open_frame(&KEY, &SALT, 6, b"h", &ct).is_err());
    }

    #[test]
    fn wrong_key_fails() {
        let ct = seal_frame(&KEY, &SALT, 1, b"", b"payload").unwrap();
        assert!(open_frame(&[0x33; KEY_LEN], &SALT, 1, b"", &ct).is_err());
    }

    #[test]
    fn wrong_salt_fails() {
        let ct = seal_frame(&KEY, &SALT, 1, b"", b"payload").unwrap();
        assert!(open_frame(&KEY, &[0x44; 14], 1, b"", &ct).is_err());
    }

    #[test]
    fn aad_mismatch_fails() {
        // AAD = «незашифрованный заголовок кадра» WebRTC: подмена ломает тег.
        let ct = seal_frame(&KEY, &SALT, 1, b"header-a", b"payload").unwrap();
        assert!(open_frame(&KEY, &SALT, 1, b"header-b", &ct).is_err());
    }

    #[test]
    fn tampered_ciphertext_fails() {
        let mut ct = seal_frame(&KEY, &SALT, 1, b"", b"payload").unwrap();
        let last = ct.len() - 1;
        ct[last] ^= 0xff;
        assert!(open_frame(&KEY, &SALT, 1, b"", &ct).is_err());
    }

    #[test]
    fn nonce_differs_per_counter() {
        let n0 = derive_nonce(&SALT, 0);
        let n1 = derive_nonce(&SALT, 1);
        let n_big = derive_nonce(&SALT, u64::MAX);
        assert_ne!(n0, n1);
        assert_ne!(n0, n_big);
        // Верхние 4 байта соли не затрагиваются счётчиком.
        assert_eq!(n0[..4], SALT[..4]);
    }

    #[test]
    fn direction_symmetry_via_swapped_keys() {
        // Отправитель шифрует своим send-ключом; приёмник расшифровывает тем же
        // (в реальности send одной стороны == recv другой, см. calls.rs).
        let send_key = [0xAA; KEY_LEN];
        let send_salt = [0xBB; 14];
        let ct = seal_frame(&send_key, &send_salt, 42, b"hdr", b"video").unwrap();
        assert_eq!(open_frame(&send_key, &send_salt, 42, b"hdr", &ct).unwrap(), b"video");
    }

    // -- C-ABI обёртки --------------------------------------------------------

    #[test]
    fn c_abi_roundtrip() {
        let pt = b"c-abi frame";
        let aad = b"aad";
        let mut ct = vec![0u8; aegis_frame_max_ciphertext(pt.len())];
        let mut ct_len = 0usize;
        let rc = unsafe {
            aegis_frame_seal(
                KEY.as_ptr(), KEY.len(),
                SALT.as_ptr(), SALT.len(),
                9,
                aad.as_ptr(), aad.len(),
                pt.as_ptr(), pt.len(),
                ct.as_mut_ptr(), ct.len(), &mut ct_len,
            )
        };
        assert_eq!(rc, OK);
        assert_eq!(ct_len, pt.len() + TAG_LEN);

        let mut out = vec![0u8; ct_len];
        let mut out_len = 0usize;
        let rc = unsafe {
            aegis_frame_open(
                KEY.as_ptr(), KEY.len(),
                SALT.as_ptr(), SALT.len(),
                9,
                aad.as_ptr(), aad.len(),
                ct.as_ptr(), ct_len,
                out.as_mut_ptr(), out.len(), &mut out_len,
            )
        };
        assert_eq!(rc, OK);
        assert_eq!(&out[..out_len], pt);
    }

    #[test]
    fn c_abi_rejects_small_output() {
        let pt = b"needs room";
        let mut ct = vec![0u8; 1]; // заведомо мало
        let mut ct_len = 0usize;
        let rc = unsafe {
            aegis_frame_seal(
                KEY.as_ptr(), KEY.len(),
                SALT.as_ptr(), SALT.len(),
                0,
                std::ptr::null(), 0,
                pt.as_ptr(), pt.len(),
                ct.as_mut_ptr(), ct.len(), &mut ct_len,
            )
        };
        assert_eq!(rc, ERR_CAP);
    }

    #[test]
    fn c_abi_rejects_bad_key_len() {
        let bad = [0u8; 16];
        let pt = b"x";
        let mut ct = vec![0u8; 64];
        let mut ct_len = 0usize;
        let rc = unsafe {
            aegis_frame_seal(
                bad.as_ptr(), bad.len(),
                SALT.as_ptr(), SALT.len(),
                0,
                std::ptr::null(), 0,
                pt.as_ptr(), pt.len(),
                ct.as_mut_ptr(), ct.len(), &mut ct_len,
            )
        };
        assert_eq!(rc, ERR_ARG);
    }

    #[test]
    fn max_ciphertext_adds_tag() {
        assert_eq!(aegis_frame_max_ciphertext(0), TAG_LEN);
        assert_eq!(aegis_frame_max_ciphertext(100), 100 + TAG_LEN);
    }
}
