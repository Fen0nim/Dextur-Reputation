# Основное администрирование — Phase 6

Это ограниченный набор операций для эксплуатации. Investigation GUI, массовая инвалидация, dashboards и полный Phase 7 не реализованы.

## Точная семантика оценки

Прежний публичный API сохранён: `visibleScore = communityScore + systemScore`. `communityScore` остаётся существующей целой проекцией точного шестизначного community accumulator (HALF_UP); его дробные units, веса, pending и журнал голосов не переписываются. Совместимый `systemScore` теперь агрегирует legacy/system/recovery **и** административный вклад. Отдельный `AdministrationData` ledger хранит admin contribution; источник recovery по-прежнему воспроизводится своим независимым ledger. Числа Trust не входят в эту формулу.

| Команда | Действие |
| --- | --- |
| `/repadmin set <игрок> <целое> <причина>` | Delta = target − текущий visibleScore; прибавляет delta к admin contribution. Результирующая публичная целая оценка точно равна target. |
| `/repadmin add <игрок> <signed amount> <причина>` | Прибавляет указанное signed число к admin contribution и visibleScore. |
| `/repadmin remove <игрок> <magnitude> <причина>` | Вычитает строго положительную величину из admin contribution и visibleScore. Ноль/отрицательный аргумент запрещён. |

Пример: community=40, system/recovery=10, admin=0 → set −100 создаёт admin=−150 и visible=−100. Последующее add +25 даёт −75; add −50 даёт −125; remove 25 даёт −150. Set не является постоянной фиксацией: последующие настоящие голоса/recovery награды снова меняют visible.

При community accumulator 0.35 публичная community-проекция равна 0. Set 100 создаёт целый admin offset 100, не стирая 0.35. Следующий голос 0.35 приводит к community 0.70 → публичной проекции 1 и visible 101. Сохраняется прежняя архитектура округления, а не вводится новый режим суммирования дробного public total. Ввод — только signed base-10 integers; вся арифметика exact, преобразование в units проверяет overflow до записи. Исторические votes/recovery/Trust не используются как административные поддельные голоса.

## Права, пределы, offline

Каждая команда проверяет отдельный node, default op: `dexturreputation.admin.set`, `.add`, `.remove`, `.voting.lock`, `.voting.unlock`, `.voting.status`, `.policy`, `.reload`. Внутреннего wildcard bypass нет. Console записывается как `CONSOLE`, игрок — канонический UUID. Имена разрешаются только по online/cached known Paper profiles; неизвестное имя/UUID не вызывает запрос к Mojang и отклоняется. Команды допускают известного offline игрока. UUID полезен при смене имени.

```yaml
config-version: 1
administration:
  reputation:
    minimum-visible-score: -100000
    maximum-visible-score: 100000
    maximum-single-adjustment: 10000
    require-reason: true
```

Конфигурационные minimum/maximum должны включать 0, minimum < maximum, single > 0; все значения проверяются также на безопасное преобразование в шестизначные units. Limits применяются к административному изменению, не пересчитывают старые audit records. Maximum-single-adjustment ограничивает абсолютную **delta**, включая set. Поэтому set из 0 в 100000 не разрешён при single=10000. Обычные votes/recovery не обрезаются этими административными bounds. Причина до 1000 символов; по умолчанию обязательна для всех mutations, включая lock/override. Отключение require-reason допускается как явный выбор оператора: actor, ID, параметры, время и исход/итог всё равно сохраняются, пустая причина не подставляется чужим текстом. Одна пара внешних кавычек в reason команды снимается. Public responses не раскрывают закрытые Trust factors.

## Voting locks и overrides

```text
/repadmin voting lock Player причина
/repadmin voting unlock Player причина
/repadmin voting status Player
/repadmin policy Player chat.send deny причина
/repadmin policy Player chat.send reset причина
```

Voting lock относится к UUID/scope, блокирует новые likes/dislikes и revoke; ранее зафиксированный operation ID подтверждается как duplicate. Прежние голоса не удаляются. Unlock снимает только admin lock; все permissions/playtime/server age/cooldown/quota/anti-abuse проверки остаются. Повтор lock с другим ID оставляет тот же boolean effect и аудирует запрос; изменившееся состояние получает after-commit effect один раз. Reason для status не нужен. Overrides policy-specific, сохраняются до reset и не обходят voting lock, emergency baseline, storage integrity или специализированную voting security pipeline. [Политики](POLICIES.md).

## Аудит и конкуренция

Каждая принятая mutation имеет schema/model 1, stable operation UUID, target UUID/scope, actor UUID/CONSOLE, type, requested amount/target/override, previous/resulting visible и admin contribution, community projection, non-admin system contribution, previous/resulting lock/override maps, применённые bounds, reason и UTC timestamp. На UUID/scope сохраняется последовательная проверяемая цепочка; ограничение 10000 audit operations fail-closed, без автоматического удаления и сброса истории.

Повтор **того же ID и payload** возвращает duplicate без второй записи/эффекта; другой payload с этим ID отклоняется. Timestamp retry не меняет идентичность payload. При обработке время аудита берётся не раньше последнего committed profile/audit timestamp: более ранний момент постановки запроса в очередь не отклоняет корректную конкурентную операцию. Это идемпотентность на UUID/scope. Обычная повторная команда создаёт новый ID; повтор `add 25` — новое явное начисление. После неопределённой ошибки сначала проверьте результат/backup, не повторяйте add вслепую.

Административная запись, aggregate/profile, tier/hysteresis и LP desired synchronization metadata сохраняются одной SQL transaction либо одним JSON atomic replacement. Кэши и local events обновляются после commit; rollback не обновляет cache. Реальная внешняя LuckPerms запись остаётся асинхронной, с прежними retries и ownership правилами. Trust автоматически не пересчитывается из-за admin score.

Backend owner сериализует mutations; application использует те же profile locks, что vote/review/recovery. Competing operations имеют порядок commit: если vote первым, set учитывает его current public score; если set первым, последующий vote прибавляет свой обычный эффект. Add и recovery reward не теряют друг друга; lock, зафиксированный первым, запрещает последующий новый vote. Policy gate делает только чтение и не создаёт отдельный профиль до vote transaction. Мультисерверные writers не поддерживаются; прямое внешнее редактирование SQL/JSON вне владельца запрещено.

## Storage и перенос

JSON format 6, SQLite user_version 6, MySQL/MariaDB additive `V6__administration.sql`, `dr_administration` с UUID/scope/revision/canonical payload/SHA-256. V1–V5 сохранены. Старые snapshots читаются с пустым admin ledger; прежние portable snapshot constructors сохранены. JSON checksum/atomic replace/backup/owner locking/64 MiB limit действуют и для новых данных. SQL не делает commit между изменением profile и ledger/tier.

Остановите **всех** writers и сохраните весь storage, schema history и все YAML/обе локали перед обновлением/миграцией. Для SQLite используйте stopped copy с WAL handling либо backup API; для MySQL/MariaDB — consistent transactional dump; JSON сохраняйте с bak/tmp/checksums. LuckPerms backup независим. Downgrade требует полного pre-upgrade backup: старый writer не поддерживает format/schema 6.

```sh
java -jar DexturReputation-StorageAdmin.jar migrate \
  --data-directory /srv/paper/plugins/DexturReputation \
  --target-config /srv/maintenance/storage-target.yml \
  --backups /srv/backups/reputation \
  --confirm STOPPED-ALL-WRITERS --language ru_RU
```

Все шесть направлений JSON ↔ SQLITE ↔ MYSQL передают полный admin/lock/override ledger вместе с votes, pending, precision, Trust, tiers, recovery, receipts и языками. Verification сравнивает полный portable snapshot до последней активации storage.yml. Неполный перенос не включается, источник автоматически не удаляется. Форматы target и secret env — [STORAGE](STORAGE.md). Сам CLI никогда не устанавливается как Paper plugin.

JSON переписывает весь snapshot; SQL — полный admin payload одного UUID/scope. История растёт, автоматического pruning нет. Пределы не обещают production SLA; обслуживание должно сохранять audit/receipts/aggregate consistency. Физический power-loss на произвольной файловой системе не гарантируется. Локальные after-commit уведомления не являются durable distributed queue.
