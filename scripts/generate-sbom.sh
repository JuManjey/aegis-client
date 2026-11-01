#!/usr/bin/env bash
# =============================================================================
# AEGIS client — генерация SBOM / инвентаря зависимостей (P9, аудит §9/§1).
#
# Формирует человекочитаемый и машиночитаемый перечень ВСЕХ зависимостей
# (Rust-ядро + Gradle/Android). Источник истины по версиям — Cargo.lock и
# gradle/libs.versions.toml; этот скрипт лишь агрегирует их для аудиторов.
#
# Выход: docs/sbom/
#   - cargo-tree.txt          — дерево крейтов (все фичи), с версиями
#   - cargo-licenses.txt      — лицензии крейтов (если установлен cargo-license)
#   - cargo-sbom.cdx.json     — CycloneDX (если установлен cargo-cyclonedx)
#   - gradle-dependencies.txt — зависимости Android-модулей
#
# Установка опциональных инструментов:
#   cargo install cargo-license cargo-cyclonedx
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_DIR="${ROOT_DIR}/rust/aegis-core"
OUT_DIR="${ROOT_DIR}/docs/sbom"
mkdir -p "${OUT_DIR}"

echo "[sbom] Rust: дерево крейтов (features signal,tor) → cargo-tree.txt"
( cd "${CORE_DIR}" && cargo tree --edges normal --features signal,tor ) > "${OUT_DIR}/cargo-tree.txt" 2>/dev/null \
  || echo "[sbom] предупреждение: cargo tree не удался (нет сети для git-зависимостей?)."

if command -v cargo-license >/dev/null 2>&1; then
  echo "[sbom] Rust: лицензии крейтов → cargo-licenses.txt"
  ( cd "${CORE_DIR}" && cargo license --features signal,tor ) > "${OUT_DIR}/cargo-licenses.txt" 2>/dev/null || true
else
  echo "[sbom] cargo-license не установлен — пропуск (cargo install cargo-license)."
fi

if command -v cargo-cyclonedx >/dev/null 2>&1; then
  echo "[sbom] Rust: CycloneDX → cargo-sbom.cdx.json"
  ( cd "${CORE_DIR}" && cargo cyclonedx --features signal,tor --format json ) 2>/dev/null || true
  find "${CORE_DIR}" -maxdepth 1 -name '*.cdx.json' -exec mv {} "${OUT_DIR}/cargo-sbom.cdx.json" \; 2>/dev/null || true
else
  echo "[sbom] cargo-cyclonedx не установлен — пропуск (cargo install cargo-cyclonedx)."
fi

echo "[sbom] Gradle: зависимости Android-модулей → gradle-dependencies.txt"
( cd "${ROOT_DIR}" && ./gradlew --no-daemon -q \
    :app:dependencies :core-common:dependencies :core-ui:dependencies \
    :crypto:dependencies :network-tor:dependencies :data-storage:dependencies \
    --configuration releaseRuntimeClasspath ) > "${OUT_DIR}/gradle-dependencies.txt" 2>/dev/null \
  || echo "[sbom] предупреждение: gradle dependencies не удался (нужны сеть/SDK)."

echo "[sbom] ГОТОВО. Файлы в ${OUT_DIR}/"
