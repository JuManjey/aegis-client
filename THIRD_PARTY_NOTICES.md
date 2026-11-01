# Сторонние компоненты и лицензии (Third-Party Notices)

AEGIS распространяется под **GPL-3.0-or-later** (см. `LICENSE`) и использует
сторонние компоненты с открытыми лицензиями. Полный машиночитаемый перечень с
точными версиями — в `docs/sbom/` (генерируется `scripts/generate-sbom.sh` из
`Cargo.lock` и `gradle/libs.versions.toml`). Ниже — обзор ключевых компонентов;
это НЕ исчерпывающий список транзитивных зависимостей.

> Совместимость: все перечисленные лицензии (Apache-2.0, MIT, BSD, MPL-2.0, BSD
> для libsignal/AGPL см. ниже) совместимы с распространением продукта под GPLv3.
> При добавлении зависимости проверяйте совместимость с GPLv3 (важно для F-Droid).

## Крипто-ядро (Rust)

| Компонент | Назначение | Лицензия |
|-----------|------------|----------|
| libsignal (signalapp) | Signal Protocol (слой B) | AGPL-3.0 (совместима при распространении product под GPL/AGPL — проверить условия связывания) |
| RustCrypto: `chacha20poly1305`, `aes-gcm`, `eax`, `camellia`, `twofish`, `aead`, `hkdf`, `sha2`, `argon2` | каскад слоя A, KDF | Apache-2.0 OR MIT |
| `ed25519-dalek`, `x25519-dalek` | подписи/ECDH | BSD-3-Clause |
| `bip39` | мнемоника восстановления | Apache-2.0 / MIT |
| `zeroize`, `getrandom`, `base64`, `serde`, `serde_json` | утилиты | Apache-2.0 / MIT |
| Arti (`arti-client`, `tor-*`) | встроенный Tor | Apache-2.0 / MIT |
| `rustls` + `ring` | TLS/крипто-провайдер | Apache-2.0 / ISC / MIT |
| `rusqlite` (bundled SQLite) | хранилище директории Arti | MIT (SQLite — public domain) |

## Pluggable transports (Go, вкладываются как бинари)

| Компонент | Назначение | Лицензия |
|-----------|------------|----------|
| lyrebird (Tor Project) | obfs4 + meek_lite | BSD-3-Clause |
| snowflake (Tor Project) | snowflake rendezvous | BSD-3-Clause |

> Мы НЕ модифицируем PT (§1) — собираем из официальных исходников по
> закреплённым ref (`pt/build_pt.sh`).

## Android / Kotlin

| Компонент | Лицензия |
|-----------|----------|
| Kotlin, AndroidX, Jetpack Compose, Material 3 | Apache-2.0 |
| `androidx.biometric` | Apache-2.0 |
| `sqlcipher-android` (Zetetic) | BSD-style (SQLCipher) |
| ZXing (`core`), zxing-android-embedded | Apache-2.0 |
| WebRTC (`webrtc-sdk/android`) | BSD-3-Clause (WebRTC) |
| kotlinx.serialization, kotlinx.coroutines | Apache-2.0 |

## Как обновить перечень

```bash
scripts/generate-sbom.sh          # обновляет docs/sbom/
# при наличии инструментов: cargo install cargo-license cargo-cyclonedx
```

Тексты лицензий отдельных компонентов доступны в их репозиториях и в
метаданных крейтов/артефактов; агрегированный список лицензий крейтов —
`docs/sbom/cargo-licenses.txt` (после `cargo license`).
