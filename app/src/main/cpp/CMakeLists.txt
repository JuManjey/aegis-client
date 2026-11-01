# AEGIS — сборка нативного кадрового слоя поверх DTLS-SRTP (§1.14).
#
# libaegis_frame.so реализует webrtc::FrameEncryptorInterface/FrameDecryptorInterface
# (см. aegis_frame.cpp) и вызывает аудированный ChaCha20-Poly1305 из
# libaegis_core.so (Rust) через dlopen. НИКАКИЕ символы WebRTC не линкуются —
# шим только реализует абстрактные интерфейсы (§1: WebRTC не форкаем/не пересобираем).
cmake_minimum_required(VERSION 3.22.1)
project(aegis_frame CXX)

add_library(aegis_frame SHARED aegis_frame.cpp diag_crash_handler.cpp)

target_compile_features(aegis_frame PRIVATE cxx_std_17)
# -fno-omit-frame-pointer + unwind-tables нужны diag_crash_handler.cpp: он
# восстанавливает стек упавшего потока обходом FP-цепочки из ucontext. Без
# кадровых указателей в отчёт попадают только pc/lr (тоже полезно, но одна точка).
target_compile_options(aegis_frame PRIVATE
    -fvisibility=hidden
    -Wall
    -Wextra
    -fno-omit-frame-pointer
    -funwind-tables
    -fasynchronous-unwind-tables)

# log — android/log.h (диагностика); dl — dlopen/dlsym (резолв Rust-AEAD).
find_library(log-lib log)
target_link_libraries(aegis_frame ${log-lib} dl)
