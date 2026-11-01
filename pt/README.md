# AEGIS — pluggable transports (PT) под ABI · §14.7 / §7.2

Анти-цензурный слой Tor: если провайдер блокирует Tor, клиент маскирует трафик
через **pluggable transports**. Мы **не пишем свои PT** (§1) — поставляем
официальные аудированные реализации Tor Project, кросс-скомпилированные под ABI
Android, и запускаем их как **управляемые (managed) PT** через встроенный Arti
(feature `pt-client`). Без Orbot/системного Tor.

## Что поставляется

| Бинарь (в APK)      | Исходник (Tor Project)                  | Протоколы                         |
|---------------------|-----------------------------------------|-----------------------------------|
| `liblyrebird.so`    | lyrebird (преемник obfs4proxy)          | `obfs4`, `meek_lite` (+obfs2/3, scramblesuit) |
| `libsnowflake.so`   | snowflake `client`                      | `snowflake`                       |

Так покрыты все три PT из §14.7: **obfs4 / snowflake / meek**.

## Почему имена `lib*.so`

Android распаковывает из APK в `nativeLibraryDir` только файлы вида `lib*.so`.
На Android 10+ (target API ≥ 29) `exec` разрешён ТОЛЬКО из `nativeLibraryDir`
(W^X: data-dir/assets — неисполняемые). Поэтому PT-бинари названы
`liblyrebird.so` / `libsnowflake.so`, а в `app/build.gradle.kts` включён
`packaging { jniLibs { useLegacyPackaging = true } }` (⇒ `extractNativeLibs=true`),
чтобы установщик распаковал их на диск. Путь резолвится в рантайме
(`PtBinaries.binaryPath` → `applicationInfo.nativeLibraryDir/lib<name>.so`).

## Сборка

Бинари — крупные артефакты (в `.gitignore`), генерируются скриптом:

```bash
export ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/30.0.15729638"
export PATH="/opt/homebrew/bin:$PATH"      # go, git
pt/build_pt.sh                              # ABI из gradle.properties (arm64-v8a,x86_64)
pt/build_pt.sh arm64-v8a                    # только одну ABI
```

Либо автоматически при сборке APK — задача `:app:buildPluggableTransports`
(зависимость `preBuild`) вызывает этот же скрипт, если найдены `go` и NDK
(best-effort, как `:crypto:cargoNdkBuild`). Отключить: `-Paegis.buildPt=false`.

### Требования
- **Go 1.21+** (`brew install go`). PT написаны на Go.
- **Android NDK** (clang для `minSdk` API, по умолчанию API 26).
- Сеть — только на первую сборку (кэш модулей в `pt/.build/`).

### Как это собирается (воспроизводимо, §9)
- Исходники клонируются на закреплённый ref (`LYREBIRD_REF` — коммит,
  `SNOWFLAKE_REF` — тег) в `pt/.build/src` и собираются `go build` из них
  (не `go install pkg@ver`: go.mod lyrebird содержит `replace`-директивы).
- `CGO_ENABLED=1` + NDK clang → корректный **Android PIE ELF** (bionic,
  интерпретатор `/system/bin/linker64`), совместимый с exec из `nativeLibraryDir`.
- `-trimpath -ldflags="-s -w -buildid="` — воспроизводимость + меньше размер.
- `-checklinkname=0` — обход запрета Go 1.23+ на `//go:linkname` к
  `net.zoneCache` в зависимости `wlynxg/anet` (перечисление сетевых интерфейсов
  на Android). Без этого линковка падает.

Закреплённые версии — вверху `build_pt.sh` (`LYREBIRD_REF` / `SNOWFLAKE_REF`).

## Запуск (managed PT в Arti)

`ArtiTorTransport.buildConfigJson` автоматически:
1. Берёт строки мостов (встроенный пресет `BuiltInBridges` **или** свои),
2. выводит нужные протоколы из первого токена каждой строки,
3. подставляет путь к вложенному бинарю (`PtBinaries`),
4. отдаёт Arti `transports:[{protocols, path}]` + `bridges:[...]`.

Arti (`TransportConfigBuilder`, `run_on_startup(false)`) запускает бинарь лениво
при первом подключении к мосту через этот транспорт.

## Границы (честно)
- Встроенные строки мостов (`BuiltInBridges`) — зеркало built-in мостов Tor
  Browser; адреса/сертификаты **ротируются**. Если пресет не подключается —
  вставьте свежие строки с bridges.torproject.org («Свои мосты»).
- Реальная проверка обхода блокировки требует цензурируемой сети и устройства
  (в этом окружении не выполнялась). Бинари проверены как корректные Android-ELF;
  контракт конфигурации PT — тестом ядра `snowflake_and_managed_obfs4_config_builds`.
- Размер: PT-бинари добавляют ~70 МБ (обе ABI, до сжатия). Для релиза используйте
  ABI splits / App Bundle — пользователь получит PT только своей архитектуры.
