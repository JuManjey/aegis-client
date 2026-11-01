//! AEGIS native core.
//!
//! Единый нативный Rust-модуль AEGIS (§9, §10.11): каскад слоя A, libsignal
//! (слой B) и Arti (Tor) собираются здесь, чтобы уменьшить число разрозненных
//! зависимостей и упростить аудит.
//!
//! ФАЗА 2 (P1, §7.1) — аккаунт без телефона:
//!  - [`identity`] — BIP-39-мнемоника + детерминированный вывод Ed25519/X25519.
//!  - [`kdf`]      — Argon2id (ключ из пароля входа).
//!  - [`seal`]     — XChaCha20-Poly1305 (запечатывание мнемоники для хранения).
//!
//! Криптографию НЕ пишем свою (§1) — только аудированные крейты RustCrypto /
//! bip39 / dalek. Граница JNI намеренно узкая: обмен строками (hex/base64/JSON),
//! секреты живут в Rust и затираются ([`zeroize`]) сразу после использования.

pub mod cascade;
pub mod discovery;
pub mod error;
pub mod frame;
pub mod identity;
pub mod kdf;
pub mod psk;
pub mod seal;

#[cfg(feature = "signal")]
pub mod calls;

#[cfg(feature = "signal")]
pub mod signal;

#[cfg(feature = "tor")]
pub mod tor;

use base64::{engine::general_purpose::STANDARD as B64, Engine};
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

const MNEMONIC_AAD: &[u8] = b"aegis/account/mnemonic/v1";

// ---------------------------------------------------------------------------
// Вспомогательные функции JNI-границы.
// ---------------------------------------------------------------------------

/// Читает Java-строку в `String` (пустая при ошибке — вызывающий проверит).
fn read_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|js| js.into()).unwrap_or_default()
}

/// Создаёт jstring из `&str`; при ошибке — null (Kotlin трактует как сбой).
fn new_jstring(env: &JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(o) => o.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn b64_encode(bytes: &[u8]) -> String {
    B64.encode(bytes)
}

fn b64_decode(s: &str) -> Option<Vec<u8>> {
    B64.decode(s.trim()).ok()
}

// ---------------------------------------------------------------------------
// nativeVersion
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeVersion<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let signal = if cfg!(feature = "signal") { "+signal" } else { "" };
    let tor = if cfg!(feature = "tor") { "+tor" } else { "" };
    let version = format!(
        "aegis-core {} (phase-2: account+cascade+psk{}{})",
        env!("CARGO_PKG_VERSION"),
        signal,
        tor
    );
    new_jstring(&env, &version)
}

/// Скомпилирован ли модуль libsignal (слой B). Для диагностики в UI.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeHasSignalSupport<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jboolean {
    if cfg!(feature = "signal") {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Скомпилирован ли встроенный Tor (Arti, слой транспорта P5). Для диагностики.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeHasTorSupport<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jboolean {
    if cfg!(feature = "tor") {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// P5 (§7.2, §10.5, §10.11): встроенный Tor-транспорт (Arti) — мост наружу.
// Символы существуют только в сборке с фичей `tor` (cargoNdkBuild включает её
// best-effort). Kotlin-фасад (ArtiTorTransport) вызывает их лишь после проверки
// hasTorSupport(); иначе используется симулятор (FakeTorTransport).
// ---------------------------------------------------------------------------

/// Запускает/перезапускает встроенный Tor по JSON-конфигу
/// (`{stateDir,cacheDir,socksPort,bridges,transports}`). Ответ:
/// `+<port>` — успех (назначенный локальный SOCKS-порт), `-<текст>` — ошибка.
#[cfg(feature = "tor")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeTorStart<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    config_json: JString<'local>,
) -> jstring {
    let config_json = read_string(&mut env, &config_json);
    match tor::start(&config_json) {
        Ok(port) => new_jstring(&env, &format!("+{port}")),
        Err(e) => new_jstring(&env, &format!("-{e}")),
    }
}

/// Останавливает встроенный Tor и освобождает ресурсы.
#[cfg(feature = "tor")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeTorStop<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) {
    tor::stop();
}

/// JSON-статус транспорта: `{state,progress,socksPort,error}` — для индикатора.
#[cfg(feature = "tor")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeTorStatusJson<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    new_jstring(&env, &tor::status_json())
}

/// Диагностика достижимости адреса (напр. onion-релея, §12.2) через Tor.
/// Ответ: `+ok` — успех, `-<текст>` — ошибка/таймаут.
#[cfg(feature = "tor")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeTorTestConnect<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    host: JString<'local>,
    port: jint,
    timeout_ms: jint,
) -> jstring {
    let host = read_string(&mut env, &host);
    match tor::test_connect(&host, port.max(0) as u16, timeout_ms.max(0) as u64) {
        Ok(()) => new_jstring(&env, "+ok"),
        Err(e) => new_jstring(&env, &format!("-{e}")),
    }
}

// ---------------------------------------------------------------------------
// Слой B (P2, §6.1): Signal Protocol — мост наружу. Символы существуют только
// в сборке с фичей `signal` (cargoNdkBuild включает её). Kotlin-фасад
// (SignalSession) вызывает эти методы лишь после проверки hasSignalSupport().
// ---------------------------------------------------------------------------

/// Нативный самотест слоя B: два клиента устанавливают сессию (X3DH/PQXDH) и
/// обмениваются сообщениями в обе стороны через Double Ratchet. Доказывает, что
/// libsignal реально линкуется и работает внутри `.so` на устройстве.
/// `pskB64` — опциональный ручной PSK (base64) для проверки биндинга §6.2;
/// пустая строка = без PSK. Возвращает JSON [`signal::RoundtripReport`] или
/// `{"error":"..."}`.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalSelfTest<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    psk_b64: JString<'local>,
) -> jstring {
    let psk_b64 = read_string(&mut env, &psk_b64);
    let psk = b64_decode(&psk_b64).filter(|p| !p.is_empty());
    let json = match signal::two_party_roundtrip(psk.as_deref()) {
        Ok(report) => serde_json::to_string(&report)
            .unwrap_or_else(|_| r#"{"error":"serialize"}"#.to_string()),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// Возвращает эталонный публичный prekey-бандл (JSON, формат обмена с backend
/// §0): свежая идентичность + signed/one-time/KYBER pre-key. Служит контрактом
/// формата и диагностикой. Приватные ключи не экспортируются (хранение — P6).
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalDemoPreKeyBundleJson<
    'local,
>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let json = match signal::generate_demo_prekey_bundle() {
        Ok(bundle) => bundle.to_json(),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

// ---------------------------------------------------------------------------
// P6 (§6.1, §6.3): stateful-сессии Signal — мост наружу. Состояние аккаунта —
// непрозрачный blob (base64), который Kotlin хранит в SQLCipher. Каждая операция
// принимает старый blob и возвращает новый (см. signal::stateful). Символы есть
// только в сборке с фичей `signal`.
// ---------------------------------------------------------------------------

/// Создаёт свежий Signal-аккаунт. Возвращает blob состояния (base64) или "".
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalCreateAccount<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match signal::stateful::create_account() {
        Ok(blob) => new_jstring(&env, &blob),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Публичный identity-ключ аккаунта (base64) для PSK-биндинга/отпечатка. "" при ошибке.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalIdentityKeyB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
) -> jstring {
    let state = read_string(&mut env, &state_b64);
    match signal::stateful::identity_key_b64(&state) {
        Ok(s) => new_jstring(&env, &s),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Генерирует комплект prekeys для локального устройства `localDevice` (мультидевайс
/// §7.1.1: бандл несёт device id, чтобы peer адресовал именно это устройство).
/// Возвращает JSON `{"state":..,"bundle":{..}}` или `{"error":".."}`.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalPublishPrekeys<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
    local_device: jint,
) -> jstring {
    let state = read_string(&mut env, &state_b64);
    let json = match signal::stateful::publish_prekeys_dev(&state, local_device.max(1) as u32) {
        Ok((new_state, bundle)) => format!(
            r#"{{"state":{},"bundle":{}}}"#,
            json_string(&new_state),
            bundle.to_json()
        ),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// Устанавливает исходящую сессию по публичному бандлу (X3DH/PQXDH).
/// Ответ: `+<новый blob>` — успех, `-<ошибка>`. `expectedIdentityB64` здесь и в
/// HasSession/Encrypt/Decrypt — закреплённый identity удалённого устройства
/// ("" — TOFU, см. `signal::stateful`); он ПОСЛЕДНИЙ, чтобы старая `.so` с
/// прежней сигнатурой просто не читала лишний аргумент.
#[cfg(feature = "signal")]
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalProcessBundle<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
    local_name: JString<'local>,
    local_device: jint,
    remote_name: JString<'local>,
    remote_device: jint,
    bundle_json: JString<'local>,
    expected_identity_b64: JString<'local>,
) -> jstring {
    let state = read_string(&mut env, &state_b64);
    let local = read_string(&mut env, &local_name);
    let remote = read_string(&mut env, &remote_name);
    let bundle = read_string(&mut env, &bundle_json);
    let expected = read_string(&mut env, &expected_identity_b64);
    match signal::stateful::process_bundle_dev(
        &state,
        &local,
        local_device.max(1) as u32,
        &remote,
        remote_device.max(0) as u32,
        &bundle,
        &expected,
    ) {
        Ok(new_state) => new_jstring(&env, &format!("+{new_state}")),
        Err(e) => new_jstring(&env, &format!("-{e}")),
    }
}

/// Есть ли установленная сессия с удалённым адресом. С непустым
/// `expectedIdentityB64` сессия на другом identity считается отсутствующей.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalHasSession<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
    remote_name: JString<'local>,
    remote_device: jint,
    expected_identity_b64: JString<'local>,
) -> jboolean {
    let state = read_string(&mut env, &state_b64);
    let remote = read_string(&mut env, &remote_name);
    let expected = read_string(&mut env, &expected_identity_b64);
    match signal::stateful::has_trusted_session(
        &state,
        &remote,
        remote_device.max(0) as u32,
        &expected,
    ) {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Шифрует сообщение слоя B. Ответ: JSON `{"state":..,"type":"PreKey|Whisper",
/// "ciphertext":"<b64>"}` или `{"error":".."}`. `plaintextB64` — base64 plaintext.
#[cfg(feature = "signal")]
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalEncrypt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
    local_name: JString<'local>,
    local_device: jint,
    remote_name: JString<'local>,
    remote_device: jint,
    plaintext_b64: JString<'local>,
    expected_identity_b64: JString<'local>,
) -> jstring {
    let state = read_string(&mut env, &state_b64);
    let local = read_string(&mut env, &local_name);
    let remote = read_string(&mut env, &remote_name);
    let pt_b64 = read_string(&mut env, &plaintext_b64);
    let expected = read_string(&mut env, &expected_identity_b64);
    let plaintext = match b64_decode(&pt_b64) {
        Some(p) => p,
        None => return new_jstring(&env, r#"{"error":"некорректный base64 plaintext"}"#),
    };
    let json = match signal::stateful::encrypt_dev(
        &state,
        &local,
        local_device.max(1) as u32,
        &remote,
        remote_device.max(0) as u32,
        &plaintext,
        &expected,
    ) {
        Ok((new_state, msg_type, ciphertext)) => format!(
            r#"{{"state":{},"type":{},"ciphertext":{}}}"#,
            json_string(&new_state),
            json_string(&msg_type),
            json_string(&b64_encode(&ciphertext)),
        ),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// Расшифровывает сообщение слоя B. Ответ: JSON `{"state":..,"plaintext":"<b64>"}`
/// или `{"error":".."}`. `messageType`: "PreKey" | "Whisper".
#[cfg(feature = "signal")]
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    state_b64: JString<'local>,
    local_name: JString<'local>,
    local_device: jint,
    remote_name: JString<'local>,
    remote_device: jint,
    ciphertext_b64: JString<'local>,
    message_type: JString<'local>,
    expected_identity_b64: JString<'local>,
) -> jstring {
    let state = read_string(&mut env, &state_b64);
    let local = read_string(&mut env, &local_name);
    let remote = read_string(&mut env, &remote_name);
    let ct_b64 = read_string(&mut env, &ciphertext_b64);
    let msg_type = read_string(&mut env, &message_type);
    let expected = read_string(&mut env, &expected_identity_b64);
    let ciphertext = match b64_decode(&ct_b64) {
        Some(c) => c,
        None => return new_jstring(&env, r#"{"error":"некорректный base64 ciphertext"}"#),
    };
    let json = match signal::stateful::decrypt_dev(
        &state,
        &local,
        local_device.max(1) as u32,
        &remote,
        remote_device.max(0) as u32,
        &ciphertext,
        &msg_type,
        &expected,
    ) {
        Ok((new_state, plaintext)) => format!(
            r#"{{"state":{},"plaintext":{}}}"#,
            json_string(&new_state),
            json_string(&b64_encode(&plaintext)),
        ),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// PSK-биндинг (§6.2) из локального и удалённого identity-ключей + ручного PSK.
/// Ответ: JSON `{"binding":"<b64>","safety":".."}` или `{"error":".."}`.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalPskBinding<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    local_identity_b64: JString<'local>,
    remote_identity_b64: JString<'local>,
    psk_b64: JString<'local>,
) -> jstring {
    let local = read_string(&mut env, &local_identity_b64);
    let remote = read_string(&mut env, &remote_identity_b64);
    let psk_b64 = read_string(&mut env, &psk_b64);
    let psk = match b64_decode(&psk_b64) {
        Some(p) => p,
        None => return new_jstring(&env, r#"{"error":"некорректный base64 PSK"}"#),
    };
    let json = match signal::stateful::compute_psk_binding(&local, &remote, &psk) {
        Ok((binding, safety)) => format!(
            r#"{{"binding":{},"safety":{}}}"#,
            json_string(&b64_encode(&binding)),
            json_string(&safety),
        ),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// Сессионная обёртка PSK поверх plaintext слоя B. Ответ: `+<b64>` или `-<ошибка>`.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalPskSealB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    local_identity_b64: JString<'local>,
    remote_identity_b64: JString<'local>,
    psk_b64: JString<'local>,
    plaintext_b64: JString<'local>,
) -> jstring {
    let local = read_string(&mut env, &local_identity_b64);
    let remote = read_string(&mut env, &remote_identity_b64);
    let psk = match b64_decode(&read_string(&mut env, &psk_b64)) {
        Some(p) if !p.is_empty() => p,
        _ => return new_jstring(&env, "-некорректный PSK"),
    };
    let plaintext = match b64_decode(&read_string(&mut env, &plaintext_b64)) {
        Some(p) => p,
        None => return new_jstring(&env, "-некорректный plaintext"),
    };
    let out = match signal::stateful::psk_session_seal_b64(&local, &remote, &psk, &plaintext) {
        Ok(sealed) => format!("+{}", b64_encode(&sealed)),
        Err(e) => format!("-{}", e),
    };
    new_jstring(&env, &out)
}

/// Снятие сессионной обёртки PSK. Legacy без метки возвращается как есть.
#[cfg(feature = "signal")]
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSignalPskOpenB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    local_identity_b64: JString<'local>,
    remote_identity_b64: JString<'local>,
    psk_b64: JString<'local>,
    sealed_b64: JString<'local>,
) -> jstring {
    let local = read_string(&mut env, &local_identity_b64);
    let remote = read_string(&mut env, &remote_identity_b64);
    let psk = match b64_decode(&read_string(&mut env, &psk_b64)) {
        Some(p) if !p.is_empty() => p,
        _ => return new_jstring(&env, "-некорректный PSK"),
    };
    let sealed = match b64_decode(&read_string(&mut env, &sealed_b64)) {
        Some(p) => p,
        None => return new_jstring(&env, "-некорректный контейнер"),
    };
    let out = match signal::stateful::psk_session_open_b64(&local, &remote, &psk, &sealed) {
        Ok(pt) => format!("+{}", b64_encode(&pt)),
        Err(e) => format!("-{}", e),
    };
    new_jstring(&env, &out)
}

// ---------------------------------------------------------------------------
// P8 (§7.5, §10.10): согласование ключей звонка — мост наружу. Символы есть
// только в сборке с фичей `signal`. Kotlin-фасад (CallCrypto) вызывает лишь
// после проверки hasSignalSupport().
// ---------------------------------------------------------------------------

/// Согласует ключи медиапотока звонка для одной стороны из общего `call_secret`
/// (доставлен через Signal-сессию), ручного `psk` (§6.2, подмешивается),
/// идентификатора звонка и «транскрипта» согласования (напр. DTLS-фингерпринты).
/// Ответ: JSON `{"sendKey":..,"sendSalt":..,"recvKey":..,"recvSalt":..,"sas":..}`
/// (все ключи base64) или `{"error":".."}`. `initiator` != 0 — эта сторона звонит.
#[cfg(feature = "signal")]
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeCallDeriveKeys<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    call_secret_b64: JString<'local>,
    psk_b64: JString<'local>,
    call_id: JString<'local>,
    initiator: jboolean,
    context_b64: JString<'local>,
) -> jstring {
    let call_secret_b64 = read_string(&mut env, &call_secret_b64);
    let psk_b64 = read_string(&mut env, &psk_b64);
    let call_id = read_string(&mut env, &call_id);
    let context_b64 = read_string(&mut env, &context_b64);
    let call_secret = match b64_decode(&call_secret_b64) {
        Some(s) if !s.is_empty() => s,
        _ => return new_jstring(&env, r#"{"error":"пустой или некорректный call_secret"}"#),
    };
    // PSK и context могут быть пустыми (без PSK/без транскрипта) — это валидно.
    let psk = b64_decode(&psk_b64).unwrap_or_default();
    let context = b64_decode(&context_b64).unwrap_or_default();
    let keys = calls::derive_call_keys(
        &call_secret,
        &psk,
        &call_id,
        initiator != JNI_FALSE,
        &context,
    );
    new_jstring(&env, &calls::call_keys_json(&keys))
}

// ---------------------------------------------------------------------------
// Аккаунт: мнемоника
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeGenerateMnemonic<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    word_count: jint,
) -> jstring {
    let phrase = identity::generate_mnemonic(word_count.max(0) as u32).unwrap_or_default();
    new_jstring(&env, &phrase)
}

#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeValidateMnemonic<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
) -> jboolean {
    let phrase = read_string(&mut env, &phrase);
    if identity::validate_mnemonic(&phrase) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Возвращает публичную информацию аккаунта в виде JSON (безопасно для UI).
/// При ошибке — JSON `{"error":"..."}`.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDeriveAccountInfo<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
    passphrase: JString<'local>,
) -> jstring {
    let phrase = read_string(&mut env, &phrase);
    let passphrase = read_string(&mut env, &passphrase);
    let json = match identity::derive_account(&phrase, &passphrase) {
        Ok(keys) => serde_json::to_string(&keys.public)
            .unwrap_or_else(|_| r#"{"error":"serialize"}"#.to_string()),
        Err(e) => format!(r#"{{"error":{}}}"#, json_string(&e.to_string())),
    };
    new_jstring(&env, &json)
}

/// Минимальное экранирование строки для встраивания в JSON.
fn json_string(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".to_string())
}

// ---------------------------------------------------------------------------
// Проверка Ed25519-подписи (авто-обнаружение адреса релея, §12.2).
//
// Клиент получает подписанный офлайн конфиг-документ через GET /v1/config и
// проверяет его подпись ЗАШИТЫМ в APK публичным ключом (BuildConfig). Приватного
// ключа нет ни на сервере, ни в клиенте → даже скомпрометированный сервер не
// подменит адрес. Своя криптография не пишется (§1) — тот же ed25519-dalek, что
// и для identity (переиспользуем identity::verify_ed25519).
// ---------------------------------------------------------------------------

/// Проверяет Ed25519-подпись. `publicHex` — публичный ключ (64 hex), `messageB64`
/// — подписанные байты (base64), `signatureB64` — подпись (base64, 64 байта).
/// Возвращает true только при корректной подписи; любой сбой разбора → false.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeVerifyEd25519<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    public_hex: JString<'local>,
    message_b64: JString<'local>,
    signature_b64: JString<'local>,
) -> jboolean {
    let public_hex = read_string(&mut env, &public_hex);
    let message_b64 = read_string(&mut env, &message_b64);
    let signature_b64 = read_string(&mut env, &signature_b64);
    let (message, signature) = match (b64_decode(&message_b64), b64_decode(&signature_b64)) {
        (Some(m), Some(s)) => (m, s),
        _ => return JNI_FALSE,
    };
    if identity::verify_ed25519(public_hex.trim(), &message, &signature) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// KDF пароля (Argon2id)
// ---------------------------------------------------------------------------

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeArgon2idDeriveB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    password: JString<'local>,
    salt_b64: JString<'local>,
    mem_kib: jint,
    iters: jint,
    parallelism: jint,
    out_len: jint,
) -> jstring {
    let password = read_string(&mut env, &password);
    let salt_b64 = read_string(&mut env, &salt_b64);
    let salt = match b64_decode(&salt_b64) {
        Some(s) => s,
        None => return new_jstring(&env, ""),
    };
    let result = kdf::argon2id_derive(
        password.as_bytes(),
        &salt,
        mem_kib.max(0) as u32,
        iters.max(0) as u32,
        parallelism.max(0) as u32,
        out_len.max(0) as usize,
    );
    match result {
        Ok(key) => new_jstring(&env, &b64_encode(&key)),
        Err(_) => new_jstring(&env, ""),
    }
}

// ---------------------------------------------------------------------------
// Запечатывание мнемоники (XChaCha20-Poly1305)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeSealMnemonicB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    mnemonic: JString<'local>,
    key_b64: JString<'local>,
) -> jstring {
    let mnemonic = read_string(&mut env, &mnemonic);
    let key_b64 = read_string(&mut env, &key_b64);
    let key = match b64_decode(&key_b64) {
        Some(k) => k,
        None => return new_jstring(&env, ""),
    };
    match seal::seal(&key, mnemonic.as_bytes(), MNEMONIC_AAD) {
        Ok(blob) => new_jstring(&env, &b64_encode(&blob)),
        Err(_) => new_jstring(&env, ""),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeOpenMnemonicB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    blob_b64: JString<'local>,
    key_b64: JString<'local>,
) -> jstring {
    let blob_b64 = read_string(&mut env, &blob_b64);
    let key_b64 = read_string(&mut env, &key_b64);
    let (blob, key) = match (b64_decode(&blob_b64), b64_decode(&key_b64)) {
        (Some(b), Some(k)) => (b, k),
        _ => return new_jstring(&env, ""),
    };
    match seal::open(&key, &blob, MNEMONIC_AAD) {
        Ok(pt) => match std::str::from_utf8(&pt) {
            Ok(s) => new_jstring(&env, s),
            Err(_) => new_jstring(&env, ""),
        },
        Err(_) => new_jstring(&env, ""),
    }
}

// ---------------------------------------------------------------------------
// Слой A (P3, §5): каскадный контейнер — мост наружу.
//
// Формат ответа: первый символ — статус, дальше полезная нагрузка.
//   "+<base64>" — успех, base64 — байты результата (может быть пустым);
//   "-<текст>"  — ошибка (провал тега слоя, неверный секрет и т.п.).
// Префикс нужен, чтобы отличить УСПЕШНУЮ расшифровку в пустую строку (пустой
// plaintext — валидный кейс) от ошибки. Все бинарные аргументы — base64.
// ---------------------------------------------------------------------------

fn cascade_ok(env: &JNIEnv, bytes: &[u8]) -> jstring {
    new_jstring(env, &format!("+{}", b64_encode(bytes)))
}

fn cascade_err(env: &JNIEnv, msg: &str) -> jstring {
    new_jstring(env, &format!("-{msg}"))
}

/// Шифрует plaintext в каскадный контейнер (XChaCha20 → AES-256 → Camellia-256
/// → Twofish-256). Все аргументы base64: `secret` — общий секрет контейнера
/// (PSK или выход Argon2id), `plaintext`, `aad` — аутентифицируемые данные.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeCascadeSealB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    secret_b64: JString<'local>,
    plaintext_b64: JString<'local>,
    aad_b64: JString<'local>,
) -> jstring {
    let secret_b64 = read_string(&mut env, &secret_b64);
    let plaintext_b64 = read_string(&mut env, &plaintext_b64);
    let aad_b64 = read_string(&mut env, &aad_b64);
    let (secret, plaintext, aad) = match (
        b64_decode(&secret_b64),
        b64_decode(&plaintext_b64),
        b64_decode(&aad_b64),
    ) {
        (Some(s), Some(p), Some(a)) => (s, p, a),
        _ => return cascade_err(&env, "некорректный base64 во входных данных"),
    };
    match cascade::seal(&secret, &plaintext, &aad) {
        Ok(container) => cascade_ok(&env, &container),
        Err(e) => cascade_err(&env, &e.to_string()),
    }
}

/// Расшифровывает каскадный контейнер (обратный порядок слоёв). При провале
/// тега любого слоя, неверном секрете или заголовке — ответ `-<ошибка>`.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeCascadeOpenB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    secret_b64: JString<'local>,
    container_b64: JString<'local>,
    aad_b64: JString<'local>,
) -> jstring {
    let secret_b64 = read_string(&mut env, &secret_b64);
    let container_b64 = read_string(&mut env, &container_b64);
    let aad_b64 = read_string(&mut env, &aad_b64);
    let (secret, container, aad) = match (
        b64_decode(&secret_b64),
        b64_decode(&container_b64),
        b64_decode(&aad_b64),
    ) {
        (Some(s), Some(c), Some(a)) => (s, c, a),
        _ => return cascade_err(&env, "некорректный base64 во входных данных"),
    };
    match cascade::open(&secret, &container, &aad) {
        Ok(plaintext) => cascade_ok(&env, &plaintext),
        Err(e) => cascade_err(&env, &e.to_string()),
    }
}

// ---------------------------------------------------------------------------
// P4 (§6.2, §10.3): ручной обмен ключом контейнера (PSK) — мост наружу.
//
// PSK НИКОГДА не уходит в сеть: эти функции обслуживают только локальный
// (out-of-band) обмен — генерацию, кодирование в мнемонику и отпечаток для
// сверки. Хранение PSK — на стороне Kotlin в защищённом Keystore/StrongBox.
// Все бинарные значения кодируются в base64; пустая строка = ошибка.
// ---------------------------------------------------------------------------

/// Генерирует новый 256-битный PSK (ОС-ГСЧ). Возвращает base64; "" при ошибке.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeGeneratePskB64<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match psk::generate_psk() {
        Ok(bytes) => new_jstring(&env, &b64_encode(&bytes)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Отпечаток PSK для повторной сверки (§6.2). Вход/выход — base64; "" при ошибке.
/// Необратим (HKDF-SHA512): сам PSK по отпечатку не восстанавливается.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativePskFingerprintB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    psk_b64: JString<'local>,
) -> jstring {
    let psk_b64 = read_string(&mut env, &psk_b64);
    let psk = match b64_decode(&psk_b64) {
        Some(p) => p,
        None => return new_jstring(&env, ""),
    };
    match psk::fingerprint(&psk) {
        Ok(fp) => new_jstring(&env, &b64_encode(&fp)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Кодирует PSK (base64) в BIP-39-подобную мнемонику (ручной ввод). "" при ошибке.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativePskToMnemonic<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    psk_b64: JString<'local>,
) -> jstring {
    let psk_b64 = read_string(&mut env, &psk_b64);
    let psk = match b64_decode(&psk_b64) {
        Some(p) => p,
        None => return new_jstring(&env, ""),
    };
    match psk::to_mnemonic(&psk) {
        Ok(phrase) => new_jstring(&env, &phrase),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Восстанавливает PSK (base64) из мнемонической фразы. "" при неверной фразе.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativePskFromMnemonicB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
) -> jstring {
    let phrase = read_string(&mut env, &phrase);
    match psk::from_mnemonic(&phrase) {
        Ok(bytes) => new_jstring(&env, &b64_encode(&bytes)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Непрозрачный идентификатор mailbox релея из PSK и направления (§7.6/§12.2).
/// Вход — base64 PSK и роль (0/1); выход — base64 256-бит id или "" при ошибке.
/// Сервер видит только этот id; в сеть PSK по-прежнему НЕ уходит.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeMailboxIdB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    psk_b64: JString<'local>,
    role: jint,
) -> jstring {
    let psk_b64 = read_string(&mut env, &psk_b64);
    let psk = match b64_decode(&psk_b64) {
        Some(p) => p,
        None => return new_jstring(&env, ""),
    };
    if !(0..=1).contains(&role) {
        return new_jstring(&env, "");
    }
    match psk::mailbox_id(&psk, role as u8) {
        Ok(id) => new_jstring(&env, &b64_encode(&id)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Per-device идентификатор mailbox v2 (мультидевайс §7.1.1, `docs/multidevice.md`
/// §4). `secretB64` — секрет соответствующего домена, `kind`:
///  * `0` — переписка (secret = PSK контакта);
///  * `1` — self-sync своих устройств (см. [`nativeDeriveSelfSyncSecretB64`]);
///  * `2` — ящик приглашений (secret = тег обнаружения, `docs/contact_requests.md`);
///  * `3` — ящик ответа на приглашение (secret = эфемерный `replyTag` раунда).
///
/// `scope` — accountId владельца ящика (`aegis:<hex>`); `deviceId` — его device id
/// (1..=255). Выход — base64 256-бит id или "" при ошибке. Сервер видит только
/// этот id (MD-2/MD-3).
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeMailboxIdDeviceB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    secret_b64: JString<'local>,
    kind: jint,
    scope: JString<'local>,
    device_id: jint,
) -> jstring {
    let secret_b64 = read_string(&mut env, &secret_b64);
    let scope = read_string(&mut env, &scope);
    let secret = match b64_decode(&secret_b64) {
        Some(s) => s,
        None => return new_jstring(&env, ""),
    };
    let kind = match kind {
        0 => psk::MailboxKind::Conversation,
        1 => psk::MailboxKind::SelfSync,
        2 => psk::MailboxKind::Invite,
        3 => psk::MailboxKind::Reply,
        _ => return new_jstring(&env, ""),
    };
    if !(1..=255).contains(&device_id) {
        return new_jstring(&env, "");
    }
    match psk::mailbox_id_device(&secret, kind, &scope, device_id as u32) {
        Ok(id) => new_jstring(&env, &b64_encode(&id)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Секрет синхронизации СВОИХ устройств из мнемоники (мультидевайс §7.1.1,
/// `docs/multidevice.md` §4.2). Общий всем линкованным устройствам (делят
/// мнемонику), неизвестен контактам. Выход — base64 32 байта или "" при ошибке.
/// В сеть НЕ уходит: используется локально как ikm для self-sync mailbox v2.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDeriveSelfSyncSecretB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
    passphrase: JString<'local>,
) -> jstring {
    let phrase = read_string(&mut env, &phrase);
    let passphrase = read_string(&mut env, &passphrase);
    match identity::derive_self_sync_secret(&phrase, &passphrase) {
        Ok(secret) => new_jstring(&env, &b64_encode(&secret[..])),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Ключ локальных хранилищ из мнемоники (см. [`identity::derive_storage_key`]):
/// второй слой поверх Keystore для ключа SQLCipher и файла PSK. Выход — base64
/// 32 байта или "" при ошибке. В сеть НЕ уходит.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDeriveStorageKeyB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
    passphrase: JString<'local>,
) -> jstring {
    let phrase = read_string(&mut env, &phrase);
    let passphrase = read_string(&mut env, &passphrase);
    match identity::derive_storage_key(&phrase, &passphrase) {
        Ok(key) => new_jstring(&env, &b64_encode(&key[..])),
        Err(_) => new_jstring(&env, ""),
    }
}

// ---------------------------------------------------------------------------
// RCH v1 (§6.2, `docs/contact_requests.md`): удалённое добавление контакта.
// Вся логика канонического порядка полей, transcript и вывода секрета слоя A
// живёт в Rust ([`discovery`]) и покрыта тестами — через JNI ходят только
// JSON/base64, чтобы Kotlin физически не мог перепутать порядок аргументов.
// ---------------------------------------------------------------------------

/// Новый тег обнаружения (256 бит) для адреса контакта. Выход — base64 или "".
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDiscoveryGenerateTagB64<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match discovery::generate_tag() {
        Ok(tag) => new_jstring(&env, &b64_encode(&tag)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Выводит из тега адрес записи каталога и ключ её запечатывания.
/// Ответ: `{"indexB64":"…","recordKeyB64":"…","tagCommitB64":"…"}` либо `{"error":"…"}`.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDiscoveryDeriveJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    tag_b64: JString<'local>,
) -> jstring {
    let tag_b64 = read_string(&mut env, &tag_b64);
    let tag = match b64_decode(&tag_b64) {
        Some(t) => t,
        None => return new_jstring(&env, &json_error("некорректный base64 тега")),
    };
    let ids = match discovery::derive_discovery(&tag) {
        Ok(v) => v,
        Err(e) => return new_jstring(&env, &json_error(&e.to_string())),
    };
    let commit = match discovery::tag_commit(&tag) {
        Ok(v) => v,
        Err(e) => return new_jstring(&env, &json_error(&e.to_string())),
    };
    let out = serde_json::json!({
        "indexB64": b64_encode(&ids.index),
        "recordKeyB64": b64_encode(ids.record_key.as_ref()),
        "tagCommitB64": b64_encode(&commit),
    });
    new_jstring(&env, &out.to_string())
}

/// Индекс каталога для ПУБЛИЧНОГО алиаса (`@ник`) после нормализации.
/// Ответ: `{"aliasNormalized":"…","indexB64":"…"}` либо `{"error":"…"}`.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeAliasIndexJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    alias: JString<'local>,
) -> jstring {
    let alias = read_string(&mut env, &alias);
    let normalized = match discovery::normalize_alias(&alias) {
        Ok(v) => v,
        Err(e) => return new_jstring(&env, &json_error(&e.to_string())),
    };
    match discovery::alias_index(&normalized) {
        Ok(index) => {
            let out = serde_json::json!({
                "aliasNormalized": normalized,
                "indexB64": b64_encode(&index),
            });
            new_jstring(&env, &out.to_string())
        }
        Err(e) => new_jstring(&env, &json_error(&e.to_string())),
    }
}

/// Запечатывает запись каталога (AAD привязан к индексу). Выход — base64 или "".
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDiscoverySealRecordB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    record_key_b64: JString<'local>,
    index_b64: JString<'local>,
    plaintext: JString<'local>,
) -> jstring {
    let record_key_b64 = read_string(&mut env, &record_key_b64);
    let index_b64 = read_string(&mut env, &index_b64);
    let plaintext = read_string(&mut env, &plaintext);
    let (key, index) = match (b64_decode(&record_key_b64), b64_decode(&index_b64)) {
        (Some(k), Some(i)) => (k, i),
        _ => return new_jstring(&env, ""),
    };
    match discovery::seal_record(&key, &index, plaintext.as_bytes()) {
        Ok(blob) => new_jstring(&env, &b64_encode(&blob)),
        Err(_) => new_jstring(&env, ""),
    }
}

/// Распечатывает запись каталога. Выход — plaintext (UTF-8) или "" при провале
/// AEAD-тега (чужой тег / битая или переставленная запись).
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeDiscoveryOpenRecord<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    record_key_b64: JString<'local>,
    index_b64: JString<'local>,
    blob_b64: JString<'local>,
) -> jstring {
    let record_key_b64 = read_string(&mut env, &record_key_b64);
    let index_b64 = read_string(&mut env, &index_b64);
    let blob_b64 = read_string(&mut env, &blob_b64);
    let (key, index, blob) = match (
        b64_decode(&record_key_b64),
        b64_decode(&index_b64),
        b64_decode(&blob_b64),
    ) {
        (Some(k), Some(i), Some(b)) => (k, i, b),
        _ => return new_jstring(&env, ""),
    };
    match discovery::open_record(&key, &index, &blob) {
        Ok(plain) => match std::str::from_utf8(&plain) {
            Ok(s) => new_jstring(&env, s),
            Err(_) => new_jstring(&env, ""),
        },
        Err(_) => new_jstring(&env, ""),
    }
}

/// Генерирует эфемерный материал одного раунда рукопожатия RCH.
/// Ответ: `{"epkSecretB64":"…","epkPublicB64":"…","contribB64":"…","nonceB64":"…",`
/// `"kemPublicB64":"…","kemSecretB64":"…"}` либо `{"error":"…"}`.
///
/// Секреты ОБЯЗАНЫ храниться инициатором до получения ответа (собеседник может
/// принять приглашение через сутки) — только в SQLCipher, и затираться сразу
/// после вывода секрета слоя A. `kem*` присутствуют лишь в сборке со слоем B.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeRchGenerateRoundJson<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    match rch_generate_round() {
        Ok(json) => new_jstring(&env, &json),
        Err(msg) => new_jstring(&env, &json_error(&msg)),
    }
}

/// Сборка без слоя B: рукопожатие RCH недоступно целиком (`INVITE` едет внутри
/// Signal PreKeyMessage, а PQ-составляющая секрета берётся из Kyber1024
/// libsignal). Отвечаем честной ошибкой, а не вырожденным материалом.
#[cfg(not(feature = "signal"))]
fn rch_generate_round() -> Result<String, String> {
    Err(RCH_NO_SIGNAL.into())
}

#[cfg(feature = "signal")]
fn rch_generate_round() -> Result<String, String> {
    let (epk_secret, epk_public) =
        discovery::generate_ephemeral().map_err(|e| e.to_string())?;
    let (contrib, nonce) = discovery::generate_contrib_nonce().map_err(|e| e.to_string())?;
    let kem = discovery::kem_generate().map_err(|e| e.to_string())?;
    let out = serde_json::json!({
        "epkSecretB64": b64_encode(epk_secret.as_ref()),
        "epkPublicB64": b64_encode(&epk_public),
        "contribB64": b64_encode(&contrib),
        "nonceB64": b64_encode(&nonce),
        "kemPublicB64": b64_encode(&kem.public),
        "kemSecretB64": b64_encode(kem.secret.as_ref()),
    });
    Ok(out.to_string())
}

/// Завершает рукопожатие RCH и выводит секрет контейнера слоя A.
///
/// Вход — JSON (все байтовые поля в base64):
/// ```json
/// { "role": "initiator" | "responder",
///   "mnemonic": "…", "passphrase": "",
///   "local":  { "epkPublicB64":"", "contribB64":"", "nonceB64":"" },
///   "remote": { "accountId":"", "ed25519Hex":"", "x25519Hex":"",
///               "epkPublicB64":"", "contribB64":"", "nonceB64":"" },
///   "localEpkSecretB64":"",
///   "kemPublicB64":"",
///   "kemSecretB64":"",    // только initiator (для декапсуляции)
///   "kemCiphertextB64":"" // только initiator (пришёл в ACCEPT)
/// }
/// ```
/// СВОИ `accountId` и Ed25519-ключ НЕ принимаются от вызывающего, а выводятся
/// здесь из мнемоники: иначе Kotlin мог бы передать значения, расходящиеся с
/// реальным ключом, и стороны молча не сошлись бы по секрету. Статический
/// X25519-секрет тоже не покидает Rust.
///
/// Ответ: `{"pskB64","transcriptB64","handshakeCode","kemCiphertextB64","localAccountId"}`
/// (`kemCiphertextB64` заполняется у `responder` — его нужно положить в `ACCEPT`)
/// либо `{"error":"…"}`.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeRchDeriveJson<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    request_json: JString<'local>,
) -> jstring {
    let request_json = read_string(&mut env, &request_json);
    match rch_derive(&request_json) {
        Ok(json) => new_jstring(&env, &json),
        Err(msg) => new_jstring(&env, &json_error(&msg)),
    }
}

/// Компактный `{"error": …}` для JSON-ответов границы JNI.
fn json_error(msg: &str) -> String {
    serde_json::json!({ "error": msg }).to_string()
}

/// Подписывает сообщение долгосрочным Ed25519-ключом аккаунта.
///
/// Приватный ключ выводится из мнемоники ЗДЕСЬ и наружу не отдаётся: Kotlin
/// присылает только подписываемые байты (base64) и получает подпись (base64).
/// Нужна для записи каталога и нагрузок рукопожатия RCH — там подпись ключом
/// аккаунта делает секрет слоя A независимым от libsignal (§6.2).
/// Пустая строка — ошибка (некорректная мнемоника / вход).
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeAccountSignB64<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    phrase: JString<'local>,
    passphrase: JString<'local>,
    message_b64: JString<'local>,
) -> jstring {
    let phrase = read_string(&mut env, &phrase);
    let passphrase = read_string(&mut env, &passphrase);
    let message_b64 = read_string(&mut env, &message_b64);
    let message = match b64_decode(&message_b64) {
        Some(m) => m,
        None => return new_jstring(&env, ""),
    };
    match identity::derive_account(&phrase, &passphrase) {
        Ok(keys) => new_jstring(&env, &b64_encode(&keys.sign(&message))),
        Err(_) => new_jstring(&env, ""),
    }
}

/// `accountId` из публичного Ed25519-ключа (hex): `aegis:<SHA-256(pub)[..16] hex>`.
///
/// Это ЕДИНСТВЕННАЯ реализация привязки на клиенте — чтобы проверка «accountId
/// действительно коммитится к этому ключу» (линчпин модели доверия удалённого
/// добавления) не была продублирована в Kotlin с риском разойтись. Пустая
/// строка — некорректный ключ.
#[no_mangle]
pub extern "system" fn Java_com_aegis_crypto_NativeAegisCore_nativeAccountIdFromEd25519<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    ed25519_hex: JString<'local>,
) -> jstring {
    let ed25519_hex = read_string(&mut env, &ed25519_hex);
    match identity::account_id_from_hex(&ed25519_hex) {
        Some(id) => new_jstring(&env, &id),
        None => new_jstring(&env, ""),
    }
}

/// Текст отказа, когда слой B не собран в текущей `.so`.
#[cfg(not(feature = "signal"))]
const RCH_NO_SIGNAL: &str = "слой B (libsignal) не собран: рукопожатие RCH недоступно";

/// Сборка без слоя B — см. [`rch_generate_round`].
#[cfg(not(feature = "signal"))]
fn rch_derive(_request_json: &str) -> Result<String, String> {
    Err(RCH_NO_SIGNAL.into())
}

/// Разбор запроса и вывод секрета RCH. Вынесено из JNI-обёртки, чтобы логику
/// можно было покрыть обычным юнит-тестом (см. `mod tests` ниже).
#[cfg(feature = "signal")]
fn rch_derive(request_json: &str) -> Result<String, String> {
    let req: serde_json::Value =
        serde_json::from_str(request_json).map_err(|e| format!("некорректный JSON: {e}"))?;

    let role = req["role"].as_str().unwrap_or_default();
    if role != "initiator" && role != "responder" {
        return Err("role должен быть \"initiator\" или \"responder\"".into());
    }

    let field_b64 = |v: &serde_json::Value, key: &str| -> Result<Vec<u8>, String> {
        let raw = v[key].as_str().unwrap_or_default();
        b64_decode(raw).ok_or_else(|| format!("некорректный base64 в поле {key}"))
    };
    let field_hex = |v: &serde_json::Value, key: &str| -> Result<Vec<u8>, String> {
        let raw = v[key].as_str().unwrap_or_default();
        hex_decode(raw).ok_or_else(|| format!("некорректный hex в поле {key}"))
    };

    // Свою личность выводим ИЗ МНЕМОНИКИ, а не из аргументов: приватные ключи не
    // покидают Rust, а расхождение «переданный accountId ↔ реальный ключ»
    // становится невозможным по конструкции.
    let mnemonic = req["mnemonic"].as_str().unwrap_or_default();
    let passphrase = req["passphrase"].as_str().unwrap_or_default();
    let keys = identity::derive_account(mnemonic, passphrase).map_err(|e| e.to_string())?;
    let local_account_id = keys.public.account_id.clone();
    let local_ed = hex_decode(&keys.public.ed25519_public_hex)
        .ok_or("не разобран свой ed25519 (внутренняя ошибка)")?;
    let local_x_secret = keys.x25519_secret_bytes();

    struct Raw {
        account_id: String,
        ed25519: Vec<u8>,
        epk: Vec<u8>,
        contrib: Vec<u8>,
        nonce: Vec<u8>,
    }
    let local_node = &req["local"];
    let local_raw = Raw {
        account_id: local_account_id.clone(),
        ed25519: local_ed,
        epk: field_b64(local_node, "epkPublicB64")?,
        contrib: field_b64(local_node, "contribB64")?,
        nonce: field_b64(local_node, "nonceB64")?,
    };
    let remote_node = &req["remote"];
    let remote_raw = Raw {
        account_id: remote_node["accountId"].as_str().unwrap_or_default().to_string(),
        ed25519: field_hex(remote_node, "ed25519Hex")?,
        epk: field_b64(remote_node, "epkPublicB64")?,
        contrib: field_b64(remote_node, "contribB64")?,
        nonce: field_b64(remote_node, "nonceB64")?,
    };
    let remote_x_pub = field_hex(remote_node, "x25519Hex")?;
    let local_epk_secret = field_b64(&req, "localEpkSecretB64")?;
    let kem_public = field_b64(&req, "kemPublicB64")?;

    // Общий секрет KEM: ответчик инкапсулирует (и отдаёт шифртекст наружу),
    // инициатор декапсулирует присланный шифртекст своим секретным ключом.
    let (kem_shared, kem_ciphertext) = if role == "responder" {
        let (shared, ct) = discovery::kem_encapsulate(&kem_public).map_err(|e| e.to_string())?;
        (shared.to_vec(), ct)
    } else {
        let kem_secret = field_b64(&req, "kemSecretB64")?;
        let ct = field_b64(&req, "kemCiphertextB64")?;
        let shared = discovery::kem_decapsulate(&kem_secret, &ct).map_err(|e| e.to_string())?;
        (shared.to_vec(), ct)
    };

    let local = discovery::Party {
        account_id: &local_raw.account_id,
        ed25519_pub: &local_raw.ed25519,
        epk_pub: &local_raw.epk,
        contrib: &local_raw.contrib,
        nonce: &local_raw.nonce,
    };
    let remote = discovery::Party {
        account_id: &remote_raw.account_id,
        ed25519_pub: &remote_raw.ed25519,
        epk_pub: &remote_raw.epk,
        contrib: &remote_raw.contrib,
        nonce: &remote_raw.nonce,
    };

    let transcript = discovery::transcript(&local, &remote, &kem_public, &kem_ciphertext)
        .map_err(|e| e.to_string())?;
    let psk = discovery::container_secret(
        &local,
        &remote,
        local_x_secret.as_ref(),
        &remote_x_pub,
        &local_epk_secret,
        &kem_public,
        &kem_ciphertext,
        &kem_shared,
    )
    .map_err(|e| e.to_string())?;

    let out = serde_json::json!({
        "pskB64": b64_encode(psk.as_ref()),
        "transcriptB64": b64_encode(&transcript),
        "handshakeCode": discovery::handshake_code(&transcript),
        "kemCiphertextB64": b64_encode(&kem_ciphertext),
        "localAccountId": local_account_id,
    });
    Ok(out.to_string())
}

#[cfg(feature = "signal")]
fn hex_decode(s: &str) -> Option<Vec<u8>> {
    let s = s.trim();
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_is_not_empty() {
        assert!(!env!("CARGO_PKG_VERSION").is_empty());
    }

    /// Полный раунд через границу JNI на РЕАЛЬНЫХ аккаунтах (две мнемоники):
    /// два JSON-запроса (инициатор/ответчик) обязаны дать ОДИН секрет слоя A и
    /// одинаковый код рукопожатия.
    #[test]
    #[cfg(feature = "signal")]
    fn rch_derive_json_roundtrip() {
        use crate::{discovery, identity};

        let phrase_a = identity::generate_mnemonic(24).unwrap();
        let phrase_b = identity::generate_mnemonic(24).unwrap();
        let acct_a = identity::derive_account(&phrase_a, "").unwrap();
        let acct_b = identity::derive_account(&phrase_b, "").unwrap();

        let (a_epk, a_epk_pub) = discovery::generate_ephemeral().unwrap();
        let (b_epk, b_epk_pub) = discovery::generate_ephemeral().unwrap();
        let (a_contrib, a_nonce) = discovery::generate_contrib_nonce().unwrap();
        let (b_contrib, b_nonce) = discovery::generate_contrib_nonce().unwrap();
        let kem = discovery::kem_generate().unwrap();

        let party_a = serde_json::json!({
            "accountId": acct_a.public.account_id,
            "ed25519Hex": acct_a.public.ed25519_public_hex,
            "x25519Hex": acct_a.public.x25519_public_hex,
            "epkPublicB64": super::b64_encode(&a_epk_pub),
            "contribB64": super::b64_encode(&a_contrib),
            "nonceB64": super::b64_encode(&a_nonce),
        });
        let party_b = serde_json::json!({
            "accountId": acct_b.public.account_id,
            "ed25519Hex": acct_b.public.ed25519_public_hex,
            "x25519Hex": acct_b.public.x25519_public_hex,
            "epkPublicB64": super::b64_encode(&b_epk_pub),
            "contribB64": super::b64_encode(&b_contrib),
            "nonceB64": super::b64_encode(&b_nonce),
        });

        // Ответчик (B) инкапсулирует и получает шифртекст для ACCEPT.
        let responder_req = serde_json::json!({
            "role": "responder",
            "mnemonic": phrase_b,
            "passphrase": "",
            "local": party_b,
            "remote": party_a,
            "localEpkSecretB64": super::b64_encode(b_epk.as_ref()),
            "kemPublicB64": super::b64_encode(&kem.public),
        })
        .to_string();
        let responder_out: serde_json::Value =
            serde_json::from_str(&super::rch_derive(&responder_req).unwrap()).unwrap();
        let kem_ct = responder_out["kemCiphertextB64"].as_str().unwrap().to_string();
        assert!(!kem_ct.is_empty());
        assert_eq!(responder_out["localAccountId"], acct_b.public.account_id);

        // Инициатор (A) декапсулирует присланный шифртекст.
        let initiator_req = serde_json::json!({
            "role": "initiator",
            "mnemonic": phrase_a,
            "passphrase": "",
            "local": party_a,
            "remote": party_b,
            "localEpkSecretB64": super::b64_encode(a_epk.as_ref()),
            "kemPublicB64": super::b64_encode(&kem.public),
            "kemSecretB64": super::b64_encode(kem.secret.as_ref()),
            "kemCiphertextB64": kem_ct,
        })
        .to_string();
        let initiator_out: serde_json::Value =
            serde_json::from_str(&super::rch_derive(&initiator_req).unwrap()).unwrap();

        assert_eq!(initiator_out["pskB64"], responder_out["pskB64"]);
        assert_eq!(initiator_out["transcriptB64"], responder_out["transcriptB64"]);
        assert_eq!(initiator_out["handshakeCode"], responder_out["handshakeCode"]);
        assert_eq!(initiator_out["localAccountId"], acct_a.public.account_id);
        assert!(!initiator_out["pskB64"].as_str().unwrap().is_empty());
    }

    /// Своя личность берётся ИЗ МНЕМОНИКИ, а не из аргументов: попытка выдать
    /// себя за другой accountId в поле `local` игнорируется.
    #[test]
    #[cfg(feature = "signal")]
    fn rch_derive_ignores_spoofed_local_identity() {
        use crate::{discovery, identity};

        let phrase = identity::generate_mnemonic(24).unwrap();
        let real = identity::derive_account(&phrase, "").unwrap();
        let (epk, epk_pub) = discovery::generate_ephemeral().unwrap();
        let (contrib, nonce) = discovery::generate_contrib_nonce().unwrap();
        let kem = discovery::kem_generate().unwrap();

        let req = serde_json::json!({
            "role": "responder",
            "mnemonic": phrase,
            "passphrase": "",
            "local": {
                "accountId": "aegis:ffffffffffffffffffffffffffffffff", // подлог
                "ed25519Hex": "00".repeat(32),                          // подлог
                "epkPublicB64": super::b64_encode(&epk_pub),
                "contribB64": super::b64_encode(&contrib),
                "nonceB64": super::b64_encode(&nonce),
            },
            "remote": {
                "accountId": "aegis:00000000000000000000000000000002",
                "ed25519Hex": "bb".repeat(32),
                "x25519Hex": real.public.x25519_public_hex, // любой валидный ключ
                "epkPublicB64": super::b64_encode(&epk_pub),
                "contribB64": super::b64_encode(&contrib),
                "nonceB64": super::b64_encode(&nonce),
            },
            "localEpkSecretB64": super::b64_encode(epk.as_ref()),
            "kemPublicB64": super::b64_encode(&kem.public),
        })
        .to_string();
        let out: serde_json::Value =
            serde_json::from_str(&super::rch_derive(&req).unwrap()).unwrap();
        assert_eq!(
            out["localAccountId"], real.public.account_id,
            "accountId обязан быть выведен из мнемоники, а не взят из аргумента",
        );
    }

    #[test]
    #[cfg(feature = "signal")]
    fn rch_derive_rejects_bad_mnemonic() {
        let req = serde_json::json!({
            "role": "responder",
            "mnemonic": "это точно не корректная bip39 фраза nope nope nope",
            "local": { "epkPublicB64": "", "contribB64": "", "nonceB64": "" },
            "remote": {},
        })
        .to_string();
        assert!(super::rch_derive(&req).is_err());
    }

    #[test]
    fn rch_derive_rejects_garbage() {
        assert!(super::rch_derive("not json").is_err());
        assert!(super::rch_derive("{}").is_err());
        assert!(super::rch_derive("{\"role\":\"nobody\"}").is_err());
    }
}
