# Recovery API — Phase 5

API artifact: `reputation-api-1.0.1.jar`, compileOnly. Все ранее опубликованные типы/сигнатуры Phase 1–5 сохранены; `ReputationSnapshot` и существующие API records не изменены. API не импортирует Paper, Adventure, storage и скрытый Trust.

После проверки storage плагин регистрирует отдельный сервис:

```java
RecoveryService recovery = getServer().getServicesManager().load(RecoveryService.class);
recovery.status(uuid).thenAccept(snapshot -> {
    // Immutable committed view. Для Bukkit выполните переход на основной поток.
    var status = snapshot.status();
});
recovery.start(uuid);  // eligibility + duplicate/cooldown checks, async commit
recovery.cancel(uuid); // сохраняет rewards/history/cooldown
Optional<RecoverySnapshot> cached = recovery.cached(uuid); // только TTL cache, без IO
AutoCloseable subscription = recovery.onChanged(change -> {
    // change.operationId(), kind(), stageId(), immutable snapshot()
});
```

`status` читает committed профиль и может сохранить разрешённый переход времени/expiry/provider pause или завершение уже подтверждённых целей. Это не enrollment command. Start/cancel в public interface принимают UUID, доверенные Paper facts собирает адаптер; внешние плагины не передают свои playtime/возраст и не редактируют репутацию/Trust. Public API не является security sandbox для установленных серверных плагинов: команды проверяют permissions, а доверенный серверный код отвечает за авторизацию своего интерфейса.

Snapshot содержит UUID/scope/revision, статус/eligibility/reason, enrollment ID, stable program/stage IDs, RU/EN names, stage icon, текущие goals/progress/required/available, completed task count/percent, выданные и предлагаемые системные rewards, enrollment/completion/cooldown UTC. Коллекции immutable. Поля names/descriptions — доверенный MiniMessage администратора; при собственном форматировании вставляйте ники/комментарии буквально. Предлагаемые rewards заморожены в enrollment, финальная сумма ограничена program/lifetime caps. До enrollment цели пустые, progress=0; это значение существующего snapshot, а не подмена cache miss. Никогда не интерпретируйте reason как публичный Trust score. Чисел Trust/факторов в DTO нет.

Events (`RecoveryService.Kind`): PROGRAM_STARTED, STAGE_COMPLETED, PROGRESS_CHANGED, REWARD_GRANTED, PROGRAM_PAUSED, PROGRAM_COMPLETED, PROGRAM_CANCELLED, PROGRAM_EXPIRED, PROGRAM_RESUMED, ADMINISTRATIVE_LOCK_CHANGED. События идут **после** COMMIT и обновления profile/tier/recovery caches, под общим per-player application lock. Чтения, duplicate callbacks, записи watermark завершённой программы не повторяют исторические события. `stageId` указывает этап события, даже если resulting snapshot уже перешёл на следующий этап. `operationId` — stable audit transition ID; reward.operationId — отдельный детерминированный ключ самого начисления.

Это локальные callbacks, не durable outbox/cross-server queue. Crash после commit до dispatch может потерять уведомление. Дубликаты внешних эффектов устраняйте по durable reward.operationId; восстанавливайте их из status().rewards(). Порядок между игроками не гарантирован. Listener должен быстро вернуть управление, не ждать `.get()`/`.join()` новых API операций и не менять Bukkit с worker. Исключения RuntimeException изолируются. Лимит 128 subscriptions; закрывайте их при disable.

## Objective providers

Пример будущего авторитетного провайдера; в DexturReputation нет реализации торгов/контрактов:

```java
RecoveryService.ProviderHandle handle = recovery.registerProvider(
    new RecoveryService.ObjectiveProvider() {
        public String id() { return "contracts"; }
        public int modelVersion() { return 1; }
        public boolean available() { return true; }
        public void validate(String objectiveId, Map<String,String> config) {
            if (!"verified_contracts".equals(objectiveId))
                throw new IllegalArgumentException("Unsupported objective");
            // Validate options against the authoritative integration.
        }
        public void cancelled(UUID enrollmentId) { /* optional local cancellation hint */ }
    });

UUID enrollment = UUID.fromString(snapshot.enrollmentId());
UUID operation = durableIntegrationOperationId; // store in YOUR integration
handle.submit(operation, playerUuid, enrollment, "verified_contracts", absoluteProgress);
// При retry передавайте тот же operation + payload.
```

Соответствующая цель в `recovery.yml`:

```yaml
- id: verified_contracts
  type: EXTERNAL
  provider: contracts
  provider-version: 1
  required: 3
  configuration: {mode: verified}
  names: {ru_RU: 'Подтверждённые контракты', en_US: 'Verified contracts'}
  descriptions:
    ru_RU: 'Три результата от авторитетного провайдера.'
    en_US: 'Three results confirmed by the authoritative provider.'
```

ID провайдера/цели стабилен и валидируется; допускается одна регистрация на ID, максимум 64. Оpaque handle связывает submission с зарегистрированным provider ID: строки из клиентской команды не дают права submit. `modelVersion` и `provider-version` должны совпадать. Validate вызывается при регистрации и для **замороженного** определения перед изменением; это выполняется вне SQL transaction. Методы availability/validate/cancelled должны быть thread-safe, быстрыми, без Bukkit/main-thread assumptions и блокирующего IO.

Progress model v1: целый **абсолютный** monotonic progress 0..required. Неправильная цель/провайдер, stale enrollment, уменьшение/переполнение progress отклоняются. Operation UUID и hash нормализованного payload сохраняются; повтор того же ID/payload ничего не начисляет, другой payload с этим ID отклоняется. Изменение идёт только текущему ACTIVE этапу. Смена definition/config не позволяет применить исторический callback к новому enrollment. После фиксации всех целей награду определяет ядро в той же транзакции, внешний код не присваивает награду и не обращается к Trust.

Недоступный provider/несовместимая frozen configuration означает PAUSED и unavailable, а не completion. После восстановления провайдера нужен admin resume. Закрытие handle прекращает новые submissions и снимает регистрацию; уже принятый запрос может завершиться. `cancelled()` — необязательный best-effort after-commit hint, не отдельная гарантия отмены внешнего контракта. Отказ provider callback не откатывает состоявшийся commit. Закрывайте handle в onDisable своего плагина.

## Future reward extensions

`registerRewardProvider(RewardProvider)` возвращает AutoCloseable и принимает stable `id`, `modelVersion`, `available()` и `CompletionStage<Void> granted(snapshot,reward)`. Это **post-commit расширение** с детерминированным reward.operationId, без доступа к изменению встроенной репутации/Trust. Ядро уже сохранило свой system/Trust reward; callback не определяет его сумму и не может отменить transaction. Экономика/предметы/Discord и другие адаптеры в Phase 5 не реализованы.

Максимум 64 reward providers и 128 незавершённых extension futures. Метод должен быстро вернуть future и дедуплицировать внешнюю операцию по reward.operationId. Переполнение, недоступность и ошибки логируются/пропускают локальную доставку, не создают второй reward. Durable delivery здесь нет: будущий provider должен самостоятельно хранить свои receipts и сверять persisted rewards после сбоя. Регистрация не replay-ит всю историю автоматически. Закрывайте регистрацию и собственные задачи при disable. Для расширения только отображения достаточно onChanged.

## Ошибки и lifecycle

Recovery использует bounded admission (database-queue-size), общий IO executor и profile locks с голосованиями/tiers. Переполнение/закрытие завершается exceptional future; caller cancellation не освобождает admission до окончания принятой обработки и не откатывает commit. Никакого CallerRunsPolicy и SQL в синхронном PAPI нет. Не удерживается транзакция в ожидании Minecraft/provider callbacks.

Start/status/cancel операции генерируют внутренние operation IDs; конкурентный start допускает только один enrollment, этапные reward IDs устойчивы независимо от ID команды. Для неопределённого результата player command сначала перечитайте status; provider retry обязан использовать прежний operation UUID.

Участвующий backend один, владелец один; Redis/Velocity/multi-server delivery не реализованы. На shutdown сервис снимается, принятые операции дренируются по общему deadline, callbacks/кэши закрываются. Bukkit ServicesManager load может вернуть null до readiness/после disable; проверяйте его. Подробности сохранения, limits, состояния и реальные runtime-проверки: [RECOVERY.md](RECOVERY.md), [PHASE_5_TEST_REPORT.md](PHASE_5_TEST_REPORT.md).
