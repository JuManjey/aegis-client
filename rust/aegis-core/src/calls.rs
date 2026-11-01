//! Слой звонков (P8, §7.5 / §10.10): согласование ключей медиапотока звонка.
//!
//! ЭТОТ МОДУЛЬ КОМПИЛИРУЕТСЯ ТОЛЬКО С ФИЧЕЙ `signal` (звонки логически часть
//! слоя B — ключи звонка привязаны к Signal-сессии и ручному PSK). Свою
//! криптографию мы НЕ пишем (§1): здесь только HKDF-SHA512 (крейт `hkdf`) —
//! стандартный вывод ключей с разделением доменов.
//!
//! ── Модель безопасности звонка (§7.5 / §10.10) ────────────────────────────
//! Медиа звонка защищает WebRTC (DTLS-SRTP) — аудированный стандарт, свой
//! транспорт мы не изобретаем. AEGIS добавляет ДВА независимых усиления:
//!
//!   1. Инициатор генерирует случайный 256-битный `call_secret` (ОС-ГСЧ) и
//!      передаёт его собеседнику ВНУТРИ Signal-сессии (слой B) поверх каскада
//!      на PSK (слой A) — тем же конвейером, что и текст (см. Kotlin
//!      `CallSignaling`). То есть корень ключей звонка «установлен через
//!      Signal-сессию», как требует §7.5, и не виден ни серверу, ни сети.
//!
//!   2. Ручной PSK владельца (§6.2) ПОДМЕШИВАЕТСЯ в вывод ключей звонка как
//!      соль HKDF. Даже если сервер полностью контролирует раздачу ключей и
//!      подменил Signal-сессию (MITM), он не знает out-of-band PSK → не может
//!      воспроизвести ни ключи медиапотока, ни короткий код сверки (SAS).
//!
//! Из общего `call_secret` + PSK + идентификатора звонка + «транскрипта»
//! согласования (напр. отсортированные DTLS-фингерпринты сторон — привязка к
//! конкретному TLS-каналу, анти-MITM на медиа-пути) выводятся:
//!   * два НЕЗАВИСИМЫХ комплекта SRTP-ключей по направлениям (i→r и r→i),
//!     каждый = 256-бит ключ + 112-бит соль (формат AES-256 SRTP);
//!   * короткий код аутентификации звонка (SAS) для устной сверки.
//!
//! Симметрия ролей: `send` инициатора равен `recv` собеседника и наоборот —
//! обе стороны из одних входных данных получают согласованные ключи.

use base64::{engine::general_purpose::STANDARD as B64, Engine};

/// Домены HKDF — разделяют вывод ключей звонка от прочих (§5/§6.2 используют
/// свои метки: `AEGIS/cascade/v1`, `aegis/psk/v1`, `aegis/psk-x3dh-binding/v1`).
const CALL_ROOT_INFO: &[u8] = b"aegis/call/root/v1";
const CALL_I2R_INFO: &[u8] = b"aegis/call/srtp/i2r/v1";
const CALL_R2I_INFO: &[u8] = b"aegis/call/srtp/r2i/v1";
const CALL_SAS_INFO: &[u8] = b"aegis/call/sas/v1";

/// Длина SRTP-ключа (AES-256) и соли (SRTP master salt = 112 бит = 14 байт).
const SRTP_KEY_LEN: usize = 32;
const SRTP_SALT_LEN: usize = 14;

/// Ключевой материал одного направления SRTP (ключ + соль).
#[derive(Clone)]
pub struct DirectionKeys {
    pub key: [u8; SRTP_KEY_LEN],
    pub salt: [u8; SRTP_SALT_LEN],
}

/// Итог согласования ключей звонка для КОНКРЕТНОЙ стороны.
pub struct CallKeys {
    /// Ключи для исходящего (отправляемого этой стороной) медиапотока.
    pub send: DirectionKeys,
    /// Ключи для входящего (принимаемого этой стороной) медиапотока.
    pub recv: DirectionKeys,
    /// Короткий код сверки (SAS) — обе честные стороны видят одинаковый.
    pub sas: String,
}

/// HKDF-SHA512(salt, ikm) → expand(info) в буфер нужной длины.
fn hkdf_expand(salt: &[u8], ikm: &[u8], info: &[u8], out: &mut [u8]) {
    let hk = hkdf::Hkdf::<sha2::Sha512>::new(Some(salt), ikm);
    hk.expand(info, out)
        .expect("длина вывода HKDF-SHA512 в допустимых пределах");
}

/// Строит `info` для второго HKDF-раунда: метка направления ‖ call_id ‖ контекст.
fn scoped_info(base: &[u8], call_id: &str, context: &[u8]) -> Vec<u8> {
    let mut v = Vec::with_capacity(base.len() + 1 + call_id.len() + 1 + context.len());
    v.extend_from_slice(base);
    v.push(0x1f); // разделитель полей (не появляется в call_id/метках)
    v.extend_from_slice(call_id.as_bytes());
    v.push(0x1f);
    v.extend_from_slice(context);
    v
}

fn direction_keys(root: &[u8; 64], call_id: &str, context: &[u8], info: &[u8]) -> DirectionKeys {
    let mut buf = [0u8; SRTP_KEY_LEN + SRTP_SALT_LEN];
    // Вторая ступень: соль = 0 (энтропия уже в root), ikm = root, info с доменом.
    hkdf_expand(&[], root, &scoped_info(info, call_id, context), &mut buf);
    let mut key = [0u8; SRTP_KEY_LEN];
    let mut salt = [0u8; SRTP_SALT_LEN];
    key.copy_from_slice(&buf[..SRTP_KEY_LEN]);
    salt.copy_from_slice(&buf[SRTP_KEY_LEN..]);
    DirectionKeys { key, salt }
}

/// Форматирует код сверки (SAS) как три группы по четыре десятичных знака —
/// удобно надиктовать голосом при звонке (как «safety number», но для звонка).
fn format_sas(bytes: &[u8]) -> String {
    bytes
        .chunks(2)
        .take(3)
        .map(|chunk| {
            let mut acc: u32 = 0;
            for &b in chunk {
                acc = (acc << 8) | b as u32;
            }
            format!("{:04}", acc % 10_000)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Согласует ключи звонка для одной стороны.
///
/// * `call_secret` — общий корень звонка (сгенерирован инициатором, доставлен
///   собеседнику через Signal-сессию поверх каскада на PSK).
/// * `psk` — ручной PSK контакта (§6.2); может быть пустым (тогда без
///   out-of-band усиления, что честно отражается в UI).
/// * `call_id` — идентификатор звонка (привязка вывода к конкретному сеансу).
/// * `initiator` — эта сторона инициировала звонок.
/// * `context` — «транскрипт» согласования (напр. отсортированные
///   DTLS-фингерпринты сторон) для привязки ключей к конкретному каналу.
pub fn derive_call_keys(
    call_secret: &[u8],
    psk: &[u8],
    call_id: &str,
    initiator: bool,
    context: &[u8],
) -> CallKeys {
    // Первая ступень: соль = PSK (подмешиваем out-of-band секрет, §10.10),
    // ikm = call_secret (корень из Signal-сессии). Корень 64 байта.
    let mut root = [0u8; 64];
    hkdf_expand(psk, call_secret, CALL_ROOT_INFO, &mut root);

    let i2r = direction_keys(&root, call_id, context, CALL_I2R_INFO);
    let r2i = direction_keys(&root, call_id, context, CALL_R2I_INFO);

    // SAS одинаков у обеих сторон: тот же root/call_id/context, метка SAS.
    let mut sas_bytes = [0u8; 8];
    hkdf_expand(&[], &root, &scoped_info(CALL_SAS_INFO, call_id, context), &mut sas_bytes);
    let sas = format_sas(&sas_bytes);

    if initiator {
        CallKeys { send: i2r, recv: r2i, sas }
    } else {
        CallKeys { send: r2i, recv: i2r, sas }
    }
}

/// Кодирует результат согласования в JSON для JNI-границы (все ключи base64).
pub fn call_keys_json(keys: &CallKeys) -> String {
    format!(
        r#"{{"sendKey":"{}","sendSalt":"{}","recvKey":"{}","recvSalt":"{}","sas":"{}"}}"#,
        B64.encode(keys.send.key),
        B64.encode(keys.send.salt),
        B64.encode(keys.recv.key),
        B64.encode(keys.recv.salt),
        keys.sas,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET: &[u8] = b"0123456789abcdef0123456789abcdef"; // 32 байта call_secret
    const PSK: &[u8] = b"aegis-manual-psk-out-of-band-key";
    const CTX: &[u8] = b"fingerprintA|fingerprintB";

    /// Обе стороны из одних входных данных получают СОГЛАСОВАННЫЕ ключи:
    /// send инициатора == recv собеседника и наоборот; SAS совпадает.
    #[test]
    fn caller_and_callee_agree() {
        let caller = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let callee = derive_call_keys(SECRET, PSK, "call-1", false, CTX);

        assert_eq!(caller.send.key, callee.recv.key, "i→r ключ должен совпасть");
        assert_eq!(caller.send.salt, callee.recv.salt);
        assert_eq!(caller.recv.key, callee.send.key, "r→i ключ должен совпасть");
        assert_eq!(caller.recv.salt, callee.send.salt);
        assert_eq!(caller.sas, callee.sas, "SAS у честных сторон одинаков");
        assert!(!caller.sas.is_empty());
    }

    /// Направления независимы: send != recv (разные домены HKDF).
    #[test]
    fn directions_are_independent() {
        let k = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        assert_ne!(k.send.key, k.recv.key);
        assert_ne!(k.send.salt, k.recv.salt);
    }

    /// PSK реально подмешивается: другой PSK → другие ключи и другой SAS.
    /// Это и есть анти-MITM (§10.10): сервер без PSK не воспроизведёт материал.
    #[test]
    fn psk_changes_keys_and_sas() {
        let with_psk = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let other_psk = derive_call_keys(SECRET, b"another-secret-psk", "call-1", true, CTX);
        let no_psk = derive_call_keys(SECRET, b"", "call-1", true, CTX);

        assert_ne!(with_psk.send.key, other_psk.send.key);
        assert_ne!(with_psk.sas, other_psk.sas);
        assert_ne!(with_psk.send.key, no_psk.send.key);
        assert_ne!(with_psk.sas, no_psk.sas);
    }

    /// Разный call_secret (корень из Signal-сессии) → разные ключи.
    #[test]
    fn different_secret_diverges() {
        let a = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let b = derive_call_keys(b"ffffffffffffffffffffffffffffffff", PSK, "call-1", true, CTX);
        assert_ne!(a.send.key, b.send.key);
        assert_ne!(a.sas, b.sas);
    }

    /// Разный идентификатор звонка → разные ключи (ключи не переиспользуются
    /// между сеансами, даже при одинаковом call_secret/PSK).
    #[test]
    fn different_call_id_diverges() {
        let a = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let b = derive_call_keys(SECRET, PSK, "call-2", true, CTX);
        assert_ne!(a.send.key, b.send.key);
        assert_ne!(a.sas, b.sas);
    }

    /// Разный «транскрипт» согласования (напр. подменённые DTLS-фингерпринты)
    /// → разные ключи и SAS: привязка к конкретному TLS-каналу (анти-MITM).
    #[test]
    fn different_context_diverges() {
        let a = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let b = derive_call_keys(SECRET, PSK, "call-1", true, b"tampered-fingerprints");
        assert_ne!(a.send.key, b.send.key);
        assert_ne!(a.sas, b.sas);
    }

    /// Длины ключей соответствуют формату AES-256 SRTP.
    #[test]
    fn key_and_salt_lengths() {
        let k = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        assert_eq!(k.send.key.len(), 32);
        assert_eq!(k.send.salt.len(), 14);
    }

    /// JSON-сериализация для JNI содержит все поля.
    #[test]
    fn json_has_all_fields() {
        let k = derive_call_keys(SECRET, PSK, "call-1", true, CTX);
        let json = call_keys_json(&k);
        assert!(json.contains("sendKey"));
        assert!(json.contains("recvKey"));
        assert!(json.contains("sas"));
    }
}
