//! P4 (§6.2, §10.3) — ключ каскадного контейнера (PSK), обмен ТОЛЬКО вручную.
//!
//! PSK (pre-shared key) — общий секрет слоя A (см. [`crate::cascade`]). По модели
//! угроз он передаётся строго out-of-band (QR лично / мнемоника / NFC) и НИКОГДА
//! не уходит в сеть. Этот модуль даёт примитивы для ручного обмена:
//!
//!  * [`generate_psk`]   — 256-битный PSK из криптографического ГСЧ ОС (§6.1);
//!  * [`fingerprint`]    — стабильный отпечаток PSK для ПОВТОРНОЙ СВЕРКИ сторон
//!    (не раскрывает сам PSK — это выход HKDF, необратим);
//!  * [`to_mnemonic`] / [`from_mnemonic`] — кодирование PSK в BIP-39-подобную
//!    фразу и обратно (ручной ввод, §6.2). 256-бит PSK ↔ 24 слова.
//!
//! Свою криптографию НЕ пишем (§1): bip39 (кодирование), hkdf/sha2 (отпечаток),
//! getrandom (ОС-ГСЧ). Байты PSK живут в [`Zeroizing`] и затираются из памяти.

use bip39::{Language, Mnemonic};
use hkdf::Hkdf;
use sha2::Sha512;
use zeroize::Zeroizing;

use crate::error::AegisError;

/// Длина PSK каскадного контейнера — 256 бит (§5). Ровно ложится в 24 слова BIP-39.
pub const PSK_LEN: usize = 32;

/// Длина отпечатка PSK (для ручной повторной сверки).
pub const FINGERPRINT_LEN: usize = 32;

/// Домен HKDF для отпечатка PSK — отделён от вывода ключей каскада
/// (`AEGIS/cascade/v1`), чтобы отпечаток и рабочие ключи не коррелировали.
const FP_SALT: &[u8] = b"AEGIS/psk/v1";
const FP_INFO: &[u8] = b"aegis/psk/v1/fingerprint";

/// Домен HKDF для идентификатора mailbox релея (§7.6/§12.2). Отделён от отпечатка
/// и ключей каскада, чтобы mailbox-id нельзя было связать с рабочими ключами.
const MAILBOX_SALT: &[u8] = b"AEGIS/mailbox/v1";

/// Домен HKDF для per-device mailbox v2 (мультидевайс, §7.1.1,
/// `docs/multidevice.md`). Отделён от v1/отпечатка/каскада (разделение доменов).
const MAILBOX_V2_SALT: &[u8] = b"AEGIS/mailbox/v2";

/// Длина идентификатора mailbox (256 бит непрозрачного capability-секрета).
pub const MAILBOX_ID_LEN: usize = 32;

/// Выводит непрозрачный идентификатор mailbox релея из PSK и направления (§7.6).
///
/// `HKDF-SHA512(salt="AEGIS/mailbox/v1", ikm=PSK, info="aegis/mailbox/v1/role/<role>")`.
/// Возвращает 256-бит значение, которое стороны используют как адрес очереди на
/// релее. Сервер видит ТОЛЬКО этот идентификатор — он не связан ни с accountId, ни
/// с identity-ключами, ни с рабочими ключами каскада/Signal (разделение доменов).
///
/// Роль (`0`/`1`) задаёт направленный подканал: обе стороны знают PSK и выводят
/// одинаковую пару id, но каждая слушает свою роль и пишет в чужую, поэтому
/// сервер не видит связь «отправитель↔получатель». Знание PSK — единственный
/// способ вычислить id (256 бит энтропии), то есть сам id и есть capability.
pub fn mailbox_id(psk: &[u8], role: u8) -> Result<[u8; MAILBOX_ID_LEN], AegisError> {
    if psk.len() < 16 {
        return Err(AegisError::InvalidArg(
            "PSK для mailbox-id должен быть >= 16 байт".into(),
        ));
    }
    let hk = Hkdf::<Sha512>::new(Some(MAILBOX_SALT), psk);
    let info = [b"aegis/mailbox/v1/role/", &[b'0' + role][..]].concat();
    let mut out = [0u8; MAILBOX_ID_LEN];
    hk.expand(&info, &mut out)
        .map_err(|_| AegisError::Crypto("HKDF expand (mailbox id) failed".into()))?;
    Ok(out)
}

/// Тип v2-подканала (мультидевайс, `docs/multidevice.md` §4): переписка
/// (секрет = PSK контакта), синхронизация СВОИХ устройств (секрет = self-sync
/// секрет аккаунта, [`crate::identity::derive_self_sync_secret`]) либо ящики
/// удалённого добавления контакта (секрет = тег обнаружения,
/// [`crate::discovery`]). Разные `tag` разделяют домены, чтобы ящики разных
/// назначений не коррелировали между собой.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MailboxKind {
    /// Подканал переписки к конкретному устройству контакта (secret = PSK).
    Conversation,
    /// Подканал синхронизации к другому СВОЕМУ устройству (secret = self-sync).
    SelfSync,
    /// Ящик ПРИГЛАШЕНИЙ владельца тега (secret = долгоживущий тег обнаружения,
    /// `docs/contact_requests.md`). Единственный подканал, адресуемый БЕЗ PSK —
    /// именно он разрывает круг «нет PSK ⇒ нет адреса ⇒ нечем согласовать PSK».
    /// Знание тега = capability написать приглашение; ротация тега его отзывает.
    Invite,
    /// Ящик ОТВЕТА на конкретное приглашение (secret = эфемерный `replyTag`,
    /// сгенерированный инициатором на один раунд). Отдельный от [`Self::Invite`],
    /// чтобы ответ не раскрывал долгоживущий тег инициатора.
    Reply,
}

impl MailboxKind {
    fn tag(self) -> &'static str {
        match self {
            MailboxKind::Conversation => "conv",
            MailboxKind::SelfSync => "self",
            MailboxKind::Invite => "invite",
            MailboxKind::Reply => "reply",
        }
    }
}

/// Выводит per-device идентификатор mailbox (v2) для мультидевайса (§7.1.1).
///
/// В отличие от [`mailbox_id`] (v1: роль 0/1 на переписку — один ящик на сторону),
/// v2 адресует КОНКРЕТНОЕ устройство владельца ящика, чтобы у каждого устройства
/// был свой входящий подканал (тогда деструктивный ack на релее не задевает
/// другие устройства — см. `docs/multidevice.md` §9):
///
/// `HKDF-SHA512(salt="AEGIS/mailbox/v2", ikm=secret,`
/// `             info="aegis/mailbox/v2/<kind>/<scope>/<device_id>")`.
///
/// `secret` — PSK переписки ([`MailboxKind::Conversation`]) либо self-sync секрет
/// аккаунта ([`MailboxKind::SelfSync`]). `scope` — `accountId` ВЛАДЕЛЬЦА ящика
/// (`aegis:<hex>`; без `/`), `device_id` — его device id (1..=255). И владелец
/// (слушает), и отправитель (доставляет) вычисляют один и тот же id, зная секрет.
/// Сервер видит только 256-бит capability, не связанный ни с accountId, ни с
/// рабочими ключами (MD-2/MD-3).
pub fn mailbox_id_device(
    secret: &[u8],
    kind: MailboxKind,
    scope: &str,
    device_id: u32,
) -> Result<[u8; MAILBOX_ID_LEN], AegisError> {
    if secret.len() < 16 {
        return Err(AegisError::InvalidArg(
            "секрет для mailbox-id (v2) должен быть >= 16 байт".into(),
        ));
    }
    let hk = Hkdf::<Sha512>::new(Some(MAILBOX_V2_SALT), secret);
    let info = format!("aegis/mailbox/v2/{}/{}/{}", kind.tag(), scope, device_id);
    let mut out = [0u8; MAILBOX_ID_LEN];
    hk.expand(info.as_bytes(), &mut out)
        .map_err(|_| AegisError::Crypto("HKDF expand (mailbox id v2) failed".into()))?;
    Ok(out)
}

/// Генерирует новый 256-битный PSK из криптографического ГСЧ ОС (§6.1).
/// Байты обёрнуты в [`Zeroizing`] — затираются при выходе из скоупа.
pub fn generate_psk() -> Result<Zeroizing<Vec<u8>>, AegisError> {
    let mut psk = Zeroizing::new(vec![0u8; PSK_LEN]);
    getrandom::getrandom(psk.as_mut_slice())
        .map_err(|e| AegisError::Crypto(format!("сбой ГСЧ ОС: {e}")))?;
    Ok(psk)
}

/// Отпечаток PSK для повторной сверки при подозрении (§6.2).
///
/// Это HKDF-SHA512(salt=FP_SALT, ikm=PSK, info=FP_INFO) → 32 байта. Функция
/// необратима: по отпечатку восстановить PSK невозможно, поэтому его безопасно
/// показывать/сверять. Детерминирован: одинаковый PSK → одинаковый отпечаток.
pub fn fingerprint(psk: &[u8]) -> Result<[u8; FINGERPRINT_LEN], AegisError> {
    if psk.len() < 16 {
        return Err(AegisError::InvalidArg(
            "PSK для отпечатка должен быть >= 16 байт".into(),
        ));
    }
    let hk = Hkdf::<Sha512>::new(Some(FP_SALT), psk);
    let mut out = [0u8; FINGERPRINT_LEN];
    hk.expand(FP_INFO, &mut out)
        .map_err(|_| AegisError::Crypto("HKDF expand (psk fingerprint) failed".into()))?;
    Ok(out)
}

/// Кодирует PSK в BIP-39-подобную мнемоническую фразу (ручной ввод, §6.2).
/// Длина PSK должна соответствовать BIP-39 (16/20/24/28/32 байта → 12..24 слова);
/// для стандартного 256-бит PSK это 24 слова.
pub fn to_mnemonic(psk: &[u8]) -> Result<String, AegisError> {
    let mnemonic = Mnemonic::from_entropy_in(Language::English, psk).map_err(|e| {
        AegisError::InvalidArg(format!("PSK не кодируется в мнемонику ({} байт): {e}", psk.len()))
    })?;
    Ok(mnemonic.to_string())
}

/// Восстанавливает PSK из мнемонической фразы (обратно к [`to_mnemonic`]).
/// Проверяет контрольную сумму BIP-39; при ошибке — [`AegisError::InvalidArg`].
pub fn from_mnemonic(phrase: &str) -> Result<Zeroizing<Vec<u8>>, AegisError> {
    let mnemonic = Mnemonic::parse_in_normalized(Language::English, phrase.trim())
        .map_err(|_| AegisError::InvalidArg("некорректная мнемоническая фраза PSK".into()))?;
    // to_entropy_array доступен без std-фич: ([u8;33], len).
    let (entropy, len) = mnemonic.to_entropy_array();
    Ok(Zeroizing::new(entropy[..len].to_vec()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_psk_has_expected_length() {
        let psk = generate_psk().unwrap();
        assert_eq!(psk.len(), PSK_LEN);
        // Два вызова подряд не должны совпадать (ГСЧ).
        let psk2 = generate_psk().unwrap();
        assert_ne!(psk.as_slice(), psk2.as_slice());
    }

    #[test]
    fn mnemonic_roundtrips_and_has_24_words() {
        let psk = generate_psk().unwrap();
        let phrase = to_mnemonic(&psk).unwrap();
        assert_eq!(phrase.split_whitespace().count(), 24, "256-бит PSK = 24 слова");
        let restored = from_mnemonic(&phrase).unwrap();
        assert_eq!(restored.as_slice(), psk.as_slice());
    }

    #[test]
    fn from_mnemonic_rejects_garbage() {
        assert!(from_mnemonic("это точно не корректная bip39 фраза nope nope nope").is_err());
    }

    #[test]
    fn fingerprint_is_deterministic_and_diverges() {
        let a = generate_psk().unwrap();
        let b = generate_psk().unwrap();
        assert_eq!(fingerprint(&a).unwrap(), fingerprint(&a).unwrap());
        assert_ne!(fingerprint(&a).unwrap(), fingerprint(&b).unwrap());
    }

    #[test]
    fn fingerprint_rejects_too_short() {
        assert!(fingerprint(b"short").is_err());
    }

    #[test]
    fn mailbox_id_is_deterministic_and_role_separated() {
        let psk = generate_psk().unwrap();
        // Детерминизм: тот же PSK+роль → тот же id.
        assert_eq!(mailbox_id(&psk, 0).unwrap(), mailbox_id(&psk, 0).unwrap());
        // Роли 0 и 1 дают РАЗНЫЕ подканалы (направленность).
        assert_ne!(mailbox_id(&psk, 0).unwrap(), mailbox_id(&psk, 1).unwrap());
        // Разный PSK → разный id (без знания PSK id невоспроизводим).
        let other = generate_psk().unwrap();
        assert_ne!(mailbox_id(&psk, 0).unwrap(), mailbox_id(&other, 0).unwrap());
        // Отделён от отпечатка (разные домены HKDF).
        assert_ne!(&mailbox_id(&psk, 0).unwrap()[..], &fingerprint(&psk).unwrap()[..]);
    }

    #[test]
    fn mailbox_id_rejects_too_short() {
        assert!(mailbox_id(b"short", 0).is_err());
    }

    #[test]
    fn mailbox_v2_is_deterministic_and_per_device() {
        let psk = generate_psk().unwrap();
        let acc = "aegis:0123456789abcdef0123456789abcdef";
        // Детерминизм: тот же секрет+scope+device → тот же id.
        assert_eq!(
            mailbox_id_device(&psk, MailboxKind::Conversation, acc, 1).unwrap(),
            mailbox_id_device(&psk, MailboxKind::Conversation, acc, 1).unwrap(),
        );
        // Разные устройства одного аккаунта → РАЗНЫЕ входящие подканалы.
        assert_ne!(
            mailbox_id_device(&psk, MailboxKind::Conversation, acc, 1).unwrap(),
            mailbox_id_device(&psk, MailboxKind::Conversation, acc, 2).unwrap(),
        );
    }

    #[test]
    fn mailbox_v2_separates_kind_scope_and_from_v1() {
        let psk = generate_psk().unwrap();
        let acc_a = "aegis:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let acc_b = "aegis:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        // conv и self-sync — разные домены при одинаковых secret/scope/device.
        assert_ne!(
            mailbox_id_device(&psk, MailboxKind::Conversation, acc_a, 1).unwrap(),
            mailbox_id_device(&psk, MailboxKind::SelfSync, acc_a, 1).unwrap(),
        );
        // Разные владельцы (scope) → разные ящики.
        assert_ne!(
            mailbox_id_device(&psk, MailboxKind::Conversation, acc_a, 1).unwrap(),
            mailbox_id_device(&psk, MailboxKind::Conversation, acc_b, 1).unwrap(),
        );
        // v2 отделён от v1 (разные соли HKDF) и от отпечатка.
        assert_ne!(
            &mailbox_id_device(&psk, MailboxKind::Conversation, acc_a, 1).unwrap()[..],
            &mailbox_id(&psk, 0).unwrap()[..],
        );
        assert_ne!(
            &mailbox_id_device(&psk, MailboxKind::Conversation, acc_a, 1).unwrap()[..],
            &fingerprint(&psk).unwrap()[..],
        );
        // Без знания секрета id невоспроизводим.
        let other = generate_psk().unwrap();
        assert_ne!(
            mailbox_id_device(&psk, MailboxKind::Conversation, acc_a, 1).unwrap(),
            mailbox_id_device(&other, MailboxKind::Conversation, acc_a, 1).unwrap(),
        );
    }

    #[test]
    fn mailbox_v2_rejects_too_short_secret() {
        assert!(mailbox_id_device(b"short", MailboxKind::Conversation, "aegis:x", 1).is_err());
    }

    /// Ящики удалённого добавления (invite/reply) отделены и друг от друга, и от
    /// переписки/self-sync — даже при одинаковых secret/scope/device.
    #[test]
    fn mailbox_v2_separates_invite_and_reply_domains() {
        let secret = generate_psk().unwrap();
        let acc = "aegis:0123456789abcdef0123456789abcdef";
        let kinds = [
            MailboxKind::Conversation,
            MailboxKind::SelfSync,
            MailboxKind::Invite,
            MailboxKind::Reply,
        ];
        let ids: Vec<_> = kinds
            .iter()
            .map(|&k| mailbox_id_device(&secret, k, acc, 1).unwrap())
            .collect();
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                assert_ne!(ids[i], ids[j], "домены {:?} и {:?} совпали", kinds[i], kinds[j]);
            }
        }
        // Детерминизм: обе стороны вычисляют один и тот же ящик приглашений.
        assert_eq!(
            mailbox_id_device(&secret, MailboxKind::Invite, acc, 1).unwrap(),
            mailbox_id_device(&secret, MailboxKind::Invite, acc, 1).unwrap(),
        );
        // Разные устройства владельца — разные ящики приглашений.
        assert_ne!(
            mailbox_id_device(&secret, MailboxKind::Invite, acc, 1).unwrap(),
            mailbox_id_device(&secret, MailboxKind::Invite, acc, 2).unwrap(),
        );
    }

    /// P4 связывает PSK со слоем A: сгенерированный PSK должен работать как
    /// секрет каскадного контейнера (§6.2 → container_secret).
    #[test]
    fn generated_psk_drives_cascade_container() {
        let psk = generate_psk().unwrap();
        let msg = b"psk-keyed cascade container payload";
        let container = crate::cascade::seal(&psk, msg, b"aegis/packet/v1").unwrap();
        let out = crate::cascade::open(&psk, &container, b"aegis/packet/v1").unwrap();
        assert_eq!(out.as_slice(), msg);
    }
}
