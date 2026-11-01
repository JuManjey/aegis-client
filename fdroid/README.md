# Публикация AEGIS в F-Droid (§12.1)

Каталог содержит материалы для публикации клиента в F-Droid:

- [`com.aegis.messenger.yml`](com.aegis.messenger.yml) — рецепт сборки
  (fdroidserver metadata): собирает приложение **из исходников**, включая
  нативные Rust/Go/C++ артефакты, без предсобранных бинарей.
- Описания/скриншоты/чейнджлоги — в
  [`../fastlane/metadata/android/`](../fastlane/metadata/android) (структура
  Fastlane Supply; F-Droid забирает `title.txt`, `short_description.txt`,
  `full_description.txt`, `changelogs/<versionCode>.txt`, `images/`).

## Куда это идёт

F-Droid хранит рецепты в отдельном репозитории
[`fdroiddata`](https://gitlab.com/fdroid/fdroiddata) в файле
`metadata/com.aegis.messenger.yml`. Отправьте туда merge request с
[`com.aegis.messenger.yml`](com.aegis.messenger.yml), заменив плейсхолдеры
(`<ORG>`, `commit`). Описания F-Droid подтянет из каталога `fastlane/` этого
репозитория автоматически.

Локальная проверка рецепта (нужен установленный `fdroidserver`):

```bash
# в клоне fdroiddata, куда скопирован рецепт:
fdroid readmeta
fdroid lint com.aegis.messenger
fdroid build -v -l com.aegis.messenger
```

## Известные сложности (честно)

- **WebRTC.** Зависимость `io.github.webrtc-sdk:android` — это предсобранный
  Maven-артефакт. F-Droid требует сборки из исходников; для приёма в основной
  репозиторий WebRTC нужно либо собирать из исходников (тяжело), либо временно
  пометить соответствующий AntiFeature. До решения этого вопроса реалистичный
  путь — **собственный F-Droid-репозиторий** (`fdroid` server) с этим рецептом.
- **Тяжёлая нативная сборка.** `--features signal,tor` тянет libsignal (git) и
  Arti (сотни крейтов) — сборка на F-Droid buildserver долгая и требовательная к
  памяти. При проблемах можно временно исключить `tor` (клиент переключится на
  fallback-транспорт), но это ослабляет продукт — только как крайняя мера.
- **Воспроизводимость.** Инварианты закреплены (пин тулчейна `1.97.0`,
  `--locked`, ремап путей, отключённый `dependenciesInfo`). Для F-Droid
  «Reproducible Builds» подписанный APK сравнивается через
  `apksigcopier` + `diffoscope` — см. `../README.md`, раздел «Воспроизводимые сборки».
- **Собственный ключ подписи.** F-Droid подписывает APK своим ключом; для
  прямого распространения (сайт/onion) используется отдельный ключ проекта и
  публикация контрольных сумм — см. `../README.md`, раздел «Публикация и проверка контрольных сумм», и
  `../scripts/publish-checksums.sh`.
