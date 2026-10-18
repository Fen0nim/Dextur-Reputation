# Команды Phase 2

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

Trust/admin reload-команд в этой фазе нет. Tab completion предлагает подкоманды и
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
