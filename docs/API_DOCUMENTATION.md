# Публичный API

```java
public interface ReputationService {
    CompletionStage<ReputationSnapshot> getProfile(UUID playerId);
}
```

`ReputationSnapshot` содержит UUID, scope, community/system/visible score, tier, revision
и createdAt/updatedAt в UTC. Это неизменяемый record; Trust, JDBC и внутренние кэши не раскрываются.
В Phase 1 создаются только нулевые профили с tier `NEUTRAL` и revision 0.
Имена игроков API не разрешает. Ошибки БД/переполнения очереди/остановки возвращаются через
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
Операции голосования, Trust и изменения репутации появятся только в соответствующих фазах.
