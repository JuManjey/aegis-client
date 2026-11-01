#!/usr/bin/env bash
# =============================================================================
# AEGIS — воспроизводимая кросс-компиляция pluggable transports (PT) под ABI
# (§14.7 / §7.2). Мы НЕ пишем свои PT (§1) — берём официальные аудированные
# реализации Tor Project (Go):
#   - lyrebird  — преемник obfs4proxy: транспорты obfs4 + meek_lite (домен-
#                 фронтинг), а также obfs2/obfs3/scramblesuit. Один бинарь.
#   - snowflake — snowflake-client (domain-fronted WebRTC rendezvous). Бинарь.
#
# Результат — исполняемые Android-бинари (ELF PIE, bionic-linked через NDK
# clang), названные `lib<name>.so`, чтобы Android распаковывал их в
# `nativeLibraryDir` (единственное место, откуда на API 29+ разрешён exec).
# Arti (feature `pt-client`) запускает их как управляемые (managed) PT.
#
# Использование:
#   ANDROID_NDK_HOME=... pt/build_pt.sh [abi ...]
#   abi ∈ {arm64-v8a, x86_64, armeabi-v7a, x86}. По умолчанию: значение
#   свойства aegis.abiFilters из gradle.properties (arm64-v8a,x86_64).
#
# Требования: go (1.21+), Android NDK (clang для minSdk API). Сеть — только на
# первую сборку (go тянет модули PT в кэш $PT_BUILD_DIR/gopath).
# =============================================================================
set -euo pipefail

# --- Воспроизводимость Go-сборки (P9, §9/§12.1) ------------------------------
# Пинуем ТОЧНУЮ версию Go: go.mod PT содержит директиву `go 1.xx`, и без пина
# Go 1.21+ может автоматически подтянуть другой toolchain (GOTOOLCHAIN=auto),
# что нарушит воспроизводимость. Тестировано на go1.26.5. Переопределяемо:
#   AEGIS_GO_TOOLCHAIN=go1.26.5 pt/build_pt.sh
# Значение "local" запрещает авто-загрузку и использует установленный go.
export GOTOOLCHAIN="${AEGIS_GO_TOOLCHAIN:-local}"
# -trimpath уже передаётся в go build; дублируем через GOFLAGS для под-вызовов.
export GOFLAGS="${GOFLAGS:-} -trimpath"
# Детерминированные метки времени в архивах/эмбедах (если PT их использует).
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-0}"

# --- Версии PT (закреплены → воспроизводимая сборка, §9) ---------------------
# Собираем ИЗ ИСХОДНИКОВ (git clone + go build), а не `go install pkg@ver`:
# go.mod lyrebird содержит replace-директивы, которые `go install` из внешнего
# модуля запрещает. Клонирование по иммутабельному ref (коммит/тег) —
# воспроизводимо и уважает go.mod/go.sum как главного модуля.
LYREBIRD_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git"
# lyrebird не публикует semver-теги в go-proxy → закрепляем на коммите.
LYREBIRD_REF="${LYREBIRD_REF:-fc105a03c0e0}"
LYREBIRD_CMD="./cmd/lyrebird"

SNOWFLAKE_REPO="https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake.git"
SNOWFLAKE_REF="${SNOWFLAKE_REF:-v2.14.1}"
SNOWFLAKE_CMD="./client"

# --- Пути --------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_DIR="${ROOT_DIR}/app/src/main/jniLibs"
PT_BUILD_DIR="${PT_BUILD_DIR:-${SCRIPT_DIR}/.build}"
export GOPATH="${PT_BUILD_DIR}/gopath"
export GOCACHE="${PT_BUILD_DIR}/gocache"
export GOMODCACHE="${GOPATH}/pkg/mod"
SRC_DIR="${PT_BUILD_DIR}/src"
mkdir -p "${PT_BUILD_DIR}" "${SRC_DIR}"

# clone_at <repo-url> <ref> <dest-dir> — идемпотентно клонирует репозиторий и
# фиксирует рабочую копию на закреплённом ref (коммит/тег) → воспроизводимость.
clone_at() {
  local repo="$1" ref="$2" dest="$3"
  if [[ ! -d "${dest}/.git" ]]; then
    echo "[pt] git clone ${repo} → ${dest}"
    git clone --quiet "${repo}" "${dest}"
  fi
  git -C "${dest}" fetch --quiet --tags origin || true
  git -C "${dest}" checkout --quiet "${ref}"
  echo "[pt] ${dest##*/} @ $(git -C "${dest}" rev-parse --short HEAD)"
}

# minSdk (API level для NDK clang). Держим в синхроне с gradle libs (minSdk=26).
API="${AEGIS_PT_API:-26}"

# --- ABI по умолчанию из gradle.properties -----------------------------------
default_abis() {
  local prop
  prop="$(grep -E '^aegis\.abiFilters=' "${ROOT_DIR}/gradle.properties" 2>/dev/null | head -1 | cut -d= -f2)"
  echo "${prop:-arm64-v8a,x86_64}" | tr ',' ' '
}

if [[ $# -gt 0 ]]; then
  ABIS=("$@")
else
  # shellcheck disable=SC2206
  ABIS=($(default_abis))
fi

# --- Резолвинг NDK -----------------------------------------------------------
resolve_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    echo "${ANDROID_NDK_HOME}"; return 0
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
    echo "${ANDROID_NDK_ROOT}"; return 0
  fi
  local sdk="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
  # Берём самую свежую установленную версию NDK.
  local cand
  cand="$(ls -d "${sdk}"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [[ -n "${cand}" ]] && echo "${cand}"
}

NDK_DIR="$(resolve_ndk)"
if [[ -z "${NDK_DIR}" || ! -d "${NDK_DIR}" ]]; then
  echo "[pt] ОШИБКА: не найден Android NDK (ANDROID_NDK_HOME/ANDROID_NDK_ROOT/\$ANDROID_HOME/ndk/*)." >&2
  exit 1
fi

# Хост-платформа тулчейна NDK.
HOST_TAG="darwin-x86_64"
case "$(uname -s)" in
  Linux)  HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
esac
TOOLBIN="${NDK_DIR}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
if [[ ! -d "${TOOLBIN}" ]]; then
  echo "[pt] ОШИБКА: не найден toolchain NDK: ${TOOLBIN}" >&2
  exit 1
fi

# --- Маппинг ABI → GOARCH / GOARM / NDK clang triple -------------------------
goarch_for() {
  case "$1" in
    arm64-v8a)   echo "arm64" ;;
    x86_64)      echo "amd64" ;;
    armeabi-v7a) echo "arm" ;;
    x86)         echo "386" ;;
    *) echo ""; ;;
  esac
}
clang_for() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android${API}-clang" ;;
    x86_64)      echo "x86_64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi${API}-clang" ;;
    x86)         echo "i686-linux-android${API}-clang" ;;
    *) echo ""; ;;
  esac
}

# --- Сборка одного PT под один ABI -------------------------------------------
# build_one <abi> <srcdir> <cmd-relpath> <out-lib-name>
build_one() {
  local abi="$1" srcdir="$2" cmd="$3" outname="$4"
  local goarch clang cc
  goarch="$(goarch_for "${abi}")"
  clang="$(clang_for "${abi}")"
  if [[ -z "${goarch}" ]]; then
    echo "[pt] пропуск неизвестного ABI: ${abi}" >&2; return 0
  fi
  cc="${TOOLBIN}/${clang}"
  if [[ ! -x "${cc}" ]]; then
    echo "[pt] ОШИБКА: не найден NDK clang: ${cc}" >&2; return 1
  fi

  local dest_dir="${OUT_DIR}/${abi}"
  mkdir -p "${dest_dir}"
  local out="${dest_dir}/${outname}"

  echo "[pt] сборка ${outname} для ${abi} (GOARCH=${goarch}) из ${srcdir##*/}/${cmd#./}"
  # CGO_ENABLED=1 + NDK CC → бинарь линкуется с bionic (Android libc), корректный
  # PIE-исполняемый, совместимый с exec из nativeLibraryDir. -trimpath и
  # -buildid= убирают локальные пути и build-id → воспроизводимость + меньше размер.
  local goarm_env=()
  [[ "${abi}" == "armeabi-v7a" ]] && goarm_env=(GOARM=7)
  # ${arr[@]+"${arr[@]}"} — безопасное раскрытие пустого массива под set -u (bash 3.2).
  # -checklinkname=0: зависимость wlynxg/anet (перечисление сетевых интерфейсов
  # на Android) обращается к внутреннему символу net.zoneCache через
  # //go:linkname; Go 1.23+ по умолчанию это запрещает (-checklinkname=1) →
  # «invalid reference to net.zoneCache». Отключаем проверку для линковки PT.
  ( cd "${srcdir}" && env \
      CGO_ENABLED=1 \
      GOOS=android \
      GOARCH="${goarch}" \
      ${goarm_env[@]+"${goarm_env[@]}"} \
      CC="${cc}" \
      go build -trimpath -ldflags="-s -w -buildid= -checklinkname=0" -o "${out}" "${cmd}" )

  if [[ ! -f "${out}" ]]; then
    echo "[pt] ОШИБКА: бинарь не собрался: ${out}" >&2; return 1
  fi
  chmod 0755 "${out}"
  echo "[pt]   → ${out} ($(du -h "${out}" | cut -f1))"
}

echo "[pt] NDK=${NDK_DIR}"
echo "[pt] ABIs=${ABIS[*]}  API=${API}"
echo "[pt] lyrebird=${LYREBIRD_REF}  snowflake=${SNOWFLAKE_REF}"

# Клонируем исходники PT на закреплённые ref (один раз, кэшируется в .build/src).
LYREBIRD_SRC="${SRC_DIR}/lyrebird"
SNOWFLAKE_SRC="${SRC_DIR}/snowflake"
clone_at "${LYREBIRD_REPO}" "${LYREBIRD_REF}" "${LYREBIRD_SRC}"
clone_at "${SNOWFLAKE_REPO}" "${SNOWFLAKE_REF}" "${SNOWFLAKE_SRC}"

for abi in "${ABIS[@]}"; do
  build_one "${abi}" "${LYREBIRD_SRC}" "${LYREBIRD_CMD}" "liblyrebird.so"
  build_one "${abi}" "${SNOWFLAKE_SRC}" "${SNOWFLAKE_CMD}" "libsnowflake.so"
done

echo "[pt] ГОТОВО. PT-бинари разложены в ${OUT_DIR}/<abi>/."
