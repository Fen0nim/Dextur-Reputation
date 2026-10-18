# Архитектура Phase 2

| Модуль | Ответственность | Зависимости проекта |
|---|---|---|
| reputation-api | Неизменяемый снимок и асинхронный ReputationService | Нет |
| reputation-domain | Язык игрока и порт хранилища | api |
| reputation-application | Сервис, очередь, ограниченный LRU/TTL, конфигурация, локализация | domain |
| reputation-storage | StorageBackend, JSON, SQLite, MySQL, portable snapshots и offline migration | application |
| reputation-paper | Жизненный цикл, команды, слушатели, необязательный PAPI-адаптер | storage |

Домен и API не импортируют Bukkit/Paper. Adventure используется в сервисе сообщений,
а не в моделях профиля. Storage-порт синхронный и вызывается только управляемыми IO workers или offline CLI.
Отдельный шестой модуль для тестов не нужен: тесты находятся рядом со своими компонентами,
а реальный сервер проверяет отдельный source set `runtimeProbe` адаптера Paper.

`onEnable` сначала сохраняет отсутствующие ресурсы и валидирует полную конфигурацию.
Затем создаёт ограниченный executor и команды. Открытие выбранного backend и проверка данных
выполняются асинхронно. Только успешный результат переводится на поток Paper, регистрирует
API, включает PAPI и прогревает онлайн-профили. Bukkit-объекты используются в обработчиках
Paper; работникам передаются UUID, строки и неизменяемые настройки. Результаты команд
доставляются через scheduler только ещё подключённым отправителям.

Вызов API может завершиться сразу при попадании в кэш. Поток continuation не гарантируется;
потребители обязаны самостоятельно переключаться на поток Paper перед работой с Bukkit.
Нельзя вызывать `join()`/`get()` асинхронного API на игровом потоке.

Первичный ключ профиля `(player_uuid, scope_id)`. `getOrCreate` использует InnoDB-транзакцию,
READ COMMITTED, upsert без изменения существующих полей и SELECT перед COMMIT.
Повторные/конкурентные вызовы не меняют revision, оценки или timestamps. Язык хранится
отдельно по UUID; повторное сохранение того же языка не меняет timestamp. Доступ к языку
в приложении сериализован фиксированными 64 stripe-locks; число блокировок не растёт
с количеством игроков. Это локальная упорядоченность; совместная запись независимых серверов в один backend не поддерживается.

V1 создаёт три таблицы: `dr_schema_history`, `dr_profiles`, `dr_player_preferences`.
Временные метки — UTC TIMESTAMP(6); driver/session timezone фиксируется в UTC.
Миграции защищены advisory lock, версией и SHA-256. MySQL DDL автоматически фиксируется:
V1 поэтому только additive/idempotent, повторяет незавершённое создание таблиц, а запись
истории появляется после завершения всех DDL. Изменение опубликованного V1 запрещено.
При несовпадении checksum или неизвестной версии запуск прекращается; автоматического
удаления таблиц/истории нет. Администратор должен восстановить правильную версию из backup.

При остановке API/слушатели/PAPI снимаются до дренирования очереди. Cleanup пула привязан
к завершению executor, включая ошибку первоначального подключения. При превышении deadline
поток сервера освобождается с явной ошибкой, а принятые операции продолжают завершаться.
Гарантия не распространяется на SIGKILL/потерю питания: завершённая SQL-транзакция надёжна,
неподтверждённая операция может быть повторена благодаря idempotent upsert.

В логах ошибок операций сохраняются название операции и класс исключения; SQL-текст,
параметры, JDBC URL и пароль не выводятся кодом плагина. Конфигурационные ошибки содержат
имя некорректного поля. Защита MiniMessage основана на вставке динамики как `Component.text`,
а не на экранировании строк с последующим повторным парсингом.

## Расширение хранения Phase 1.1

StorageManager реализует прежний ProfileRepository и выбирает ровно один StorageBackend.
JSON/SQLite/MySQL предоставляют одинаковые операции профиля/языка плюс внутренний portable
snapshot и maintenance-методы; публичный ReputationService не менялся. MySQL V1 сохранена;
SQLite использует user_version, JSON — version/generation/checksum. Подробности ownership,
crash recovery, atomic activation и ограничений в [STORAGE.md](STORAGE.md). Все timestamps
локальных backend — UTC Instant с микросекундами, что позволяет перенос без округления SQL.

## Голоса Phase 2

API: VotingService, immutable request/entry/receipt/view/page/statistics, after-commit subscriptions.
Domain: VoterFacts, VotingRules и чистый VotingEngine; версия 1 использует заданный base weight.
Application: VotingApplication переиспользует bounded executor и lifecycle foundation. Trusted
facts собираются на потоке Paper, IO — на workers; pending facts завершаются ошибкой при disable.
Profile stripes (64, фиксированный порядок пары) синхронизируют чтения профиля и vote cache update.
Затем публикуется новое committed событие; дубликаты и ошибки не публикуются.

StorageBackend расширяет старый ProfileRepository общим VotingRepository. JSON projector строит
новый полный snapshot и фиксирует единственный файл. SQL adapter использует indexed reads,
prepared statements, транзакцию журнала/active index/profile и уникальные ключи. В пределах одного
владельца операции сериализованы; MySQL voting transaction SERIALIZABLE, профильные старые
транзакции сохранены. Количество I/O workers/соединений ограничено.

Journal — append-only CAST/REPLACE/REVOKE с operation UUID, hash нормализованного запроса,
глобальным sequence, предыдущим событием пары, весами и before/after community. Active index
SQL проверяется по журналу. Portable snapshot проверяет также цепочки, delta и финальный профиль.
JSON v1 читается, v2 пишет журнал вместе с профилями; SQLite user_version 2, MySQL additive
V2 с прежним V1 checksum. Migration переносит этот же snapshot, так что проверка активации
включает голоса, counters/cooldown и историю, а не только profile totals.

Paper: отдельные VotingCommands/VotingGui, immutable config и native Player eligibility. Inventory
holder служит токеном сессии, busy блокирует повторный click; async callbacks сверяют identity.
Перед снятием listeners при disable закрываются все GUI плагина, чтобы их предметы не остались доступны.
Подробные ограничения масштабирования, неопределённого commit и event delivery — VOTING.md.
Никаких Trust/Phase 3 компонентов не добавлено.
