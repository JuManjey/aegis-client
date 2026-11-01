# CI/CD в AEGIS (P9, §9/§12.1)

Организационное завершение приоритета **P9**: автоматизация тестов,
воспроизводимости и публикации. Пайплайны — GitHub Actions в обоих репозиториях.
Секреты подписи НИКОГДА не коммитятся — только GitHub Secrets (§12.1).

## Клиент (`aegis-client`)

| Workflow | Триггеры | Что делает |
|----------|----------|------------|
| `.github/workflows/ci.yml` | push/PR в `main`, вручную | `rust-core`: `cargo fmt --check`, `clippy --features signal,tor`, тесты `base` (49/49) и `signal,tor` (68/68). `android`: `:app:assembleDebug` (Kotlin + C++-шим кадрового слоя §1.26 через CMake) + JVM unit-тесты. |
| `.github/workflows/reproducible-build.yml` | тег `v*`, вручную, еженедельно | `scripts/verify-reproducible-build.sh` — двойная сборка `libaegis_core.so` + сравнение SHA-256 на независимом раннере. |
| `.github/workflows/release.yml` | тег `v*`, вручную | `:app:assembleRelease` (полные `.so` + PT + C++-шим) → `SHA256SUMS` (+GPG-подпись) → GitHub Release. |

### Инварианты, на которых держится CI
- Rust-тулчейн пинуется `rust/aegis-core/rust-toolchain.toml` (**1.97.0**);
  rustup доустанавливает его автоматически при первой сборке в этом каталоге.
- `protoc` нужен для `signal` (libsignal/prost-build) — ставится в job.
- NDK `30.0.15729638` и CMake `3.22.1` — совпадают с `libs.versions.toml` и
  `app/build.gradle.kts`.
- В `android`-job Rust/PT отключены (`-Paegis.buildRust=false
  -Paegis.buildPt=false`), чтобы быстро проверять Kotlin/Gradle/**C++-шим**;
  корректность Rust проверяет отдельный `rust-core`-job.

## Backend (`aegis-server`)

| Workflow | Триггеры | Что делает |
|----------|----------|------------|
| `.github/workflows/ci.yml` | push/PR в `main`, вручную | `cargo fmt --check`, `clippy`, тесты (22/22), `cargo build --release --locked`; отдельный job — `docker build` по `Dockerfile` (`rust:1.97.0-bookworm`). |
| `.github/workflows/reproducible-build.yml` | тег `v*`, вручную, еженедельно | `scripts/verify-reproducible-build.sh` (двойная сборка `aegis-relay`), опционально сверка бинаря из Docker. |

## Секреты (GitHub Secrets) для release

Все опциональны — без них release-workflow всё равно соберёт APK (debug-подпись)
и `SHA256SUMS` (без GPG-подписи):

| Секрет | Назначение |
|--------|------------|
| `ANDROID_KEYSTORE_BASE64` | keystore подписи APK (base64) |
| `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` | параметры подписи APK |
| `GPG_PRIVATE_KEY` | приватный GPG-ключ проекта (armored) для подписи `SHA256SUMS` |
| `GPG_KEY_ID` | id/отпечаток ключа подписи |

Проверка пользователем — см. [`../README.md`](../README.md), раздел «Публикация и проверка контрольных сумм».

## Локальные эквиваленты CI

```bash
# Крипто-ядро (то же, что rust-core job):
cargo test --manifest-path rust/aegis-core/Cargo.toml
cargo test --manifest-path rust/aegis-core/Cargo.toml --features signal,tor

# Воспроизводимость:
scripts/verify-reproducible-build.sh
AEGIS_ABIS="arm64-v8a x86_64" scripts/verify-reproducible-build.sh

# Контрольные суммы/подпись релиза:
OUT_DIR=dist scripts/publish-checksums.sh app/build/outputs/apk/release
```
