# Расследования — Phase 7

Все сведения этого документа приватны для административных permissions. Они отсутствуют в обычном Reputation API, policy results, player GUI и PlaceholderAPI. Корреляция, suspicion и pending не означают доказанную манипуляцию или alternate accounts.

## Inspect и детализация

`/repadmin inspect Player` показывает UUID/последнее известное Paper имя, visible/community/system, отдельный administrative contribution, recovery rewards и прежний system baseline, tier/hysteresis/LP intent, положительные/отрицательные/активные/invalidated/pending counts, distinct historical voters, lock, recovery status, policy overrides, Trust summary, unresolved flags, timestamps и profile revision. Counters считаются в согласованном owner read; SQL использует индексы и aggregate queries.

`vote <UUID>` показывает voter/target UUID, тип/причину/комментарий, время, состояние, исторический base и точный signed effective contribution, calculationVersion, Trust/reciprocity/repeat/diversity/maturity diagnostics, severity/signals, predecessor operation и moderation audit. Имена известных игроков доступны через inspect; UUID остаётся устойчивой идентичностью после переименования. Отрицательный exact contribution — signed вес DISLIKE, а не отрицательный множитель.

История разбита на votes/admin/trust/recovery/flags и общий audit. По умолчанию 14 записей на страницу, `--page=2`; GUI также листает страницы. SQL LIMIT/OFFSET и индексированные ranges не загружают весь журнал голосов для одной страницы. Большой OFFSET и точный COUNT могут требовать чтения большого диапазона индекса игрока; это IO worker, не main thread.

## Фильтры и поиск

```text
/repadmin pending --target=Player --type=DISLIKE --min-age=3600 --severity=HIGH
/repadmin pending --voter=Player --source=NEGATIVE_SPIKE --page=2
/repadmin history Player votes --status=INVALIDATED
/repadmin history Player votes --search=комментарий
/repadmin flags Player --status=FALSE_POSITIVE --source=NEGATIVE_SPIKE
/repadmin audit Player --search=причина
/repadmin bulk invalidate voter Player причина --source=DENSE_RECIPROCAL_GROUP --dry-run
```

Поддержаны `--player`/`--target`, `--voter`, `--flag`, `--type=LIKE|DISLIKE|POSITIVE|NEGATIVE`, `--status`, `--min-age`/`--max-age` в секундах (включительные), `--severity=LOW|MEDIUM|HIGH`, `--source`, `--search`, `--page`. Фильтры votes/pending/bulk используют одну модель. Поиск — буквальная регистрозависимая подстрока длиной до 128: vote UUID/reason/comment; audit/flags — canonical record text. `%` и `_` не становятся wildcard. Для текстового поиска голосов нужен явный player/voter/target/flag, чтобы не обходить весь журнал. Audit/flag поиск ограничен игроком.

Фильтры severity/source относятся к сохранённым detector assessments, не к вине. Состояния flags отличаются от состояний votes. Неверные типы/диапазоны/IDs отклоняются; SQL inputs всегда bind-параметры.

## Trust

`trust Player`: score/model/revision, last calculation и facts timestamps, account/playtime maturity, submitted/invalidated/reliability, confirmed penalties, restrictions, bounded rehabilitation credits, stale indicator. Сама команда ничего не изменяет. Новый global input revision или истёкший интервал делает stale indicator консервативным; это не доказательство corruption.

Инвалидация не переписывает прежний Trust ledger. Отдельный `recalculate trust` обновляет invalidated influence по текущим статусам и поддерживаемой модели, сохраняя confirmed evidence и допустимую rehabilitation. Последние verified offline facts не выдаются за fresh Paper measurements. Administrative reputation set не превращается в Trust grant. Неизвестный historical model version блокирует unsafe recalculation.

## Flags

Flags получают устойчивые ID из vote/source; хранят detector, LOW/MEDIUM/HIGH, исходное и последнее время, evidence summary, voter/target/vote IDs, текущее resolution. Исторические сигналы не удаляются при разрешении.

`SUSPICION` — подозрение; `PENDING_REVIEW` — ожидает проверки; `CONFIRMED_ABUSE` — явная классификация администратора; `FALSE_POSITIVE` — разрешённое ложное срабатывание. `flag …` аудирует actor/reason/revision. Классификация сама не начисляет скрытый штраф, не снимает прежний confirmed penalty и не инвалидирует голоса: это отдельные решения. Старый Phase 3 confirmed-manipulation механизм и его evidence/restrictions сохраняются.

## Relations и clusters

`relations Player` читает bounded incoming/outgoing ranges, по умолчанию максимум 1000 событий и 20 соседей, с явным work budget. Показывает repeated pairs, incoming/outgoing/reciprocity counts, последние часовые события и уже сохранённые detector groups, содержащие игрока. Порядок: число взаимодействий DESC, UUID ASC. Нет неограниченного обхода графа.

Complete=false означает обрезанное окно, а не отсутствие риска. GraphComplete отдельно относится к прежнему anti-abuse detector. Вывод помечен CORRELATION_ONLY. Совпадение паттернов не доказывает alternate accounts; в интерфейсе нет команды, автоматически подтверждающей вину всего кластера. Review/bulk требуют явно выбранного scope, причины и dry-run/confirmation правил.

`history Player trust` объединяет исторические private vote/review observations и новые Trust/full recalculation audits в одной paginated временной ленте. Там, где старая модель не сохраняла точный after-Trust, число не выдумывается; доступны исходное событие, revision и evidence.

Подробные `vote` и `audit operation` выводят длинные поля полностью, частями до 1000 Unicode code points; emoji не разрезаются. Списки и GUI оставляют компактное превью, после которого используется drill-down.
