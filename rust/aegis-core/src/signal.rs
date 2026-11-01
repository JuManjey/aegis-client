//! Слой B — Signal Protocol (X3DH/PQXDH + Double Ratchet) через libsignal (P2, §6.1).
//!
//! ЭТОТ МОДУЛЬ КОМПИЛИРУЕТСЯ ТОЛЬКО С ФИЧЕЙ `signal`. Мы НЕ пишем свою
//! криптографию (§1) — используем официальную аудированную libsignal
//! (`libsignal-protocol`, git-зависимость, зафиксирована по тегу `v0.94.5`).
//!
//! ── Что здесь реализовано ─────────────────────────────────────────────────
//!  * [`two_party_roundtrip`] — интеграционный сценарий «два клиента»:
//!      1. Alice/Bob генерируют identity (`IdentityKeyPair::generate`).
//!      2. Bob публикует prekeys: signed pre-key + one-time pre-key + KYBER
//!         pre-key (v0.94.5 использует PQXDH — kyber pre-key ОБЯЗАТЕЛЕН).
//!      3. Alice выполняет X3DH/PQXDH: [`process_prekey_bundle`].
//!      4. Обмен сообщением: [`message_encrypt`] → [`message_decrypt`]
//!         (тип PreKey), ответ Bob→Alice (тип Whisper = ratchet сработал).
//!  * [`psk_session_binding`] — привязка ручного PSK к identity-ключам сессии
//!    через HKDF-тег (safety-code). Публичный API libsignal не даёт хука в KDF
//!    PQXDH; форкать аудированную библиотеку нельзя (§1).
//!  * [`psk_session_seal`] / [`psk_session_open`] — AEAD-обёртка plaintext
//!    слоя B на ключе HKDF(PSK, sort(idA‖idB)): без PSK и без тех identity,
//!    которые сторона реально увидела, payload не вскрыть, даже если слой A снят.
//!  * [`wire`] — JSON-формат публичного prekey-бандла для согласования с
//!    `aegis-server` (§0): сериализация/парсинг + проверка интеропа.
//!
//! ── Требования окружения для сборки фичи ──────────────────────────────────
//!  * `protoc` (Protocol Buffers compiler) — build.rs libsignal генерирует код
//!    через prost-build. Без protoc сборка фичи `signal` не пройдёт.
//!  * Rust >= 1.85 (крейт использует edition 2024) — у нас 1.97, ОК.

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use libsignal_protocol::{
    kem, message_decrypt, message_encrypt, process_prekey_bundle, CiphertextMessage,
    CiphertextMessageType, DeviceId, GenericSignedPreKey, IdentityKey, IdentityKeyPair,
    InMemSignalProtocolStore, KeyPair, KyberPreKeyRecord, PreKeyBundle, PreKeyRecord,
    PreKeySignalMessage, ProtocolAddress, PublicKey, SessionStore, SignalProtocolError,
    SignedPreKeyRecord, Timestamp,
};
use rand::rngs::OsRng;
use rand::{RngCore, TryRngCore};
use std::time::SystemTime;

/// Метка слоя B — для диагностики в UI.
pub fn linked_version() -> &'static str {
    "libsignal v0.94.5 (layer B: X3DH/PQXDH + Double Ratchet)"
}

// ---------------------------------------------------------------------------
// Мини-исполнитель: стораджи InMem синхронны, поэтому futures готовы сразу.
// Позволяет не тащить async-runtime в нативное ядро.
// ---------------------------------------------------------------------------

fn block_on<F: std::future::Future>(fut: F) -> F::Output {
    use std::task::{Context, Poll};
    let mut fut = std::pin::pin!(fut);
    let waker = std::task::Waker::noop();
    let mut cx = Context::from_waker(waker);
    loop {
        if let Poll::Ready(v) = fut.as_mut().poll(&mut cx) {
            return v;
        }
    }
}

/// Регистрационный ID Signal должен помещаться в 14 бит.
fn random_registration_id<R: rand::Rng>(rng: &mut R) -> u32 {
    rng.random::<u16>() as u32 & 0x3FFF
}

// ---------------------------------------------------------------------------
// PSK и Signal-сессия (§6.2): привязка + сессионная AEAD-обёртка.
// ---------------------------------------------------------------------------
//
// ЧЕСТНО о конструкции. Публичный API libsignal v0.94.5 (`process_prekey_bundle`)
// НЕ даёт хука для подмешивания внешнего секрета в KDF внутри PQXDH. Форкать
// аудированную библиотеку ради этого нельзя (§1). Поэтому AEGIS реализует
// требование §6.2 ДВУМЯ слоями ВНЕ KDF PQXDH:
//
//   1. binding = HKDF-SHA256(salt = PSK,
//                            ikm  = sort(idA_pub || idB_pub),
//                            info = "aegis/psk-x3dh-binding/v1")
//      — safety-code, сверка identity до активации чата.
//   2. seal_key = HKDF-SHA256(salt = PSK, ikm = тот же sort(idA‖idB),
//                             info = "aegis/psk-session-seal/v1")
//      — ChaCha20-Poly1305 вокруг plaintext ДО message_encrypt.
//
// Обе стороны вычисляют `binding` из тех identity-ключей, которые КАЖДАЯ из них
// фактически увидела при установлении сессии, и обязаны сверить значения до
// активации чата (совпадение = safety-code). Свойства:
//   * Сервер, полностью контролирующий раздачу публичных ключей, при MITM
//     подменит identity — тогда стороны увидят разные ключи, `binding`
//     разойдётся, чат не активируется. Это и есть закрытие «доверия к серверу
//     за раздачу ключей» (§6.2, §10.7).
//   * Не зная PSK, атакующий вообще не может вычислить корректный `binding`.
//   * Порядок сторон не важен: identity-ключи сортируются лексикографически.
// Конфиденциальность канала обеспечивают слой B (Double Ratchet) и слой A
// (каскад, P3); здесь PSK добавляет аутентичность, недостижимую без out-of-band.

const PSK_BINDING_INFO: &[u8] = b"aegis/psk-x3dh-binding/v1";
/// Домен отдельного AEAD-слоя, который подмешивает PSK в полезную нагрузку
/// Signal-сессии. Публичный API libsignal не даёт хука в KDF PQXDH — форкать
/// аудированную библиотеку нельзя (§1). Этот слой — собственная обёртка вокруг
/// plaintext ДО `message_encrypt`: без PSK и без тех identity-ключей, которые
/// сторона реально увидела, вскрыть payload нельзя, даже если слой A снят.
const PSK_SEAL_INFO: &[u8] = b"aegis/psk-session-seal/v1";
const PSK_SEAL_MAGIC: &[u8; 4] = b"A1S1";
const PSK_SEAL_NONCE_LEN: usize = 12;
const PSK_SEAL_TAG_LEN: usize = 16;

/// Вычисляет 32-байтовый тег связывания PSK с парой identity-ключей.
/// `local`/`remote` — как их видит конкретная сторона; порядок не важен.
pub fn psk_session_binding(
    psk: &[u8],
    local_identity: &IdentityKey,
    remote_identity: &IdentityKey,
) -> [u8; 32] {
    let ikm = sorted_identity_ikm(local_identity, remote_identity);
    let hk = hkdf::Hkdf::<sha2::Sha256>::new(Some(psk), &ikm);
    let mut out = [0u8; 32];
    hk.expand(PSK_BINDING_INFO, &mut out)
        .expect("32 — валидная длина вывода HKDF-SHA256");
    out
}

/// Ключ AEAD сессионной обёртки: HKDF-SHA256(salt=PSK, ikm=sort(idA‖idB),
/// info=psk-session-seal/v1). Домен отделён от биндинга/safety-code, чтобы
/// компрометация тега сверки не раскрывала ключ запечатывания.
fn psk_seal_key(
    psk: &[u8],
    local_identity: &IdentityKey,
    remote_identity: &IdentityKey,
) -> [u8; 32] {
    let ikm = sorted_identity_ikm(local_identity, remote_identity);
    let hk = hkdf::Hkdf::<sha2::Sha256>::new(Some(psk), &ikm);
    let mut out = [0u8; 32];
    hk.expand(PSK_SEAL_INFO, &mut out)
        .expect("32 — валидная длина вывода HKDF-SHA256");
    out
}

/// Запечатывает plaintext Signal-сессии AEAD на ключе из PSK+identity.
/// Формат: `A1S1 || nonce(12) || ciphertext||tag`. Без PSK / при подмене
/// identity тег не сойдётся.
pub fn psk_session_seal(
    psk: &[u8],
    local_identity: &IdentityKey,
    remote_identity: &IdentityKey,
    plaintext: &[u8],
) -> Result<Vec<u8>, SignalProtocolError> {
    if psk.is_empty() {
        return Err(SignalProtocolError::InvalidArgument(
            "PSK сессионной обёртки пуст".into(),
        ));
    }
    let key = psk_seal_key(psk, local_identity, remote_identity);
    let cipher = ChaCha20Poly1305::new_from_slice(&key).map_err(|_| {
        SignalProtocolError::InvalidArgument("некорректный ключ сессионной обёртки".into())
    })?;
    let mut nonce_bytes = [0u8; PSK_SEAL_NONCE_LEN];
    let mut rng = OsRng.unwrap_err();
    rng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);
    let ct = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext,
                aad: PSK_SEAL_INFO,
            },
        )
        .map_err(|_| {
            SignalProtocolError::InvalidArgument("не удалось запечатать PSK-слой".into())
        })?;
    let mut out = Vec::with_capacity(PSK_SEAL_MAGIC.len() + PSK_SEAL_NONCE_LEN + ct.len());
    out.extend_from_slice(PSK_SEAL_MAGIC);
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ct);
    Ok(out)
}

/// Снимает сессионную обёртку. Если магической метки нет — возвращает вход как
/// есть (совместимость со старыми конвертами без этого слоя). При метке и
/// провале тега — ошибка (fail-closed).
pub fn psk_session_open(
    psk: &[u8],
    local_identity: &IdentityKey,
    remote_identity: &IdentityKey,
    sealed: &[u8],
) -> Result<Vec<u8>, SignalProtocolError> {
    if !sealed.starts_with(PSK_SEAL_MAGIC) {
        return Ok(sealed.to_vec());
    }
    let min = PSK_SEAL_MAGIC.len() + PSK_SEAL_NONCE_LEN + PSK_SEAL_TAG_LEN;
    if sealed.len() < min {
        return Err(SignalProtocolError::InvalidArgument(
            "обрезанная сессионная обёртка PSK".into(),
        ));
    }
    if psk.is_empty() {
        return Err(SignalProtocolError::InvalidArgument(
            "PSK сессионной обёртки пуст".into(),
        ));
    }
    let nonce_off = PSK_SEAL_MAGIC.len();
    let ct_off = nonce_off + PSK_SEAL_NONCE_LEN;
    let key = psk_seal_key(psk, local_identity, remote_identity);
    let cipher = ChaCha20Poly1305::new_from_slice(&key).map_err(|_| {
        SignalProtocolError::InvalidArgument("некорректный ключ сессионной обёртки".into())
    })?;
    let nonce = Nonce::from_slice(&sealed[nonce_off..ct_off]);
    cipher
        .decrypt(
            nonce,
            Payload {
                msg: &sealed[ct_off..],
                aad: PSK_SEAL_INFO,
            },
        )
        .map_err(|_| SignalProtocolError::InvalidArgument("PSK-слой сессии не сошёлся".into()))
}

/// Есть ли на буфере метка сессионной обёртки v1.
pub fn psk_session_seal_present(buf: &[u8]) -> bool {
    buf.starts_with(PSK_SEAL_MAGIC)
}

/// Каноничный ikm из пары identity-ключей (порядок сторон не важен).
fn sorted_identity_ikm(local: &IdentityKey, remote: &IdentityKey) -> Vec<u8> {
    let a = local.serialize();
    let b = remote.serialize();
    let (lo, hi) = if a.as_ref() <= b.as_ref() {
        (a.as_ref(), b.as_ref())
    } else {
        (b.as_ref(), a.as_ref())
    };
    let mut ikm = Vec::with_capacity(lo.len() + hi.len());
    ikm.extend_from_slice(lo);
    ikm.extend_from_slice(hi);
    ikm
}

/// Человекочитаемый safety-code из тега связывания (для сверки в UI, §6.2):
/// шесть групп по пять цифр, как «safety number» Signal, но на базе PSK-тега.
pub fn psk_safety_code(binding: &[u8; 32]) -> String {
    binding
        .chunks(5)
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

// ---------------------------------------------------------------------------
// Публикация prekeys Bob и построение бандла.
// ---------------------------------------------------------------------------

/// Готовит Bob к приёму сессии: генерирует signed/one-time/KYBER pre-key,
/// сохраняет их в его хранилище и возвращает опубликованный `PreKeyBundle`.
fn publish_bob_prekeys<R: rand::Rng + rand::CryptoRng>(
    store: &mut InMemSignalProtocolStore,
    device_id: DeviceId,
    rng: &mut R,
) -> Result<PreKeyBundle, SignalProtocolError> {
    use libsignal_protocol::{IdentityKeyStore, KyberPreKeyStore, PreKeyStore, SignedPreKeyStore};

    let identity = block_on(store.get_identity_key_pair())?;
    let registration_id = block_on(store.get_local_registration_id())?;

    // One-time (EC) pre-key.
    let pre_key_id = 1u32;
    let pre_key_pair = KeyPair::generate(rng);
    block_on(store.save_pre_key(
        pre_key_id.into(),
        &PreKeyRecord::new(pre_key_id.into(), &pre_key_pair),
    ))?;

    // Signed pre-key (подписан identity-ключом Bob).
    let signed_pre_key_id = 1u32;
    let signed_pre_key_pair = KeyPair::generate(rng);
    let signed_pre_key_public = signed_pre_key_pair.public_key.serialize();
    let signed_pre_key_signature = identity
        .private_key()
        .calculate_signature(&signed_pre_key_public, rng)?;
    block_on(store.save_signed_pre_key(
        signed_pre_key_id.into(),
        &SignedPreKeyRecord::new(
            signed_pre_key_id.into(),
            Timestamp::from_epoch_millis(42),
            &signed_pre_key_pair,
            &signed_pre_key_signature,
        ),
    ))?;

    // KYBER pre-key — ОБЯЗАТЕЛЕН для PQXDH (v0.94.5).
    let kyber_pre_key_id = 1u32;
    let kyber_pre_key_pair = kem::KeyPair::generate(kem::KeyType::Kyber1024, rng);
    let kyber_pre_key_public = kyber_pre_key_pair.public_key.serialize();
    let kyber_pre_key_signature = identity
        .private_key()
        .calculate_signature(&kyber_pre_key_public, rng)?;
    block_on(store.save_kyber_pre_key(
        kyber_pre_key_id.into(),
        &KyberPreKeyRecord::new(
            kyber_pre_key_id.into(),
            Timestamp::from_epoch_millis(43),
            &kyber_pre_key_pair,
            &kyber_pre_key_signature,
        ),
    ))?;

    PreKeyBundle::new(
        registration_id,
        device_id,
        Some((pre_key_id.into(), pre_key_pair.public_key)),
        signed_pre_key_id.into(),
        signed_pre_key_pair.public_key,
        signed_pre_key_signature.to_vec(),
        kyber_pre_key_id.into(),
        kyber_pre_key_pair.public_key.clone(),
        kyber_pre_key_signature.to_vec(),
        *identity.identity_key(),
    )
}

// ---------------------------------------------------------------------------
// Интеграционный сценарий «два клиента».
// ---------------------------------------------------------------------------

/// Итог прогонки «двух клиентов» — безопасно для вывода в UI/логи диагностики.
#[derive(Debug, serde::Serialize)]
pub struct RoundtripReport {
    /// Полный роундтрип (оба сообщения расшифрованы верно) прошёл.
    pub ok: bool,
    /// Версия установленной сессии (для PQXDH/KYBER-aware ожидается 4).
    pub session_version: u32,
    /// Тип первого сообщения Alice→Bob (ожидается "PreKey").
    pub alice_to_bob_type: String,
    /// Тип ответа Bob→Alice (ожидается "Whisper" — ratchet сработал).
    pub bob_to_alice_type: String,
    /// Совпал ли PSK-биндинг у обеих сторон (None, если PSK не задан).
    pub psk_binding_matches: Option<bool>,
    /// Safety-code PSK-биндинга (None, если PSK не задан).
    pub psk_safety_code: Option<String>,
    /// Метка слоя B.
    pub layer_b: String,
}

fn message_type_name(t: CiphertextMessageType) -> String {
    match t {
        CiphertextMessageType::Whisper => "Whisper",
        CiphertextMessageType::PreKey => "PreKey",
        CiphertextMessageType::SenderKey => "SenderKey",
        CiphertextMessageType::Plaintext => "Plaintext",
    }
    .to_string()
}

/// Прогоняет полный обмен между двумя свежими клиентами.
///
/// Если `psk` задан — дополнительно проверяет PSK-биндинг (§6.2):
/// обе стороны вычисляют [`psk_session_binding`] по фактически увиденным
/// identity-ключам, значения должны совпасть.
pub fn two_party_roundtrip(psk: Option<&[u8]>) -> Result<RoundtripReport, SignalProtocolError> {
    let mut rng = OsRng.unwrap_err();

    let bob_device_id = DeviceId::new(1).expect("valid device id");
    let alice_address = ProtocolAddress::new("alice".to_owned(), DeviceId::new(1).unwrap());
    let bob_address = ProtocolAddress::new("bob".to_owned(), bob_device_id);

    // Свежие идентичности и хранилища обеих сторон.
    let alice_identity = IdentityKeyPair::generate(&mut rng);
    let bob_identity = IdentityKeyPair::generate(&mut rng);
    let alice_identity_pub = *alice_identity.identity_key();
    let bob_identity_pub = *bob_identity.identity_key();

    let mut alice_store =
        InMemSignalProtocolStore::new(alice_identity, random_registration_id(&mut rng))?;
    let mut bob_store =
        InMemSignalProtocolStore::new(bob_identity, random_registration_id(&mut rng))?;

    // Bob публикует prekeys; Alice получает бандл (в проде — через backend, §0).
    let bob_bundle = publish_bob_prekeys(&mut bob_store, bob_device_id, &mut rng)?;

    // Alice выполняет X3DH/PQXDH и создаёт сессию.
    block_on(process_prekey_bundle(
        &bob_address,
        &alice_address,
        &mut alice_store.session_store,
        &mut alice_store.identity_store,
        &bob_bundle,
        SystemTime::now(),
        &mut rng,
    ))?;

    // Alice → Bob (первое сообщение — тип PreKey).
    let original = "AEGIS layer B: два клиента, PQXDH + Double Ratchet";
    let a2b = block_on(message_encrypt(
        original.as_bytes(),
        &bob_address,
        &alice_address,
        &mut alice_store.session_store,
        &mut alice_store.identity_store,
        SystemTime::now(),
        &mut rng,
    ))?;
    let alice_to_bob_type = message_type_name(a2b.message_type());

    // Симулируем передачу по сети через сериализацию PreKeySignalMessage.
    let a2b_wire =
        CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(a2b.serialize())?);
    let bob_got = block_on(message_decrypt(
        &a2b_wire,
        &alice_address,
        &bob_address,
        &mut bob_store.session_store,
        &mut bob_store.identity_store,
        &mut bob_store.pre_key_store,
        &bob_store.signed_pre_key_store,
        &mut bob_store.kyber_pre_key_store,
        &mut rng,
    ))?;
    let bob_got_ok = bob_got.as_slice() == original.as_bytes();

    // Bob → Alice (ответ — тип Whisper, значит ratchet продвинулся).
    let response = "Who watches the watchers? — ответ через ratchet";
    let b2a = block_on(message_encrypt(
        response.as_bytes(),
        &alice_address,
        &bob_address,
        &mut bob_store.session_store,
        &mut bob_store.identity_store,
        SystemTime::now(),
        &mut rng,
    ))?;
    let bob_to_alice_type = message_type_name(b2a.message_type());

    let alice_got = block_on(message_decrypt(
        &b2a,
        &bob_address,
        &alice_address,
        &mut alice_store.session_store,
        &mut alice_store.identity_store,
        &mut alice_store.pre_key_store,
        &alice_store.signed_pre_key_store,
        &mut alice_store.kyber_pre_key_store,
        &mut rng,
    ))?;
    let alice_got_ok = alice_got.as_slice() == response.as_bytes();

    let session_version = block_on(bob_store.load_session(&alice_address))?
        .and_then(|rec| rec.session_version().ok())
        .unwrap_or(0);

    // PSK-биндинг: каждая сторона считает тег по своим ключам.
    let (psk_binding_matches, psk_safety_code) = match psk {
        Some(psk) => {
            let alice_side = psk_session_binding(psk, &alice_identity_pub, &bob_identity_pub);
            let bob_side = psk_session_binding(psk, &bob_identity_pub, &alice_identity_pub);
            let matches = alice_side == bob_side;
            (Some(matches), Some(psk_safety_code(&alice_side)))
        }
        None => (None, None),
    };

    Ok(RoundtripReport {
        ok: bob_got_ok && alice_got_ok,
        session_version,
        alice_to_bob_type,
        bob_to_alice_type,
        psk_binding_matches,
        psk_safety_code,
        layer_b: linked_version().to_string(),
    })
}

/// Генерирует свежую идентичность + prekeys и возвращает публичный бандл в
/// формате обмена с backend (§0). Используется мостом как эталон формата и
/// диагностика линковки слоя B на устройстве. Приватные ключи здесь не
/// экспортируются — их персистентное хранение появится вместе с БД (P6).
pub fn generate_demo_prekey_bundle() -> Result<wire::PreKeyBundlePublic, SignalProtocolError> {
    let mut rng = OsRng.unwrap_err();
    let device_id = DeviceId::new(1).expect("valid device id");
    let identity = IdentityKeyPair::generate(&mut rng);
    let mut store = InMemSignalProtocolStore::new(identity, random_registration_id(&mut rng))?;
    let bundle = publish_bob_prekeys(&mut store, device_id, &mut rng)?;
    wire::PreKeyBundlePublic::from_bundle(&bundle)
}

// ---------------------------------------------------------------------------
// Формат обмена prekeys с aegis-server (§0).
// ---------------------------------------------------------------------------

pub mod wire {
    //! JSON-безопасный публичный prekey-бандл — контракт формата с `aegis-server`.
    //!
    //! Backend хранит ТОЛЬКО публичные части (§7.6, zero-trust): identity-ключ,
    //! signed pre-key + подпись, KYBER pre-key + подпись и (опционально)
    //! один one-time pre-key. Приватные ключи и сессии никогда не уходят с
    //! устройства. Все бинарные поля кодируются base64 (STANDARD).

    use super::*;
    use serde::{Deserialize, Serialize};

    #[derive(Debug, Clone, Serialize, Deserialize)]
    pub struct PreKeyBundlePublic {
        /// Версия формата — для согласованной эволюции клиент/сервер.
        pub version: u8,
        pub registration_id: u32,
        pub device_id: u32,
        /// identity-ключ (base64, сериализация IdentityKey).
        pub identity_key: String,
        pub signed_pre_key_id: u32,
        pub signed_pre_key: String,
        pub signed_pre_key_sig: String,
        pub kyber_pre_key_id: u32,
        pub kyber_pre_key: String,
        pub kyber_pre_key_sig: String,
        pub one_time_pre_key_id: Option<u32>,
        pub one_time_pre_key: Option<String>,
    }

    impl PreKeyBundlePublic {
        pub const FORMAT_VERSION: u8 = 1;

        /// Строит публичный формат из внутреннего `PreKeyBundle` libsignal.
        pub fn from_bundle(bundle: &PreKeyBundle) -> Result<Self, SignalProtocolError> {
            let (one_time_pre_key_id, one_time_pre_key) = match (
                bundle.pre_key_id()?,
                bundle.pre_key_public()?,
            ) {
                (Some(id), Some(pk)) => (Some(u32::from(id)), Some(B64.encode(pk.serialize()))),
                _ => (None, None),
            };

            Ok(Self {
                version: Self::FORMAT_VERSION,
                registration_id: bundle.registration_id()?,
                device_id: bundle.device_id()?.into(),
                identity_key: B64.encode(bundle.identity_key()?.serialize()),
                signed_pre_key_id: bundle.signed_pre_key_id()?.into(),
                signed_pre_key: B64.encode(bundle.signed_pre_key_public()?.serialize()),
                signed_pre_key_sig: B64.encode(bundle.signed_pre_key_signature()?),
                kyber_pre_key_id: bundle.kyber_pre_key_id()?.into(),
                kyber_pre_key: B64.encode(bundle.kyber_pre_key_public()?.serialize()),
                kyber_pre_key_sig: B64.encode(bundle.kyber_pre_key_signature()?),
                one_time_pre_key_id,
                one_time_pre_key,
            })
        }

        /// Восстанавливает `PreKeyBundle` libsignal для X3DH на стороне отправителя.
        pub fn to_bundle(&self) -> Result<PreKeyBundle, SignalProtocolError> {
            let decode = |s: &str| -> Result<Vec<u8>, SignalProtocolError> {
                B64.decode(s.trim()).map_err(|_| {
                    SignalProtocolError::InvalidArgument("base64 в prekey-бандле".to_owned())
                })
            };

            let identity = IdentityKey::decode(&decode(&self.identity_key)?)?;
            let signed_pre_key = PublicKey::deserialize(&decode(&self.signed_pre_key)?)?;
            let kyber_pre_key = kem::PublicKey::deserialize(&decode(&self.kyber_pre_key)?)?;

            let one_time = match (self.one_time_pre_key_id, &self.one_time_pre_key) {
                (Some(id), Some(pk)) => {
                    Some((id.into(), PublicKey::deserialize(&decode(pk)?)?))
                }
                _ => None,
            };

            PreKeyBundle::new(
                self.registration_id,
                DeviceId::new(u8::try_from(self.device_id).map_err(|_| {
                    SignalProtocolError::InvalidArgument("device_id вне диапазона u8".to_owned())
                })?)
                .map_err(|_| SignalProtocolError::InvalidArgument("device_id".to_owned()))?,
                one_time,
                self.signed_pre_key_id.into(),
                signed_pre_key,
                decode(&self.signed_pre_key_sig)?,
                self.kyber_pre_key_id.into(),
                kyber_pre_key,
                decode(&self.kyber_pre_key_sig)?,
                identity,
            )
        }

        pub fn to_json(&self) -> String {
            serde_json::to_string(self).unwrap_or_else(|_| "{}".to_owned())
        }

        pub fn from_json(s: &str) -> Result<Self, SignalProtocolError> {
            serde_json::from_str(s).map_err(|e| {
                SignalProtocolError::InvalidArgument(format!("prekey json: {e}"))
            })
        }
    }
}

// ---------------------------------------------------------------------------
// P6 (§6.1, §6.3): Персистентные stateful-сессии Signal.
//
// ЗАЧЕМ. Роундтрип `two_party_roundtrip` доказал, что криптоядро X3DH/PQXDH +
// Double Ratchet работает, но использовал `InMemSignalProtocolStore` — состояние
// терялось между вызовами. Для реального мессенджера нужно: (1) хранить identity,
// prekeys и сессии между запусками; (2) отправлять/принимать реальные сообщения.
//
// МОДЕЛЬ. Мы НЕ форкаем libsignal (§1) и НЕ пишем свою крипту. Мы лишь реализуем
// объявленные libsignal трейты хранилищ поверх сериализуемых карт, где ЗНАЧЕНИЯ —
// это НАТИВНЫЕ записи libsignal (`SessionRecord`/`PreKeyRecord`/…), сериализованные
// их же методами `.serialize()`. Всё состояние аккаунта упаковывается в один
// непрозрачный blob (base64(JSON)), который Kotlin хранит в SQLCipher (ключ БД — в
// StrongBox). Каждая операция принимает старый blob и возвращает НОВЫЙ — так слой
// Rust остаётся без глобального изменяемого состояния (нет утечек/гонок), а точкой
// персистентности владеет зашифрованное хранилище.
//
// БЕЗОПАСНОСТЬ. Blob содержит приватные ключи и ключи сессий — он ОБЯЗАН лежать
// только в SQLCipher (см. Kotlin `SignalStateStore`). В сеть уходит лишь публичный
// prekey-бандл (`wire::PreKeyBundlePublic`) и шифртекст Double Ratchet.
// ---------------------------------------------------------------------------

/// Персистентные stateful-сессии поверх сериализуемых хранилищ libsignal (P6).
pub mod stateful {
    use super::wire::PreKeyBundlePublic;
    use super::{
        psk_safety_code, psk_session_binding, psk_session_open, psk_session_seal,
        random_registration_id,
    };
    use async_trait::async_trait;
    use base64::{
        engine::general_purpose::{STANDARD as B64, STANDARD_NO_PAD},
        Engine,
    };
    use libsignal_protocol::error::Result;
    use libsignal_protocol::{
        kem, message_decrypt, message_encrypt, process_prekey_bundle, CiphertextMessage,
        CiphertextMessageType, DeviceId, Direction, GenericSignedPreKey, IdentityChange,
        IdentityKey, IdentityKeyPair, IdentityKeyStore, KeyPair, KyberPreKeyId, KyberPreKeyRecord,
        KyberPreKeyStore, PreKeyBundle, PreKeyId, PreKeyRecord, PreKeySignalMessage, PreKeyStore,
        ProtocolAddress, PublicKey, SessionRecord, SessionStore, SignalMessage,
        SignalProtocolError, SignedPreKeyId, SignedPreKeyRecord, SignedPreKeyStore, Timestamp,
    };
    use rand::rngs::OsRng;
    use rand::TryRngCore;
    use std::collections::HashMap;
    use std::time::SystemTime;

    const BLOB_VERSION: u8 = 1;
    /// Локальный device id клиента (мультидевайс — §7.1.1, будущая работа).
    pub const LOCAL_DEVICE_ID: u32 = 1;

    // Синхронный исполнитель: наши хранилища не делают I/O, futures готовы сразу.
    fn block_on<F: std::future::Future>(fut: F) -> F::Output {
        super::block_on(fut)
    }

    fn addr(name: &str, device: u32) -> Result<ProtocolAddress> {
        let d = u8::try_from(device)
            .map_err(|_| SignalProtocolError::InvalidArgument("device id вне диапазона".into()))?;
        Ok(ProtocolAddress::new(
            name.to_owned(),
            DeviceId::new(d).map_err(|_| SignalProtocolError::InvalidArgument("device id".into()))?,
        ))
    }

    fn addr_parts(a: &ProtocolAddress) -> (String, u32) {
        (a.name().to_owned(), u32::from(a.device_id()))
    }

    // ── Сериализуемые реализации трейтов хранилищ libsignal ──────────────────

    #[derive(Clone)]
    struct IdStore {
        key_pair: IdentityKeyPair,
        registration_id: u32,
        known: HashMap<ProtocolAddress, IdentityKey>,
    }

    #[async_trait(?Send)]
    impl IdentityKeyStore for IdStore {
        async fn get_identity_key_pair(&self) -> Result<IdentityKeyPair> {
            Ok(self.key_pair)
        }
        async fn get_local_registration_id(&self) -> Result<u32> {
            Ok(self.registration_id)
        }
        async fn save_identity(
            &mut self,
            address: &ProtocolAddress,
            identity: &IdentityKey,
        ) -> Result<IdentityChange> {
            match self.known.get(address) {
                None => {
                    self.known.insert(address.clone(), *identity);
                    Ok(IdentityChange::NewOrUnchanged)
                }
                Some(k) if k == identity => Ok(IdentityChange::NewOrUnchanged),
                Some(_) => {
                    self.known.insert(address.clone(), *identity);
                    Ok(IdentityChange::ReplacedExisting)
                }
            }
        }
        async fn is_trusted_identity(
            &self,
            address: &ProtocolAddress,
            identity: &IdentityKey,
            _direction: Direction,
        ) -> Result<bool> {
            // TOFU, только пока ключ не закреплён из доверенного канала
            // (`Store::pin_identity`); смена identity (MITM сервером за раздачу
            // ключей) детектируется здесь И дополнительно PSK-биндингом (§6.2).
            match self.known.get(address) {
                None => Ok(true),
                Some(k) => Ok(k == identity),
            }
        }
        async fn get_identity(&self, address: &ProtocolAddress) -> Result<Option<IdentityKey>> {
            Ok(self.known.get(address).cloned())
        }
    }

    #[derive(Clone, Default)]
    struct PkStore {
        pre_keys: HashMap<PreKeyId, PreKeyRecord>,
    }

    #[async_trait(?Send)]
    impl PreKeyStore for PkStore {
        async fn get_pre_key(&self, id: PreKeyId) -> Result<PreKeyRecord> {
            Ok(self
                .pre_keys
                .get(&id)
                .ok_or(SignalProtocolError::InvalidPreKeyId)?
                .clone())
        }
        async fn save_pre_key(&mut self, id: PreKeyId, record: &PreKeyRecord) -> Result<()> {
            self.pre_keys.insert(id, record.clone());
            Ok(())
        }
        async fn remove_pre_key(&mut self, id: PreKeyId) -> Result<()> {
            self.pre_keys.remove(&id);
            Ok(())
        }
    }

    #[derive(Clone, Default)]
    struct SpkStore {
        signed: HashMap<SignedPreKeyId, SignedPreKeyRecord>,
    }

    #[async_trait(?Send)]
    impl SignedPreKeyStore for SpkStore {
        async fn get_signed_pre_key(&self, id: SignedPreKeyId) -> Result<SignedPreKeyRecord> {
            Ok(self
                .signed
                .get(&id)
                .ok_or(SignalProtocolError::InvalidSignedPreKeyId)?
                .clone())
        }
        async fn save_signed_pre_key(
            &mut self,
            id: SignedPreKeyId,
            record: &SignedPreKeyRecord,
        ) -> Result<()> {
            self.signed.insert(id, record.clone());
            Ok(())
        }
    }

    #[derive(Clone, Default)]
    struct KpkStore {
        kyber: HashMap<KyberPreKeyId, KyberPreKeyRecord>,
        seen: HashMap<(KyberPreKeyId, SignedPreKeyId), Vec<PublicKey>>,
    }

    #[async_trait(?Send)]
    impl KyberPreKeyStore for KpkStore {
        async fn get_kyber_pre_key(&self, id: KyberPreKeyId) -> Result<KyberPreKeyRecord> {
            Ok(self
                .kyber
                .get(&id)
                .ok_or(SignalProtocolError::InvalidKyberPreKeyId)?
                .clone())
        }
        async fn save_kyber_pre_key(
            &mut self,
            id: KyberPreKeyId,
            record: &KyberPreKeyRecord,
        ) -> Result<()> {
            self.kyber.insert(id, record.clone());
            Ok(())
        }
        async fn mark_kyber_pre_key_used(
            &mut self,
            kyber_prekey_id: KyberPreKeyId,
            ec_prekey_id: SignedPreKeyId,
            base_key: &PublicKey,
        ) -> Result<()> {
            let seen = self.seen.entry((kyber_prekey_id, ec_prekey_id)).or_default();
            if seen.contains(base_key) {
                return Err(SignalProtocolError::InvalidMessage(
                    CiphertextMessageType::PreKey,
                    "reused base key".to_owned(),
                ));
            }
            seen.push(*base_key);
            Ok(())
        }
    }

    #[derive(Clone, Default)]
    struct SessStore {
        sessions: HashMap<ProtocolAddress, SessionRecord>,
    }

    #[async_trait(?Send)]
    impl SessionStore for SessStore {
        async fn load_session(&self, address: &ProtocolAddress) -> Result<Option<SessionRecord>> {
            Ok(self.sessions.get(address).cloned())
        }
        async fn store_session(
            &mut self,
            address: &ProtocolAddress,
            record: &SessionRecord,
        ) -> Result<()> {
            self.sessions.insert(address.clone(), record.clone());
            Ok(())
        }
    }

    /// Полное состояние Signal-аккаунта (identity + prekeys + сессии). Отдельные
    /// под-хранилища — как в `InMemSignalProtocolStore`, чтобы `message_decrypt`
    /// мог заимствовать их независимо (5 раздельных `&mut`).
    pub struct Store {
        ids: IdStore,
        sess: SessStore,
        pre: PkStore,
        spk: SpkStore,
        kpk: KpkStore,
        next_pre: u32,
        next_spk: u32,
        next_kpk: u32,
    }

    // ── Сериализация состояния в непрозрачный blob (JSON, хранится в SQLCipher) ──

    #[derive(serde::Serialize, serde::Deserialize)]
    struct StateBlob {
        v: u8,
        idkp: String,
        reg: u32,
        #[serde(default)]
        ident: Vec<(String, u32, String)>,
        #[serde(default)]
        sess: Vec<(String, u32, String)>,
        #[serde(default)]
        pre: Vec<(u32, String)>,
        #[serde(default)]
        spk: Vec<(u32, String)>,
        #[serde(default)]
        kpk: Vec<(u32, String)>,
        #[serde(default)]
        kseen: Vec<(u32, u32, Vec<String>)>,
        #[serde(default)]
        next_pre: u32,
        #[serde(default)]
        next_spk: u32,
        #[serde(default)]
        next_kpk: u32,
    }

    fn invalid(msg: &str) -> SignalProtocolError {
        SignalProtocolError::InvalidArgument(msg.to_owned())
    }

    fn b64d(s: &str) -> Result<Vec<u8>> {
        B64.decode(s.trim()).map_err(|_| invalid("некорректный base64 в blob состояния"))
    }

    /// identity-ключ из base64 без учёта паддинга — так же его сравнивает
    /// Kotlin (`PrekeyBundle.identityMatches`).
    fn decode_identity(b64: &str) -> Result<IdentityKey> {
        let bytes = STANDARD_NO_PAD
            .decode(b64.trim().trim_end_matches('='))
            .map_err(|_| invalid("некорректный base64 identity-ключа"))?;
        IdentityKey::decode(&bytes)
    }

    impl Store {
        fn to_blob(&self) -> StateBlob {
            let ident = self
                .ids
                .known
                .iter()
                .map(|(a, k)| {
                    let (n, d) = addr_parts(a);
                    (n, d, B64.encode(k.serialize()))
                })
                .collect();
            let sess = self
                .sess
                .sessions
                .iter()
                .filter_map(|(a, r)| {
                    let (n, d) = addr_parts(a);
                    r.serialize().ok().map(|b| (n, d, B64.encode(b)))
                })
                .collect();
            let pre = self
                .pre
                .pre_keys
                .iter()
                .filter_map(|(id, r)| r.serialize().ok().map(|b| (u32::from(*id), B64.encode(b))))
                .collect();
            let spk = self
                .spk
                .signed
                .iter()
                .filter_map(|(id, r)| r.serialize().ok().map(|b| (u32::from(*id), B64.encode(b))))
                .collect();
            let kpk = self
                .kpk
                .kyber
                .iter()
                .filter_map(|(id, r)| r.serialize().ok().map(|b| (u32::from(*id), B64.encode(b))))
                .collect();
            let kseen = self
                .kpk
                .seen
                .iter()
                .map(|((kid, eid), keys)| {
                    (
                        u32::from(*kid),
                        u32::from(*eid),
                        keys.iter().map(|k| B64.encode(k.serialize())).collect(),
                    )
                })
                .collect();
            StateBlob {
                v: BLOB_VERSION,
                idkp: B64.encode(self.ids.key_pair.serialize()),
                reg: self.ids.registration_id,
                ident,
                sess,
                pre,
                spk,
                kpk,
                kseen,
                next_pre: self.next_pre,
                next_spk: self.next_spk,
                next_kpk: self.next_kpk,
            }
        }

        fn from_blob(blob: StateBlob) -> Result<Self> {
            let key_pair = IdentityKeyPair::try_from(b64d(&blob.idkp)?.as_slice())
                .map_err(|_| invalid("повреждён identity-ключ в blob"))?;
            let mut known = HashMap::new();
            for (n, d, k) in blob.ident {
                known.insert(addr(&n, d)?, IdentityKey::decode(&b64d(&k)?)?);
            }
            let mut sessions = HashMap::new();
            for (n, d, r) in blob.sess {
                sessions.insert(addr(&n, d)?, SessionRecord::deserialize(&b64d(&r)?)?);
            }
            let mut pre_keys = HashMap::new();
            for (id, r) in blob.pre {
                pre_keys.insert(id.into(), PreKeyRecord::deserialize(&b64d(&r)?)?);
            }
            let mut signed = HashMap::new();
            for (id, r) in blob.spk {
                signed.insert(id.into(), SignedPreKeyRecord::deserialize(&b64d(&r)?)?);
            }
            let mut kyber = HashMap::new();
            for (id, r) in blob.kpk {
                kyber.insert(id.into(), KyberPreKeyRecord::deserialize(&b64d(&r)?)?);
            }
            let mut seen: HashMap<(KyberPreKeyId, SignedPreKeyId), Vec<PublicKey>> = HashMap::new();
            for (kid, eid, keys) in blob.kseen {
                let mut v = Vec::with_capacity(keys.len());
                for k in keys {
                    v.push(PublicKey::deserialize(&b64d(&k)?)?);
                }
                seen.insert((kid.into(), eid.into()), v);
            }
            Ok(Store {
                ids: IdStore {
                    key_pair,
                    registration_id: blob.reg,
                    known,
                },
                sess: SessStore { sessions },
                pre: PkStore { pre_keys },
                spk: SpkStore { signed },
                kpk: KpkStore { kyber, seen },
                next_pre: blob.next_pre,
                next_spk: blob.next_spk,
                next_kpk: blob.next_kpk,
            })
        }

        fn encode(&self) -> Result<String> {
            let bytes = serde_json::to_vec(&self.to_blob())
                .map_err(|_| invalid("не удалось сериализовать состояние"))?;
            Ok(B64.encode(bytes))
        }

        fn decode(blob_b64: &str) -> Result<Self> {
            let bytes = b64d(blob_b64)?;
            let blob: StateBlob = serde_json::from_slice(&bytes)
                .map_err(|_| invalid("не удалось разобрать состояние"))?;
            Self::from_blob(blob)
        }

        /// Публичный identity-ключ аккаунта (для PSK-биндинга и отпечатка).
        fn identity_key(&self) -> IdentityKey {
            *self.ids.key_pair.identity_key()
        }

        /// Закрепляет identity удалённого устройства, полученный по доверенному
        /// каналу (QR/NFC, подписанная запись каталога или заявка, объявление
        /// устройства под слоем A), поверх того, что запомнил TOFU. Дальше
        /// libsignal сам отвергает (`UntrustedIdentity`) бандл, PreKey-сообщение
        /// и сессию на другом ключе — в том числе ключ, который сервер успел
        /// закрепить первым. Пустая строка — ключ неизвестен, остаётся TOFU.
        fn pin_identity(&mut self, remote: &ProtocolAddress, expected_b64: &str) -> Result<()> {
            if expected_b64.trim().is_empty() {
                return Ok(());
            }
            self.ids
                .known
                .insert(remote.clone(), decode_identity(expected_b64)?);
            Ok(())
        }
    }

    // ── Высокоуровневые операции (принимают/возвращают blob) ─────────────────

    /// Создаёт свежий Signal-аккаунт (identity + registration id, пустые сессии).
    /// Возвращает непрозрачный blob состояния (base64) для хранения в SQLCipher.
    pub fn create_account() -> Result<String> {
        let mut rng = OsRng.unwrap_err();
        let identity = IdentityKeyPair::generate(&mut rng);
        let store = Store {
            ids: IdStore {
                key_pair: identity,
                registration_id: random_registration_id(&mut rng),
                known: HashMap::new(),
            },
            sess: SessStore::default(),
            pre: PkStore::default(),
            spk: SpkStore::default(),
            kpk: KpkStore::default(),
            next_pre: 0,
            next_spk: 0,
            next_kpk: 0,
        };
        store.encode()
    }

    /// Генерирует новый комплект prekeys (signed + kyber + one-time EC), сохраняет
    /// приватные части в состоянии и возвращает публичный бандл (`wire`-формат §0)
    /// для публикации на backend. Возвращает `(новый blob, бандл)`.
    pub fn publish_prekeys(blob_b64: &str) -> Result<(String, PreKeyBundlePublic)> {
        publish_prekeys_dev(blob_b64, LOCAL_DEVICE_ID)
    }

    /// Как [`publish_prekeys`], но публикует бандл под ЯВНЫМ локальным device id
    /// (мультидевайс, §7.1.1, `docs/multidevice.md`). Бандл несёт `device_id`,
    /// чтобы peer адресовал X3DH именно этому устройству аккаунта. Для первичного
    /// устройства `local_device == LOCAL_DEVICE_ID (1)` — поведение [`publish_prekeys`].
    pub fn publish_prekeys_dev(
        blob_b64: &str,
        local_device: u32,
    ) -> Result<(String, PreKeyBundlePublic)> {
        let mut store = Store::decode(blob_b64)?;
        let mut rng = OsRng.unwrap_err();

        let identity = store.ids.key_pair;
        let registration_id = store.ids.registration_id;
        let device = addr("self", local_device)?.device_id();

        let pre_key_id = store.next_pre + 1;
        let signed_pre_key_id = store.next_spk + 1;
        let kyber_pre_key_id = store.next_kpk + 1;

        // One-time EC pre-key.
        let pre_key_pair = KeyPair::generate(&mut rng);
        block_on(store.pre.save_pre_key(
            pre_key_id.into(),
            &PreKeyRecord::new(pre_key_id.into(), &pre_key_pair),
        ))?;

        // Signed pre-key (подписан identity-ключом).
        let signed_pre_key_pair = KeyPair::generate(&mut rng);
        let signed_pub = signed_pre_key_pair.public_key.serialize();
        let signed_sig = identity.private_key().calculate_signature(&signed_pub, &mut rng)?;
        block_on(store.spk.save_signed_pre_key(
            signed_pre_key_id.into(),
            &SignedPreKeyRecord::new(
                signed_pre_key_id.into(),
                Timestamp::from_epoch_millis(now_millis()),
                &signed_pre_key_pair,
                &signed_sig,
            ),
        ))?;

        // KYBER pre-key — обязателен для PQXDH (v0.94.5).
        let kyber_pair = kem::KeyPair::generate(kem::KeyType::Kyber1024, &mut rng);
        let kyber_pub = kyber_pair.public_key.serialize();
        let kyber_sig = identity.private_key().calculate_signature(&kyber_pub, &mut rng)?;
        block_on(store.kpk.save_kyber_pre_key(
            kyber_pre_key_id.into(),
            &KyberPreKeyRecord::new(
                kyber_pre_key_id.into(),
                Timestamp::from_epoch_millis(now_millis()),
                &kyber_pair,
                &kyber_sig,
            ),
        ))?;

        store.next_pre = pre_key_id;
        store.next_spk = signed_pre_key_id;
        store.next_kpk = kyber_pre_key_id;

        let bundle = PreKeyBundle::new(
            registration_id,
            device,
            Some((pre_key_id.into(), pre_key_pair.public_key)),
            signed_pre_key_id.into(),
            signed_pre_key_pair.public_key,
            signed_sig.to_vec(),
            kyber_pre_key_id.into(),
            kyber_pair.public_key.clone(),
            kyber_sig.to_vec(),
            *identity.identity_key(),
        )?;
        let public = PreKeyBundlePublic::from_bundle(&bundle)?;
        Ok((store.encode()?, public))
    }

    /// Устанавливает исходящую сессию с удалённым адресом по его публичному
    /// prekey-бандлу (X3DH/PQXDH). Возвращает новый blob состояния. Без
    /// закреплённого identity (TOFU) — см. [`process_bundle_dev`].
    pub fn process_bundle(
        blob_b64: &str,
        local_name: &str,
        remote_name: &str,
        remote_device: u32,
        bundle_json: &str,
    ) -> Result<String> {
        process_bundle_dev(
            blob_b64,
            local_name,
            LOCAL_DEVICE_ID,
            remote_name,
            remote_device,
            bundle_json,
            "",
        )
    }

    /// Как [`process_bundle`], но с ЯВНЫМ локальным device id (мультидевайс).
    /// Позволяет одному аккаунту держать раздельные исходящие сессии с одним и
    /// тем же удалённым устройством из разных СВОИХ устройств (`docs/multidevice.md`).
    /// `expected_identity_b64` закрепляется до X3DH ([`Store::pin_identity`]):
    /// бандл на другом identity отвергается.
    pub fn process_bundle_dev(
        blob_b64: &str,
        local_name: &str,
        local_device: u32,
        remote_name: &str,
        remote_device: u32,
        bundle_json: &str,
        expected_identity_b64: &str,
    ) -> Result<String> {
        let mut store = Store::decode(blob_b64)?;
        let mut rng = OsRng.unwrap_err();
        let bundle = PreKeyBundlePublic::from_json(bundle_json)?.to_bundle()?;
        let local = addr(local_name, local_device)?;
        let remote = addr(remote_name, remote_device)?;
        store.pin_identity(&remote, expected_identity_b64)?;
        block_on(process_prekey_bundle(
            &remote,
            &local,
            &mut store.sess,
            &mut store.ids,
            &bundle,
            SystemTime::now(),
            &mut rng,
        ))?;
        store.encode()
    }

    /// Есть ли уже установленная сессия с удалённым адресом.
    pub fn has_session(blob_b64: &str, remote_name: &str, remote_device: u32) -> Result<bool> {
        let store = Store::decode(blob_b64)?;
        let remote = addr(remote_name, remote_device)?;
        Ok(block_on(store.sess.load_session(&remote))?.is_some())
    }

    /// Есть ли сессия с удалённым адресом НА закреплённом identity. Сессия на
    /// другом ключе считается отсутствующей: вызывающий заведёт новую по
    /// сверенному бандлу, а не упрётся в отказ libsignal на каждом шифровании.
    /// Пустой `expected_identity_b64` — то же, что [`has_session`].
    pub fn has_trusted_session(
        blob_b64: &str,
        remote_name: &str,
        remote_device: u32,
        expected_identity_b64: &str,
    ) -> Result<bool> {
        let store = Store::decode(blob_b64)?;
        let remote = addr(remote_name, remote_device)?;
        let Some(record) = block_on(store.sess.load_session(&remote))? else {
            return Ok(false);
        };
        if expected_identity_b64.trim().is_empty() {
            return Ok(true);
        }
        let expected = decode_identity(expected_identity_b64)?;
        let current = record
            .remote_identity_key_bytes()
            .ok()
            .flatten()
            .and_then(|bytes| IdentityKey::decode(&bytes).ok());
        Ok(current == Some(expected))
    }

    /// Зашифровывает сообщение слоя B (Double Ratchet). Возвращает
    /// `(новый blob, тип сообщения, шифртекст)`. Тип: "PreKey" (первое) / "Whisper".
    /// Без закреплённого identity (TOFU) — см. [`encrypt_dev`].
    pub fn encrypt(
        blob_b64: &str,
        local_name: &str,
        remote_name: &str,
        remote_device: u32,
        plaintext: &[u8],
    ) -> Result<(String, String, Vec<u8>)> {
        encrypt_dev(
            blob_b64,
            local_name,
            LOCAL_DEVICE_ID,
            remote_name,
            remote_device,
            plaintext,
            "",
        )
    }

    /// Как [`encrypt`], но шифрует ОТ ИМЕНИ явного локального device id
    /// (мультидевайс, §7.1.1). Каждое своё устройство ведёт свой Double Ratchet
    /// с каждым устройством получателя (fan-out, `docs/multidevice.md` §6).
    /// С `expected_identity_b64` сессия на другом identity не используется.
    pub fn encrypt_dev(
        blob_b64: &str,
        local_name: &str,
        local_device: u32,
        remote_name: &str,
        remote_device: u32,
        plaintext: &[u8],
        expected_identity_b64: &str,
    ) -> Result<(String, String, Vec<u8>)> {
        let mut store = Store::decode(blob_b64)?;
        let mut rng = OsRng.unwrap_err();
        let local = addr(local_name, local_device)?;
        let remote = addr(remote_name, remote_device)?;
        store.pin_identity(&remote, expected_identity_b64)?;
        let msg = block_on(message_encrypt(
            plaintext,
            &remote,
            &local,
            &mut store.sess,
            &mut store.ids,
            SystemTime::now(),
            &mut rng,
        ))?;
        let type_name = super::message_type_name(msg.message_type());
        let ciphertext = msg.serialize().to_vec();
        Ok((store.encode()?, type_name, ciphertext))
    }

    /// Расшифровывает входящее сообщение слоя B. `msg_type`: "PreKey" | "Whisper".
    /// Возвращает `(новый blob, plaintext)`. Без закреплённого identity (TOFU) —
    /// см. [`decrypt_dev`].
    pub fn decrypt(
        blob_b64: &str,
        local_name: &str,
        remote_name: &str,
        remote_device: u32,
        ciphertext: &[u8],
        msg_type: &str,
    ) -> Result<(String, Vec<u8>)> {
        decrypt_dev(
            blob_b64,
            local_name,
            LOCAL_DEVICE_ID,
            remote_name,
            remote_device,
            ciphertext,
            msg_type,
            "",
        )
    }

    /// Как [`decrypt`], но расшифровывает НА явном локальном device id
    /// (мультидевайс, §7.1.1). `remote_device` — device id ОТПРАВИТЕЛЯ (его несёт
    /// конверт на проводе, `docs/multidevice.md` §7), чтобы вести отдельную
    /// сессию с каждым устройством собеседника. С `expected_identity_b64`
    /// PreKey-сообщение на другом identity отвергается, не меняя состояния.
    #[allow(clippy::too_many_arguments)]
    pub fn decrypt_dev(
        blob_b64: &str,
        local_name: &str,
        local_device: u32,
        remote_name: &str,
        remote_device: u32,
        ciphertext: &[u8],
        msg_type: &str,
        expected_identity_b64: &str,
    ) -> Result<(String, Vec<u8>)> {
        let mut store = Store::decode(blob_b64)?;
        let mut rng = OsRng.unwrap_err();
        let local = addr(local_name, local_device)?;
        let remote = addr(remote_name, remote_device)?;
        store.pin_identity(&remote, expected_identity_b64)?;

        let msg = match msg_type {
            "PreKey" => {
                CiphertextMessage::PreKeySignalMessage(PreKeySignalMessage::try_from(ciphertext)?)
            }
            "Whisper" => CiphertextMessage::SignalMessage(SignalMessage::try_from(ciphertext)?),
            other => return Err(invalid(&format!("неизвестный тип сообщения: {other}"))),
        };

        let plaintext = block_on(message_decrypt(
            &msg,
            &remote,
            &local,
            &mut store.sess,
            &mut store.ids,
            &mut store.pre,
            &store.spk,
            &mut store.kpk,
            &mut rng,
        ))?;
        Ok((store.encode()?, plaintext))
    }

    /// Публичный identity-ключ аккаунта (base64) — для PSK-биндинга и отпечатка.
    pub fn identity_key_b64(blob_b64: &str) -> Result<String> {
        Ok(B64.encode(Store::decode(blob_b64)?.identity_key().serialize()))
    }

    /// Вычисляет PSK-биндинг (§6.2) по локальному и удалённому identity-ключам
    /// (оба base64) и ручному PSK. Возвращает `(тег, safety-code)`.
    pub fn compute_psk_binding(
        local_identity_b64: &str,
        remote_identity_b64: &str,
        psk: &[u8],
    ) -> Result<([u8; 32], String)> {
        let local = IdentityKey::decode(&b64d(local_identity_b64)?)?;
        let remote = IdentityKey::decode(&b64d(remote_identity_b64)?)?;
        let binding = psk_session_binding(psk, &local, &remote);
        let code = psk_safety_code(&binding);
        Ok((binding, code))
    }

    /// Запечатывает plaintext сессионной обёрткой PSK (см. [`crate::signal::psk_session_seal`]).
    pub fn psk_session_seal_b64(
        local_identity_b64: &str,
        remote_identity_b64: &str,
        psk: &[u8],
        plaintext: &[u8],
    ) -> Result<Vec<u8>> {
        let local = IdentityKey::decode(&b64d(local_identity_b64)?)?;
        let remote = IdentityKey::decode(&b64d(remote_identity_b64)?)?;
        psk_session_seal(psk, &local, &remote, plaintext)
    }

    /// Снимает сессионную обёртку PSK (legacy без метки пропускается).
    pub fn psk_session_open_b64(
        local_identity_b64: &str,
        remote_identity_b64: &str,
        psk: &[u8],
        sealed: &[u8],
    ) -> Result<Vec<u8>> {
        let local = IdentityKey::decode(&b64d(local_identity_b64)?)?;
        let remote = IdentityKey::decode(&b64d(remote_identity_b64)?)?;
        psk_session_open(psk, &local, &remote, sealed)
    }

    fn now_millis() -> u64 {
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn libsignal_links() {
        assert!(linked_version().contains("libsignal"));
    }

    /// Основной тест P2: два клиента устанавливают сессию (X3DH/PQXDH) и
    /// обмениваются сообщениями в обе стороны через Double Ratchet.
    #[test]
    fn two_party_x3dh_double_ratchet_roundtrip() {
        let report = two_party_roundtrip(None).expect("roundtrip ok");
        assert!(report.ok, "оба сообщения должны расшифроваться верно");
        // v0.94.5 = PQXDH/KYBER-aware сессия -> версия 4.
        assert_eq!(report.session_version, 4, "ожидается KYBER-aware сессия v4");
        assert_eq!(report.alice_to_bob_type, "PreKey");
        assert_eq!(report.bob_to_alice_type, "Whisper");
        assert!(report.psk_binding_matches.is_none());
    }

    /// PSK-биндинг (§6.2): совпадает у честных сторон и
    /// расходится при подмене identity (MITM сервером за раздачу ключей).
    #[test]
    fn psk_binding_agrees_and_detects_mitm() {
        let report = two_party_roundtrip(Some(b"aegis-manual-psk-out-of-band")).expect("ok");
        assert_eq!(report.psk_binding_matches, Some(true));
        assert!(report.psk_safety_code.is_some());

        let mut rng = OsRng.unwrap_err();
        let alice = *IdentityKeyPair::generate(&mut rng).identity_key();
        let bob = *IdentityKeyPair::generate(&mut rng).identity_key();
        let mallory = *IdentityKeyPair::generate(&mut rng).identity_key();
        let psk = b"shared-secret";

        // Честные стороны видят одни и те же ключи (порядок не важен).
        let a = psk_session_binding(psk, &alice, &bob);
        let b = psk_session_binding(psk, &bob, &alice);
        assert_eq!(a, b, "биндинг должен быть симметричным");

        // Bob на деле разговаривает с Mallory (сервер подменил ключ Alice).
        let bob_sees_mitm = psk_session_binding(psk, &bob, &mallory);
        assert_ne!(a, bob_sees_mitm, "подмена identity должна ломать биндинг");

        // Без знания PSK корректный биндинг не воспроизвести.
        let wrong_psk = psk_session_binding(b"guessed", &alice, &bob);
        assert_ne!(a, wrong_psk);
    }

    /// Сессионная обёртка PSK: roundtrip, подмена identity и чужой PSK ломают тег.
    #[test]
    fn psk_session_seal_roundtrip_and_binds_identity() {
        let mut rng = OsRng.unwrap_err();
        let alice = *IdentityKeyPair::generate(&mut rng).identity_key();
        let bob = *IdentityKeyPair::generate(&mut rng).identity_key();
        let mallory = *IdentityKeyPair::generate(&mut rng).identity_key();
        let psk = b"aegis-manual-psk-out-of-band";
        let pt = b"\x01{\"text\":\"hello\"}";

        let sealed = psk_session_seal(psk, &alice, &bob, pt).expect("seal");
        assert!(psk_session_seal_present(&sealed));
        assert!(!sealed.windows(pt.len()).any(|w| w == pt));
        assert_eq!(psk_session_open(psk, &alice, &bob, &sealed).unwrap(), pt);
        assert_eq!(psk_session_open(psk, &bob, &alice, &sealed).unwrap(), pt);

        assert!(psk_session_open(psk, &alice, &mallory, &sealed).is_err());
        assert!(psk_session_open(b"guessed", &alice, &bob, &sealed).is_err());

        // Legacy без метки пропускается как есть.
        assert_eq!(psk_session_open(psk, &alice, &bob, pt).unwrap(), pt);
    }

    /// Формат prekey-бандла для backend (§0): сериализация → JSON → парсинг →
    /// рабочий X3DH (интероп клиент/сервер сохраняет корректность).
    #[test]
    fn prekey_bundle_wire_format_roundtrips() {
        use wire::PreKeyBundlePublic;

        let mut rng = OsRng.unwrap_err();
        let bob_device_id = DeviceId::new(1).unwrap();
        let bob_identity = IdentityKeyPair::generate(&mut rng);
        let mut bob_store =
            InMemSignalProtocolStore::new(bob_identity, random_registration_id(&mut rng)).unwrap();

        let bundle = publish_bob_prekeys(&mut bob_store, bob_device_id, &mut rng).unwrap();

        let public = PreKeyBundlePublic::from_bundle(&bundle).unwrap();
        assert_eq!(public.version, PreKeyBundlePublic::FORMAT_VERSION);
        assert!(public.one_time_pre_key.is_some());

        let json = public.to_json();
        let parsed = PreKeyBundlePublic::from_json(&json).unwrap();
        let rebuilt = parsed.to_bundle().unwrap();

        // Восстановленный из JSON бандл пригоден для X3DH.
        let alice_identity = IdentityKeyPair::generate(&mut rng);
        let mut alice_store =
            InMemSignalProtocolStore::new(alice_identity, random_registration_id(&mut rng))
                .unwrap();
        let bob_address = ProtocolAddress::new("bob".to_owned(), bob_device_id);
        let alice_address = ProtocolAddress::new("alice".to_owned(), DeviceId::new(1).unwrap());

        block_on(process_prekey_bundle(
            &bob_address,
            &alice_address,
            &mut alice_store.session_store,
            &mut alice_store.identity_store,
            &rebuilt,
            SystemTime::now(),
            &mut rng,
        ))
        .expect("X3DH из восстановленного бандла должен пройти");
    }

    // ── P6: персистентные stateful-сессии через сериализуемый blob ───────────

    /// Полный обмен между двумя аккаунтами, где состояние КАЖДОЙ стороны
    /// проходит через сериализацию/десериализацию blob между всеми шагами —
    /// доказывает, что сессии переживают перезапуск (хранение в SQLCipher, P6).
    #[test]
    fn stateful_persistent_two_party_roundtrip() {
        // Свежие аккаунты (как после онбординга).
        let mut alice = stateful::create_account().expect("alice account");
        let mut bob = stateful::create_account().expect("bob account");

        // Имена = accountId сторон (в проде — `aegis:<hex>`).
        let alice_id = "aegis:alice";
        let bob_id = "aegis:bob";
        let dev = stateful::LOCAL_DEVICE_ID;

        // Bob публикует prekeys (в проде — на backend); Alice получает бандл.
        let (bob2, bob_bundle) = stateful::publish_prekeys(&bob).expect("bob prekeys");
        bob = bob2;
        let bundle_json = bob_bundle.to_json();

        // Имитация персистентности: гоняем blob через строку (как из SQLCipher).
        alice = reserialize(&alice);
        bob = reserialize(&bob);

        // Alice устанавливает сессию по бандлу Bob (X3DH/PQXDH).
        alice = stateful::process_bundle(&alice, alice_id, bob_id, dev, &bundle_json)
            .expect("alice X3DH");
        assert!(stateful::has_session(&alice, bob_id, dev).unwrap());

        // Alice → Bob (первое сообщение — тип PreKey).
        let (alice2, a_type, a_ct) =
            stateful::encrypt(&alice, alice_id, bob_id, dev, b"hello bob").expect("enc a");
        alice = reserialize(&alice2);
        assert_eq!(a_type, "PreKey");

        // Bob расшифровывает (после «перезапуска»).
        bob = reserialize(&bob);
        let (bob3, got) =
            stateful::decrypt(&bob, bob_id, alice_id, dev, &a_ct, "PreKey").expect("dec a");
        bob = bob3;
        assert_eq!(got, b"hello bob");

        // Bob → Alice (ответ — тип Whisper, ratchet продвинулся).
        let (bob4, b_type, b_ct) =
            stateful::encrypt(&bob, bob_id, alice_id, dev, b"hi alice").expect("enc b");
        bob = reserialize(&bob4);
        assert_eq!(b_type, "Whisper");

        alice = reserialize(&alice);
        let (alice3, got2) =
            stateful::decrypt(&alice, alice_id, bob_id, dev, &b_ct, "Whisper").expect("dec b");
        alice = alice3;
        assert_eq!(got2, b"hi alice");

        // Второе сообщение Alice → Bob уже Whisper (сессия установлена).
        let (_alice4, a2_type, a2_ct) =
            stateful::encrypt(&alice, alice_id, bob_id, dev, b"again").expect("enc a2");
        assert_eq!(a2_type, "Whisper");
        let (_bob5, got3) =
            stateful::decrypt(&bob, bob_id, alice_id, dev, &a2_ct, "Whisper").expect("dec a2");
        assert_eq!(got3, b"again");
    }

    /// PSK-биндинг (§6.2), вычисленный из identity-ключей ПЕРСИСТЕНТНЫХ аккаунтов,
    /// совпадает у обеих сторон и ломается при подмене identity (MITM).
    #[test]
    fn stateful_psk_binding_matches_both_sides() {
        let alice = stateful::create_account().unwrap();
        let bob = stateful::create_account().unwrap();
        let mallory = stateful::create_account().unwrap();

        let a_id = stateful::identity_key_b64(&alice).unwrap();
        let b_id = stateful::identity_key_b64(&bob).unwrap();
        let m_id = stateful::identity_key_b64(&mallory).unwrap();
        let psk = b"aegis-manual-psk";

        let (a_tag, a_code) = stateful::compute_psk_binding(&a_id, &b_id, psk).unwrap();
        let (b_tag, b_code) = stateful::compute_psk_binding(&b_id, &a_id, psk).unwrap();
        assert_eq!(a_tag, b_tag, "честные стороны получают одинаковый биндинг");
        assert_eq!(a_code, b_code);
        assert!(!a_code.is_empty());

        // Bob на деле говорит с Mallory (сервер подменил ключ Alice) → расхождение.
        let (mitm_tag, _) = stateful::compute_psk_binding(&b_id, &m_id, psk).unwrap();
        assert_ne!(a_tag, mitm_tag, "подмена identity ломает биндинг");
    }

    /// Мультидевайс (§7.1.1, `docs/multidevice.md`): ДВА устройства одного
    /// аккаунта Alice (разные Signal-identity, локальные device id 1 и 2) ведут
    /// НЕЗАВИСИМЫЕ Double Ratchet-сессии с одним Bob. Bob различает устройства по
    /// `remote_device` и держит две раздельные сессии `(alice, 1)` и `(alice, 2)`.
    #[test]
    fn stateful_multidevice_per_device_sessions() {
        let alice_id = "aegis:alice";
        let bob_id = "aegis:bob";

        // Три независимых Signal-эндпоинта: два устройства Alice + Bob.
        let mut a1 = stateful::create_account().unwrap();
        let mut a2 = stateful::create_account().unwrap();
        let mut bob = stateful::create_account().unwrap();

        // Bob публикует ДВА бандла (разные one-time prekeys) — как сервер выдаёт
        // отдельный prekey каждому запросу; оба устройства Alice устанавливают
        // X3DH к Bob, но КАЖДОЕ от своего локального device id (1 и 2).
        let (bob2, bundle_a1) = stateful::publish_prekeys(&bob).unwrap();
        bob = bob2;
        let (bob3, bundle_a2) = stateful::publish_prekeys(&bob).unwrap();
        bob = bob3;

        a1 = stateful::process_bundle_dev(&a1, alice_id, 1, bob_id, 1, &bundle_a1.to_json(), "")
            .unwrap();
        a2 = stateful::process_bundle_dev(&a2, alice_id, 2, bob_id, 1, &bundle_a2.to_json(), "")
            .unwrap();

        // Каждое устройство Alice шифрует ОТ своего device id.
        let (a1b, t1, ct1) =
            stateful::encrypt_dev(&a1, alice_id, 1, bob_id, 1, b"from d1", "").unwrap();
        a1 = a1b;
        let (a2b, t2, ct2) =
            stateful::encrypt_dev(&a2, alice_id, 2, bob_id, 1, b"from d2", "").unwrap();
        a2 = a2b;
        assert_eq!(t1, "PreKey");
        assert_eq!(t2, "PreKey");
        // Независимые ratchet → разные шифртексты даже для разных устройств.
        assert_ne!(ct1, ct2);

        // Bob расшифровывает, указывая device id ОТПРАВИТЕЛЯ (из конверта).
        let (bob3, m1) = stateful::decrypt(&bob, bob_id, alice_id, 1, &ct1, "PreKey").unwrap();
        bob = bob3;
        let (bob4, m2) = stateful::decrypt(&bob, bob_id, alice_id, 2, &ct2, "PreKey").unwrap();
        bob = bob4;
        assert_eq!(m1, b"from d1");
        assert_eq!(m2, b"from d2");

        // Bob держит ДВЕ раздельные сессии — по одной на устройство Alice.
        assert!(stateful::has_session(&bob, alice_id, 1).unwrap());
        assert!(stateful::has_session(&bob, alice_id, 2).unwrap());

        // Ответ Bob адресно каждому устройству (fan-out): d1 не читает конверт d2.
        let (bob5, rt1, rct1) = stateful::encrypt(&bob, bob_id, alice_id, 1, b"reply d1").unwrap();
        bob = bob5;
        let (_bob6, rt2, rct2) = stateful::encrypt(&bob, bob_id, alice_id, 2, b"reply d2").unwrap();
        assert_eq!(rt1, "Whisper");
        assert_eq!(rt2, "Whisper");

        let (_a1c, r1) =
            stateful::decrypt_dev(&a1, alice_id, 1, bob_id, 1, &rct1, "Whisper", "").unwrap();
        let (_a2c, r2) =
            stateful::decrypt_dev(&a2, alice_id, 2, bob_id, 1, &rct2, "Whisper", "").unwrap();
        assert_eq!(r1, b"reply d1");
        assert_eq!(r2, b"reply d2");

        // Конверт для d2 НЕ расшифровывается устройством d1 (разные сессии).
        assert!(stateful::decrypt_dev(&a1, alice_id, 1, bob_id, 1, &rct2, "Whisper", "").is_err());
    }

    /// Self-sync (`docs/multidevice.md` §4.2/§6): два СВОИХ устройства аккаунта
    /// общаются между собой через per-device Signal-сессию (name = один accountId,
    /// разные device id) — фундамент синхронизации истории без общего ratchet.
    #[test]
    fn stateful_self_sync_between_own_devices() {
        let me = "aegis:me";
        let mut d1 = stateful::create_account().unwrap();
        let mut d2 = stateful::create_account().unwrap();

        // d2 публикует свой бандл под device id 2; d1 устанавливает сессию к (me, 2).
        let (d2b, d2_bundle) = stateful::publish_prekeys_dev(&d2, 2).unwrap();
        d2 = d2b;
        assert_eq!(d2_bundle.device_id, 2, "бандл несёт локальный device id");
        let bundle = d2_bundle.to_json();

        d1 = stateful::process_bundle_dev(&d1, me, 1, me, 2, &bundle, "").unwrap();
        let (d1b, ty, ct) = stateful::encrypt_dev(&d1, me, 1, me, 2, b"sync from d1", "").unwrap();
        d1 = d1b;
        assert_eq!(ty, "PreKey");
        let _ = d1;

        // d2 расшифровывает синхронизацию от d1 (remote = (me, 1)).
        let (_d2c, got) = stateful::decrypt_dev(&d2, me, 2, me, 1, &ct, "PreKey", "").unwrap();
        assert_eq!(got, b"sync from d1");
    }

    // ── Закрепление identity из доверенного канала вместо TOFU ──────────────

    const ALICE: &str = "aegis:alice";
    const BOB: &str = "aegis:bob";

    /// X3DH от `local` к `remote` (оба — устройство 1) с закреплённым `pin`.
    fn session_to(
        state: &str,
        local: &str,
        remote: &str,
        bundle: &wire::PreKeyBundlePublic,
        pin: &str,
    ) -> Result<String, SignalProtocolError> {
        stateful::process_bundle_dev(state, local, 1, remote, 1, &bundle.to_json(), pin)
    }

    fn send(
        state: &str,
        local: &str,
        remote: &str,
        msg: &[u8],
        pin: &str,
    ) -> Result<(String, String, Vec<u8>), SignalProtocolError> {
        stateful::encrypt_dev(state, local, 1, remote, 1, msg, pin)
    }

    fn receive_prekey(
        state: &str,
        local: &str,
        remote: &str,
        ciphertext: &[u8],
        pin: &str,
    ) -> Result<(String, Vec<u8>), SignalProtocolError> {
        stateful::decrypt_dev(state, local, 1, remote, 1, ciphertext, "PreKey", pin)
    }

    /// Сервер отдаёт вместо бандла Bob бандл Mallory. Без закрепления (TOFU)
    /// сессия заводится на ключе Mallory; с identity Bob из QR — отказ, а
    /// настоящий бандл проходит.
    #[test]
    fn pinned_identity_rejects_substituted_bundle() {
        let alice = stateful::create_account().unwrap();
        let bob = stateful::create_account().unwrap();
        let mallory = stateful::create_account().unwrap();
        let bob_key = stateful::identity_key_b64(&bob).unwrap();
        let (_, bob_bundle) = stateful::publish_prekeys(&bob).unwrap();
        let (_, mallory_bundle) = stateful::publish_prekeys(&mallory).unwrap();

        // Без закрепления подмена бандла проходит — это и закрываем.
        assert!(session_to(&alice, ALICE, BOB, &mallory_bundle, "").is_ok());
        assert!(session_to(&alice, ALICE, BOB, &mallory_bundle, &bob_key).is_err());

        let alice = session_to(&alice, ALICE, BOB, &bob_bundle, &bob_key).unwrap();
        assert!(stateful::has_trusted_session(&alice, BOB, 1, &bob_key).unwrap());
    }

    /// Сервер первым присылает Bob PreKey-сообщение «от alice» на своём ключе.
    /// С закреплённым identity Alice оно отвергается без изменения состояния, а
    /// настоящее сообщение Alice проходит.
    #[test]
    fn pinned_identity_rejects_prekey_message_from_impostor() {
        let alice = stateful::create_account().unwrap();
        let bob = stateful::create_account().unwrap();
        let mallory = stateful::create_account().unwrap();
        let alice_key = stateful::identity_key_b64(&alice).unwrap();
        let bob_key = stateful::identity_key_b64(&bob).unwrap();
        let (bob, for_mallory) = stateful::publish_prekeys(&bob).unwrap();
        let (bob, for_alice) = stateful::publish_prekeys(&bob).unwrap();

        let mallory = session_to(&mallory, ALICE, BOB, &for_mallory, "").unwrap();
        let (_, kind, forged) = send(&mallory, ALICE, BOB, b"forged", "").unwrap();
        assert_eq!(kind, "PreKey");
        assert!(receive_prekey(&bob, BOB, ALICE, &forged, &alice_key).is_err());
        assert!(!stateful::has_session(&bob, ALICE, 1).unwrap());

        let alice = session_to(&alice, ALICE, BOB, &for_alice, &bob_key).unwrap();
        let (_, _, genuine) = send(&alice, ALICE, BOB, b"hello", &bob_key).unwrap();
        let (bob, got) = receive_prekey(&bob, BOB, ALICE, &genuine, &alice_key).unwrap();
        assert_eq!(got, b"hello");
        assert!(stateful::has_trusted_session(&bob, ALICE, 1, &alice_key).unwrap());
    }

    /// TOFU «отравлен»: Bob без закрепления принял сервер за alice, и настоящая
    /// Alice перестала проходить. Закреплённый identity лечит состояние: Alice
    /// снова проходит, а дальнейшие сообщения сервера отвергаются.
    #[test]
    fn pinned_identity_heals_tofu_poisoning() {
        let alice = stateful::create_account().unwrap();
        let bob = stateful::create_account().unwrap();
        let mallory = stateful::create_account().unwrap();
        let alice_key = stateful::identity_key_b64(&alice).unwrap();
        let (bob, for_mallory) = stateful::publish_prekeys(&bob).unwrap();
        let (bob, for_alice) = stateful::publish_prekeys(&bob).unwrap();

        let mallory = session_to(&mallory, ALICE, BOB, &for_mallory, "").unwrap();
        let (mallory, _, forged_1) = send(&mallory, ALICE, BOB, b"forged", "").unwrap();
        let (_, _, forged_2) = send(&mallory, ALICE, BOB, b"forged again", "").unwrap();
        let alice = session_to(&alice, ALICE, BOB, &for_alice, "").unwrap();
        let (_, _, genuine) = send(&alice, ALICE, BOB, b"hello", "").unwrap();

        let (poisoned, got) = receive_prekey(&bob, BOB, ALICE, &forged_1, "").unwrap();
        assert_eq!(got, b"forged");
        assert!(receive_prekey(&poisoned, BOB, ALICE, &genuine, "").is_err());

        let (healed, got) = receive_prekey(&poisoned, BOB, ALICE, &genuine, &alice_key).unwrap();
        assert_eq!(got, b"hello");
        assert!(stateful::has_trusted_session(&healed, ALICE, 1, &alice_key).unwrap());
        assert!(receive_prekey(&healed, BOB, ALICE, &forged_2, &alice_key).is_err());
    }

    /// Сессия на другом identity не считается доверенной и не шифрует, а
    /// закреплённый ключ читается без учёта паддинга и не принимает мусор.
    #[test]
    fn trusted_session_follows_pinned_identity() {
        let alice = stateful::create_account().unwrap();
        let bob = stateful::create_account().unwrap();
        let mallory = stateful::create_account().unwrap();
        let bob_key = stateful::identity_key_b64(&bob).unwrap();
        let mallory_key = stateful::identity_key_b64(&mallory).unwrap();
        let (_, bob_bundle) = stateful::publish_prekeys(&bob).unwrap();
        let alice = session_to(&alice, ALICE, BOB, &bob_bundle, "").unwrap();

        assert!(stateful::has_trusted_session(&alice, BOB, 1, "").unwrap());
        assert!(stateful::has_trusted_session(&alice, BOB, 1, &bob_key).unwrap());
        let unpadded = bob_key.trim_end_matches('=');
        assert!(stateful::has_trusted_session(&alice, BOB, 1, unpadded).unwrap());
        assert!(!stateful::has_trusted_session(&alice, BOB, 1, &mallory_key).unwrap());
        assert!(send(&alice, ALICE, BOB, b"x", &mallory_key).is_err());
        assert!(stateful::has_trusted_session(&alice, BOB, 1, "не base64").is_err());
        assert!(send(&alice, ALICE, BOB, b"x", "AAAA").is_err());
    }

    /// Хелпер: прогоняет blob состояния через строковую сериализацию (симуляция
    /// записи/чтения из зашифрованного хранилища).
    fn reserialize(blob: &str) -> String {
        blob.to_string()
    }
}
