# Phase 1.1 — Verification Report

Проверено 19 октября 2026 (Asia/Yekaterinburg), Linux amd64, Temurin Java 25+36,
Gradle 9.7.0, Paper 26.2 build 134. Version: 0.1.1-SNAPSHOT.

## Полный verification suite

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build :reputation-paper:runtimeProbeJar --no-build-cache
```

**PASS: 67 tests, 0 failures, 0 errors, 0 skipped.** Команда проверена без обновления locks
и verification metadata. До этого при добавлении sqlite dependency были явно обновлены
locks/checksums через `--write-locks --write-verification-metadata sha256`. TLS/checksum
verification не отключались.

| Suite | Cases | Проверки |
|---|---:|---|
| ConfigurationAndMessagesTest | 11 | JSON defaults, legacy MYSQL/no rewrite, явный selector, пути/границы/версии, старые locale-файлы, RU/EN/placeholders/MiniMessage/fallback |
| ReputationApplicationTest | 4 | Async port, bounded queue/backpressure, исключения, drain/cleanup, язык |
| PaperAdaptersTest | 4 | Прежние права/команды, язык, offline cached player, PAPI cache-only |
| LocalStorageTest | 17 | JSON/SQLite restart, 40 concurrent creates, scopes, все snapshot fields, pending/owner guards, corrupt files, JSON fault points/duplicates/size/external corruption, SQLite rollback/busy timeout |
| StorageMigrationTest | 7 | JSON ↔ SQLite, backup/receipt/source retention, interruption without activation, nonempty target/live owner, explicit JSON recovery/quarantine, RU/EN CLI confirmation |
| MariaDbIntegrationTest | 12 | Прежний SQL контракт + все 6 направлений миграции, corrupted records, failed import rollback, pending guard, second owner, потеря ownership |
| MySqlIntegrationTest | 12 | Тот же контракт на MySQL 8.4.7 |

43 local/unit/adapter cases, 24 real database integration cases. SQL platforms:
MariaDB 11.4.8, MySQL 8.4.7; приложение в обоих случаях использует MariaDB Connector/J 3.5.6.
SQLite JDBC 3.51.1.0. Матрица внутри каждого SQL suite выполняет все 6 направлений переноса
с полным сравнением профилей, нескольких scopes, ненулевых score/revision, UTC timestamps
и RU/EN preferences. Score fixtures не вводят gameplay API голосования.

Ошибки записи JSON инжектируются до write, до rename и после rename. В последнем случае
допускается новая полная версия при неподтверждённом caller результате; открытый backend
переходит в fail-closed. SQL batch failure вызывает ROLLBACK, после неудачного переноса
назначение остаётся pending. Это моделирование сбоев, не физическое отключение питания.

## Реальный Paper 26.2 / Java 25

Базовая команда для каждого backend:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
python scripts/paper-smoke.py --accept-eula --storage-type JSON \
  --directory /workspace/.setup/phase11-smoke-JSON \
  --paper-jar /workspace/.setup/runtime/paper.jar \
  --mojang-cache /workspace/.setup/runtime/cache/mojang_26.2.jar \
  --placeholderapi-jar /workspace/.setup/PlaceholderAPI-2.12.3-release.jar
```

Аналогично SQLITE/MYSQL; дополнительный MYSQL запуск с `--legacy-mysql` проверяет плоский
v1 config. После последней корректировки MySQL ownership финальный JAR также повторно
проверен на JSON/SQLite без PAPI (2 restart runs каждого).

| Target | Runs | Result |
|---|---:|---|
| JSON, без DB password/container | 3 + 2 final | PASS |
| SQLITE, без DB password/container | 3 + 2 final | PASS |
| MYSQL, новый v2 config, MariaDB | 3 | PASS |
| MYSQL, legacy v1 без storage.type | 2 | PASS |

**15/15 runtime runs PASS.** Во всех проверены явные PROBE_PASS: регистрация API, zero/NEUTRAL,
идемпотентность, createdAt между restart, прежние команды, unknown/console behavior,
новая storage diagnostic и unregister service при shutdown. В третьем прогоне каждого backend
PROBE_PAPI_PASS проверяет все три placeholders и cleanup expansion.

Final artifact SHA совпадает с JAR, использованным в final JSON/SQLite и MYSQL/legacy runs.
Paper и повторно использованный Mojang cache проверены по официальным SHA-256.
EULA для локальных тестов явно разрешена пользователем в этой сессии.

Логи текущей машины:

- `/workspace/.setup/phase11-smoke-{JSON,SQLITE,MYSQL}/smoke-{1,2,3}.log`
- `/workspace/.setup/phase11-smoke-legacy/smoke-{1,2}.log`
- `/workspace/.setup/phase11-final-{JSON,SQLITE}/smoke-{1,2}.log`
- `/workspace/.setup/phase11-locked-final.log` — clean complete suite.

Сценарии останавливают серверы и удаляют только собственные MYSQL-контейнеры.

## Отдельный standalone CLI

Проверен **настоящий** `java -jar DexturReputation-StorageAdmin.jar`, без Paper classpath:
JSON → SQLITE → новый JSON-каталог, русский и английский success-report.
Обе операции PASS, snapshots/checksum равны, backups и исходные файлы сохранены,
активный storage.yml переключён только после проверки. Папка проверки:
`/workspace/.setup/phase11-cli`. CLI без confirm отдельно возвращает exit 2 и usage.
В JUnit fixture также проверяются язык/все timestamp-поля; в отдельном CLI smoke один профиль
без сохранённой player preference (нет реального подключённого игрока).

## Reproducible artifacts

Обычная сборка checkout и повторная сборка через сохранённый cloud setup с внешними build
directories дали одинаковые байты:

```text
DexturReputation.jar
79c0925183c60957c411f695002683602f2cc5213231a9b05f238dfaeaa2a010

DexturReputation-StorageAdmin.jar
890156ffdfe76e4ed173a262bd5d9950d7ba976bf377a8c53a3e2ddf88b3fb81
```

Это проверка на данной toolchain/платформе, не обещание идентичного bytecode между всеми
будущими Java patch releases. Generated artifacts/caches не включаются в Git.

## Ограничения

- Реальный Minecraft client/player UI, production TLS базы, load/stress/power-loss и сетевые
  файловые системы не тестировались. JSON/POSIX гарантия ограничена поведением filesystem/device.
- Online Mojang authentication не проверялась: smoke loopback/offline-mode. В фоновых логах
  могут быть сетевые ошибки Mojang/PAPI внешних служб; app API/storage/placeholders проверены отдельно.
- Для безопасного offline migration должны быть остановлены **все** внешние/старые writers;
  confirm не заменяет это требование. Новый протокол ownership защищает соблюдающие его owners.
- GitHub Actions workflow обновлён, удалённый CI запуск/production deployment не объявлены PASS
  на основании локальной сборки. Публикация cloud snapshot — отдельное действие пользователя.
- API и Phase 1 команды сохранены. Phase 2 voting и другие gameplay features не реализованы.
