# Аудит и хранение — Phase 7

## Схема и атомарность

JSON snapshot format **7**; SQLite user_version **7**; MySQL/MariaDB additive `V7__investigation.sql`. V1–V6 и SQLite historical migrations сохранены. Public API class-файлы Phase 6 не меняются. Старые snapshot constructors сохранены, отсутствие investigation означает пустое новое состояние.

В portable snapshot добавлен `investigation.records`: moderation markers, append-only audit, flag resolutions, bulk operation payload/selected IDs/cursor/status и low-risk diagnostics. SQL хранит canonical payload + SHA-256 и дублируемые проверяемые индексы kind/UUID/scope/actor/target/vote/status/revision/time. Индексы покрывают vote lookup, player history, operation ID, flags/status и retention; pending использует существующий assessment status index. Несовпадение payload/index/checksum блокирует startup.

Mutation фиксирует profile/precision/pair/assessment, tier/hysteresis/LP desired state и audit одной транзакцией либо одной JSON generation. SQLite сохраняет WAL/FULL, busy timeout и serialized writes; MySQL/MariaDB — pool, owner lock, prepared statements и transactions. Нет fallback. Реальные LP/health effects следуют после commit.

## Записи и lookup

Каждая Phase 7 mutation имеет audit/operation UUID, UUID/CONSOLE actor, scope/target, action, reason, timestamp, schema 1, request fingerprint, expected revision, before/after reputation/community/system/tier/Trust/revisions и связанные vote IDs. Private protection transition также хранит exact removed/added units и old/new active IDs. Повтор ID/payload не повторяет audit/effect; конфликт ID отклоняется. NO_CHANGE сохраняется как отдельное объяснимое административное решение.

`audit Player` — индексированная общая история; `audit operation UUID` — lookup. Phase 6 lock/set/add/remove/policy и прежние recovery/review записи получают индексированные копии с namespaced ID. Их исходные ledgers не меняются. Lookup поддерживает и оригинальные IDs; original operation ID и legacy actor label сохранены. Старые recovery records могли иметь метки PLAYER/ADMIN/provider вместо UUID: индексированная копия сохраняет исходную метку, PLAYER сопоставляется target, непреобразуемая метка отображается SYSTEM. Новый Phase 7 actor всегда UUID или CONSOLE; новые вызовы прежнего recovery admin command также сохраняют эту устойчивую идентичность. Историческую отсутствующую личность администратора нельзя достоверно восстановить.

Начальная индексация выполняется до readiness. Если история превышает configured cap/JSON bytes, startup останавливается без backend fallback. Увеличение лимита — явное решение оператора после backup; новая схема и уже успешно построенные SQL индексы остаются для возобновляемого startup.

Bulk имеет один устойчивый ID и детерминированные IDs элементов. Start/completion/cancel, cursor, PAUSED/error и каждый destructive effect сохраняются. Ошибка batch откатывает этот batch и его cursor; диагностическая metadata failure фиксируется отдельно после rollback, без raw SQL/credentials. При неопределённом JSON commit backend блокируется и может не записать дополнительную диагностику: проверяйте файлы, а не повторяйте начисления вслепую.

## Retention и рост

Destructive audit, vote transitions, admin adjustments, moderation markers, flag resolutions и bulk metadata **автоматически не удаляются**. Они объясняют текущее состояние и обеспечивают идемпотентность. По умолчанию максимум 100000 новых indexed records (hard cap 1000000), после достижения mutations fail closed. Phase 6 per-player admin history cap 10000 остаётся.

Только low-risk DIAGNOSTIC failure records имеют configurable `diagnostic-retention-days` (default 30). Они очищаются при startup и безопасных последующих write boundaries; чтение/dry-run не запускает cleanup. Это retention при обслуживании, не обещание удаления ровно в заданную секунду. Чтения не создают бесконечный diagnostic audit.

JSON уже загружает snapshot в память и переписывает его целиком; cap 64 MiB сохранён. Новые administrative warnings используют меньший из старого anti-abuse порога и `audit.json-warning-percent` (default 75), раз в maintenance interval. SQL не читает весь vote journal для страницы, но payload histories и indexed legacy audit copies растут. Нужны наблюдение за размером, внешнее архивирование и backups. Автоматического pruning доказательств нет.

## Backup, миграция и восстановление

Остановите **всех** writers. Сохраните выбранное storage, все YAML/locales и schema history; LuckPerms копируется независимо. JSON — полная directory с state/bak/tmp/checksums; SQLite — stopped copy с WAL handling либо backup API; SQL — consistent transactional dump. Не редактируйте вручную checksums, audit chains, operation IDs, reward receipts или pending markers.

```sh
java -jar DexturReputation-StorageAdmin.jar migrate \
  --data-directory /srv/paper/plugins/DexturReputation \
  --target-config /srv/maintenance/storage-target.yml \
  --backups /srv/backups/reputation \
  --confirm STOPPED-ALL-WRITERS --language ru_RU
```

Все шесть направлений JSON ↔ SQLITE ↔ MYSQL включают profiles/votes/precision/Trust/pending/flags, tiers, recovery/rewards/activity/receipts, policy/admin, moderation audit и bulk cursor/payload. Проверяется полный portable snapshot, конфигурация активируется последней, источник не удаляется. MySQL и MariaDB проверяются отдельно. Tokens GUI/CLI confirmation ephemeral, не входят в migration; уже начатые durable bulk операции входят и требуют явного resume на новом storage.

Downgrade требует полного pre-upgrade backup. JSON atomic replace/checksums/fsync не равны возможностям транзакционной SQL БД и не гарантируют физический power-loss на произвольной FS. Базовые команды recovery/backups — [STORAGE](STORAGE.md).

## События

Внутренний `InvestigationApplication.onCommitted` публикует VoteInvalidated, VoteRestored, PendingVoteReviewed, ReputationRecalculated, TrustRecalculated, остальные typed action names и AdministrationBulkOperationCompleted. Только после commit; duplicate и NO_CHANGE не повторяют changed effects. Listener failures не отменяют committed outcome. Callbacks работают на IO worker, не являются durable outbox. Bulk ограничен batch size; Bukkit/LP handlers работают вне SQL transaction. После crash возможна потеря уведомления, поэтому внешние эффекты сверяют persisted revisions/IDs.
