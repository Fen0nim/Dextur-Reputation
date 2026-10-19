# Публичный API

```java
public interface ReputationService {
    CompletionStage<ReputationSnapshot> getProfile(UUID playerId);
}
```

`ReputationSnapshot` содержит UUID, scope, community/system/visible score, tier, revision
и createdAt/updatedAt в UTC. Это неизменяемый record; Trust, JDBC и внутренние кэши не раскрываются.
Новые профили создаются нулевыми с tier `NEUTRAL` и revision 0; Phase 2 меняет community/visible/revision через голоса.
Имена игроков API не разрешает. Ошибки выбранного storage/переполнения очереди/остановки возвращаются через
exceptional completion. Snapshot из кэша может быть старше БД не более настроенного TTL.

Для потребителя: добавьте `depend: [DexturReputation]`, API подключите `compileOnly`.
Готовность плагина асинхронна: при необходимости подпишитесь на Bukkit `ServiceRegisterEvent`,
а не считайте возврат `onEnable` гарантией наличия сервиса. После регистрации:

```java
ReputationService service = getServer().getServicesManager().load(ReputationService.class);
if (service != null) {
    service.getProfile(playerUuid).whenComplete((snapshot, error) -> {
        if (error != null) {
            // Handle unavailable service without treating it as reputation zero.
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            // Check this plugin is still enabled and the player is still connected.
            // Use snapshot.visibleScore() here; no database call is needed.
        });
    });
}
```

Не блокируйте игровой поток ожиданием результата. При `ServiceUnregisterEvent`
прекратите использовать сохранённую ссылку. После остановки новые запросы отклоняются.
Кэш PAPI отдельно не предоставляется публичному API; синхронный доступ не делает IO.
Trust не предоставляется через публичный API.

API Phase 1.1 не зависит от backend и не меняет контракты Phase 1. Потребителю не требуется знать JSON/SQLite/MySQL.

## VotingService (Phase 2)

Отдельный сервис Paper ServicesManager, регистрируется вместе с ReputationService только
после валидации backend. Контракт старого сервиса/record не изменён, storage-классы не раскрываются.

```java
public interface VotingService {
    CompletionStage<VoteReceipt> submit(VoteRequest request);
    CompletionStage<ReputationView> view(UUID playerId);
    CompletionStage<VotePage> history(UUID playerId, VotePage.Direction direction, int page);
    AutoCloseable onCommitted(Consumer<VoteCommitted> listener);
}
```

`VoteRequest` содержит operationId, voterId, targetId, SUBMIT/REVOKE, POSITIVE/NEGATIVE,
reason ID и comment. Для REVOKE type=null, reason="". Строки не null; comment="" означает
отсутствие комментария. Причина нормализуется strip/lowercase, комментарий strip. Неверная
структура request отклоняется конструктором синхронно. Иные ошибки возвращаются exceptional
completion: `VoteRejectedException` с enum code и remainingSeconds для cooldown, либо ошибка
storage/переполнения очереди/остановки. Не преобразуйте storage failure в нулевую репутацию.

```java
VotingService voting = getServer().getServicesManager().load(VotingService.class);
UUID operationId = UUID.randomUUID(); // persist/reuse this ID when retrying the same intent
VoteRequest request = new VoteRequest(operationId, voterUuid, targetUuid,
        VoteRequest.Action.SUBMIT, VoteType.POSITIVE, "helpful", "Thank you!");
voting.submit(request).whenComplete((receipt, error) -> {
    // Do not block the Paper thread. Schedule Bukkit work explicitly.
    // On uncertain IO failure, retry the SAME request, including operationId.
});
```

Новый запрос требует настоящего онлайн voter, известную цель и действующие permissions;
это проверяет доверенный Paper provider, а не поля request. API UUID чтения может создать
нулевой профиль; оно не доказывает известность игрока для голосования. Повтор сохранённого ID
со всеми теми же полями возвращает `duplicate=true` и не проверяет текущую eligibility снова.
ID с другим payload/scope даёт ID_CONFLICT. ID глобально уникален в физическом хранилище.

`VoteReceipt.entry` — исходный immutable audit event, `currentView` — актуальное состояние
при выполнении/повторном чтении, а не историческая копия original receipt. `ReputationView`
содержит прежний ReputationSnapshot и VoteStatistics(positive,negative,uniqueVoters,historyEntries).
Веса/версия/previousOperationId/sequence в VoteEntry обеспечивают историю замены и расширение
расчёта в следующей фазе. Квоты и даты имеют семантику [VOTING.md](VOTING.md).

`VotePage` — immutable entries с Item(entry,active), page начиная с 1, настроенный pageSize,
полный totalEntries. Direction RECEIVED/GIVEN. Дальняя страница возвращает пустой список;
page<=0 отклоняется. Порядок — sequence descending. Snapshot нескольких разных API запросов
не является общей транзакцией. Кэш старого ReputationService обновляется после голосования
под теми же ограниченными profile locks, так что in-flight старое чтение не перезаписывает
новый результат. PAPI остаётся cache-only и видит этот же commit.

`onCommitted` возвращает подписку, которую нужно закрыть при disable потребителя. Событие
приходит после durable commit и обновления кэша один раз за новую успешную операцию в процессе;
на дубликат/отказ/сбой оно не приходит. Поток и порядок callbacks между workers не гарантированы:
используйте sequence и scheduler. Исключение listener изолировано и не превращает commit в ошибку.
Это in-process событие, не durable outbox: авария между commit и dispatch может потерять событие;
потребители восстанавливают состояние через view/history. Обработчик не должен блокировать IO
worker или синхронно ждать новых API запросов. При shutdown оба сервиса снимаются.

## Дополнение Phase 3 (совместимое)

Прежние сигнатуры/компоненты records сохранены. Добавлены default-методы:

```java
CompletionStage<VoteProcessingOutcome> outcome(UUID operationId);
AutoCloseable onStatusChanged(Consumer<VoteStatusChanged> listener);
// VoteStatusChanged(transitionId, operationId, status, view)
```

Старый implementation VotingService продолжает загружаться: default outcome завершается
UnsupportedOperationException, default subscription ничего не делает. Реализация плагина
возвращает ACCEPTED / PENDING / SUPERSEDED / REVOKED / INVALIDATED асинхронно; внутренний
SUSPICIOUS обобщается до ACCEPTED. Trust, severity, признаки, пороги и точные факторы отсутствуют
в API JAR. Не используйте reflection к implementation в обычных потребителях.

`VoteEntry.calculationVersion=1` сохраняет прежнюю целую арифметику. Версия 2: baseWeight и
effectiveWeight — целое представление веса (HALF_UP; base минимум 1 для старого контракта),
contribution() — подписанное представление одного голоса, **не delta общего точного счёта**.
Оно может быть 0 для законного дробного/ожидающего голоса. Не суммируйте individual display
weights: получайте актуальный `view.profile.visibleScore`, а изменение исходной операции —
`communityAfter - communityBefore`. После review исходный entry не переписывается;
актуальные status и score получайте через outcome/view. Item.active означает применённый
вклад; при pending-замене прежний entry остаётся active. Глобальная уникальность operation ID,
правила retry и отсутствие событий на duplicate/reject/IO failure сохранены.

`onCommitted` приходит и для сохранённого pending (успех сохранения, не обещание нового вклада).
`onStatusChanged` сообщает отдельно о принятом административном решении после COMMIT и cache
update; duplicate решения события не создаёт. Ошибки callbacks изолированы. Это in-process
уведомления, не durable outbox; после аварии сверяйте outcome/view/history. Не блокируйте
callback ожиданием API/IO и не полагайтесь на межпоточный порядок. Новые ошибки:
TEMPORARILY_RESTRICTED (remainingSeconds) и PROCESSING_BUSY. Формулы и Trust публично не выдаются.

Число незавершённых submit дополнительно ограничено database-queue-size (default 256),
включая ожидание фактов Paper; переполнение возвращает PROCESSING_BUSY без записи.
Отмена возвращённого Future не отменяет уже принятую внутреннюю обработку, не освобождает
её admission slot раньше завершения и не откатывает commit. После неопределённого результата
проверяйте outcome и повторяйте тот же operation ID, как при разрыве соединения.

## Phase 4: immutable tiers and formatting

All Phase 1–3 public types and method signatures are preserved. `ReputationSnapshot.tier()` now contains the selected stable configured ID, including persistent hysteresis. Score semantics and all voting contracts are unchanged.

A separate Bukkit service `org.dextur.reputation.api.TierService` is registered after storage validation/reconciliation:

```java
TierService tiers = getServer().getServicesManager().load(TierService.class);
tiers.getTier(uuid).thenAccept(tier -> {
    // IO worker callback. Schedule Bukkit/world/entity changes on Paper's thread.
    String stableId = tier.id();
});
Optional<ReputationTierSnapshot> cached = tiers.cachedTier(uuid); // no IO
String miniMessageScore = tiers.format(uuid, "ru_RU"); // cached; empty on miss
AutoCloseable subscription = tiers.onTierChanged(change -> {
    UUID localTransitionId = change.transitionId();
    // Committed state; callbacks are ordered for each player, not durable delivery.
});
```

`ReputationTierSnapshot` contains UUID/scope, stable ID, integral visible score, tier/profile revisions, an immutable localized-name map, hexadecimal color, configured MiniMessage prefix/suffix templates and nullable next-positive entry threshold and configured display priority. Localized names may themselves contain trusted administrator MiniMessage. It contains no Trust, exact factors, mutable policy, permission ownership or confidential evidence. `TierService.format` returns serialized MiniMessage for the configured score, accepts supported language IDs and never warms the cache. `cachedTier` requires a matching fresh profile revision. `getTier` performs asynchronous worker IO when necessary and can warm a known offline UUID (as with the existing profile service, an absent UUID can receive a zero profile).

`TierChanged` contains transition ID, immutable resulting tier snapshot, previous stable ID and `notifyPlayer` hint. Subscriptions should be closed when no longer needed; at most 128 active subscriptions. Read operations, duplicate vote requests and cache refreshes do not emit old transitions. Bootstrap/configuration reconciliation suppresses player notifications. Persisted history and permission intent repair state, not lost public event delivery. This is a local single-owner API, not a cross-server outbox. On shutdown services unregister and asynchronous new requests fail; existing accepted storage work drains according to the existing deadline.

Formatting conventions for every old/new PAPI value are in [DISPLAY.md](DISPLAY.md). Chat integrations should use this API or placeholders without intercepting DexturChat messages.
