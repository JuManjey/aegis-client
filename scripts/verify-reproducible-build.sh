#!/usr/bin/env bash
# =============================================================================
# AEGIS client — проверка ВОСПРОИЗВОДИМОСТИ сборки (P9, §9/§12.1).
#
# Собирает нативное крипто-ядро (libaegis_core.so) ДВАЖДЫ в чистых условиях и
# сравнивает SHA-256. Идентичные хэши ⇒ сборка детерминирована на этой машине.
# Полноценная кросс-машинная проверка — на CI/у стороннего ребилдера; главное
# условие для неё уже выполнено: пин тулчейна (rust-toolchain.toml), Cargo.lock,
# ремап путей (RUSTFLAGS ниже) и фикс версий зависимостей (libs.versions.toml).
#
# Использование:
#   scripts/verify-reproducible-build.sh              # ядро (.so) — быстрый и точный тест
#   AEGIS_VERIFY_APK=1 scripts/verify-reproducible-build.sh   # + unsigned release APK
#
# Требования: rustup + cargo-ndk + Android NDK (см. README.md, раздел «Подготовка окружения»).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_DIR="${ROOT_DIR}/rust/aegis-core"
ABIS="${AEGIS_ABIS:-arm64-v8a}"   # для скорости по умолчанию один ABI

# --- Детерминированное окружение --------------------------------------------
export TZ=UTC
export LC_ALL=C
export CARGO_INCREMENTAL=0
# SOURCE_DATE_EPOCH из времени HEAD-коммита (стабильно между прогонами).
if git -C "${ROOT_DIR}" rev-parse HEAD >/dev/null 2>&1; then
  export SOURCE_DATE_EPOCH="$(git -C "${ROOT_DIR}" show -s --format=%ct HEAD)"
else
  export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-0}"
fi

# --- Ремап абсолютных путей → воспроизводимость между машинами ---------------
# Пока профильный trim-paths не стабилизирован (Cargo 1.97.0), убираем пути к
# исходникам, к $CARGO_HOME и к rustup через --remap-path-prefix.
CARGO_HOME_DIR="${CARGO_HOME:-${HOME}/.cargo}"
RUSTUP_HOME_DIR="${RUSTUP_HOME:-${HOME}/.rustup}"
export RUSTFLAGS="${RUSTFLAGS:-} \
  --remap-path-prefix=${CORE_DIR}=/aegis-core \
  --remap-path-prefix=${ROOT_DIR}=/aegis \
  --remap-path-prefix=${CARGO_HOME_DIR}=/cargo \
  --remap-path-prefix=${RUSTUP_HOME_DIR}=/rustup"

sha256() { command -v sha256sum >/dev/null 2>&1 && sha256sum "$1" | cut -d' ' -f1 || shasum -a 256 "$1" | cut -d' ' -f1; }

build_core() {
  local out="$1"
  ( cd "${CORE_DIR}" && cargo clean )
  # Собираем те же фичи, что и в релизном APK: слой B (signal) + транспорт (tor).
  ( cd "${CORE_DIR}" && cargo ndk $(for a in ${ABIS}; do printf ' -t %s' "$a"; done) \
      -o "${out}" build --release --locked --features signal,tor )
}

echo "[repro] toolchain: $(rustc --version)"
echo "[repro] SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH}  ABIS=${ABIS}"
echo "[repro] RUSTFLAGS=${RUSTFLAGS}"

TMP1="$(mktemp -d)"; TMP2="$(mktemp -d)"
trap 'rm -rf "${TMP1}" "${TMP2}"' EXIT

echo "[repro] === Сборка 1/2 ==="
build_core "${TMP1}"
echo "[repro] === Сборка 2/2 ==="
build_core "${TMP2}"

FAIL=0
for abi in ${ABIS}; do
  so1="${TMP1}/${abi}/libaegis_core.so"
  so2="${TMP2}/${abi}/libaegis_core.so"
  h1="$(sha256 "${so1}")"; h2="$(sha256 "${so2}")"
  if [[ "${h1}" == "${h2}" ]]; then
    echo "[repro] OK   ${abi}: ${h1}"
  else
    echo "[repro] FAIL ${abi}: ${h1} != ${h2}"; FAIL=1
  fi
done

if [[ "${AEGIS_VERIFY_APK:-0}" == "1" ]]; then
  echo "[repro] === APK (unsigned release, двойная сборка) ==="
  echo "[repro] Для сравнения ПОДПИСАННЫХ APK используйте apksigcopier + diffoscope"
  echo "[repro] (подпись зависит от ключа; сравнивают содержимое без блока подписи)."
  ( cd "${ROOT_DIR}" && ./gradlew --no-daemon clean :app:assembleRelease -Paegis.abiFilters="${ABIS// /,}" )
  A1="$(ls "${ROOT_DIR}"/app/build/outputs/apk/release/*.apk | head -1)"; cp "${A1}" "${TMP1}/app1.apk"
  ( cd "${ROOT_DIR}" && ./gradlew --no-daemon clean :app:assembleRelease -Paegis.abiFilters="${ABIS// /,}" )
  A2="$(ls "${ROOT_DIR}"/app/build/outputs/apk/release/*.apk | head -1)"; cp "${A2}" "${TMP2}/app2.apk"
  h1="$(sha256 "${TMP1}/app1.apk")"; h2="$(sha256 "${TMP2}/app2.apk")"
  if [[ "${h1}" == "${h2}" ]]; then echo "[repro] OK   APK: ${h1}"; else echo "[repro] DIFF APK: ${h1} != ${h2} (проверьте diffoscope)"; fi
fi

if [[ "${FAIL}" == "0" ]]; then
  echo "[repro] РЕЗУЛЬТАТ: нативное ядро воспроизводимо ✅"
else
  echo "[repro] РЕЗУЛЬТАТ: обнаружены расхождения ❌" >&2; exit 1
fi
