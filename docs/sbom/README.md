# SBOM / инвентарь зависимостей

Машиночитаемый и человекочитаемый перечень зависимостей для аудита (P9, §9/§1).

## Файлы

| Файл | Что это | Как получить |
|------|---------|--------------|
| `cargo-tree.txt` | дерево крейтов Rust-ядра (features `signal,tor`) с версиями | `scripts/generate-sbom.sh` |
| `cargo-licenses.txt` | лицензии крейтов | `cargo install cargo-license` затем скрипт |
| `cargo-sbom.cdx.json` | CycloneDX SBOM (стандарт) | `cargo install cargo-cyclonedx` затем скрипт |
| `gradle-dependencies.txt` | зависимости Android-модулей (releaseRuntimeClasspath) | `scripts/generate-sbom.sh` (нужны SDK/сеть) |

## Источник истины

Версии фиксируются в `rust/aegis-core/Cargo.lock` и `gradle/libs.versions.toml`.
Эти снимки в `docs/sbom/` — производные, обновляются скриптом перед релизом/аудитом.

## Обновление

```bash
scripts/generate-sbom.sh
git add docs/sbom && git commit -m "docs(sbom): обновить инвентарь зависимостей"
```

Обзор ключевых компонентов и лицензий — `../../THIRD_PARTY_NOTICES.md`.
