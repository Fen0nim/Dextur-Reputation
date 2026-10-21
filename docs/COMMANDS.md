# Команды 1.0.0

| Команда | Результат | Доступ |
|---|---|---|
| /dexturreputation | Локализованная справка | Все, включая консоль |
| /dexturreputation help | Справка | Все |
| /dexturreputation version | Версия, Paper/Java target | Все |
| /dexturreputation status | Состояние инициализации, очередь, наличие PAPI | dexturreputation.status |
| /dexturreputation storage | Выбранный backend, без IO/переноса | dexturreputation.status |
| /rep | Свой профиль | Игрок, dexturreputation.use |
| /rep &lt;player&gt; | Профиль онлайн/кэшированного известного игрока | dexturreputation.use |
| /rep language ru_RU | Сохранить русский язык | Игрок, use + language |
| /rep language en_US | Сохранить английский язык | Игрок, use + language |

Status показывает инициализацию сервиса, а не выполняет синхронный healthcheck БД.
Последующая ошибка БД возвращается командой как локализованная ошибка и записывается
без параметров запроса. Подтверждение смены языка отправляется после успешной записи.
Неизвестные/некэшированные имена не запрашиваются у Mojang. `/rep` и смена языка из консоли
возвращают сообщение «только игрокам». Console `/rep <known-player>` поддерживается.
Ошибочные аргументы и отсутствие разрешений обрабатываются локализованно.

Trust/admin команды описаны в ADMINISTRATION.md; reload обновляет только features.yml. Tab completion предлагает подкоманды и
поддерживаемые языки с учётом прав, не раскрывает списки офлайн-игроков.

Перенос и JSON recovery выполняются только offline отдельным StorageAdmin JAR: [STORAGE.md](STORAGE.md).

## Голосование

| Команда | Результат | Право по умолчанию |
|---|---|---|
| /like player reason [comment] | Положительный голос/замена | vote + like |
| /dislike player reason [comment] | Отрицательный голос/замена | vote + dislike |
| /rep revoke player [comment] | Отзыв своей активной оценки | use + vote + revoke |
| /rep history [page] | Собственная полученная история | use + history |
| /rep given [page] | Собственная отправленная история | use + history |
| /rep reasons | Коды причин с переводами и типами | use + reasons |
| /rep gui [player] | Профиль, статистика, действия и история | use + gui |
| /rep help | Справка по голосованию | use |

Узлы в таблице имеют prefix `dexturreputation.` и настраиваются в `voting.yml`.
`/rep [player]` теперь дополняет прежнюю строку профиля статистикой; default остаётся текстовым.
Имена, причины и языки имеют tab completion с учётом прав. Консоль может читать профиль/причины,
но не голосовать и не открывать GUI. На успех отвечает только durable commit. Правила и
поведение неопределённой ошибки — [VOTING.md](VOTING.md).


## Phase 5

/rep recovery открывает отдельный экран; status/start/tasks/progress/rewards/help/cancel — локализованные команды. /rep admin recovery <known-name|UUID> status|start|pause|resume|reset_stage|cancel|lock|unlock [reason] — ограниченное администрирование, включая offline. Eligibility/cooldown сохраняются для admin start. Force reward/Trust overwrite/удаление history не добавлены. Права и примеры: [RECOVERY.md](RECOVERY.md).


## Phase 6

/repadmin set/add/remove; /repadmin voting lock/unlock/status; /repadmin policy allow/deny/reset; /repadmin reload; /rep policies [id]. Полная грамматика, signed integer semantics, UUID/console audit и offline поведение — [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md). Public consequences/GUI — [POLICIES](POLICIES.md). Расследования и массовые операции описаны в ADMINISTRATION.md.

## Phase 7

Private `/repadmin` investigation/moderation and GUI, administration.yml and schema/format 7 are documented in [ADMINISTRATION](ADMINISTRATION.md), [INVESTIGATION](INVESTIGATION.md) and [AUDIT](AUDIT.md). Public API class files are preserved; sensitive internal contracts are not registered as player services or PAPI. Historical SQL V1–V6 remain unchanged.

## Полная административная грамматика

`/repadmin help` выводит основную и расширенную справку. Дополнительные aliases в plugin.yml не объявлены. Все action-specific nodes — [PERMISSIONS](PERMISSIONS.md).

- `set|add|remove <known-name|UUID> <integer> <reason>`; add принимает signed amount, remove — положительную magnitude.
- `voting lock|unlock <known-name|UUID> <reason>`; `voting status <known-name|UUID>`.
- `policy <known-name|UUID> <policy-id> allow|deny|reset <reason>`; `reload` — только features.yml.
- `inspect <player>`; `history <player> [votes|admin|trust|recovery|flags]`; `vote <vote-id>`.
- `invalidate|restore <vote-id> <reason>`; `pending [player]`; `pending approve|reject <vote-id> <reason>`.
- `trust|flags|relations <player>`; `flag <flag-id> SUSPICION|PENDING_REVIEW|CONFIRMED_ABUSE|FALSE_POSITIVE <reason>`.
- `recalculate reputation|trust|tier|all <player> [reason]`.
- `audit <player>`; `audit operation <operation-id>`; `gui <player>` (только игрок).
- `bulk invalidate|restore|recalculate voter|target <player> <reason> [--dry-run]`; `bulk … pair <voter> <target> <reason>`; `bulk … flag <flag-id> <reason>`.
- `bulk status|resume|cancel <operation-id>`; `confirm <token>`.

Списки поддерживают bounded pagination `--page=N` и фильтры согласно [INVESTIGATION](INVESTIGATION.md). Bulk preview/confirmation и границы описаны в [ADMINISTRATION](ADMINISTRATION.md). Аргументы целиком ограничены 8192 UTF-16 символами/4096 tokens, управляющие символы, кроме tab, отклоняются. Это внешний parsing limit; field limits (comment/reason) проверяются отдельно. Unicode сохраняется как буквальный текст MiniMessage. UUID должны иметь полную форму 8-4-4-4-12. Имена/UUID целей разрешаются только для известных Paper profiles; сетевого поиска Mojang нет.

## Offline StorageAdmin

`java -jar DexturReputation-StorageAdmin.jar verify --data-directory DIR --confirm STOPPED-ALL-WRITERS [--language ru_RU|en_US]` проверяет выбранное хранилище без ремонта оценок. `migrate` требует target-config и пустую цель; `recover-json` — candidate backup|temporary. Все writers должны быть остановлены; этот CLI не является серверной командой. [Полная процедура](STORAGE.md).
