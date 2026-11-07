# Администрирование 1.0.1

Paper 26.2 / Java 25, JSON / SQLite / MySQL / MariaDB. Все команды Phase 6 сохранены. Обычный игрок не получает административных прав или доступа к Trust. `main` не используется для разработки. Релиз 1.0.0 включает hardening Phase 8; результаты приведены в PHASE_8_TEST_REPORT.md.

## Команды и права

Каждый node начинается с `dexturreputation.admin.`, default `op`. Внутреннего универсального bypass нет. Console хранится отдельно от UUID администратора. Цели — известные Paper online/offline profiles; неизвестные имена не отправляются в Mojang. Vote/operation/flag ID — полный UUID.

| Команда `/repadmin …` | Node |
| --- | --- |
| `inspect <player>` | `inspect` |
| `history <player> [votes\|admin\|trust\|recovery\|flags]` | `history`; Trust/flags дополнительно требуют соответствующее inspect право |
| `vote <voteId>` | `vote.inspect` |
| `invalidate <voteId> <reason>` | `vote.invalidate` |
| `restore <voteId> <reason>` | `vote.restore` |
| `pending [player]` | `pending.view` |
| `pending approve <voteId> <reason>` | `pending.approve` |
| `pending reject <voteId> <reason>` | `pending.reject` |
| `trust <player>` | `trust.inspect` |
| `flags <player>` | `flags.inspect` |
| `flag <flagId> SUSPICION\|PENDING_REVIEW\|CONFIRMED_ABUSE\|FALSE_POSITIVE <reason>` | `flags.resolve` |
| `relations <player>` | `relations.inspect` |
| `recalculate reputation\|trust\|tier\|all <player> [reason]` | отдельное `recalculate.reputation`, `.trust`, `.tier`, `.all` |
| `audit <player>` / `audit operation <operationId>` | `audit` |
| `gui <player>` | `gui` плюс право каждого открываемого раздела/действия |
| `bulk …` / `confirm <token>` | `bulk` плюс право выполняемой mutation |

Прежние `set/add/remove`, `voting lock/unlock/status`, `policy`, `reload` продолжают работать. Их точная семантика и права — [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md). `reload` перечитывает только features.yml; administration.yml требует restart.

## Состояния голосов

Ни одна административная операция не удаляет VoteEntry и не создаёт поддельный голос. Исторические base/effective weight, calculationVersion, requestHash и дробные units сохраняются. Публичный communityScore остаётся прежней HALF_UP проекцией шестизначного accumulator.

- **Invalidate active:** удаляется точный подписанный активный вклад, например −0.35 превращается в delta +0.35, а не +1. Репутация, precision, pair state, assessment, tier/hysteresis и audit фиксируются вместе.
- **Invalidate pending:** новый вклад не возникает; прежний активный голос при pending replacement сохраняется. Pending остаётся в истории с INVALIDATED.
- **Invalidate replaced/inactive:** историческая запись получает moderation marker; текущая репутация не меняется. Инвалидация исторического REVOKE также инертна.
- **Повтор:** одинаковый operation UUID/payload возвращает duplicate без повторного эффекта. Другой payload с тем же UUID отклоняется. Новый запрос к уже INVALIDATED голосу аудируется как NO_CHANGE без changed-события.
- **Restore:** только последний голос пары voter/target. Исходные SUPERSEDED/REVOKED не восстанавливаются; конфликт с другим активным голосом запрещён. Исходный PENDING возвращается в PENDING, сохраняя prior active. Принятый голос получает свой исторический точный вес, без применения новой модели или повышения pair ceiling. История инвалидаций остаётся.
- **Approve:** только актуальный pending; прежний активный вклад заменяется точным pending весом атомарно. **Reject:** pending исторически инвалидируется, prior active сохраняется. Restore rejected возвращает pending и требует отдельного approval.

Порядок concurrent commits определяет результат. Invalidate старого голоса после replacement не затрагивает новый. Approval после invalidation отклоняется. GUI дополнительно передаёт ожидаемую ревизию; устаревший запрос отклоняется вместо применения к изменившимся данным.

## Перерасчёты

`reputation` воспроизводит committed точный accumulator из authoritative baseline/журнала переходов, сохраняя исторические веса, system/recovery и admin contribution. Для прежнего журнала v1 используется его исходный baseline и активные pair contributions. Профиль без истории сохраняет импортированный baseline Phase 1. Это исправление проекции; повреждённый checksum/journal не обходится и не «лечится» выдуманными числами.

`trust` использует поддерживаемую модель 1 и последние сохранённые проверенные maturity facts. Offline факты не выдаются за новое измерение playtime; online maintenance по-прежнему получает реальные Paper statistics. Invalidated influence пересчитывается по текущим статусам, confirmed penalties и recovery rehabilitation сохраняются. Revision checks и чтение внутри owner transaction предотвращают stale writes; время queued запроса нормализуется не раньше уже сохранённого Trust timestamp. Неизвестная модель блокирует чтение/перерасчёт.

`tier` сохраняет репутацию и историю голосов, согласует thresholds/hysteresis и заново ставит desired LuckPerms synchronization в очередь. `all` последовательно проверяет reputation, пересчитывает Trust, согласует tier/LP intent в одном commit; после commit обновляет profile/policy/GUI/PAPI caches и запрашивает health reconciliation. Операции с одним профилем используют те же locks, что vote/recovery/admin. Внешние Bukkit/LP вызовы выполняются после SQL commit.

## Bulk, dry-run и подтверждения

```text
/repadmin bulk invalidate voter Player причина --dry-run
/repadmin bulk invalidate target Player причина --type=DISLIKE
/repadmin bulk invalidate pair Voter Target причина
/repadmin bulk invalidate flag <flagId> причина
/repadmin bulk restore target Player причина --dry-run
/repadmin bulk recalculate target Player причина --dry-run
/repadmin confirm <token>
/repadmin bulk status <operationId>
/repadmin bulk resume <operationId>
/repadmin bulk cancel <operationId>
```

Scope обязателен: voter, target, направленная pair или конкретный flag. Команды «вся база» нет. Cluster review можно сузить `--source=DENSE_RECIPROCAL_GROUP` вместе с явным игроком; корреляция не является подтверждением вины. Массовый `recalculate` выполняет full reconciliation для уникальных выбранных targets; явно указанный target без голосов также допустим.

Dry-run и реальное исполнение используют один selector. План возвращает выбранные UUID, число игроков, точную ожидаемую публичную delta, ожидаемые tiers и pending changes. Для recalculation это предварительный план проверки проекции; фактическое исправление выводится в audit. Dry-run не пишет состояние, audit, tokens или metadata.

По умолчанию максимум 200 выбранных записей, batch 10, confirmation threshold 10, token TTL 60 seconds. Выбор сверх cap отклоняется целиком. Для больших операций token привязан к UUID/CONSOLE, полному неизменяемому плану и одноразовому operation ID. Другой администратор не потребляет token; истёкший/использованный token не действует. Изменившийся до подтверждения профиль/vote/flag делает план stale. Tokens существуют только в памяти и не восстанавливаются после restart.

После начала сохраняются выбранные UUID, payload, operation ID и cursor. Каждый **batch** атомарен вместе с cursor и аудитом; весь bulk не является одной неограниченной транзакцией. Успешные пакеты остаются после cancel/failure. Ошибка откатывает текущий пакет; metadata получает PAUSED и безопасный код ошибки, затем требуется явный resume. IDs элементов детерминированы как operation/index. Повтор завершённой операции не повторяет mutation или completion event. Ошибка неопределённой JSON записи блокирует backend; нельзя продолжать до проверки файлов. UI сообщает operation ID до исполнения, чтобы можно было проверить результат после разрыва соединения.

Продолжение принадлежит исходному actor и требует прав bulk/action. Перезапуск сервера не возобновляет bulk автоматически; выполните status/resume. Отключение плагина останавливает постановку следующих пакетов, принятые транзакции дренируются. Обычные votes/recovery могут выполняться между пакетами; каждый пакет работает с актуальным состоянием. Небезопасное restore после replacement откатывает пакет и требует нового решения администратора.

## GUI

`/repadmin gui Player` открывает главное меню на 27 слотов. История, доверие, сигналы, связи, реабилитация, изменения, аудит и перерасчёт имеют отдельные адаптивные экраны без повторных кнопок чужих разделов. Загрузка асинхронна; inventory изменяется на main thread. Legacy layout валидируется, но геометрия определяется назначением экрана. [Размеры, аудит участников и фильтры](ADMIN_UX.md).

Votes: ЛКМ открывает детали; в деталях ЛКМ предлагает invalidate/restore. Pending: ЛКМ approve, ПКМ reject. Flags: ЛКМ false positive, ПКМ confirmed classification. Recalculate содержит четыре отдельных действия. Каждая mutation открывает отдельное подтверждение с UUID, причиной и operation ID. GUI-причина — явно показанное локализованное «Подтверждено администратором в GUI»; произвольную причину вводите через команду.

Подтверждение ограничено TTL, owner, session и fingerprint; permission проверяется повторно. Busy gate, click debounce и ожидаемые ревизии предотвращают spam/double execution. Item movement, shift, drag, hotbar/offhand transfers отменяются. Refresh, next/previous, cancel/close предусмотрены; quit/disable очищают sessions. Право gui само по себе не открывает Trust или mutations.

## Конфигурация и эксплуатация

Полный пример — `reputation-paper/src/main/resources/administration.yml`: page size 14 (1..28), maximum bulk 200 (hard 1000), batch 10 (hard 50), TTL 60 (5..600), graph/relations/recalculation budgets, console allowance, reason policy, audit limits и GUI layout. Причина bulk обязательна всегда; destructive audit отключить нельзя. Для recalculate без текста сохраняется явная локализованная стандартная причина. Config defaults добавляются в памяти; существующие файлы не перезаписываются.

[INVESTIGATION](INVESTIGATION.md) описывает фильтры и диагностику; [AUDIT](AUDIT.md) — хранение, retention, backups и миграции; [PHASE_7_TEST_REPORT](PHASE_7_TEST_REPORT.md) — фактически выполненные проверки.

Большой dry-run показывает players/deltas/tiers страницами (`--page=N`), по configured page size; полный selection и token остаются связанными со всем планом. Inspect выводит overrides отдельными paginated rows, сохраняя summary компактным. Для полного отдельного audit record используйте `audit operation UUID`.

Restore поддерживает также исторические Phase 3 invalidations: исходный статус извлекается из сохранённых VOTE/ACCEPT/review audit records. Старые confirmed Trust penalties и ограничения при этом не удаляются.

## Новый интерфейс управления игроком

`/repadmin gui <игрок>` теперь использует отдельные понятные значки, компактную сводку, доступ к голосованию и начальный bypass, выданные оценки и постраничные технические подробности. Изменения требуют подтверждения. Справка разбита на три страницы. [Меню, force и уведомления](ADMIN_UX.md).
