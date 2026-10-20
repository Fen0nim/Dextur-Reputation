# DexturReputation — Phase 6

Основа репутации Dextur: **Paper 26.2**, **Java 25**, три взаимозаменяемых хранилища.
Новые установки используют **JSON без БД и пароля**. SQLite и MySQL/MariaDB выбираются
единственным параметром `storage.type`. Конфиги Phase 1 без этого параметра по-прежнему
используют MySQL; обновление не переключает и не переносит данные автоматически.

Реализованы положительные/отрицательные голоса, обязательные причины, комментарии,
лимиты, замена/отзыв, истории, статистика и GUI. Новые профили имеют нулевые оценки,
tier `NEUTRAL`; прежний API и команды сохранены. Добавлены скрытый Trust, точные дробные веса,
защита от повторов/взаимных групп и всплесков, pending-review и атомарный аудит решений.
Trust не показывается игрокам и не зависит от знака публичной репутации. Добавлены настраиваемые уровни, гистерезис, оформление и необязательная LuckPerms. Добавлена ограниченная многоэтапная реабилитация с проверяемыми целями и отдельным системным журналом наград.

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
- `reputation-api/build/libs/reputation-api-0.6.0-SNAPSHOT.jar` — API для `compileOnly`;
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
[текущий отчёт](docs/PHASE_6_TEST_REPORT.md), [история](CHANGELOG.md).

**Работа ограничена Phase 6. Phase 7 требует отдельного явного разрешения.**

## Phase 3

При первом запуске добавляются `trust.yml` и `anti-abuse.yml`. Существующие настройки не
переписываются. Перед обновлением остановите сервер и сохраните полный backup; обратный
переход требует восстановления backup. Старые голоса сохраняют свои исходные вклады.
Новые веса накапливаются с шестью знаками внутри; игрок видит округлённую целую сумму.
Pending-голос не вносит новый вклад, pending-замена сохраняет прежний активный вклад.

- [Trust: модель, приватность и внутреннее администрирование](docs/TRUST.md)
- [Веса, anti-abuse, pending и ограничения](docs/ANTI_ABUSE.md)
- [Полный отчёт Phase 3](docs/PHASE_3_TEST_REPORT.md)

Для обслуживания pending используется закрытый асинхронный `ProtectionAdministration`
в application-модуле. Публичной команды модерации пока нет: она относится к будущей фазе.
Перед включением pending на действующем сервере обеспечьте доверенный административный
адаптер либо осознанно настройте `allow-pending: false` (переполнение бюджета отклоняет запрос).
Автоматических постоянных наказаний нет.

`--protection` запускается отдельно от `--voting`: три native QA-клиента, веса .35, threshold 2
только в тестовом каталоге, CAS Trust, idempotent review/status events, pending через restart,
GUI и PAPI privacy. Production defaults сценарий не изменяет.

`--phase2-json-fixture /path/to/old/state.json` с `--protection` проверяет обновление настоящего
снимка Phase 2 (format 2, calculationVersion 1). Для SQL он сначала запускает standalone
StorageAdmin с backup/verification, затем проверяет старую историю и исходный score на Paper.
Файл-источник не изменяется; используйте копию остановленного QA-сервера, не живые данные.

### Phase 4 — tiers and display

Phase 4 adds configurable reputation tiers with durable hysteresis, immutable asynchronous `TierService`, centralized RU/EN formatting, cache-only tier/PAPI values, configurable notifications and profile GUI tier/progress lore. Optional nameplates use exclusively owned free scoreboard teams; TAB defaults to PLACEHOLDER_ONLY. Optional LuckPerms manages cosmetic reputation memberships with explicit context ownership and durable reconciliation. No DexturChat interception, Discord synchronization, gameplay benefits or Phase 5 features are included.

Read [TIERS](docs/TIERS.md), [DISPLAY](docs/DISPLAY.md), [LUCKPERMS](docs/LUCKPERMS.md), [storage](docs/STORAGE.md), [API](docs/API_DOCUMENTATION.md) and the [Phase 4 verification report](docs/PHASE_4_TEST_REPORT.md). Configuration changes merge defaults in memory and never overwrite administrator files. JSON format 4 / SQL schema 4 preserve all Phase 1–3 data; migrations retain complete tier history and permission intent. The offline StorageAdmin JAR remains an offline utility, not a Paper plugin.

**STOP after Phase 6. Phase 7 requires explicit authorization.**


### Phase 5 — восстановление репутации

`recovery.yml`: committed score ≤ -100, 30 часов игры, 7 дней с первого входа; ручное вступление по умолчанию. Три настраиваемых этапа: активная игра, разные login days, ожидание. Default reward 5/10/15 system points, cap 30/program и 150/lifetime UUID/scope. Старые голоса не стираются. Trust-кредит ограничен, скрыт и не обходит подтверждённые ограничения.

`/rep recovery [status|start|tasks|progress|rewards|cancel|help]`, собственный GUI и шесть cache-only PAPI values. Ограниченные offline admin pause/resume/reset_stage/cancel/lock/unlock доступны операторам. Frozen definitions, persistent operation IDs, session watermarks, отдельный audit и единая транзакция reward/profile/Trust/tier защищают restart/повторы/переносы. JSON format 5 / SQL V5 сохраняют прежние данные; StorageAdmin переносит полный ledger с backup/verification.

Читайте [RECOVERY](docs/RECOVERY.md), [Recovery API](docs/RECOVERY_API.md) и [проверки Phase 5](docs/PHASE_5_TEST_REPORT.md), включая ограничения AFK, системных часов, роста истории и локальных событий. Нет распознавания всех ботов или реализации торгов/контрактов/economy. После вступления и resume первый интервал, пересекающий границу, устанавливает baseline; прежняя/приостановленная активность не переигрывается.

Runtime: `--recovery` отдельно от `--tiers/--protection/--voting`; три native клиента, один передаёт настоящие movement packets. QA меняет только отдельные тестовые конфиги. `--phase4-json-fixture /path/to/stopped/phase4/state.json` проверяет полный прежний снимок (рядом должен сохраниться исходный tiers.yml/integrations.yml). `--recovery-disabled` проверяет отключённую функцию. MYSQL engine выбирается `--database-engine MARIADB|MYSQL`. Используйте свежий каталог, pinned Paper/Mojang jars и полный PAPI JAR, как в остальных smoke-сценариях.

### Phase 6 — политики и основное администрирование

Добавлены асинхронный ReputationPolicyService и features.yml: публичные условия по score/tier/recovery/context, детерминированные ALLOW/DENY/LIMITED/UNAVAILABLE, limits/multipliers и lifecycle contracts будущих интеграций. Policy definition не означает установленную внешнюю функцию; DexturChat repository/API в этой задаче недоступен. Support emergency-contact baseline не отключается репутацией.

/repadmin set/add/remove меняют отдельный auditable admin contribution без удаления votes/recovery/Trust; set задаёт результирующую публичную целую оценку. /repadmin voting lock/unlock/status работает для известных offline UUID; policy allow/deny/reset сохраняет индивидуальные overrides. /repadmin reload атомарно применяет только features.yml. Optional health adapter выключен по умолчанию и использует собственный обратимый transient modifier. /rep policies, GUI и три cache-only PAPI значения показывают публичные последствия без скрытого Trust.

JSON format 6 / SQL V6 сохраняют весь Phase 1–5 dataset; offline StorageAdmin переносит admin ledger/locks/overrides во всех шести направлениях. Читайте [POLICIES](docs/POLICIES.md), [ADMIN_ESSENTIALS](docs/ADMIN_ESSENTIALS.md), [HEALTH](docs/HEALTH.md) и [отчёт Phase 6](docs/PHASE_6_TEST_REPORT.md). Phase 7 не реализована.
