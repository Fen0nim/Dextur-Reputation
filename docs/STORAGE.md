# Хранилища, резервные копии и перенос — Phase 2

## Выбор и совместимость

В `storage.yml` v2 только **один** selector:

```yaml
config-version: 2
storage:
  type: JSON # JSON | SQLITE | MYSQL
  json:
    directory: storage/json
    maximum-file-bytes: 67108864
  sqlite:
    file: storage/reputation.sqlite
    busy-timeout-ms: 5000
  mysql:
    host: localhost
    port: 3306
    database: dexturreputation
    username: dexturreputation
    password-environment: DEXTURREPUTATION_DB_PASSWORD
    ssl-mode: verify-full
    maximum-pool-size: 2
    connection-timeout-ms: 5000
    max-lifetime-ms: 1800000
```

Новый конфиг выбирает JSON. В старом плоском `config-version: 1` отсутствие `storage.type`
означает MYSQL; файл не переписывается. Если администратор добавляет в v1 `storage: {type: JSON}`
или SQLITE, это явное переключение с сохранением прочих legacy-полей. Для обычного переноса
предпочтителен полный целевой конфиг v2. Независимых enable-booleans нет.

Все объявленные параметры валидируются до регистрации API. Для MYSQL дополнительно нужен
реальный пароль в указанной переменной окружения процесса; для файловых backend он не читается.
Никакой ошибки типа/данных/подключения не приводит к автоматическому fallback.
Относительные пути должны оставаться в каталоге плагина; symlink-пути запрещены.

## JSON

`storage/json/state.json` содержит formatVersion=2, generation, profiles, preferences, votes и SHA-256.
Формат 1 Phase 1.1 читается без потери данных; запись обновляет его до 2.
Сохраняются **все scope, оценки, tier, revision, UUID, timestamps профилей и выбора языка**.
Снимок канонически сортируется; дубликаты ключей/профилей/языков, некорректные UUID/enum/числа,
неподдерживаемый формат и неверная checksum отвергаются. Hash защищает от повреждения,
не является подписью/защитой от владельца файловой системы.

Каждая мутация сериализована под lock и подтверждается только после:

1. Проверки, что текущий файл не повреждён/не изменён вне владельца.
2. Durable-замены предыдущей версии в `state.json.bak`.
3. Записи полного нового `state.json.tmp` и `FileChannel.force(true)`.
4. `ATOMIC_MOVE` в `state.json` на той же файловой системе и fsync каталога.

Создание новых каталогов также синхронизирует родительские записи. Новые snapshot/backup
файлы создаются с POSIX mode 0600. Нет неатомарного обходного пути. Ошибка/неопределённый
результат записи переводит открытый backend в fail-closed до перезапуска/проверки.
Если rename успел завершиться до ошибки fsync/падения, после рестарта может существовать новая
полная версия, хотя caller не получил успех. Повторные getOrCreate/выбор того же языка идемпотентны; голос повторяйте с тем же operationId.

Повреждённый/пропавший primary **не** вызывает автоматического отката или создания пустой БД,
если остались backup/tmp. Корректный primary загружается; незавершённый tmp не активируется.
Backup — предыдущая версия, не бесконечный журнал. Возможна потеря последних изменений при
явном восстановлении старого backup. Все варианты сохраняются в quarantine перед восстановлением.

Ограничения: полная сериализация и fsync на каждую мутацию (O(N)), ограничение размера файла
по умолчанию 64 MiB, полный snapshot в памяти, один процесс-владелец. Нет SQL-запросов,
построчного журнала или произвольных многооперационных транзакций. Подходит для небольших
установок; для роста выбирайте SQLite/MySQL. Проверена Linux/POSIX local filesystem;
Windows, NFS/FUSE и устройства, не соблюдающие fsync/atomic rename, не заявлены. Неподдерживаемые
операции должны завершаться ошибкой, а не ослаблять гарантию. Настоящая потеря питания не тестировалась.

## SQLite

Локальный JDBC, WAL, `synchronous=FULL`, `foreign_keys=ON`, busy timeout 250–30000 ms
(по умолчанию 5000). Один владелец файла и контролируемая последовательность операций внутри
процесса; параметры SQL передаются prepared statements. Создание профиля, выбор языка и bulk
import используют транзакции, ошибки делают ROLLBACK. При старте выполняются integrity_check,
проверка schema user_version и полная валидация сохранённых записей.

Таблицы содержат те же логические данные, timestamps — ISO UTC с микросекундами.
Не копируйте только главный `.sqlite` во время работы: актуальные данные могут быть в `-wal`.
Остановите всех писателей, либо используйте штатный SQLite backup API/CLI отдельным инструментом.
Не открывайте SQLite на сетевой файловой системе; concurrent внешний SQL writer не является
поддерживаемым способом администрирования.

## MySQL/MariaDB

Сохраняются Phase 1 `dr_profiles`, `dr_player_preferences`, `dr_schema_history`, UUID/scope PK,
UTC TIMESTAMP(6), HikariCP, MariaDB Connector/J и неизменённая V1/checksum. Аддитивная таблица
`dr_storage_control` хранит формат метаданных и запрет активации незавершённого импорта.
Пароль берётся только из окружения, не записывается в snapshot/receipt/config и не выводится CLI.
Для production `ssl-mode: verify-full`; `disable` только для изолированных тестов.

На одну физическую базу допускается один владелец DexturReputation. Отдельная JDBC-сессия
удерживает advisory lock; она добавляется **к** настроенному request pool (учтите N+1 соединений).
Перед операциями проверяется сохранение владения. Потеря сессии не вызывает автоматического
перехвата: операции завершаются ошибкой до явного рестарта. Pool/ownership очищаются вместе.
Существующие SQL-транзакции по-прежнему атомарны; внешние ручные writers/старые версии плагина
не соблюдают этот протокол, поэтому их необходимо остановить до переноса.

## Offline перенос

Инструмент: `DexturReputation-StorageAdmin.jar`, **не** основной plugin JAR.
Требуются Java 25, файловые права и (для MYSQL) credential/environment выбранных баз.

1. Полностью остановите Paper **и все другие писатели исходной/целевой БД**. Консольная
   команда статуса не является остановкой. Сохраните обычную внешнюю резервную копию.
2. Подготовьте отдельный полный `storage-target.yml` по примеру выше с нужным type/путями.
   Назначение должно быть **пустым**. Не выбирайте старую непустую JSON/SQLite/SQL базу.
   При обратном переносе используйте новый путь/новую БД: исходные данные сохраняются.
3. Для MYSQL задайте пароль безопасно в окружении; если у баз разные пароли, настройте
   разные `password-environment`. Не передавайте пароль аргументом командной строки.
4. Выполните явно:

```sh
java -jar DexturReputation-StorageAdmin.jar migrate \
  --data-directory /srv/minecraft/plugins/DexturReputation \
  --target-config /srv/minecraft/storage-target.yml \
  --backups /srv/backups/dextur-reputation \
  --confirm STOPPED-ALL-WRITERS \
  --language ru_RU
```

Для английского отчёта — `--language en_US`. Без точного confirm инструмент не меняет данные.

Процедура берёт `.storage.lock`, backend ownership обоих хранилищ и проверяет, что identities
различаются. Создаёт уникальную папку backup с `source.json`, `target-before.json`,
`storage-before.yml`, `storage-target.yml`. Все backup перечитываются и проверяются.
Назначение помечается pending, выполняется атомарный bulk import (JSON snapshot / SQL transaction).
Затем сравниваются полные source/target snapshots, а не только counts: timestamps, языки,
оценки, scope, revision и полный журнал голосов должны совпасть. Создаётся проверенный `VERIFIED.json`.
**Только после этого** снимается pending и атомарно заменяется активный `storage.yml`.
Конфигурация содержит один selector, поэтому два активных backend не появляются.

При ошибке до подтверждённой активации прежний config сохраняется/восстанавливается, а
назначение остаётся pending. Плагин откажется его запускать. Не снимайте marker руками и
не делайте downgrade до Phase 1, которая не знает marker. Для повтора выбирайте новое пустое
назначение, сохранив неудачный результат и backups для анализа. При сбое во время финальной
замены проверьте config и VERIFIED receipt перед запуском. Никогда не считайте один exit code
после аварийного завершения доказательством того, какой backend активен.

5. Проверьте отчёт/backup, запустите Paper, выполните `/dexturreputation storage` и `/rep`.
   Старое хранилище **не удаляется**. Если новая система уже принимала записи, возврат старого
   config откатит эти записи: безопасный rollback требует нового проверенного обратного переноса.

Протокол не может остановить произвольные внешние SQL-клиенты или writers старой версии.
Подтверждение STOPPED-ALL-WRITERS — реальная эксплуатационная предпосылка, а не её автоматическая
проверка для всех машин. Соблюдающие протокол владельцы на этой версии защищены locks.

## Явное JSON recovery

Остановите сервер. Выберите `backup` (предыдущая полная версия) или `temporary`
(возможная незавершённая запись, которая могла не быть подтверждена вызывающему):

```sh
java -jar DexturReputation-StorageAdmin.jar recover-json \
  --data-directory /srv/minecraft/plugins/DexturReputation \
  --candidate backup \
  --confirm STOPPED-ALL-WRITERS \
  --language ru_RU
```

Кандидат сначала проверяется по формату/checksum/данным. Текущий primary, backup и tmp
сохраняются в `recovery-<UUID>/`, затем primary заменяется атомарно. Invalid candidate не
активируется. Recovery не снимает pending миграции. После него проверьте данные до старта.

## Регулярные резервные копии

- JSON: остановить владельца и копировать **весь** JSON-каталог (включая primary/bak/tmp),
  конфиги и lang; проверять checksum через восстановление в отдельном изолированном каталоге.
- SQLite: остановить всех владельцев и копировать DB вместе с оставшимися WAL/SHM, либо
  использовать официальный SQLite backup API. Не удалять WAL вручную.
- MYSQL: согласованный дамп/backup средствами СУБД с безопасными client credentials;
  `--single-transaction` для InnoDB, без concurrent DDL. Дополнительно сохранить конфиги/lang.
- Portable snapshots миграции содержат все данные Phase 1/1.1/2/3, но не произвольные таблицы других
  плагинов и не являются заменой полного DBA backup. Храните внешние копии и проверяйте restore.

## Журнал Phase 2

Additive MySQL migration V2 добавляет dr_votes и dr_active_votes, сохраняя исходный V1/checksum.
SQLite user_version=2. JSON profiles и votes фиксируются одним snapshot; SQL — одной транзакцией.
Backup и recovery всегда охватывают их вместе. Все шесть направлений migration переносят
полную историю, idempotency IDs и веса, восстанавливают active index, сверяют результат перед
активацией. Подробный расчёт/квоты/границы событий и роста журнала — [VOTING.md](VOTING.md).
Downgrade требует полного согласованного pre-upgrade backup; старый плагин не понимает новую
схему. После JSON ошибки сначала проверьте состояние, не повторяйте команды с новым намерением
вслепую: commit мог успеть завершиться. API предоставляет явный idempotent retry.

## Trust и защита Phase 3

MySQL/MariaDB применяют additive checksummed V3, не меняя V1/V2; SQLite — transactional
user_version=3 при включении защиты. Новые таблицы: dr_trust, dr_vote_pairs, dr_precision,
dr_vote_assessments, dr_protection_audit, dr_protection_scope. Они содержат канонический
private payload и проверяемые индексные колонки. Индексы scope/actor, target/sequence,
due/status и participant/time позволяют bounded context, CAS и постраничный аудит.
Незавершённая V3 DDL MySQL повторяется с проверкой уже созданных индексов; неизвестный
checksum/version блокирует старт. Для уже защищённого состояния запись без конфигурации
pipeline отклоняется; offline CLI читает/переносит его без отключения защиты данных.

JSON читает v1/v2/v3 и пишет v3, как только появляются private данные; единый checksum
охватывает profile/preferences/votes/**весь private state**. Проверяются audit sequences,
точные delta/score chains, active/latest pointers, факторы расчёта, Trust и публичный итог.
Checksum не является цифровой подписью и не защищает от DBA, согласованно переписавшего всё.

StorageAdmin сохраняет точные baseline/current units, lifetime ceiling, Trust/его коэффициенты,
assessment versions/status, pending/suspicious/audit/graph metadata и языки во всех шести
направлениях JSON↔SQLite↔MYSQL. Полное сравнение исходного snapshot, backup и результата
перед activation включает эти записи. Действующие configs остаются в том же data folder;
инструмент заменяет только storage.yml. Не забудьте отдельный backup **всех** настроек/lang.
Pending target при ошибке не запускается, source не удаляется. Новых CLI-команд не требуется.

Default JSON limit 64 MiB остаётся прежним. При `storage.json-warning-fraction: .75` из
anti-abuse.yml раз в минуту выводится RU/EN предупреждение о приближении к configured limit.
Достижение limit отклоняет запись до rename и не включает другой backend. На большом
журнале выбирайте SQL; backup/restore всегда включает Trust вместе с голосами и профилями.
Downgrade после записи v3 требует полного согласованного backup старой версии; удаление
private таблиц или ручной сброс version не является допустимым downgrade.

## Phase 4 tier data

JSON writes format **4** when tier state exists, reads formats 1–4, and keeps the existing SHA-256 canonical integrity check, atomic rename/fsync, previous recovery copy, owner lease and configured size limit (default 64 MiB). Tier state and score share a single snapshot; a failed pre-rename write cannot activate half a tier change. An error after rename can mean a committed result with a failed acknowledgment: stop, verify/recover explicitly, and retry the original vote ID. Checksums detect accidental corruption, not malicious edits with a recomputed checksum.

SQLite adds `dr_tiers` and `PRAGMA user_version=4`, retaining WAL/FULL, prepared statements, busy timeout and the serialized backend owner. MySQL/MariaDB add checksummed **V4__tiers.sql**; V1/V2/V3 files and checksums are unchanged. Each tier payload has an independent checksum, indexed identity/revisions and canonical payload validation. Vote/review tier updates use the same SQL transaction as the score/journal/private anti-abuse changes. Startup refuses corrupt data and never switches backends.

The portable `StorageSnapshot` now includes `TierSnapshot`: current tier, policy fingerprint, profile/tier revisions, complete transition history, desired group, sync/applied revisions and owned group metadata. Existing public profile record constructors and earlier portable snapshot constructors remain available. Old snapshots have an empty tier projection until explicit plugin startup reconciliation. Old writer code without a tier policy must not mutate Phase 4 vote state.

All six offline migration directions carry the complete projection with exact snapshot equality verification. Existing commands, external backups, empty-target requirement, owner locks, pending guards and last-step atomic `storage.yml` activation are unchanged. Source data is retained. No tier recalculation runs during offline migration; the destination preserves hysteresis exactly. On its next plugin startup, a genuinely changed tier policy initiates controlled reconciliation.

Back up `config.yml`, `storage.yml`, `tiers.yml`, `display.yml`, `integrations.yml`, `gui.yml`, both locales and the complete selected storage while **all writers are stopped**. Back up LuckPerms independently when enabled. For JSON preserve primary/backup/temporary candidates and never hand-edit transitions. Recovery requires explicit backup/temporary selection with quarantine, as above; a recovered older snapshot also rolls back its scores, pending votes and tier history together. For SQLite use a stopped-file copy or SQLite backup API including WAL handling; do not copy an active database file alone. For MySQL/MariaDB use a consistent transactional dump/snapshot and retain migration history.

JSON still rewrites/validates the whole snapshot and stores full history under its size cap; it has no SQL transaction log, online incremental backup, independent row locking, multi-writer support or proven physical power-loss guarantee on every filesystem. SQL tier payload updates rewrite that player's transition history; there is no production throughput claim. Monitor growth and migrate to SQL before the JSON limit. Phase 4 adds no automatic pruning or deletion. See [TIERS.md](TIERS.md) and [LUCKPERMS.md](LUCKPERMS.md).


## Phase 5

JSON format 5 / SQLite user_version 5 / MySQL-MariaDB V5__recovery.sql добавляют полный recovery ledger; V1–V4 сохранены. Portable StorageSnapshot имеет дополнительный RecoverySnapshotData и прежние constructors. Source/target backups и pending/equality/last-step config activation StorageAdmin охватывают программы, activity/progress, rewards, receipts/audit и весь прежний private/tier state. Все шесть направлений проверяются отдельно с MySQL и MariaDB. Перед обновлением остановите writers и сохраните полный storage и recovery.yml/GUI/локали. Подробнее: [RECOVERY.md](RECOVERY.md).


## Phase 6

JSON format6 / SQLite schema6 / MySQL-MariaDB V6__administration.sql добавляют отдельный canonical checksummed admin ledger, voting locks и policy overrides. V1–V5 сохранены; старые portable constructors и snapshots поддерживаются. Все шесть migration directions охватывают полный Phase 1–6 state с backups/pending/full verification/last activation/source retention. Backup и точная aggregate модель: [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md).

## Phase 7

Private `/repadmin` investigation/moderation and GUI, administration.yml and schema/format 7 are documented in [ADMINISTRATION](ADMINISTRATION.md), [INVESTIGATION](INVESTIGATION.md) and [AUDIT](AUDIT.md). Public API class files are preserved; sensitive internal contracts are not registered as player services or PAPI. Historical SQL V1–V6 remain unchanged.
