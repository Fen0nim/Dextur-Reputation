# Восстановление репутации — Phase 5

Paper 26.2 / Java 25, JSON / SQLite / MySQL / MariaDB. Отрицательная репутация сама по себе не означает нарушение правил. Восстановление добавляет ограниченную **системную** оценку; не удаляет и не исправляет голоса сообщества, не создаёт likes. Trust остаётся скрытым, отдельным показателем. Phase 6 и полный административный набор Phase 7 не реализованы.

## Вступление и состояния

`recovery.yml`, `config-version: 1`, применяется после полного restart. Отсутствующие значения объединяются с defaults в памяти; пользовательские файлы не переписываются. Неизвестная версия, повторные YAML keys/IDs, неверные типы, timezone, материалы, markup и противоречивые лимиты отклоняются до регистрации сервисов. В этой версии допускается ровно одна активная программа на UUID/scope. Программы строго последовательны.

Defaults: включено, committed visible score **≤ -100**, минимум **30 часов** Paper PLAY_ONE_MINUTE / 20 и **7 дней** с первого входа. Manual=true, automatic=false; повторное вступление через **30 суток**, срок программы **180 суток**. Серверный возраст не является возрастом Minecraft-аккаунта. Placeholder `%statistic_time_played%` для вычислений не нужен: используются исходные серверные данные, без зависимости от PAPI.

| Состояние | Переходы |
| --- | --- |
| AVAILABLE | Производное состояние без enrollment. Условия показываются отдельно. START → ACTIVE, если все условия выполнены. |
| ACTIVE | Завершение целей → следующий этап или COMPLETED. PAUSE/lock/confirmed policy → PAUSED; cancel → CANCELLED; deadline → EXPIRED. |
| PAUSED | Прогресс и награды сохраняются. Явный admin resume → ACTIVE после проверки enabled/lock/providers. Cancel/expiry сохраняются. |
| COMPLETED | Терминальное состояние enrollment, rewards остаются. Новый START возможен после cooldown и новой проверки eligibility. |
| CANCELLED | Терминальное; отмена не возвращает лимиты и не позволяет повторять старую активность/награды. |
| EXPIRED | Терминальное; проверяется по persisted deadline при обращении/обработке, а не исчезающим таймером. |

Переход -100 → -99 у действующей программы не сбрасывает прогресс. PAUSED продолжает занимать единственное место. Cooldown считается от завершения/отмены/expiry; применяется больший cooldown из старого enrollment и текущего конфига. Срок программы продолжает идти во время паузы. WAIT_PERIOD не засчитывает паузу: stage timestamp сдвигается при resume. Автоматическое вступление проверяется тем же транзакционным кодом при серверных activity samples. Read-only status не включает manual enrollment.

При `enabled: false` startup до readiness устойчиво приостанавливает все активные программы, включая офлайн-профили. Прерванный startup повторяет процедуру. Возврат enabled=true не возобновляет их автоматически: нужен admin resume. Наград в отключённом состоянии нет.

## Программы и цели

Каждое вступление сохраняет полный immutable `RecoveryRules` с ID/version программы, порядком, именами RU/EN, icons, целями, параметрами и лимитами. Удаление, переименование и перестановка stages в текущем файле влияют на новые вступления. Старые продолжают сохранённую программу; её названия/награды/иконки отображаются из снимка. Увеличивайте `program.version` при изменениях. Автоматического переписывания активных программ и live reload нет.

| Этап по умолчанию | Требования | Системная награда | Trust-кредит |
| --- | --- | ---: | ---: |
| stabilization | ACTIVE_PLAYTIME 18000 секунд | 5 | 5000 units = 0.005 |
| consistency | 3 LOGIN_DAYS и ещё 36000 секунд ACTIVE_PLAYTIME | 10 | 0 |
| restoration | WAIT_PERIOD 604800 секунд и ещё 18000 секунд ACTIVE_PLAYTIME | 15 | 5000 units = 0.005 |

Типы:

- `PLAYTIME`: connected seconds, полученные из монотонного серверного трекера во время ACTIVE; AFK здесь засчитывается намеренно. Отличается от общего Paper playtime для eligibility.
- `ACTIVE_PLAYTIME`: только eligible activity seconds текущего этапа; до enrollment и прошлые этапы повторно не засчитываются.
- `LOGIN_DAYS`: вход в календарный день плюс минимальная eligible activity **после вступления**. Defaults: UTC, граница 00:00, минимум 300 секунд. Повторные входы и restart не добавляют второй день. Присутствие онлайн через полночь без нового входа не является новым login day. На следующем этапе нужен новый день. ZoneId/календарные даты корректно учитывают DST; timezone заморожен в enrollment.
- `WAIT_PERIOD`: persisted stage timestamp и серверный UTC Instant; downtime засчитывается, PAUSED — нет.
- `NO_CONFIRMED_ABUSE`: доступен только при существующем Phase 3 Trust record; проверяет отсутствие подтверждённой **манипуляции голосами** и действующего restriction. Это не проверка всех нарушений сервера, банов или внешней модерации. Без надёжной записи цель недоступна.
- `EXTERNAL`: прогресс только через зарегистрированный доверенный провайдер с совпадающей версией и проверенной конфигурацией. Отсутствие/невалидность провайдера приостанавливает этап; auto-completion нет. Интеграции trades/help/contracts не реализованы.

IDs: `[a-z][a-z0-9_-]{0,63}`, уникальны в программе; максимум 32 этапа × 32 цели. Цель имеет `id`, `type`, `required`, `names`, `descriptions`; EXTERNAL также `provider`, `provider-version`, строковый `configuration`. Progress — целые неотрицательные значения до required. Stage: `id`, `names`, `icon`, `objectives`, `reputation`, `trust-credit-units`. Все названия/описания содержат ru_RU/en_US; разрешён доверенный MiniMessage конфига, динамические значения вставляются буквально.

## Активность и время

Defaults: inactivity timeout 120 секунд, минимум 3 meaningful signals, persistence 30 секунд, batch 32, maximum tracked 4096. Значимые сигналы: накопленные 3 метра горизонтального перемещения либо успешный BlockBreak/BlockPlace. Сигналы объединяются максимум один раз в секунду; предыдущая неполная серия истекает по timeout. Повороты камеры, chat, клики по воздуху и packet spam без перемещения не считаются. Teleport events, скачки >8 м за событие, spectator, смерть и транспорт не дают movement signal. Участие в creative не запрещено по умолчанию.

Время измеряется `System.nanoTime`; пауза между сигналами учитывается только до inactivity deadline. Нет retroactive credit до достижения minimum signals. Глобальные параметры трекера (timeout/minimum signals/cadence/capacity) применяются после restart ко всей будущей активности; сохранённые stage requirements/rewards/timezone/day target не переписываются. Новая политика не пересчитывает прошлые samples. Первый cumulative sample, пересекающий enrollment/resume/reset boundary, устанавливает baseline без награды за этот интервал: невозможно доказать распределение активности внутри него. Это может недосчитать время до одного фактического persistence interval, зато прежняя/приостановленная активность не переигрывается. Накопление дробных наносекунд в трекере предотвращает накрутку округлением каждого события; persisted seconds округляются вниз, пограничная секунда при flush может быть недосчитана. На IO передаются неизменяемые cumulative counters и случайный session UUID. Backend сохраняет session watermarks и историю использованных session IDs, отвергает replay прошлой сессии, игнорирует устаревшие samples, не переносит pre-enrollment counters в новый этап.

Одна задача раз в секунду берёт bounded batch онлайн-игроков с ротацией; per-tick сканирования нет. IO выполняется при наступлении persistence interval. Для большого online фактический интервал включает время обхода `ceil(online / batch-size)` секунд; увеличьте batch/cap под сервер. Профили сверх maximum-tracked-players не получают activity progress до появления места; команды статуса/администрирование остаются доступны. Нет обещания конкретного TPS/throughput.

Quit/disable отправляют последний snapshot без блокировки игрового потока и освобождают трекер/GUI. Сбой/kill до сохранения теряет несохранённый интервал; повторно уже сохранённое время не начисляется. Очередь ограничена, переполнение отклоняет запрос; cumulative counters позволяют следующему sample догнать пропуск. SQL и файловое IO выполняются на существующих workers.

**Предел AFK-защиты:** движение и блоки — серверно наблюдаемые действия, не доказательство намерений человека. Макрос, бег по кругу или автоматизированный клиент могут имитировать активность; QA-клиент проверяет именно учёт настоящих movement packets. Совершенного распознавания ботов нет. Антифарминг дополнительно обеспечивают одно enrollment, порядок этапов, cooldown и постоянные лимиты. Для более строгих целей нужен внешний авторитетный провайдер.

Wall-clock используется для age, дней, cooldown, expiry и ожидания downtime. Регресс относительно последнего сохранения блокирует изменение (`CLOCK_REGRESSION`); watermark дней не откатывается. Коррекция часов вперёд неотличима от настоящего downtime и может продвинуть WAIT/expiry: синхронизация и защита системных часов — обязанность оператора. Изменение клиентского времени не влияет. Disabled startup безопасно использует максимум сохранённого времени и текущего UTC только для паузы.

## Награды и Trust

`rewards.maximum-per-program: 30`, `maximum-lifetime-recovery: 150`, trust program cap 10000 units, lifetime cap 50000 units. Все лимиты относятся к UUID/scope и сохраняются при restart/миграции/новом enrollment. Для активного enrollment применяется меньший из замороженного и текущего лимитов; исчерпанный лимит даёт нулевую награду и устойчивый completion marker. Изменение конфига не переигрывает выданные награды.

Reward operation UUID детерминирован из enrollment/stage. Ledger хранит stage, amount, Trust credit, system before/after, эффективные caps/условие допустимости Trust и время. Суммы воспроизводятся по сохранённым определениям, budget до операции и этим caps. Snapshot проверяет цепочку сумм, ссылок и принадлежность программе; system score воспроизводится как baseline + lifetime rewards. Community score/журнал голосов не меняются. Reward, progress/completion, Trust update и обычная tier reconciliation фиксируются **одним** SQL COMMIT либо JSON atomic replacement. Гистерезис работает как для остальных committed изменений. Повтор/параллельный callback не выдаёт второй reward.

Trust rehabilitation credit имеет отдельную версию `rehabilitation_model=1` в закрытых факторах обычной модели Trust v1. Кредит прибавляется после обычного расчёта с его min/max, хранится и воспроизводится; верхняя инженерная граница 0.10, defaults ниже. Нет arbitrary overwrite, публичного Trust API или пересчёта исторических голосов. Последующие обычные recalculations сохраняют кредит. Confirmed manipulation или действующий Phase 3 restriction исключает кредит; подтверждённый счётчик блокирует новые кредиты и удаляет старый из следующего обычного расчёта. Удалённый кредит не возвращает lifetime budget. Отключённая модель Trust не получает награду. Старый initial-only Trust без необходимых факторов безопасно даёт 0 до обычного refresh; новые initial records содержат пределы модели.

Существующий pair ceiling остаётся неувеличивающимся: кредит не усиливает старую пару/старый голос. Recovery не принимает pending/подозрение/обычные отрицательные голоса за доказанное нарушение. Defaults: suspicious-signal=NONE (другие значения отклоняются), confirmed-abuse=PAUSE, administrative-lock=PAUSE. Для последних двух разрешены NONE/PAUSE/RESET_STAGE/CANCEL. RESET_STAGE очищает текущие требования и оставляет программу PAUSED; уже выданные награды не повторяются. Административный recovery lock устойчив до unlock; unlock сам по себе не делает resume. Это блокировка программы, не Phase 6 gameplay restriction и не глобальная интеграция банов.

Аудит фиксирует stable transition ID, enrollment/stage, вид, UTC, actor/reason, исходный/итоговый статус, objective progress, cumulative activity, confirmed watermark и lock; изменение lock фиксируется также без enrollment. В публичном снимке нет скрытого Trust/счётчика/факторов. Completion предыдущих objectives выводится из сохранённого completed stage и замороженных требований.

## Команды, GUI, PAPI

`/rep recovery` открывает экран. Подкоманды: `status`, `start`, `tasks`, `progress`, `rewards`, `help`, `cancel`. Общие права: dexturreputation.use + dexturreputation.recovery; start/status/cancel требуют одноимённых recovery permissions. Default true. GUI/tasks/progress/rewards/help используют status. Tab completion учитывает права.

Ограниченный административный набор (default op):

```text
/rep admin recovery <известный ник или UUID> status
/rep admin recovery <игрок> start [причина]
/rep admin recovery <игрок> pause|resume|reset_stage|cancel|lock|unlock [причина]
```

Нужен `dexturreputation.recovery.admin`. Offline операции используют известный Paper профиль/статистику; неизвестные имена не разрешаются через Mojang. Start администратора сохраняет общие eligibility/cooldown/caps, но не ограничение manual=false. Force reward/Trust overwrite/удаления истории нет.

`gui.yml.recovery-panel`: enabled, entry-slot, size (45/54), минимум 32 уникальных content-slots, summary/start/cancel/back/close slots; material, objective/completed/start/cancel materials. Icons этапов берутся из frozen программы. Title/lore — recovery-* RU/EN messages. Существующий профиль GUI сохраняется; entry-slot 47 используется только если свободен и это собственный профиль, при конфликте остаётся `/rep recovery`. Есть status, программа/этап, goals/progress/remaining targets, rewards, cooldown, навигация. Все transfer/drag/shift-click отменяются, выполняется только одиночный top left click. Busy, 250 ms cooldown, повторная проверка permissions и identity session защищают async обновления. Quit/disable освобождают owned GUI.

`integrations.yml.recovery-placeholders: true` включает cache-only:

| Placeholder | Значение |
| --- | --- |
| `%dexturreputation_recovery_status%` | AVAILABLE/ACTIVE/PAUSED/COMPLETED/CANCELLED/EXPIRED |
| `%dexturreputation_recovery_stage%` | stable stage ID, пусто до вступления |
| `%dexturreputation_recovery_progress%` | 0–100, среднее долей текущих objectives |
| `%dexturreputation_recovery_completed_tasks%` | завершённые цели текущего этапа |
| `%dexturreputation_recovery_total_tasks%` | число целей текущего этапа |
| `%dexturreputation_recovery_eligible%` | lowercase true/false для нового вступления |

Без fresh immutable cache/unready/при выключенном integration возвращается `""`, без SQL и выдуманных нулей. Исторические завершённые цели сохраняют completion при отсутствии провайдера после restart. Старые placeholders не менялись. Snapshot cache имеет общие TTL/size, activity/status обновляют его; для offline потребителя вызовите status асинхронно.

## Хранение, backup, перенос

JSON format **5**, SQLite user_version **5**, MySQL/MariaDB **V5__recovery.sql**, таблица `dr_recovery` (UUID/scope/revision/canonical payload/SHA-256). SQL V1–V4 не изменены. Старые snapshots читаются с пустым recovery ledger, старые constructors portable snapshot сохранены. JSON owner lock/checksum/backup/atomic replace/size cap и процедуры quarantine recovery сохранены. Повреждение/неполный migration блокируют запуск без fallback.

Recovery SQL payload переписывает состояние одного UUID/scope; JSON переписывает весь snapshot и весь аудит. Старые данные автоматически не удаляются. История растёт; receipt/session journal имеет fail-closed предел **10000** операций на UUID/scope, календарная история — **3660** дат, providers — 64 каждого типа, listeners — 128, reward extension futures — 128. Это инженерные пределы, не способы сбрасывать lifetime caps. При достижении предела сохраните backup и запланируйте контролируемое обслуживание; ручное удаление receipts/rewards небезопасно. Нет SLA для больших histories и многосерверной записи. JSON не имеет транзакционного SQL-журнала, row-level concurrency, инкрементальных backup или универсальной гарантии физического power loss.

Перед обновлением/изменением backend остановите **все** writers и сохраните полный storage + все *.yml/оба языка. SQLite копируйте остановленным с корректным WAL handling или через backup API; MySQL/MariaDB — consistent transactional dump со schema history. JSON сохраняйте с .bak/.tmp и исходными checksums. Откат на Phase 4 требует восстановления **всего** backup: старый writer не поддерживает format/schema 5.

Пример безопасного переноса (target — новый пустой backend):

```sh
java -jar DexturReputation-StorageAdmin.jar migrate \
  --data-directory /srv/paper/plugins/DexturReputation \
  --target-config /srv/maintenance/storage-target.yml \
  --backups /srv/backups/dextur-reputation \
  --confirm STOPPED-ALL-WRITERS --language ru_RU
```

Форматы target JSON/SQLITE/MYSQL и secret env — [STORAGE.md](STORAGE.md). Все шесть направлений переносят одинаковый portable snapshot, включая frozen definitions, progress, receipts/sessions/days, rewards, audit, Trust, pending/голоса, precision, tier hysteresis/ownership, язык. CLI сохраняет проверенные source/target/config backups; pending guard и полное equality verification предшествуют **последней** атомарной активации storage.yml. Источник не удаляется. Ручная смена type не переносит ledger и создаёт независимую базу — используйте CLI, чтобы не сбросить caps.

API/интеграционные ограничения: [RECOVERY_API.md](RECOVERY_API.md). Фактическая матрица проверок: [PHASE_5_TEST_REPORT.md](PHASE_5_TEST_REPORT.md).
