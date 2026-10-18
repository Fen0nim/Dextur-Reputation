# Права Phase 2

| Permission | По умолчанию | Назначение |
|---|---|---|
| dexturreputation.use | true | Просмотр профиля |
| dexturreputation.language | true | Смена собственного языка; также нужен use |
| dexturreputation.status | op | Диагностика очереди и интеграции |

Справка и версия общедоступны. Чужой язык менять нельзя.
Права не дают возможности редактировать файлы/SQL. Отказ в доступе проходит через
сервис локализации, а не через нелокализованное permission-message Paper.

Все новые узлы имеют `default: true` в plugin.yml. В `voting.yml.permissions` можно назначить
собственные узлы; для custom nodes настройте выдачу в вашей permission-системе.

| Ключ voting.yml | Default node | Назначение |
|---|---|---|
| vote | dexturreputation.vote | Любая новая операция, в том числе отзыв |
| positive | dexturreputation.like | Положительный голос |
| negative | dexturreputation.dislike | Отрицательный голос |
| revoke | dexturreputation.revoke | Отзыв своего голоса |
| history | dexturreputation.history | Командные/GUI истории |
| gui | dexturreputation.gui | Inventory GUI |
| reasons | dexturreputation.reasons | Список причин |

Для подкоманд `/rep` также нужен прежний `dexturreputation.use`. Команды `/like`/`/dislike`
проверяют vote и соответствующий type node. Проверки не обходятся через GUI/API: факты и
права новой операции получает Paper-адаптер. Нет permission bypass для квоты, возраста или
cooldown. Дубликат уже зафиксированного API operation ID можно подтвердить без повторной
eligibility-проверки; это не новая запись и не изменение репутации.
