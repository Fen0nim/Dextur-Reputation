# Скрытый Trust — Phase 3

Trust оценивает надёжность **автора оценок**, публичная reputation — применённые вклады
оценок **получателя** плюс прежняя systemScore. Они независимы: высокая положительная
reputation не добавляет Trust, отрицательная не доказывает злоупотребление. Репутация
используется лишь прежней Phase 2 квотой. IP, Mojang account age и неподтверждённые связи
не используются. Дата — первый вход на этот сервер, playtime — Paper PLAY_ONE_MINUTE / 20.
`%statistic_time_played%` можно показывать внешним PAPI expansion; расчёт от него не зависит.

## Модель 1

Новый известный онлайн-игрок получает initial=.50 при инициализации. Первый голос или
плановый refresh рассчитывает новую оценку с текущими проверенными фактами. Нулевое
API-чтение произвольного UUID не создаёт выдуманную зрелость.

```text
age = clamp(serverAgeSeconds / (ageDays × 86400), 0, 1)
play = clamp(verifiedPlaytimeSeconds / (playHours × 3600), 0, 1)
maturity = (age + play) / 2
outgoingDiversity = min(distinctRecentTargets / fullCreditVoters, 1)
invalidatedFraction = invalidated / max(1, submitted)
repeatRisk = min(repeatedSubmissionsAtOrAboveEvidence / max(1, evidence), 1)
score = initial
      + maturityInfluence × (maturity − .5)                 [maturity enabled]
      + outgoingDiversityInfluence × outgoingDiversity     [history enabled]
      − invalidatedPenalty × invalidatedFraction           [history enabled]
      − repeatRiskPenalty × repeatRisk                     [history enabled]
      − confirmedPenalty × min(confirmed, 1000000)          [penalties enabled]
score = clamp(score, minimum, maximum)
```

При trust.enabled=false используется initial в заданных границах, multiplier Trust выключен.
Age/play нормируются 90 сутками/200 часами; influence .20. History coefficients .05/.30/.10,
confirmed penalty .25. На полностью зрелом аккаунте без history score=.60; это не означает
подтверждённую личность. Входы ограничены локальным recent context максимального настроенного
окна. История submitted/invalidated/confirmed хранится за всё время. Revoke не повышает
submitted и не удаляет историю. Входящий popularity/diversity не повышает Trust.

Модель и коэффициенты настраиваются в trust.yml; точность — 6 знаков, как у весов.
Объяснение хранит факторы, коэффициенты, switches, границы и исходный initial, позволяя
воспроизвести score независимо от текущего конфига. У initial-only записи сохраняется initial.
Хранятся UUID/scope, modelVersion, calculatedAt, CAS revision, inputRevision scope-аудита,
проверенный playtime/firstJoined/factsAt, счётчики и restrictedUntil. Offline refresh не
выдаёт прежний playtime за новое измерение: factsAt остаётся прежним. Возраст продолжает
отсчитываться от известного server first join.

## Пересчёт и конкурентность

Default cadence 60 минут, batch 20 (максимум 100). Paper раз в минуту захватывает свежие
факты ограниченной группы онлайн-игроков с ротацией; всё IO/расчёт — worker. Отдельный
неперекрывающийся due job обслуживает offline записи. CAS expectedRevision и проверка времени
отклоняют устаревший refresh, а голосование/решение изменяет Trust в той же транзакции, что
и остальное состояние. Нет ретроактивного изменения веса голоса при refresh Trust.
Новые коэффициенты применяются после restart к будущим расчётам; для аудита храните версии
конфигов вместе с backup. Никакой скрытой массовой переоценки старых вкладов нет.

## Закрытое администрирование

`ProtectionAdministration` находится в application, не в reputation-api. Его capability
сохраняется внутри Paper-плагина, package-private accessor предназначен доверенному будущему
адаптеру. Он не регистрируется Bukkit ServicesManager, не доступен через команды/PAPI/GUI/TAB.
Это граница публичного контракта, не защита от произвольного установленного Java-плагина или DBA.

Асинхронные методы: trust(UUID), assessment(operationId), audit(afterSequence,limit≤100),
review(transitionId,voteId,operatorUUID,reason,action,expectedRevision), refresh/initialize,
recalculateDue/analyze, metrics/storageBytes. Доверенный адаптер обязан проверить полномочия
оператора, хранить reason и не отправлять скрытые поля обычным игрокам. Phase 4-команды и
интерфейс модерации пока отсутствуют. Нельзя выполнять прямые SQL/JSON-правки вместо review.

ACCEPT возможен только для актуального pending; INVALIDATE удаляет применённый вклад или
отклоняет pending; CONFIRM_MANIPULATION — **явное решение администратора**, дополнительно
увеличивающее confirmed и ограничивающее новые голоса автора на default 1440 минут. Это не
бан, игровой запрет или автоматическая санкция по графу. Обычный подозрительный сигнал
не увеличивает confirmed/invalidated. Повтор того же transition ID/payload идемпотентен;
изменённый payload даёт ID_CONFLICT, stale revision отклоняется. История не удаляется.

Trust, точные формулы и пороги не выводятся игрокам. RU/EN feedback сообщает лишь
pending, факт обработки, временную недоступность и обобщённый статус. Все динамические
значения вставляются безопасными Components. Файлы БД/portable backup содержат скрытые данные:
ограничьте доступ администраторами, как и к конфигам/обычным резервным копиям.
