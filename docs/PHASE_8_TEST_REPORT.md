# Phase 8 — DexturReputation 1.0.0

**PHASE COMPLETE — VERIFIED**

Обязательные локальные проверки завершены. Версия 1.0.0 в ветке `work` готова к отдельному решению владельца о слиянии и выпуске. Слияние в `main`, тег `v1.0.0` и GitHub Release в рамках этой задачи не выполняются. Недоступные внешние проверки отмечены отдельно ниже.

## Исправления и сохранённая совместимость

Продолжены сохранённые изменения Phase 8 без сброса или удаления предыдущей работы. Исходная точка Phase 7: `e0c8aaf9a732b931b76b71d30bd90454865bbd3b`. Все 95 защищённых файлов сохранены: 61 исходник прежних тестов/фикстур, 19 исходников публичного API, исторические миграции, lockfiles и контрольные суммы. Все 41 прежний класс публичного API побайтово совпали. V1–V7 не изменены, V8 нет; формат записи JSON выбирается по сохранённым разделам, чтение форматов 1–7 сохранено. Все прежние 600 тестов выполнены без ослабления.

- Исключение наблюдателя не меняет результат уже завершённой транзакции и не мешает остальным обработчикам; предупреждение ограничено одним в минуту.
- Проверяются символьные ссылки в пути и его родительских каталогах; файл блокировки открывается с `NOFOLLOW_LINKS`.
- YAML использует SafeConstructor, отклоняет повторные/null/нестроковые ключи и ссылки на коллекции; диагностика не печатает исходные строки с возможными секретами.
- Превышение лимита JSON отклоняется до замены файла; предыдущий снимок остаётся доступным. Есть RU/EN инструкции и ограничение частоты предупреждений.
- Ограничен размер командного ввода, проверяется канонический UUID, Unicode и пользовательская разметка обрабатываются буквально. Сохранено автодополнение прежних команд, действия фильтруются своими правами; справка включает `flag`.
- SQL-импорт использует подготовленные пакеты по 100 записей и строит окончательный индекс активных голосов один раз. Ошибка rollback не скрывает исходную ошибку; статистика оптимизатора обновляется до активации цели.
- Чтение SQL-профиля не вычисляет историю голосования. Глубокая пагинация сначала выбирает ID; `inspect` использует эквивалентные агрегаты новых и исторических состояний.
- StorageAdmin не содержит дескриптор и классы Paper-плагина; офлайн-команда `verify` проверяет целостность снимка без исправления оценок.

Новых игровых типов публичного API нет. `JsonStorage.LimitException` служит диагностикой хранилища, `CommandInput` имеет доступ в пределах пакета; расследования и Trust остаются внутренними. Новые игровые функции, платформы, Redis и Velocity не добавлялись.

## Автоматические тесты

Два последовательных независимых финальных запуска `./gradlew clean build --no-build-cache` (локальные прогоны 2 и 3): **714 PASS каждый**, failures=0/errors=0/skips=0. Это 600 прежних и 114 новых тестов. Первая промежуточная сборка до последних проверок автодополнения и предупреждений дала 711 PASS; она не подменяет финальные две.

| Suite | Tests | Failures / errors / skips |
| --- | ---: | --- |
| reputation-domain / test | 10 | 0 / 0 / 0 |
| reputation-application / test | 95 | 0 / 0 / 0 |
| reputation-paper / test | 80 | 0 / 0 / 0 |
| reputation-storage / test | 301 | 0 / 0 / 0 |
| reputation-storage / integrationTest | 228 | 0 / 0 / 0 |

Обязательных пропущенных проверок нет. Docker обязателен для integrationTest; MariaDB и MySQL проверены отдельно.

## Fresh install, upgrades, Paper

Paper **26.2 build 134**, Java Temurin **25+36**, Linux amd64. Все финальные запуски использовали один набор SHA ниже. **48 сценариев / 163 успешных запуска JVM**: 21 сценарий установки/обновления по 4 запуска = 84, плюс 27 регрессионных сценариев = 79. Пилоты и неудачный первый QA-запуск без параметров proxy/truststore не включены. После исправления тестового окружения ошибка не воспроизводилась; TLS-проверка сохранена.

Чистая установка: **16 комбинаций** JSON/SQLite/MariaDB/MySQL × без интеграций/только PAPI/только LP/обе интеграции, по четыре цикла JVM. JSON устанавливается без существующего каталога данных; для SQL заранее указан только выбор хранилища, остальные defaults создаёт плагин. Проверены RU/EN GUI, версия, права обычного игрока, PAPI-оценка и недоступность скрытого Trust, reload/restart, режимы TAB DISABLED/DIRECT/PLACEHOLDER_ONLY, здоровье, сохранение чужих модификаторов/команд и синхронизация LP. Использованы PAPI 2.12.3 и полный LuckPerms 5.5.87 (compile-only API 5.4). Интеграции остаются необязательными.

| Источник | Фактическая проверка |
| --- | --- |
| Phase 1 SQL schema 1 | Реальный V1 DDL/checksum и flat storage.yml v1; MariaDB и MySQL отдельно, representative controlled rows; selector остаётся MYSQL |
| Phase 1.1 JSON format 1 | Сохранённый оригинальный snapshot, native restart/restore и сохранение всех исходных records |
| Phase 1.1 SQLite user_version 1 | Сохранённый настоящий sqlite file, native upgrade/restarts/restore, исходные profiles/preferences сохранены |
| Phase 2 format 2 | Genuine retained fixture, старые calculationVersion=1 weights/journal сохранены |
| Phase 3 format 3 | Genuine protection fixture, Trust/pending/anti-abuse сохранены |
| Phase 4 format 4 | Genuine tiers fixture, tier/hysteresis/LP intent сохранены |
| Phase 5 format 5 | Genuine recovery fixture, programs/activity/rewards/receipts сохранены |
| Phase 6 format 6 | Genuine policies fixture, contribution/locks/overrides сохранены |
| Phase 7 format 7 | Genuine full snapshot: JSON и bootstrap в MariaDB/MySQL; все original records retained, включая flags/moderation/audit/staged bulk |

Реальное SQL-обновление проверено отдельно от совместимости конфигурации. На каждом движке создана исходная V1 DDL с оригинальной контрольной суммой и контролируемыми данными: **3 профиля в двух scope и 2 языковых предпочтения**. Paper применил V1→V7; все исходные записи и история схем сохранены. Независимая проверка старого плоского `storage.yml` v1 подтвердила сохранение MYSQL и исходных байтов конфигурации. Это представительный QA-набор, а не заявленная копия производственной базы Phase 1.

Регрессионная матрица: administration 5 сценариев/15 запусков; policies 5/15; recovery, включая отключённый режим, 5/15; tiers 4/12; protection 3/9; voting 3/9; SQL schema 1 — 2/4. Проверены реальные команды, голосование/pending/Trust, GUI, admin set/add/remove/lock, invalidate/restore, пересчёты, bulk dry-run/confirm, RU/EN, офлайн-игроки и PAPI/LP.

Отдельно выполнены **24 основные проверки направлений CLI**: шесть направлений × два SQL-движка × два полных набора состояния (schema 7 administration и schema 6 policies), с новой JVM и сохранением источника. Направления: JSON→SQLite, SQLite→JSON, JSON→MySQL, MySQL→JSON, SQLite→MySQL, MySQL→SQLite. Проверено сохранение pending, Trust, tiers, recovery, admin, moderation, audit и bulk. Дополнительные unit/integration-тесты проверяют запрет активации частичной цели; конфигурация переключается последней, источник и резервные копии сохраняются.

## Backup, failures, integrity

JSON/SQLite: stopped writer, checksummed physical copies, clean destination, verify/reopen/plugin restart. JSON state.json и recovery copy сохранены; explicit recovery/quarantine проверяется suite. SQLite live WAL test с quiesced writer/checkpoint busy=0; stopped runtime copy только после пустого/отсутствующего WAL. Отдельные busy timeout/rollback/retry и unsupported schema/corruption tests.

MariaDB/MySQL: **10 native runtime restores** суммарно по SQL release/upgrade cases. Dump --single-transaction --skip-lock-tables --no-tablespaces, clean new database reputation_restore, полный portable snapshot равен до/после; plugin открывает restored database. dr_schema_history/все application tables сохранены. После shutdown application SQL sessions=0. Отдельные native restore integration tests проверяют schema V7 и полное data equality.

Фактически использованные native инструменты:

```text
mariadb from 11.4.8-MariaDB, client 15.2 for debian-linux-gnu (x86_64) using  EditLine wrapper
mariadb-dump from 11.4.8-MariaDB, client 10.19 for debian-linux-gnu (x86_64)
mysql  Ver 8.4.7 for Linux on x86_64 (MySQL Community Server - GPL)
mysqldump  Ver 8.4.7 for Linux on x86_64 (MySQL Community Server - GPL)
```

Failure injection: JSON BEFORE_WRITE/BEFORE_RENAME/AFTER_RENAME (полное old либо new generation, без partial); SQL rollback/reward failure/corrupt payload/checksum; KILL exclusive ownership connection, fail-closed и fresh-owner equality; unavailable DB без fallback; реальные child JVM Runtime.halt(73) на JSON/SQLite/MYSQL migration оставляют source config/data/backups и блокируют partial target. Старые данные автоматически не удаляются.

Offline `verify` требует STOPPED-ALL-WRITERS, проверяет canonical snapshot/checksums/journal/precision/tier/recovery/admin/audit references; не пересчитывает оценки. Обычный startup/schema initialization при открытии старых данных возможен, поэтому pre-upgrade backup обязателен. Repair: verify/report/dry-run/explicit confirmation/recalculate либо восстановление проверенной копии, без ручного редактирования checksums/cursor/rewards.

## Stress и большие данные

**736 shuffled jobs /8 workers/backend**, 138 unique vote operations. Duplicate normal submission/approval, admin add/locks, pending moderation, recovery sampling/reward, Trust/reputation recalculation, policy и GUI-report reads, bounded bulk. Exact target community=45/admin=64/visible=109, один recovery grant, durable revisions и dry-run immutability; все futures завершаются с deadline, deadlocks/lost updates/duplicate effects не обнаружены. Production application queue отдельно: 1000 requests, bounded queue saturation/rejection и пять lifecycle cycles; это не production load.

Большой синтетический SQL-набор: 10 000 профилей/100 000 голосов, 3000 записей аудита, 500 модераций и 1000 флагов. Добавлены реальные pending/reject/restore, recovery с наградой и 50 административных действий; перед измерениями — 10 301 профиль/100 200 голосов, после новых голосований — 100 300 голосов. Запрос pending возвращает 75 настоящих записей. Использование индекса `dr_votes_target` проверено по фактическому EXPLAIN key/detail, а не possible_keys. Рабочие страницы ограничены в Java; SQL-агрегаты могут обходить историю внутри СУБД. Проверка при запуске и офлайн-снимки намеренно читают полный набор в память.

JSON dataset превышает default **67 108 864 bytes** и корректно отвергнут без повышения cap; предыдущий файл побайтово сохранён. Дополнительный nonempty 4KiB test проверяет предыдущую generation/recovery copy/restart, warning boundary 75% и RU/EN rate limiting проверены Paper adapter tests. JSON timings относятся к отдельной малой базе.

Все median/p95/p99, параметры набора и оборудования, число выборок и условия прогрева приведены в [PERFORMANCE](PERFORMANCE.md). Обычно 100 измерений; для кеша — 1000, bulk/relations — 10 (p99 не вычисляется). Замер GUI относится к модели отчёта, а не отрисовке клиентом. TPS/SLA не заявляются.

## Config, localization, commands, security

Проверены все **14** текущих файлов: 12 конфигураций и 2 локали. Проверены версии, неверный YAML, defaults, дополнение недостающих полей в памяти, сохранение пользовательских значений, enum/диапазоны/разметка, связи tiers/GUI и атомарная перезагрузка features. Неверный пользовательский файл не заменяется defaults. Storage использует v2, историческая v1 остаётся MYSQL; остальные файлы имеют config-version 1. `/repadmin reload` обновляет только `features.yml`; остальные изменения требуют перезапуска.

RU/EN имеют одинаковые **434 mapping keys**, включая config-version; required key arguments/fallback/literal player input проверены. Все storage diagnostics/admin verify messages представлены на обоих языках. [COMMANDS](COMMANDS.md)/[PERMISSIONS](PERMISSIONS.md) сверены с пятью реальными roots, aliases не объявлены, sensitive nodes default op, wildcard/children grants нет. Known offline/unknown/no-network lookup, console/player-only, Unicode/long input, reasons/comments и tab delegation/action permissions проверены.

Prepared SQL/user-value binding, fixed fragment allowlists, Safe YAML, literal MiniMessage, path/ancestor/NOFOLLOW restrictions, UUID/replay/token ownership/expiry/stale plan, temp modes и safe failure logs проверены. Секреты/QA/test classes/servers не упакованы. Main artifact не содержит полного PAPI/LP, StorageAdmin не имеет plugin.yml/Bukkit/Paper implementation. Audit сравнил все old API class bytes. [SECURITY](SECURITY.md), [DEPENDENCIES](DEPENDENCIES.md).

Локально проверены закреплённые версии, STRICT locks, контрольные суммы, scopes и лицензии по кешированным POM; новых зависимостей Phase 8 нет. Тестовые MySQL driver/Testcontainers/Mockito/JUnit не попали в runtime. **External vulnerability scan: NOT VERIFIED (URLError).** Недоступность OSV сама по себе не блокирует выпуск по условиям задачи; отсутствие CVE не заявляется.

CI использует Java 25, обязательный Docker, clean build, повторную упаковку, `cmp` и сохранение артефактов; права репозитория read-only, repository secrets не нужны. Workflow структурно проверен, локальные команды выполнены. **Remote GitHub Actions execution: NOT VERIFIED** — API недоступен, успешный удалённый запуск не наблюдался.

## Reproducibility и SHA

Две независимые final clean builds без build cache: main и CLI **побайтово идентичны**. SHA-256:

```text
232c02ce1aa27384557ce6071093d76ebf4158a77e5d3ed9568bfd2a4537ccec  DexturReputation.jar
8e72895207461a720b79ab14d1849e544ae27ecd280e6508385c12416ee3a6bc  DexturReputation-StorageAdmin.jar
131a7f0cccc0d90da97bee7bcc523ae3b1298b8bc9e3203e9312a0d3e6977cca  DexturReputationProbe.jar (QA only)
```

Контроль идентичности исходников, тестов, QA, конфигурации и зависимостей охватывает **252 файла**: 251 зафиксирован перед сборками; после них добавлен только QA-скрипт настоящего SQL V1 upgrade, все прежние 251 хеш неизменны. Проверены 83 сохранённых входных файла runtime. Хеши перепроверены независимо от сводки runner. Исходные логи, XML, измерения, копии и доказательства восстановления сохранены в `/workspace/.setup`, вне Git/JAR. Основные локальные манифесты: `phase8-reproducible-builds.json`, `phase8-suite-verification.json`, `phase8-final-runtime-proof.json`, `phase8-release-runtime-proof.json`, `phase8-final-artifacts.json`, `phase8-final-inputs.json`.

## Ограничения и завершение

Допускается один владелец хранилища; общего писателя нескольких серверов, Redis, Velocity и Folia нет. JSON переписывает полный снимок, использует память, atomic rename/fsync и лимит 64 MiB по умолчанию. Для SQL остаётся стоимость OFFSET и агрегатов. Аудит и receipts растут, превышение лимитов отклоняется; разрушительная история автоматически не удаляется. События локальные и не являются сохраняемой очередью; внешние эффекты LP/health согласуются повторными проверками.

Проверена Linux/POSIX. Физическое отключение питания, произвольные NFS/FUSE/Windows, производственный TLS/онлайн-аутентификация, ожидание реальных суток, ручная проверка пикселей клиента и универсальная совместимость сторонних плагинов не заявлены. Адаптер на стороне DexturChat не реализован, доступен только контракт Reputation. Гарантии production TPS/SLA нет.

Завершающий Git-шаг выполняется только в `origin/work`: commit/push, независимый `git ls-remote` и проверка чистого дерева. Точные local/remote SHA приводятся в итоговом сообщении и локальном `phase8-git-verification.json`, поскольку хеш содержащего этот отчёт коммита нельзя включить в него самого. Готовность к слиянию и выпуску 1.0.0 не является разрешением их выполнять. STOP после Phase 8.
