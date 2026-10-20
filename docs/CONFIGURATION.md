# Конфигурация Phase 3

Создаются `config.yml`, `storage.yml`, `integrations.yml`, `voting.yml`, `gui.yml`, `trust.yml`, `anti-abuse.yml`, `lang/ru_RU.yml`, `lang/en_US.yml`.
Файлы администратора не переписываются. Неизвестные версии, дубликаты YAML, неверные типы,
пустые обязательные строки, невалидные границы/разметка/placeholder-наборы отклоняются.

## Основные параметры (`config-version: 1`)

| config.yml | Default | Диапазон |
|---|---|---|
| scope | global | `[a-z0-9_-]`, 1–32 |
| default-language | ru_RU | ru_RU / en_US |
| cache-maximum-size | 10000 | 1–100000 на кэш |
| cache-ttl-seconds | 60 | 1–3600 |
| database-workers | 2 | 1–16; MYSQL не больше pool size |
| database-queue-size | 256 | 1–10000 |
| shutdown-timeout-seconds | 15 | 1–60 |

Названия database-workers/queue сохранены для совместимости; они управляют IO workers
всех backend. File IO/SQL не выполняются на основном игровом потоке.

## Storage

Полный пример и процедуры — [STORAGE.md](STORAGE.md). Новый `storage.yml` имеет version 2,
`storage.type: JSON`. Legacy version 1 без selector означает MYSQL и продолжает работать
без изменения. Единственный selector может быть только JSON, SQLITE, MYSQL.

| Параметр | Default | Диапазон |
|---|---|---|
| storage.json.directory | storage/json | относительный путь внутри data folder |
| storage.json.maximum-file-bytes | 67108864 | 1024–268435456 |
| storage.sqlite.file | storage/reputation.sqlite | относительный путь внутри data folder |
| storage.sqlite.busy-timeout-ms | 5000 | 250–30000 |
| storage.mysql.host / port | localhost / 3306 | DNS/IPv4, 1–65535; не JDBC URL |
| storage.mysql.database / username | dexturreputation | database `[A-Za-z0-9_]`, 1–64; username непустой |
| storage.mysql.password-environment | DEXTURREPUTATION_DB_PASSWORD | имя переменной; значение нужно только MYSQL |
| storage.mysql.ssl-mode | verify-full | verify-full / verify-ca / disable |
| storage.mysql.maximum-pool-size | 2 | 1–16; плюс отдельная ownership-сессия |
| storage.mysql.connection-timeout-ms | 5000 | 250–30000 |
| storage.mysql.max-lifetime-ms | 1800000 | 30000–3600000 |

Все секции в поставляемом полном конфиге валидируются, включая неактивные. Для v1 MYSQL
параметры находятся в корне файла, как в Phase 1. JSON/SQLITE не требуют DB credentials.
MYSQL принимает настоящий process password, не HTTPS proxy placeholder. Default SSL проверяет
цепочку и hostname; настройте Java truststore своего CA. JDBC socketTimeout — 10000 ms, UTC.

`integrations.yml` version 1: `placeholderapi: true`; отсутствие PAPI безопасно.
Команды/default permissions объявлены в plugin.yml; узлы голосования переопределяются в voting.yml. Добавлена диагностическая подкоманда `storage`,
она не выполняет перенос онлайн и не делает блокирующего healthcheck.

## Локализация

Файлы языков остаются version 1. Шаблоны MiniMessage доверены администратору; dynamic значения
вставляются как plain Components. Действуют prefix, RU/EN, per-player язык, locale number formatting.
Новые `storage-*` ключи поставляются в обоих языках. Для старых lang-файлов недостающие новые
ключи берутся из bundled defaults без перезаписи custom messages; отсутствующие обязательные
старые ключи по-прежнему вызывают ошибку. Offline CLI использует bundled RU/EN.

## Изменения persistent state

Reload в этой фазе нет. Настройки целиком применяются после restart; backend активируется
только после успешного открытия/валидации. Смена type/пути/БД вручную **не переносит** данные:
используйте offline StorageAdmin. Изменение scope создаёт отдельный набор профилей, старый
сохраняется; выбранный язык общий по UUID. Смена default-language не меняет сохранённый выбор.
Кэши очищаются при shutdown, их лимиты не меняют persistent values. Не редактируйте version/checksum
миграций или pending marker вручную. Старые данные после успешного переноса не удаляются.

## Phase 2

Полная таблица `voting.yml` и правила `gui.yml` — [VOTING.md](VOTING.md). Отсутствующие файлы
создаются из defaults, существующие не переписываются. При обновлении Phase 1/1.1 новые
`vote-*`, `gui-*`, `reason-*` ключи подставляются из bundled RU/EN. Custom messages сохраняются.
Причины пользовательского администратора должны ссылаться на существующие переводы без
placeholders. Новые настройки валидируются до открытия backend. Нет online reload.

## Phase 3

Полные комментированные defaults — `trust.yml` и `anti-abuse.yml` в ресурсах. Для каждого
отсутствующего поля берётся bundled default **в памяти**, custom YAML не переписывается.
Оба файла version 1. Неверный тип/диапазон/комбинация блокирует enable до открытия backend.
Все изменения требуют restart. Множители действуют только на новые оценки, исторические
веса не пересчитываются. Периодический Trust refresh меняет только будущую оценку Trust.
Массовый пересчёт вклада старых голосов в этой фазе не предоставлен.

| Группа | Default / допустимые границы |
|---|---|
| trust.enabled / model-version | true / только 1 |
| trust.score minimum / maximum / initial | 0 / 1 / 0.50; 0 ≤ min ≤ initial ≤ max ≤ 1 |
| maturity enabled, full age / playtime | true, 90 суток / 200 часов; 1..36500 / 1..1000000 |
| maturity influence | 0.20; 0..1 |
| history enabled, invalidated / diversity / repeat penalty | true, 0.30 / 0.05 / 0.10; 0..1 |
| abuse-penalties enabled, confirmed penalty / restriction | true, 0.25 / 1440 минут; 0..1 / 0..525600 |
| recalculation enabled / interval / batch | true / 60 минут / 20; 1..10080 / 1..100 |
| voting-weight enabled, base like/dislike | true, 1.0/1.0; 0.000001..1000000 |
| effective-weight min/max | 0..1.50; 0 ≤ min ≤ max ≤ 1000000 |
| trust-multiplier enabled / min/max | true / 0.40..1.25; 0 ≤ min ≤ max ≤ 10 |
| diversity enabled / min/max / full-credit-voters | true / 0.75..1 / 8; [0,1], min≤max; 1..10000 |
| account-maturity enabled / min/max | true / 0.50..1; [0,1], min≤max |
| repeated-target / reciprocity enabled / window | true / 30 суток; 1..3650 |
| multipliers first,second,third,fourth,subsequent | 1, .70, .35, .10, 0; пять невозрастающих [0,1] |
| reciprocity minimum-interactions-for-flag | 4; 2..10000 |
| clusters enabled / window / interval | true / 30 суток / 30 сек; 1..3650 / 1..86400 |
| clusters maximum-events / nodes / edges | 2048 / 128 / 4096; 16..10000 / 4..256 / 12..65536 |
| clusters maximum-work / milliseconds | 20000 / 100; 100..1000000 / 1..1000 |
| clusters minimum-members / density / mutual-density | 4 / .75 / .75; 4..nodes / (0,1] / (0,1] |
| clusters confidence-multiplier | .50; [0,1] |
| burst enabled / window / count | true / 30 минут / 10; 1..10080 / 2..maximum-events |
| burst immature-threshold / weak-voter-fraction | .30 / .50; [0,1] / (0,1] |
| mark-suspicious / allow-pending / automatic-permanent-punishment | true / true / только false |
| rapid-change enabled / window / count / weak-voter-fraction | true / 30 минут / 12 / .50; как burst |
| storage.json-warning-fraction | .75; (0,1], доля maximum-file-bytes для предупреждения |

Десятичные значения допускают до 6 знаков без потери точности; NaN, Infinity, overflow и
лишняя ненулевая точность отклоняются. Булевы параметры не выбирают backend: единственный
selector остаётся `storage.type`. `voting-weight.enabled: false` сохраняет номинальные веса
voting.yml, но **не отключает** detection/pending/restriction и сохранённый lifetime ceiling. Trust.enabled=false
даёт initial для будущего Trust и отключает его multiplier; отдельные detection switches
управляют сигналами. Нельзя использовать изменение конфига для удаления старых данных.
Подробная политика: [TRUST.md](TRUST.md), [ANTI_ABUSE.md](ANTI_ABUSE.md).

## Phase 4 configuration

See the complete commented shipped examples `tiers.yml`, `display.yml`, `integrations.yml` and `gui.yml`, plus [TIERS.md](TIERS.md), [DISPLAY.md](DISPLAY.md) and [LUCKPERMS.md](LUCKPERMS.md). New files are created only when absent; missing new fields in existing files receive in-memory bundled defaults without rewriting customizations. Configuration version remains 1 for these additive files. Restart applies tier policy changes through controlled reconciliation before API readiness. No historical vote weights/Trust are recalculated by changing display/tier settings.

Safe defaults: configured tiers enabled; nameplates DISABLED; TAB PLACEHOLDER_ONLY; LuckPerms disabled with group auto-creation disabled; chat notifications enabled with ten-second cooldown. New tier/display/GUI references, names/markup, bounds, intervals, visibility/ownership modes and permission group namespace are validated. A configuration error blocks readiness with the existing localized configuration diagnostic. Do not enable DIRECT alongside another list renderer. To render nameplates through an existing TAB owner, consume the PAPI values and keep the internal nameplate adapter disabled.


## Phase 5

Добавлен recovery.yml version 1; полная схема, defaults и диапазоны — [RECOVERY.md](RECOVERY.md). gui.yml.recovery-panel и integrations.yml.recovery-placeholders объединяют defaults в памяти. Старые файлы/локали не переписываются. Новые recovery-* ключи RU/EN получают additive fallback. Активные определения сохраняются immutable на enrollment; restart применяет изменения только новым программам (текущие safety caps могут ужесточиться). При disabled startup устойчиво ставит активные программы на PAUSED, включая offline.


## Phase 6

features.yml version1, administration bounds/reason, ordered policies и optional health: [POLICIES](POLICIES.md), [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md), [HEALTH](HEALTH.md). gui.yml.policy-panel и integrations.yml.policy-placeholders имеют additive defaults без перезаписи файлов. /repadmin reload применяет только полный валидный features candidate; при ошибке остаётся прежний. Остальные файлы требуют restart.
