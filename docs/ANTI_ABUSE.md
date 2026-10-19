# Веса и anti-abuse — Phase 3

## Точность и историческая совместимость

Все новые величины — signed long в миллионных долях: SCALE=1000000. Входные decimals
проверяются точно; переполнение отклоняет всю операцию. Произведение вычисляется через
BigDecimal без промежуточного округления произведения и квантуется HALF_EVEN до 6 знаков.
Нормированные факторы/interpolation также имеют 6 знаков. Общее точное community хранится
отдельно и округляется HALF_UP симметрично от нуля лишь при построении публичного профиля:
.35+.35+.35=1.05 → 1; −.35−.35=−.70 → −1. System score не меняется и прибавляется после
округления community. Диапазон точной суммы около ±9.22×10¹² целых; старые long-профили
сохраняются, но новая операция за пределами точного диапазона явно отклоняется.

```text
raw = clamp(base × trustMultiplier × repeatMultiplier × reciprocityMultiplier
                 × diversityMultiplier × maturityMultiplier × clusterMultiplier, min, max)
newWeight = min(raw, persistedPairLifetimeCeiling)   // первая новая пара: raw
newCommunityUnits = oldCommunityUnits − oldActiveUnits + newActiveUnits
```

Default base 1, max 1.50, min 0. Trust multiplier=.40+(1.25−.40)×Trust.score (score в [0,1]).
Maturity multiplier=.50+.50×maturity. Diversity multiplier=.75+.25×min(uniqueRecentVoters/8,1).
Это ограниченная confidence-модель входящей поддержки, не доказательство независимости
личностей и не прибавка к Trust получателя. Cluster factor .50; прочие шаги ниже.
При отключении voting-weight номинальный base берётся из voting.yml без этих множителей;
сохранённый lifetime ceiling по-прежнему ограничивает будущий вклад пары. Detection/pending
управляются отдельными switches. После ceiling итог может быть ниже настроенного min.

Phase 2 `calculationVersion=1` остаётся неизменной: старый вклад целый, точный baseline
включает существующую community. Замена/отзыв вычитает исходный сохранённый вклад; новый
голос имеет calculationVersion=2. Старые голоса не переоцениваются по нынешнему Trust.
Private assessment содержит base/точный weight, все множители/границы/ceiling, evaluation time,
policy SHA-256, inputRevision, severity/signals и status. Формулу веса можно воспроизвести
по сохранённым факторам без нынешнего конфига. Новый policy не меняет старый entry.
Public VoteEntry сохраняет прежнюю форму, веса в нём — целое представление. Для актуального
итога используйте view; не складывайте per-vote округлённые effectiveWeight.

## Повторы и взаимность

Окно default 30 суток. Число SUBMIT пары включает замены, оба знака и текущую заявку;
revocation не удаляет прошлые SUBMIT. Шаги по номеру 1..5+: 1/.70/.35/.10/0. Отдельный
lifetime ceiling пары никогда не увеличивается: истечение окна, revoke или рост Trust не
восстанавливают вес старой пары. Это сознательно консервативная политика против farming;
рост доверия улучшает лишь новые пары. Управляемой массовой переоценки ceiling в этой фазе нет.

Reciprocity учитывает оба направления и оба знака, требует хотя бы одно обратное SUBMIT
и default минимум 4 суммарных взаимодействия. Первые 3 не штрафуются; 4-е → .70, 5-е → .35,
6-е → .10, 7+ → 0. Чередование like/dislike и замены не сбрасывают счётчик. Сигналы повторов
не означают подтверждённое нарушение. Обычные eligibility, permissions, cooldown, квоты,
ONCE/REPLACE и reason/comment validation Phase 2 выполняются перед расчётом нового голоса.
Дубликат operation ID возвращает сохранённую операцию без нового расхода квоты.

## Группы и бюджеты

Анализ работает на IO workers по локальному окружению двух участников. До 2048 событий
из максимального observation window, 128 nodes, 4096 directed edges, 20000 work steps,
100 ms и минимум 30 секунд между graph calculations на scope (всё настраивается).
SQL получает четыре ограниченных индексированных диапазона по voter/target с JOIN assessment.
В памяти — bounded samples/sets; полного graph scan на Paper main thread нет.

Для каждого центра берутся взаимные соседи; группа минимум 4 с directed/mutual density≥.75
помечается как подозрительная. Группы хранятся отдельно: разные группы не становятся одной
только из-за участия в одном batch. Результат содержит участников и признаки; коэффициент
уверенности понижается только для новых оценок внутри обнаруженной группы. Дружба сама
по себе не приводит к ban/confirmed violation. Три друга не достигают default evidence.

Cached graph не пересчитывается каждым запросом. Фоновый non-overlapping job после interval
обрабатывает новые события и записывает отдельный ANALYSIS transition с input revision,
work/completeness и участниками; прежние веса не меняет. Повтор без новых событий no-op.
Это bounded локальная эвристика, **не полный поиск всех сетей**, не доказательство аккаунтной
связи. Последний локальный anchor не гарантирует охват всего scope; большие/разреженные/
удалённые сети могут остаться незамеченными. Сохраняйте и проверяйте ручные доказательства.

При превышении event/node/edge/work лимита контекст считается неполным: SUBMIT ставится
pending (если разрешено), иначе PROCESSING_BUSY. Превышение wall-clock budget отклоняет
операцию PROCESSING_BUSY до commit; timing не меняет уже сохранённый вес. Новый запрос может
успешно повториться при меньшей нагрузке. Нет автоматического переключения backend.

## Всплески, pending и решения

Default negative spike: 10 разных авторов за 30 минут. Rapid change: 12 разных авторов любых
знаков за 30 минут. Это SUBMIT history, а не только нынешние active: замена/отзыв не скрывает
недавнюю активность. Исторический Phase 2 vote без достоверных Trust/maturity факторов не
считается выдуманным слабым аккаунтом. Для known facts weak означает maturity<.30 или
Trust<initial. Только spike/growth вместе с weak fraction≥.50 либо связанной dense group
даёт HIGH и qualifying pending. Всплеск зрелых независимых аккаунтов получает signal без
автоматического pending/наказания. LOW — record, MEDIUM — reciprocal/group confidence,
HIGH — pending, если разрешён. Эти категории не равны подтверждённому обману.

Pending CAST даёт 0 нового вклада. Pending REPLACE сохраняет прежний active pointer и вклад;
он не удаляет старую оценку до решения. Pending потребляет обычную квоту/cooldown, сохраняется
при restart и миграции. Новое SUBMIT/REVOKE делает прежнее pending намерение устаревшим;
устаревшее решение не может воскресить старый голос. Нулевой допустимый вес может оставаться
active и учитываться в статистике как активная оценка.

ACCEPT применяет сохранённый точный вес; INVALIDATE отклоняет pending, сохраняя старый
active, либо удаляет принятый вклад; CONFIRM_MANIPULATION дополнительно меняет Trust и
временную доступность **нового голосования** по явному решению оператора. Каждое решение
требует expectedRevision, transition UUID, operator UUID и причину. Review и его duplicate
имеют описанный в TRUST.md асинхронный внутренний capability. Public API/GUI не предоставляет
модерацию. Если доверенного административного адаптера ещё нет, pending не может быть
принят игроком; настройте политику осознанно до production запуска.

## Атомарность и ограничения

Journal, pair, точные суммы, public profile, Trust, assessment/status/audit/meta фиксируются
в одном JSON v3 rename или SQL transaction. ACCEPT/INVALIDATE — такая же атомарная операция.
Только после commit обновляются cache и публикуются события; audit не является durable
очередью уведомлений. Если сбой случился после rename/commit до ответа, проверьте состояние
и повторяйте тот же ID/payload. JSON fail-closed после неопределённой записи.

JSON полностью сериализует/проверяет snapshot O(N), хранит его в памяти, fsync/rename требует
надёжной локальной FS. Default 64 MiB; warning .75 от configured maximum. Это не транзакционная
СУБД с page-level journal, replication или внешней устойчивостью к дисковым ошибкам. На большой
истории переходите в SQL до достижения лимита. SQL сохраняет prepared statements, WAL/busy
для SQLite, InnoDB/Hikari/checksummed migrations для MySQL/MariaDB и один owner/writer.
Полная проверка старта/backup/migration остаётся O(N); async очереди и runtime graph bounded.

Нет production benchmark, доказательства power-loss durability оборудования, гарантированного
tick SLA, распределённых writers или точной идентификации ботов. Default thresholds требуют
наблюдения на конкретном сервере. Публичные целые веса не раскрывают точный Trust, но влияние
любых механик можно статистически исследовать; абсолютная секретность алгоритма не заявляется.
