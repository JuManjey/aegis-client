#!/usr/bin/env bash
# =============================================================================
# AEGIS — генерация контрольных сумм и (опционально) подписи для релиза (P9,
# §12.1 «Верификация: как пользователю проверить подпись/контрольную сумму»).
#
# Делает три вещи для набора артефактов (APK/AAB/…):
#   1) SHA256SUMS  — суммы всех артефактов в одном файле (формат `sha256sum -c`);
#   2) *.sha256    — отдельная сумма на каждый артефакт (удобно для скачивания);
#   3) SHA256SUMS.asc / SHA256SUMS.minisig — подпись файла сумм ключом проекта,
#      ЕСЛИ доступен GPG-ключ (env AEGIS_GPG_KEY / gpg --default-key) либо
#      minisign-ключ (env AEGIS_MINISIGN_SECRET). Секреты подписи НИКОГДА не
#      коммитятся (§12.1) — передаются через окружение CI/локально.
#
# Использование:
#   scripts/publish-checksums.sh <файл-или-каталог> [ещё файлы...]
#   scripts/publish-checksums.sh app/build/outputs/apk/release
#   OUT_DIR=dist scripts/publish-checksums.sh app-release.apk
#
# Переменные окружения:
#   OUT_DIR                — куда положить SHA256SUMS (по умолч. рядом; при
#                            нескольких входах — текущий каталог).
#   AEGIS_GPG_KEY          — id/отпечаток GPG-ключа для подписи (иначе default-key).
#   AEGIS_MINISIGN_SECRET  — путь к minisign secret key (альтернатива GPG).
# =============================================================================
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: $0 <file-or-dir> [more files...]" >&2
  exit 2
fi

sha256() { command -v sha256sum >/dev/null 2>&1 && sha256sum "$@" || shasum -a 256 "$@"; }

# --- Собираем список артефактов (файлы + содержимое каталогов) ---------------
FILES=()
for arg in "$@"; do
  if [[ -d "$arg" ]]; then
    while IFS= read -r -d '' f; do FILES+=("$f"); done \
      < <(find "$arg" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' \) -print0)
  elif [[ -f "$arg" ]]; then
    FILES+=("$arg")
  else
    echo "[checksums] пропуск: не файл и не каталог: $arg" >&2
  fi
done

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "[checksums] нет артефактов (.apk/.aab) для обработки" >&2
  exit 1
fi

# Каталог результата: OUT_DIR, иначе каталог первого артефакта.
OUT_DIR="${OUT_DIR:-$(dirname "${FILES[0]}")}"
mkdir -p "${OUT_DIR}"
SUMS="${OUT_DIR}/SHA256SUMS"
: > "${SUMS}"

echo "[checksums] артефактов: ${#FILES[@]} → ${SUMS}"
for f in "${FILES[@]}"; do
  base="$(basename "$f")"
  line="$(cd "$(dirname "$f")" && sha256 "${base}")"
  echo "${line}" >> "${SUMS}"
  # Отдельная сумма рядом с артефактом.
  ( cd "$(dirname "$f")" && sha256 "${base}" > "${base}.sha256" )
  echo "[checksums]   ${line}"
done
# Стабильный порядок строк (детерминизм между прогонами).
sort -k2 "${SUMS}" -o "${SUMS}"

# --- Подпись файла сумм (best-effort) ----------------------------------------
signed=0
if command -v gpg >/dev/null 2>&1 && { [[ -n "${AEGIS_GPG_KEY:-}" ]] || gpg --list-secret-keys >/dev/null 2>&1; }; then
  key_args=()
  [[ -n "${AEGIS_GPG_KEY:-}" ]] && key_args=(--local-user "${AEGIS_GPG_KEY}")
  if gpg --batch --yes "${key_args[@]}" --armor --detach-sign --output "${SUMS}.asc" "${SUMS}" 2>/dev/null; then
    echo "[checksums] GPG-подпись: ${SUMS}.asc"
    signed=1
  fi
fi

if [[ "${signed}" -eq 0 && -n "${AEGIS_MINISIGN_SECRET:-}" ]] && command -v minisign >/dev/null 2>&1; then
  if minisign -S -s "${AEGIS_MINISIGN_SECRET}" -m "${SUMS}" -x "${SUMS}.minisig"; then
    echo "[checksums] minisign-подпись: ${SUMS}.minisig"
    signed=1
  fi
fi

if [[ "${signed}" -eq 0 ]]; then
  echo "[checksums] подпись НЕ создана (нет GPG/minisign-ключа). Файл сумм готов,"
  echo "[checksums] подпишите вручную: gpg --armor --detach-sign ${SUMS}"
fi

cat <<EOF

[checksums] Готово. Проверка на стороне пользователя (§12.1):
  sha256sum -c SHA256SUMS
  gpg --verify SHA256SUMS.asc SHA256SUMS        # если есть подпись
  apksigner verify --print-certs <artifact>.apk # отпечаток ключа подписи APK
EOF
