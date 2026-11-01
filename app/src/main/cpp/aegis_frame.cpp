// AEGIS — нативный кадровый слой поверх DTLS-SRTP (§1.14, §7.5/§8/§10.10).
//
// Реализует `webrtc::FrameEncryptorInterface`/`FrameDecryptorInterface`
// (insertable streams) как НЕЗАВИСИМЫЙ AEAD-слой поверх DTLS-SRTP: каждый
// медиа-кадр дополнительно шифруется на ключах звонка (Signal-сессия + ручной
// PSK, см. Rust `calls.rs`). Компрометация DTLS-SRTP или подмена согласования
// недоверенным сервером не раскрывает медиа, пока цел out-of-band корень.
//
// Свою крипту НЕ пишем (§1): AEAD выполняет аудированный ChaCha20-Poly1305 в
// Rust-ядре (`libaegis_core.so`, символы `aegis_frame_seal/open`), которое мы
// резолвим через dlopen. Этот C++-модуль — только vtable-совместимая оболочка +
// маршалинг буферов + монотонный счётчик кадра (nonce), никакой криптологики.
//
// Формат кадра на проводе: [counter(8, BE)] || [ciphertext || tag(16)].
// Счётчик передаётся, чтобы приёмник восстановил nonce при потере/переупорядоч.

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <vector>

#include <android/log.h>

#include "webrtc_frame_abi.h"

#define AEGIS_LOG_TAG "AegisFrame"
#define AEGIS_LOGW(...) \
  __android_log_print(ANDROID_LOG_WARN, AEGIS_LOG_TAG, __VA_ARGS__)
#define AEGIS_LOGI(...) \
  __android_log_print(ANDROID_LOG_INFO, AEGIS_LOG_TAG, __VA_ARGS__)

namespace {

// --- Накладные расходы кадра ------------------------------------------------
constexpr size_t kCounterLen = 8;   // BE u64 счётчик кадра (nonce-material)
constexpr size_t kTagLen = 16;      // тег Poly1305
constexpr size_t kOverhead = kCounterLen + kTagLen;

// --- C-ABI Rust-ядра (см. rust/aegis-core/src/frame.rs) ---------------------
using SealFn = int32_t (*)(const uint8_t*, size_t,          // key
                           const uint8_t*, size_t,          // salt
                           uint64_t,                        // counter
                           const uint8_t*, size_t,          // aad
                           const uint8_t*, size_t,          // pt
                           uint8_t*, size_t, size_t*);      // out, cap, out_len
using OpenFn = SealFn;                                      // та же сигнатура
using AvailFn = int32_t (*)();

struct RustAead {
  SealFn seal = nullptr;
  OpenFn open = nullptr;
  bool ok = false;
};

// Резолвит символы кадрового AEAD из libaegis_core.so один раз (thread-safe).
const RustAead& rust_aead() {
  static RustAead cached = [] {
    RustAead r;
    // libaegis_core.so уже загружена (System.loadLibrary в CryptoModule), но на
    // всякий случай dlopen её же (RTLD_NOLOAD вернёт handle, если загружена).
    void* h = dlopen("libaegis_core.so", RTLD_NOW | RTLD_NOLOAD);
    if (h == nullptr) h = dlopen("libaegis_core.so", RTLD_NOW);
    if (h == nullptr) {
      AEGIS_LOGW("libaegis_core.so не найдена: %s", dlerror());
      return r;
    }
    auto avail = reinterpret_cast<AvailFn>(dlsym(h, "aegis_frame_available"));
    r.seal = reinterpret_cast<SealFn>(dlsym(h, "aegis_frame_seal"));
    r.open = reinterpret_cast<OpenFn>(dlsym(h, "aegis_frame_open"));
    r.ok = (avail != nullptr && avail() == 1 && r.seal != nullptr && r.open != nullptr);
    if (!r.ok) {
      AEGIS_LOGW("символы aegis_frame_* не разрешились — кадровый слой отключён");
    } else {
      AEGIS_LOGI("кадровый AEAD (Rust ChaCha20-Poly1305) подключён");
    }
    return r;
  }();
  return cached;
}

void put_be64(uint8_t* p, uint64_t v) {
  for (int i = 0; i < 8; ++i) p[i] = static_cast<uint8_t>(v >> (56 - 8 * i));
}

uint64_t get_be64(const uint8_t* p) {
  uint64_t v = 0;
  for (int i = 0; i < 8; ++i) v = (v << 8) | p[i];
  return v;
}

// Базовый рефкаунт-объект в стиле webrtc::RefCountedObject: счётчик стартует с 0,
// первый AddRef (его делает scoped_refptr в JNI_RtpSender_SetFrameEncryptor)
// доводит до 1; на Release до 0 объект самоуничтожается.
template <typename Interface>
class RefCounted : public Interface {
 public:
  void AddRef() const override { ref_count_.fetch_add(1, std::memory_order_relaxed); }
  webrtc::RefCountReleaseStatus Release() const override {
    if (ref_count_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
      delete this;
      return webrtc::RefCountReleaseStatus::kDroppedLastRef;
    }
    return webrtc::RefCountReleaseStatus::kOtherRefsRemained;
  }

 protected:
  ~RefCounted() override = default;

 private:
  mutable std::atomic<int> ref_count_{0};
};

// Ключевой материал одного направления (send или recv).
struct DirKeys {
  std::vector<uint8_t> key;   // 32
  std::vector<uint8_t> salt;  // 14
};

class AegisFrameEncryptor final : public RefCounted<webrtc::FrameEncryptorInterface> {
 public:
  explicit AegisFrameEncryptor(DirKeys keys) : keys_(std::move(keys)) {}

  int Encrypt(cricket::MediaType /*media_type*/,
              uint32_t /*ssrc*/,
              rtc::ArrayView<const uint8_t> additional_data,
              rtc::ArrayView<const uint8_t> frame,
              rtc::ArrayView<uint8_t> encrypted_frame,
              size_t* bytes_written) override {
    const RustAead& r = rust_aead();
    if (!r.ok) return 1;
    if (encrypted_frame.size() < frame.size() + kOverhead) return 2;

    const uint64_t counter = counter_.fetch_add(1, std::memory_order_relaxed);
    uint8_t* out = encrypted_frame.data();
    put_be64(out, counter);

    size_t ct_len = 0;
    int32_t rc = r.seal(
        keys_.key.data(), keys_.key.size(),
        keys_.salt.data(), keys_.salt.size(),
        counter,
        additional_data.data(), additional_data.size(),
        frame.data(), frame.size(),
        out + kCounterLen, encrypted_frame.size() - kCounterLen, &ct_len);
    if (rc != 0) return 3;
    *bytes_written = kCounterLen + ct_len;
    return 0;
  }

  size_t GetMaxCiphertextByteSize(cricket::MediaType /*media_type*/,
                                  size_t frame_size) override {
    return frame_size + kOverhead;
  }

 private:
  DirKeys keys_;
  mutable std::atomic<uint64_t> counter_{0};
};

class AegisFrameDecryptor final : public RefCounted<webrtc::FrameDecryptorInterface> {
 public:
  explicit AegisFrameDecryptor(DirKeys keys) : keys_(std::move(keys)) {}

  Result Decrypt(cricket::MediaType /*media_type*/,
                 const std::vector<uint32_t>& /*csrcs*/,
                 rtc::ArrayView<const uint8_t> additional_data,
                 rtc::ArrayView<const uint8_t> encrypted_frame,
                 rtc::ArrayView<uint8_t> frame) override {
    const RustAead& r = rust_aead();
    if (!r.ok) return Result(Status::kFailedToDecrypt, 0);
    if (encrypted_frame.size() < kOverhead) return Result(Status::kFailedToDecrypt, 0);

    const uint64_t counter = get_be64(encrypted_frame.data());
    const uint8_t* ct = encrypted_frame.data() + kCounterLen;
    const size_t ct_len = encrypted_frame.size() - kCounterLen;

    size_t pt_len = 0;
    int32_t rc = r.open(
        keys_.key.data(), keys_.key.size(),
        keys_.salt.data(), keys_.salt.size(),
        counter,
        additional_data.data(), additional_data.size(),
        ct, ct_len,
        frame.data(), frame.size(), &pt_len);
    if (rc != 0) return Result(Status::kFailedToDecrypt, 0);
    return Result(Status::kOk, pt_len);
  }

  size_t GetMaxPlaintextByteSize(cricket::MediaType /*media_type*/,
                                 size_t encrypted_frame_size) override {
    // Верхняя граница (никогда не меньше фактического plaintext): достаточно.
    return encrypted_frame_size;
  }

 private:
  DirKeys keys_;
};

// Копирует jbyteArray в вектор байт (или пустой при null).
std::vector<uint8_t> to_bytes(JNIEnv* env, jbyteArray arr) {
  std::vector<uint8_t> out;
  if (arr == nullptr) return out;
  const jsize n = env->GetArrayLength(arr);
  if (n <= 0) return out;
  out.resize(static_cast<size_t>(n));
  env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
  return out;
}

// Проверяет корректность длин ключа/соли и доступность Rust-AEAD.
bool valid_keys(const DirKeys& k) {
  return rust_aead().ok && k.key.size() == 32 && k.salt.size() >= 12;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aegis_messenger_call_NativeFrameCrypto_nativeAvailable(JNIEnv*, jobject) {
  return rust_aead().ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_aegis_messenger_call_NativeFrameCrypto_nativeCreateEncryptor(
    JNIEnv* env, jobject, jbyteArray key, jbyteArray salt) {
  DirKeys k{to_bytes(env, key), to_bytes(env, salt)};
  if (!valid_keys(k)) return 0;
  auto* enc = new AegisFrameEncryptor(std::move(k));
  return reinterpret_cast<jlong>(static_cast<webrtc::FrameEncryptorInterface*>(enc));
}

JNIEXPORT jlong JNICALL
Java_com_aegis_messenger_call_NativeFrameCrypto_nativeCreateDecryptor(
    JNIEnv* env, jobject, jbyteArray key, jbyteArray salt) {
  DirKeys k{to_bytes(env, key), to_bytes(env, salt)};
  if (!valid_keys(k)) return 0;
  auto* dec = new AegisFrameDecryptor(std::move(k));
  return reinterpret_cast<jlong>(static_cast<webrtc::FrameDecryptorInterface*>(dec));
}

}  // extern "C"
