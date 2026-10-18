# DexturReputation — Phase 2

Основа репутации Dextur: **Paper 26.2**, **Java 25**, три взаимозаменяемых хранилища.
Новые установки используют **JSON без БД и пароля**. SQLite и MySQL/MariaDB выбираются
единственным параметром `storage.type`. Конфиги Phase 1 без этого параметра по-прежнему
используют MySQL; обновление не переключает и не переносит данные автоматически.

Реализованы положительные/отрицательные голоса, обязательные причины, комментарии,
лимиты, замена/отзыв, истории, статистика и GUI. Новые профили имеют нулевые оценки,
tier `NEUTRAL`; прежний API и команды сохранены. Trust и Phase 3 не реализованы.

## Сборка и проверка

```sh
./gradlew clean build :reputation-paper:runtimeProbeJar --no-build-cache
```

Нужны JDK 25 и Docker для обязательных тестов MySQL/MariaDB. `./gradlew test` выполняет
локальные suites, включая JSON/SQLite; `./gradlew :reputation-storage:integrationTest` — SQL
контракты. Недоступный Docker приводит к FAIL, не к незаметному пропуску.
Gradle Wrapper 9.7.0, версии/locks зависимостей и SHA-256 фиксированы. Проверка TLS не отключается.

Результаты сборки:

- `reputation-paper/build/libs/DexturReputation.jar` — устанавливаемый Paper-плагин.
- `reputation-paper/build/libs/DexturReputation-StorageAdmin.jar` — **отдельный offline CLI**
  для переноса/восстановления. Не помещайте этот JAR в `plugins/`.
- `reputation-api/build/libs/reputation-api-0.2.0-SNAPSHOT.jar` — API для `compileOnly`;
  не упаковывайте повторную копию API в плагины-потребители.

## Установка и обновление

1. Установите основной JAR в `plugins/` Paper 26.2 и запустите сервер на Java 25.
2. На новой установке автоматически создаются конфиги и JSON в
   `plugins/DexturReputation/storage/json/`. База данных для этого не требуется.
3. Дождитесь `READY: ReputationService registered`. В случае ошибки выбранного хранилища
   плагин отключается; подмены другим backend нет.
4. При обновлении Phase 1 оставьте прежний `storage.yml`: его формат v1 сохраняет MYSQL.
   Новый параметр и новые сообщения не требуют перезаписи пользовательских файлов.
5. Для переноса существующих данных остановите сервер и выполните процедуру
   [STORAGE.md](docs/STORAGE.md). Простая правка типа не переносит данные.

Профиль идентифицируется UUID + scope (`global` по умолчанию). Язык игрока хранится отдельно
по UUID в выбранном backend. Все storage IO идут через управляемые workers, очередь и кэши
ограничены. При остановке принятые операции дренируются до закрытия backend.

## Команды и интеграции

`/dexturreputation [help|version|status|storage]`, `/rep [игрок]`,
`/rep language ru_RU|en_US`, `/like|dislike <игрок> <причина> [комментарий]`,
`/rep history|given [страница]`, `/rep reasons`, `/rep gui [игрок]`,
`/rep revoke <игрок> [комментарий]`. Русский язык по умолчанию. Ник используется только для
кэшированного поиска известного игрока; unknown не создаёт выдуманные UUID и не обращается
к Mojang. API работает непосредственно с UUID. RU/EN, fallback и защита MiniMessage сохранены.

Необязательная PlaceholderAPI **2.12.3**: `%dexturreputation_score%`,
`%dexturreputation_score_formatted%`, `%dexturreputation_tier%`. Запрос читает только кэш;
cache miss возвращает пустую строку, не делает IO и не подменяется выдуманным нулём.
Для сервера используйте полный [официальный релиз](https://github.com/PlaceholderAPI/PlaceholderAPI/releases/tag/2.12.3),
а не Maven compile-only JAR.

## Настоящий Paper smoke

```sh
./gradlew build :reputation-paper:runtimeProbeJar
python3 scripts/paper-smoke.py --accept-eula --storage-type JSON --directory /tmp/dextur-json-new
python3 scripts/paper-smoke.py --accept-eula --storage-type SQLITE --directory /tmp/dextur-sqlite-new
python3 scripts/paper-smoke.py --accept-eula --storage-type MYSQL --directory /tmp/dextur-mysql-new
```

`--accept-eula` означает явное согласие с [Minecraft EULA](https://aka.ms/MinecraftEULA).
Директория должна быть новой/пустой и вне checkout. Сценарий проверяет checksum Paper 26.2
build 134, два запуска с сохранёнными данными и cleanup API. JSON/SQLite запускаются без
DB credentials/контейнера; MYSQL поднимает и удаляет только собственную тестовую MariaDB.
`--legacy-mysql` с `--storage-type MYSQL` проверяет конфиг v1.
`--placeholderapi-jar /path/to/full-release.jar` добавляет третий прогон с PAPI.
`--mojang-cache /path/to/mojang_26.2.jar` повторно использует проверенный checksum-кэш.
Сервер слушает только loopback на 25566, offline-mode; это не production deployment.
`--voting` добавляет двух локальных протокольных клиентов, ослабляет только QA eligibility/
cooldown/quota и проверяет настоящие Player commands, GUI events, дубликаты, замены/отзывы,
историю, права, restart и обновление PAPI cache. Без флага сохранён прежний workflow.
QA-плагин не входит в основной JAR. Логи/миры остаются вне checkout.

Правила, расчёт, все настройки `voting.yml`/`gui.yml`, сбои и ограничения: [VOTING.md](docs/VOTING.md).

Документация: [хранилища/миграции/backup](docs/STORAGE.md), [конфигурация](docs/CONFIGURATION.md),
[архитектура](docs/ARCHITECTURE.md), [API](docs/API_DOCUMENTATION.md), [команды](docs/COMMANDS.md),
[права](docs/PERMISSIONS.md), [совместимость](docs/COMPATIBILITY.md),
[текущий отчёт](docs/PHASE_2_TEST_REPORT.md), [история](CHANGELOG.md).

**Работа ограничена Phase 2. Phase 3 требует отдельного явного разрешения.**
