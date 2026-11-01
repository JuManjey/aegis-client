# Вклад в AEGIS (CONTRIBUTING)

Спасибо за интерес к AEGIS — защищённому мессенджеру с моделью «нулевого
доверия». Это репозиторий Android-клиента; сервер — в `aegis-server`. Оба
репозитория развиваются согласованно (§0 технического задания).

## Прежде всего — безопасность

- Уязвимости — **не** через публичные issue/PR. См. [`SECURITY.md`](SECURITY.md).
- **Свою криптографию не пишем** (§1): только аудированные библиотеки
  (libsignal, RustCrypto, Arti, официальные PT). PR с самописными
  крипто-примитивами не принимаются.
- **Безопасно по умолчанию** (§8, пресет «Параноик»); любое ослабление — только
  явным осознанным действием пользователя с предупреждением в UI.

## Требования к изменениям

- Читайте документацию: `README.md`, `docs/` и `aegis-server/PROTOCOL.md`.
- Учитывайте **обе стороны**: изменение протокола/формата на клиенте требует
  согласованной реализации на сервере, и наоборот.
- Обновляйте `CHANGELOG.md` и затронутые документы в `docs/` вместе с кодом.
- Не коммитьте секреты (ключи подписи, `.env`, `keystore.properties`).
- Фиксируйте версии зависимостей (`gradle/libs.versions.toml`, `Cargo.lock`) —
  требование воспроизводимых сборок (§9).

## Проверки перед PR

```bash
# Крипто-ядро:
cargo test --manifest-path rust/aegis-core/Cargo.toml --features signal,tor   # 55/55
cargo fmt --manifest-path rust/aegis-core/Cargo.toml --check
cargo clippy --manifest-path rust/aegis-core/Cargo.toml --features signal,tor

# Android:
./gradlew :app:assembleDebug
./gradlew :app:lint

# Воспроизводимость (для изменений в ядре/сборке):
scripts/verify-reproducible-build.sh
```

## Стиль

- Kotlin: `kotlin.code.style=official`; Compose Material 3, цветовые токены (§13,
  без хардкода HEX по экранам).
- Rust: `cargo fmt`; ошибки — явные, крипто-провалы «fail-closed» (при провале
  AEAD-тега расшифровка прекращается).
- Комментарии объясняют «почему» (замысел/ограничения), а не «что».

## Лицензия

Отправляя вклад, вы соглашаетесь лицензировать его на условиях проекта —
**GPL-3.0-or-later** (см. [`LICENSE`](LICENSE)).
