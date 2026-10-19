# Отчёт Phase 3 — Trust и anti-abuse

Дата: 19 октября 2026. Версия: **0.3.0-SNAPSHOT**, ветка `work`.
Исходная Phase 2: `9bd3fcae6f269d1399a746384256be4323b94ec6`, присутствует в истории.
Перед изменениями проверены чистое дерево, origin, архитектура, контракты и maintenance CLI.
Phase 4 не начата. Новых gameplay-титулов, LuckPerms, квестов и сетевых адаптеров нет.

## Реализация и совместимость

1. **Trust model 1** отделён от reputation. Initial .50, нормирование проверенного server age
   и Paper PLAY_ONE_MINUTE / 20, исходящие diversity/reliability/repeat risk и явные confirmed
   penalties; default границы [0,1]. Входящая положительная/отрицательная репутация не является
   фактором Trust. Сохраняются timestamps, CAS/input revisions и воспроизводимые коэффициенты.
   Неподтверждённые IP/account ownership сигналы не используются.
2. **Weighted calculation 2**: шесть десятичных знаков, HALF_EVEN для произведения,
   HALF_UP для публичного общего итога. Дроби не теряются при накоплении. Phase 2 contribution
   и legacy baseline сохраняются; изменение настроек не переоценивает старые голоса.
3. **Anti-abuse**: repeat steps, lifetime ceiling пары, взаимные оба знака/замены, ограниченная
   diversity, локальные dense reciprocal groups, immature/low-Trust negative bursts и rapid
   growth. LOW/MEDIUM/HIGH — сигналы, не доказательство обмана; severe auto-punishment отсутствует.
4. **Pending**: новый вклад 0, при pending replacement прежний active остаётся применённым.
   ACCEPT/INVALIDATE/CONFIRM_MANIPULATION имеют operator/reason/transition ID/expected revision,
   идемпотентный retry и аудируемые атомарные изменения. Stale намерение не воскресает после
   revoke/replace. Автоматических постоянных санкций нет.
5. **Конфигурация**: trust.yml и anti-abuse.yml version 1, валидированные defaults/диапазоны,
   missing-field merge без перезаписи custom YAML, restart для применения. Единственный backend
   selector storage.type сохранён; JSON default, flat v1 остаётся MYSQL, fallback при сбое нет.
6. **RU/EN**: полные новые feedback/diagnostic/admin templates, additive locale upgrade,
   pending/adjusted/restricted/busy/status сообщения без Trust/порогов. Dynamic MiniMessage
   значения вставляются plain Components. GUI использует тот же transactional pipeline.
7. **Хранение**: единый JSON v3 snapshot с fsync/atomic rename/backup/checksum/recovery;
   SQLite schema 3 с WAL/FULL/busy/transactions; additive MySQL/MariaDB V3 с prepared statements,
   Hikari и ownership. V1/V2 SQL не изменены. Полный snapshot проверяет private/public chains,
   индексы, точные суммы, факторы веса и Trust. Предупреждение роста JSON настраивается.
8. **StorageAdmin** переносит весь private state, включая pending, Trust, точные contributions,
   lifetime ceilings, assessments/audit/graph metadata, языки и старый журнал. Полное equality
   до config activation, backups, pending guard, source retention сохранены.
9. **API**: прежние методы и record-компоненты сохранены. Добавлены default outcome и
   onStatusChanged; публичный outcome содержит лишь operation ID/status. Trust не входит в API
   JAR/ServicesManager/PAPI/GUI/профили/TAB. Сравнение javap подтвердило сохранение публичных
   деклараций **всех 17 классов Phase 2 API**. Для calculationVersion=2 целый effectiveWeight —
   представление отдельного веса; актуальную сумму нужно брать из view, не складывать округления.
10. **Внутреннее администрирование**: закрытый асинхронный capability для inspection, review,
    bounded audit и CAS refresh. Публичной команды модерации пока нет; это будущая фаза.

Подробные модели и правила: [TRUST.md](TRUST.md), [ANTI_ABUSE.md](ANTI_ABUSE.md),
[CONFIGURATION.md](CONFIGURATION.md), [STORAGE.md](STORAGE.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

## Автоматические проверки

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache
# Затем с тем же окружением:
./gradlew :reputation-paper:runtimeProbeJar
```

Финальный общий прогон после усиления конкурентных assertions: **BUILD SUCCESSFUL in 1m 28s**,
36 задач выполнены без build cache; **169 tests, 0 failures, 0 errors, 0 skipped**.
Исходные **106 тестов сохранены без изменения**, добавлено **63** параметризованных/обычных cases.

| Suite | Cases |
|---|---:|
| domain / прежний VotingEngine | 5 |
| application / configuration, locale, cache, queue | 21 |
| storage local / JSON, SQLite, models, failures, migration, service | 77 |
| MariaDB 11.4.8 / прежние + protection contracts | 28 |
| MySQL 8.4.7 / прежние + protection contracts | 28 |
| Paper adapters / GUI | 10 |
| **Всего** | **169** |

Docker обязателен; SQL suites используют реальные СУБД, не mocks и не silent skips.
Проверены initial/bounds/model version/reputation independence, все весовые факторы/clamps,
симметричное округление и fractional accumulation, historical compatibility, оба знака и их
чередование, revoke/window-expiry farming, reciprocal evidence, dense groups и три друга,
зрелый независимый burst и immature attacks, positive rapid growth, bounded evidence,
pending replacement/accept/invalidate/confirmed restriction/stale decision, idempotency.

Общие контракты на **всех четырёх движках** проверяют 24 одновременных retry одного ID,
20 competing CAS с одним победителем и 24 независимых одновременных голоса без потери дробей.
После 25 вкладов .35 точная сумма 8.75, public total 9. Admission test отдельно удерживает
Paper facts futures: переполнение отклоняется, caller cancellation не снимает лимит раньше
времени, завершение/ошибка освобождают всю ёмкость.

JSON fault injection до write/rename и после rename не подтверждает неопределённый commit;
restart видит целую старую либо целую новую snapshot с Trust/pending. SQLite/MySQL/MariaDB
ошибки после private/journal writes при profile UPDATE полностью откатывают vote/review.
Проверены byte limit, explicit recovery/quarantine, private index corruption, checksum и
семантическое повреждение с пересчитанным checksum, unknown model/version, точные integer
payloads, неполные миграции и guard. V3 DDL retry/checksum проверен в обеих сетевых СУБД.
Все шесть направлений полного Phase 3 переноса проверены отдельно с каждой СУБД, включая
непустые group metadata, confirmed audit и pending. Старые migration/конфигурационные тесты
также остаются в общем прогоне. RU/EN completeness и injection tests проходят.

## Настоящий Paper

Paper **26.2 build 134**, Temurin **25+36**, Linux amd64. Paper JAR SHA-256:
`3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b`.

На одном неизменном plugin JAR выполнены **29 успешных запусков**:

| Сценарий | JSON | SQLite | MYSQL (MariaDB) | Всего |
|---|---:|---:|---:|---:|
| Phase 3 + настоящий Phase 2 snapshot + restart/PAPI | 3 | 3 | 3 | 9 |
| Прежний --voting workflow + restart/PAPI | 3 | 3 | 3 | 9 |
| Исходный baseline + restart/PAPI | 3 | 3 | 3 | 9 |
| Неизменённый flat storage.yml v1 | — | — | 2 | 2 |
| **Всего** | **9** | **9** | **11** | **29** |

Во всех логах проверен PROBE_PASS, в соответствующих — PROBE_PROTECTION_PASS,
PROBE_LEGACY_PHASE2_PASS, PROBE_VOTING_PASS; девять третьих прогонов — PROBE_PAPI_PASS.
Все десять копий plugin JAR сверены по SHA-256 с release artifact.

Три настоящих local protocol-776 клиента для Phase 3 проверяют реальный server first join/
playtime, offline target, initial Trust, CAS/stale refresh, дроби .35, 12 simultaneous retries,
pending replacement без потери старого вклада, ACCEPT и duplicate review/event, native
команды RU/EN, GUI, cache/PAPI и сохранение pending/Trust/точного веса после двух рестартов.
Публичный PAPI-запрос trust не возвращает значение. Прежний --voting проверяет реальные
inventory events, permissions, reason completion, revoke/replace/history, пять committed
событий за run, GUI cleanup и cache. QA eligibility/cooldowns/quota и часть detection policy
ослаблены **только в отдельном тестовом каталоге**; production defaults проверены общими suites.

Исходный реальный format-2 снимок содержит 15 голосов calculationVersion=1, профили и языки.
Для SQL он импортирован настоящим standalone CLI до старта Paper. Старые history/score
проверяются на каждом restart. Для JSON и SQLite дополнительно сравнен **весь** старый dataset.
После Paper проверен настоящий CLI перенос Phase 3 SQLite → JSON и полный round trip
JSON → MariaDB → JSON с equality всех данных, Trust/audit/pending и сохранением source.

Серверы слушают только loopback 25566, сценарии последовательны, тестовые контейнеры удалены
после завершения. Внешний Mojang public-key DNS запрос в offline среде выдавал предупреждения;
это не проверка online authentication. Обычный Minecraft client/pixel UI, production TLS,
реальное отключение питания и production load не проверялись. Успешные прогоны не подменяют
эти проверки. QA plugin/clients не входят в устанавливаемый JAR.

## Производительность и ограничения

- Вся SQL/JSON обработка и граф выполняются worker-ами; Paper захватывает только native facts.
  Queue и отдельное число незавершённых submit ограничены (default 256), workers default 2,
  caches bounded, 64 фиксированных profile stripes, CallerRuns отсутствует.
- Trust batch default 20/max 100, CAS и non-overlap; отдельный graph job, кооперативный stop,
  дренирование принятых операций. Metrics: batches/refreshed/stale/failed/reviewed/time/running,
  graph work/completeness/revision доступны внутреннему администрированию.
- Default graph budgets: 2048 events / 128 nodes / 4096 edges / 20000 work / 100 ms / 30 секунд.
  Это локальная эвристика, не полный graph search и не доказательство общего владельца аккаунтов.
- JSON default 64 MiB, warning .75, O(N) serialization/validation, local atomic rename/fsync.
  Он уступает transactional SQL в масштабировании и не даёт гарантий оборудования/replication.
  Startup/backup/migration validation SQL тоже O(N). Shared независимые writers не поддерживаются.
- Внутренний fixed-point accumulator имеет предел около ±9.22×10¹² целых. Старые широкие long values
  сохраняются; новая операция за пределом точности явно отклоняется без частичной записи.
- Lifetime ceiling намеренно консервативен. Истечение окна/рост Trust/изменение конфига не
  восстанавливают старый вес. Массового пересчёта/Phase 4 интерфейса нет.
- Pending нужен доверенный административный адаптер к внутреннему capability; обычные игроки
  его не принимают. Сигнал не означает confirmed violation. In-process события не durable outbox;
  после неопределённого исхода проверяйте outcome и повторяйте тот же ID/payload.
- Проверка 29 запусков и конкурентных invariants не является TPS benchmark или гарантией SLA.
  Политика по умолчанию требует наблюдения и настройки на конкретном сервере.

## Артефакты и среда

Plugin SHA-256: `9853c1ff819854d72af052ddfa7ee4f9eaa0f0f7b0c821dc60c0ffdb38f67b91`.
Standalone StorageAdmin SHA-256: `eb78ddfac85333df0c2fbc693d6773e51215b6961bd8c9206f173310e8e5ed67`.
Install/helper проверен; три helper JAR побайтово совпали с обычной сборкой. Dependency locks,
verification metadata и опубликованные V1/V2 не менялись. CI обновлён для Phase 3; запуск
GitHub Actions и восстановление новой cloud task здесь не объявляются проверенными.

Логи текущей среды находятся вне checkout:
`/workspace/.setup/phase3-complete-verification.log`, `phase3-release-matrix.log/json`,
`phase3-release-*/smoke-*.log`, `phase3-cli-roundtrip.log`, `phase3-install-release.log`.
Полная матрица проверяется по markers и SHA, а не по одному exit code.

## Git

Разработка выполнена в `work`, origin `Fen0nim/Dextur-Reputation`; main не сливалась и
не использовалась вместо Phase 2. Проверка полного diff, secret patterns и старых contracts
выполнена перед commit. После commit требуется обычный push origin/work и независимый
`git ls-remote origin refs/heads/work`; фактический SHA и результат публикуются в итоговом
сообщении, чтобы отчёт не пытался содержать собственный хеш. Force push не применяется.
