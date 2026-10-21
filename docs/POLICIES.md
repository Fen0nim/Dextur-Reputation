# Политики репутации — Phase 6

Paper 26.2 / Java 25. DexturReputation оценивает условия и публикует результат; создание лота, покупка, выдача набора, работа чата или обращения остаются обязанностью соответствующего плагина. Наличие policy ID не означает работающую интеграцию. DexturCore, Redis, Velocity и реализации будущих Dextur-плагинов не добавлены.

## Результат и правила

`ReputationPolicyService` — отдельный Bukkit service после storage readiness. Асинхронный `evaluate(UUID, policyId[, publicContext])` читает согласованный профиль, административное состояние и recovery state. Immutable result содержит decision (`ALLOW`, `DENY`, `LIMITED`, `UNAVAILABLE`), видимую целую репутацию, tier, описание публичного условия, optional limit/value, точный decimal multiplier, reason key, UTC evaluation timestamp, integrationActive и revision vector: profile / administration / recovery / configuration fingerprint. Trust и закрытые антиабузные факторы в API отсутствуют. Политики используют сохранённый recovery status; expiry/WAIT обрабатывает RecoveryService, а не read-only policy evaluation.

`ALLOW` и `LIMITED` допускают переход к проверкам самого потребителя. `LIMITED` возвращает настроенные параметры. `DENY` запрещает обычное действие. В YAML rules задаются эти три решения; `UNAVAILABLE` формируется для неизвестной/отключённой политики. Недоступное/повреждённое хранилище завершает future exceptionally и не вызывает fallback. Внешний плагин обязан определить свою обработку unavailable и дополнительные инварианты. Отсутствующий сервис не доказывает ALLOW.

`features.yml`, config-version 1. Все определения immutable; `priority` уникален внутри политики, меньшее значение проверяется первым. Первое совпадение побеждает. Должно быть ровно одно безусловное правило с последним priority. Overlap порогов допустим при явном порядке; повтор priority/одинаковых условий, противоречивый диапазон, неизвестные condition/output keys и неверные типы отклоняются. Порядок YAML rows не заменяет priority.

Условия `when`: `reputation-at-least`, `reputation-at-most` (включительные), `tiers`, `recovery`, `context`. Между разными условиями AND; внутри списка tiers/recovery OR. Context — публичные строки от доверенного потребителя, максимум 16 ключей, значение до 128 символов; клиенту нельзя позволять выбирать авторитетные context fields. Пустой context используется в cache-only API и встроенной voting-проверке. Внутренний Trust не является условием этой версии.

```yaml
config-version: 1
policies:
  chat.send:
    enabled: true
    names: {ru_RU: 'Отправка сообщений', en_US: 'Send chat messages'}
    rules:
      - {priority: 10, when: {reputation-at-most: -250}, decision: DENY}
      - {priority: 1000, decision: ALLOW}
  chat.cooldown:
    rules:
      - {priority: 10, when: {reputation-at-most: -100}, decision: LIMITED, multiplier: 3.0}
      - {priority: 20, when: {reputation-at-least: 250}, decision: LIMITED, multiplier: 0.9}
      - {priority: 1000, decision: LIMITED, multiplier: 1.0}
  example.verified-action:
    enabled: true
    names: {ru_RU: 'Проверенное действие', en_US: 'Verified action'}
    rules:
      - priority: 10
        when: {tiers: [HONORED], recovery: [COMPLETED], context: {mode: verified}}
        decision: LIMITED
        limit: 15
      - {priority: 1000, decision: DENY}
```

Это пример намеренно включаемых ограничений. Shipped `chat.send` разрешает сообщения; shipped chat.cooldown только определяет multiplier. Без подключённого DexturChat он не применяется к сообщениям. Имена/причины/GUI поддерживают RU/EN; динамические значения вставляются буквально. Полный рабочий пример всех settings — shipped `features.yml`.

## Стабильные ID

| ID | Назначение |
| --- | --- |
| reputation.vote | Внешний policy gate перед существующим voting safety pipeline |
| chat.send / chat.cooldown | Разрешение сообщений / multiplier будущего cooldown |
| shop.use | Доступ к будущему магазину |
| auction.use / auction.create-listing / auction.listing-limit | Доступ, создание, лимит будущих лотов |
| warps.create / warps.limit / warps.visit | Будущие player warps |
| rewards.daily / rewards.weekly | Будущие награды |
| kits.use / reward-codes.use | Будущие наборы и коды |
| buyer.use / buyer.limit | Будущий скупщик |
| support.create-ticket / support.open-ticket-limit | Будущие обычные обращения |
| support.emergency-contact | Неотключаемый репутацией базовый канал контакта |
| health.maximum | Собственный optional health adapter, [HEALTH](HEALTH.md) |

Defaults для limit-политик: score ≤ −250 → 1, ≤ −100 → 3, ≥ 250 → 15, ≥ 100 → 10, иначе 7. Chat cooldown: ≤ −100 → 3, ≤ −25 → 1.5, ≥ 250 → 0.9, иначе 1. Остальные access policies ALLOW. Все числа и условия редактируются; staff permissions не выдаются. Limit целый неотрицательный, multiplier с точностью до 6 знаков в диапазоне 0..1000; health value 2..40. Policy IDs содержат 2–4 lowercase сегмента через точку, каждый до 48 символов. Не более 256 definitions × 64 rules; default обязателен. Config и YAML size ограничены существующим loader.

Future DexturSupport должен всегда предоставлять реальный аварийный/admin-contact маршрут, независимо от обычных ticket ограничений и репутации. `support.emergency-contact` всегда ALLOW, не допускает deny/disable/override. Сам маршрут реализует будущий DexturSupport, а не Reputation. При отсутствии Reputation API потребитель также должен сохранить свой аварийный маршрут. Это не обход защиты повреждённого хранилища для других действий.

## Voting и overrides

Порядок: healthy exclusive backend → durable duplicate acknowledgement → administrative voting lock → outer reputation.vote policy → прежние permissions/playtime/age/quota/cooldown/anti-abuse checks. FORCE_ALLOW не снимает voting lock и не отменяет специализированные проверки. Старые likes/dislikes/pending не удаляются. Policy evaluation для reputation.vote — только внешний gate; ALLOW не гарантирует, что новый голос пройдёт все проверки.

`/repadmin policy <known-player> <policy-id> allow|deny|reset <reason>` сохраняет UUID/scope/policy-specific override с аудитом. Allow/deny меняют decision, сохраняя числовые параметры совпавшего правила; reset возвращает конфигурационное решение. Unknown/disabled остаётся UNAVAILABLE, voting lock и emergency contact не обходятся. В этой версии override без срока: действует до reset. Permission `dexturreputation.admin.policy`, default op. Подробнее: [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md).

Числовые outputs reputation.vote не заменяют квоты/cooldown из voting.yml: для подробных voting limits используйте прежний конфиг и специализированный pipeline.

## API для DexturChat и будущих плагинов

```java
ReputationPolicyService policies = Bukkit.getServicesManager().load(ReputationPolicyService.class);
policies.evaluate(uuid, "chat.send").thenAccept(result -> {
    // Проверить decision, затем вернуться на main thread перед Bukkit-вызовами.
    // LIMITED/ALLOW не заменяет mute, permission и проверки самого чата.
});
policies.evaluate(uuid, "chat.cooldown").thenAccept(result -> {
    // Использовать result.multiplier() при расчёте собственного базового cooldown.
});
AutoCloseable connection = policies.connect("chat.send", "dexturchat");
// Закрыть connection при disable/отключении реального адаптера.
```

В этой задаче чтение `Fen0nim/DexturChat` и `Fen0nim/Dextur-Chat` вернуло `Repository not found`. Его исходный API не был доступен; DexturChat не изменялся, hard dependency и перехват chat events не добавлены. Пример выше использует настоящий Paper ServicesManager и новый Reputation-side API, не выдуманный метод DexturChat. Подключение/применение результатов должен выполнить реальный потребитель.

Для optional зависимости клиент изолирует API bridge от classloading при отсутствии DexturReputation. После проверки наличия плагина сервис ещё может быть не зарегистрирован до asynchronous storage readiness; обработайте null и дождитесь ServiceRegisterEvent либо повторите bounded проверку. Не блокируйте main thread в ожидании future.

`register(Metadata)` объявляет namespaced policy metadata будущего провайдера, не переписывает правила и не заменяет built-ins. Сервер добавляет definition в features.yml; без него evaluation UNAVAILABLE. Регистрация и `connect(policyId,providerId)` возвращают lifecycle handles, не допускают collisions, максимум 64 каждого вида. Connect объявляет работающий consumer adapter, не меняет rules; reputation.vote/health.maximum/emergency managed internally и не принимают внешнюю connection. API — доверенный код сервера, не sandbox против злонамеренного установленного плагина.

`cached(UUID,id)` использует только immutable input cache с общим TTL/size и пустым context; cache miss → Optional.empty. Никакого SQL/main-thread IO. Профильные изменения инвалидируют cache, heartbeat online игроков его прогревает. Для offline данных вызовите evaluate асинхронно. При принятии необратимого внешнего действия используйте fresh evaluate и собственные транзакционные проверки; API не блокирует внешнюю транзакцию от будущего изменения репутации.

`onChanged` даёт after-commit события: REPUTATION_POLICY_CHANGED, VOTING_LOCK_CHANGED, ADMINISTRATIVE_ADJUSTMENT_APPLIED, POLICY_OVERRIDE_CHANGED, POLICIES_RELOADED. Operation IDs устойчивы; retry с тем же mutation ID не повторяет effects. Revision event использует -1 для компонентов, которые наблюдатель не читает (generic invalidation — admin/recovery, administrative event — recovery, global reload — все player components); для полного vector выполните evaluate. No-op lock/override аудируется без повторного changed effect. Это local callbacks без durable outbox: crash после COMMIT до dispatch может потерять уведомление. Listener быстро возвращает управление, не блокирует worker, не вызывает Bukkit без main-thread перехода. Максимум 128 listeners.

Изменения только recovery status доступны через публичный RecoveryService.onChanged; потребитель может подписаться на него и заново вызвать evaluate. Policy cache инвалидируется при recovery observations и прогревается heartbeat. Revision vector описывает versioned inputs конкретного чтения, а не durable cross-server generation или уникальный ID результата для разных context. После restart потребитель заново получает fresh view, включая текущий tier.

## UI, PAPI, reload

`/rep policies [id]`, permission `dexturreputation.policies` + `.use`, default true. GUI показывает built-in policy outputs, limits/value/multiplier и отдельно integration status. Подключение metadata само по себе не создаёт auction/shop/rewards. `features.yml.information.enabled` и `gui.yml.policy-panel.enabled` управляют показом; entry-slot 48, size 27, material BOOK настраиваются. Конфликт со старым slot не переписывает старую кнопку: остаётся команда. Inventory transfer/drag отменяются; асинхронный ответ проверяет текущий holder/player.

`integrations.yml.policy-placeholders: true`: `%dexturreputation_policy_vote%`, `%dexturreputation_policy_chat_send%` → decision enum; `%dexturreputation_health_modifier%` → фактически применённый собственный ADD_NUMBER modifier. Все читают cache; miss/выключенная интеграция → пустая строка. `policy_vote` не является полной проверкой daily limit/cooldown. Health miss после изменения revision остаётся пустым до main-thread reconciliation. Старые PAPI значения сохранены.

`/repadmin reload` (permission `.admin.reload`) перечитывает **только features.yml**, строит/проверяет весь candidate и атомарно активирует его. При ошибке старые rules продолжают работать. Health пересогласуется bounded task; gui/layout/locales/tiers требуют полного restart. Старые файлы не перезаписываются; отсутствующие поля получают defaults в памяти. Metadata registrations сохраняются при reload. [Хранение и перенос](ADMIN_ESSENTIALS.md), [фактический отчёт](PHASE_6_TEST_REPORT.md).
