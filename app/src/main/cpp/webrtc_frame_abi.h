// AEGIS — минимальный ABI-совместимый интерфейс WebRTC для кадрового слоя (§1.14).
//
// НАЗНАЧЕНИЕ. Готовый артефакт `io.github.webrtc-sdk:android:125.6422.07` (WebRTC
// M125, ветка branch-heads/6422) НЕ поставляет C++-заголовки, а интерфейсы
// `org.webrtc.FrameEncryptor`/`FrameDecryptor` отдают лишь НАТИВНЫЙ указатель
// (`getNativeFrameEncryptor()`), который JNI-биндинг reinterpret_cast'ит в
// `webrtc::FrameEncryptorInterface*`. Чтобы реализовать этот интерфейс, нам нужна
// его РОВНО ТАКАЯ ЖЕ декларация (порядок и сигнатуры виртуальных методов, layout
// базового рефкаунт-класса и `rtc::ArrayView`), как в M125.
//
// Мы НЕ форкаем и НЕ пересобираем WebRTC (§1): этот заголовок — точная (дословная)
// копия ПУБЛИЧНЫХ деклараций WebRTC M125 (BSD-3-Clause, © The WebRTC project
// authors), урезанная до абсолютного минимума. Наш шим только РЕАЛИЗУЕТ эти
// абстрактные интерфейсы и вызывается WebRTC через vtable — никаких символов
// WebRTC мы не линкуем.
//
// Источники (сверено дословно, branch-heads/6422):
//   api/ref_count.h, rtc_base/ref_count.h — RefCountInterface/RefCountReleaseStatus
//   api/media_types.h                      — cricket::MediaType
//   api/array_view.h                       — rtc::ArrayView (layout {T*; size_t})
//   api/crypto/frame_encryptor_interface.h — FrameEncryptorInterface
//   api/crypto/frame_decryptor_interface.h — FrameDecryptorInterface
//
// ГРАНИЦА (честно): корректность держится на ABI-совместимости с M125. Смена
// версии WebRTC (`libs.versions.toml: webrtc`) требует ревизии этого файла.
// Компиляционные static_assert ниже ловят изменение layout ArrayView.

#ifndef AEGIS_WEBRTC_FRAME_ABI_H_
#define AEGIS_WEBRTC_FRAME_ABI_H_

#include <cstddef>
#include <cstdint>
#include <type_traits>
#include <vector>

namespace webrtc {

// --- api/ref_count.h (дословно) --------------------------------------------
enum class RefCountReleaseStatus { kDroppedLastRef, kOtherRefsRemained };

class RefCountInterface {
 public:
  virtual void AddRef() const = 0;
  virtual RefCountReleaseStatus Release() const = 0;

 protected:
  virtual ~RefCountInterface() {}
};

}  // namespace webrtc

namespace rtc {
// rtc_base/ref_count.h: обратно-совместимые алиасы в M125.
using webrtc::RefCountInterface;
using webrtc::RefCountReleaseStatus;
}  // namespace rtc

namespace cricket {
// --- api/media_types.h (дословно) ------------------------------------------
enum MediaType {
  MEDIA_TYPE_AUDIO,
  MEDIA_TYPE_VIDEO,
  MEDIA_TYPE_DATA,
  MEDIA_TYPE_UNSUPPORTED
};
}  // namespace cricket

namespace rtc {

// --- api/array_view.h: ABI-совместимый минимум ------------------------------
// WebRTC `rtc::ArrayView<T>` переменного размера — тривиально копируемый
// {T* data_; size_t size_;} (см. array_view_internal::ArrayViewBase<T, VarSize>).
// Реплицируем РОВНО этот layout: WebRTC конструирует ArrayView на своей стороне и
// передаёт нам ПО ЗНАЧЕНИЮ; мы только читаем data()/size(). Никаких RTC_DCHECK.
template <typename T>
class ArrayView {
 public:
  ArrayView() : data_(nullptr), size_(0) {}
  ArrayView(T* data, size_t size) : data_(size == 0 ? nullptr : data), size_(size) {}

  T* data() const { return data_; }
  size_t size() const { return size_; }
  bool empty() const { return size_ == 0; }

 private:
  T* data_;
  size_t size_;
};

}  // namespace rtc

static_assert(sizeof(rtc::ArrayView<const uint8_t>) == 2 * sizeof(void*),
              "ArrayView layout (ptr,size) должен совпадать с WebRTC M125");
static_assert(std::is_trivially_copyable<rtc::ArrayView<const uint8_t>>::value,
              "ArrayView должен быть тривиально копируемым (передача по значению)");

namespace webrtc {

// --- api/crypto/frame_encryptor_interface.h (дословно, с нашими rtc::ArrayView)
class FrameEncryptorInterface : public rtc::RefCountInterface {
 public:
  ~FrameEncryptorInterface() override {}

  virtual int Encrypt(cricket::MediaType media_type,
                      uint32_t ssrc,
                      rtc::ArrayView<const uint8_t> additional_data,
                      rtc::ArrayView<const uint8_t> frame,
                      rtc::ArrayView<uint8_t> encrypted_frame,
                      size_t* bytes_written) = 0;

  virtual size_t GetMaxCiphertextByteSize(cricket::MediaType media_type,
                                          size_t frame_size) = 0;
};

// --- api/crypto/frame_decryptor_interface.h (дословно) ----------------------
class FrameDecryptorInterface : public rtc::RefCountInterface {
 public:
  enum class Status { kOk, kRecoverable, kFailedToDecrypt, kUnknown };

  struct Result {
    Result(Status status, size_t bytes_written)
        : status(status), bytes_written(bytes_written) {}

    bool IsOk() const { return status == Status::kOk; }

    const Status status;
    const size_t bytes_written;
  };

  ~FrameDecryptorInterface() override {}

  virtual Result Decrypt(cricket::MediaType media_type,
                         const std::vector<uint32_t>& csrcs,
                         rtc::ArrayView<const uint8_t> additional_data,
                         rtc::ArrayView<const uint8_t> encrypted_frame,
                         rtc::ArrayView<uint8_t> frame) = 0;

  virtual size_t GetMaxPlaintextByteSize(cricket::MediaType media_type,
                                         size_t encrypted_frame_size) = 0;
};

}  // namespace webrtc

#endif  // AEGIS_WEBRTC_FRAME_ABI_H_
