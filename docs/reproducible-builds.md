# AEGIS — воспроизводимые сборки (reproducible builds)

> P9 (§9, §11, §12.1 технического задания). Для «самого защищённого» мессенджера
> воспроизводимость — не опция, а фундамент доверия: любой independent-ребилдер
> должен получить **побитово тот же** артефакт из тех же исходников и доказать,
> что распространяемый APK/бинарь собран именно из открытого кода, без закладок.

Этот документ описывает, что уже зафиксировано для воспроизводимости, как её
проверить и какие остаются честные ограничения.

---

## 1. Что фиксирует сборку (инварианты)

| Слой | Механизм фиксации | Файл |
|------|-------------------|------|
| Rust-тулчейн | точная версия `1.97.0` (не плавающий `stable`) | `rust/aegis-core/rust-toolchain.toml` |
| Крейты Rust | `Cargo.lock` коммитится; сборка с `--locked` | `rust/aegis-core/Cargo.lock` |
| Пути в `.so` | ремап `--remap-path-prefix` (RUSTFLAGS) | `scripts/verify-reproducible-build.sh` |
| Release-профиль | `opt-level=z, lto, codegen-units=1, strip, panic=abort` | `rust/aegis-core/Cargo.toml` |
| Gradle / AGP / Kotlin | версии в каталоге; версия Gradle в wrapper | `gradle/libs.versions.toml`, `gradle/wrapper/…` |
| Android-зависимости | точные версии (не `+`/динамические) | `gradle/libs.versions.toml` |
| Метаданные зависимостей в APK | отключены (`dependenciesInfo{...=false}`) | `app/build.gradle.kts` |
| PT-бинарь (Go) | пин версий исходников PT + `GOTOOLCHAIN` + `-trimpath -buildid=` | `pt/build_pt.sh` |
| NDK | точная версия `30.0.15729638` | `gradle/libs.versions.toml` (`ndk`) |

Единая версия тулчейна на весь продукт: клиентское ядро, backend
(`aegis-server/rust-toolchain.toml` + `rust:1.97.0-bookworm` в Dockerfile) —
один и тот же `rustc 1.97.0`.

---

## 2. Проверка воспроизводимости нативного ядра (быстро и точно)

`libaegis_core.so` — самый security-критичный артефакт (слой A каскад, слой B
libsignal, Arti/Tor). Он собирается детерминированно; проверка — двойная сборка
и сравнение SHA-256:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.15729638"
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:$JAVA_HOME/bin:$PATH"

scripts/verify-reproducible-build.sh            # ABI по умолчанию arm64-v8a
AEGIS_ABIS="arm64-v8a x86_64" scripts/verify-reproducible-build.sh
```

Скрипт выставляет детерминированное окружение (`TZ=UTC`, `LC_ALL=C`,
`CARGO_INCREMENTAL=0`, `SOURCE_DATE_EPOCH` из времени HEAD-коммита) и ремапит
абсолютные пути (исходники, `$CARGO_HOME`, `$RUSTUP_HOME`) через
`--remap-path-prefix`, после чего собирает `.so` дважды и сверяет хэши.

> Почему ремап через RUSTFLAGS, а не профильный `trim-paths`: в Cargo 1.97.0
> ключ `[profile.release] trim-paths` ещё не стабилизирован (требует nightly),
> поэтому используем классический `--remap-path-prefix`. После стабилизации —
> перенести в `Cargo.toml` (см. TODO в профиле).

---

## 3. Проверка воспроизводимости APK

APK детерминирован при фиксированных AGP/JDK/зависимостях, но у него есть
особенность: **блок подписи** (v2/v3 APK Signature Scheme) зависит от ключа
подписи и не должен сравниваться «в лоб». Правильный процесс (как в F-Droid):

```bash
# Двойная сборка unsigned release APK и сравнение:
AEGIS_VERIFY_APK=1 scripts/verify-reproducible-build.sh

# Сравнение ПОДПИСАННОГО APK с эталоном (без учёта блока подписи):
pip install apksigcopier diffoscope   # или из пакетов дистрибутива
apksigcopier compare reference.apk rebuilt.apk --unsigned
# либо детальный diff содержимого:
diffoscope reference.apk rebuilt.apk
```

`apksigcopier` копирует/сравнивает подпись отдельно, `diffoscope` показывает
любые байтовые расхождения содержимого — так публикуется доказательство, что
distributed-APK собран из этого кода.

---

## 4. Воспроизводимость backend

См. `aegis-server/docs/reproducible-builds.md` (если добавлен) или кратко:

```bash
cd ../aegis-server
scripts/verify-reproducible-build.sh                    # локальная двойная сборка
AEGIS_VERIFY_DOCKER=1 scripts/verify-reproducible-build.sh   # + бинарь из Docker
```

Docker-образ пинуется как `rust:1.97.0-bookworm`; для максимальной строгости
рекомендуется пин по digest (`rust:1.97.0-bookworm@sha256:…`) — фиксирует и
версию Debian, и патч-уровень.

---

## 5. Честные ограничения

- **Кросс-платформенность хоста.** `.so` собирается кросс-компилятором
  (cargo-ndk → NDK clang), поэтому ОС хоста влияет слабо, но версия NDK — сильно
  (зафиксирована). Полную кросс-машинную воспроизводимость подтверждает CI/
  сторонний ребилдер, а не одна машина.
- **PT-бинари (Go).** Пин версий исходников + `-trimpath -buildid=` даёт
  воспроизводимость при одной версии Go (`GOTOOLCHAIN`); разные минорные версии
  Go могут дать разный код.
- **Подпись.** Секреты подписи не в репозитории (§12.1); воспроизводится
  *содержимое*, подпись сравнивается отдельно (`apksigcopier`).
- **Транзитивные обновления.** `--locked`/`Cargo.lock` и точные версии в
  каталоге исключают тихий дрейф; обновления зависимостей — только явным PR.

---

## 6. Чек-лист перед релизом

- [ ] `scripts/verify-reproducible-build.sh` → «ядро воспроизводимо ✅».
- [ ] `AEGIS_VERIFY_APK=1 …` → unsigned APK совпадает между двумя сборками.
- [ ] `scripts/generate-sbom.sh` обновил `docs/sbom/`.
- [ ] Опубликованы SHA-256 артефактов + инструкция проверки (`README.md`, раздел «Публикация и проверка контрольных сумм»).
- [ ] Тег версии = `versionName`; `Cargo.lock` и `libs.versions.toml` без ручных правок после сборки.
