# Конфигурация Phase 2

Создаются `config.yml`, `storage.yml`, `integrations.yml`, `voting.yml`, `gui.yml`, `lang/ru_RU.yml`, `lang/en_US.yml`.
Файлы администратора не переписываются. Неизвестные версии, дубликаты YAML, неверные типы,
пустые обязательные строки, невалидные границы/разметка/placeholder-наборы отклоняются.

## Основные параметры (`config-version: 1`)

| config.yml | Default | Диапазон |
|---|---|---|
| scope | global | `[a-z0-9_-]`, 1–32 |
| default-language | ru_RU | ru_RU / en_US |
| cache-maximum-size | 10000 | 1–100000 на кэш |
| cache-ttl-seconds | 60 | 1–3600 |
| database-workers | 2 | 1–16; MYSQL не больше pool size |
| database-queue-size | 256 | 1–10000 |
| shutdown-timeout-seconds | 15 | 1–60 |

Названия database-workers/queue сохранены для совместимости; они управляют IO workers
всех backend. File IO/SQL не выполняются на основном игровом потоке.

## Storage

Полный пример и процедуры — [STORAGE.md](STORAGE.md). Новый `storage.yml` имеет version 2,
`storage.type: JSON`. Legacy version 1 без selector означает MYSQL и продолжает работать
без изменения. Единственный selector может быть только JSON, SQLITE, MYSQL.

| Параметр | Default | Диапазон |
|---|---|---|
| storage.json.directory | storage/json | относительный путь внутри data folder |
| storage.json.maximum-file-bytes | 67108864 | 1024–268435456 |
| storage.sqlite.file | storage/reputation.sqlite | относительный путь внутри data folder |
| storage.sqlite.busy-timeout-ms | 5000 | 250–30000 |
| storage.mysql.host / port | localhost / 3306 | DNS/IPv4, 1–65535; не JDBC URL |
| storage.mysql.database / username | dexturreputation | database `[A-Za-z0-9_]`, 1–64; username непустой |
| storage.mysql.password-environment | DEXTURREPUTATION_DB_PASSWORD | имя переменной; значение нужно только MYSQL |
| storage.mysql.ssl-mode | verify-full | verify-full / verify-ca / disable |
| storage.mysql.maximum-pool-size | 2 | 1–16; плюс отдельная ownership-сессия |
| storage.mysql.connection-timeout-ms | 5000 | 250–30000 |
| storage.mysql.max-lifetime-ms | 1800000 | 30000–3600000 |

Все секции в поставляемом полном конфиге валидируются, включая неактивные. Для v1 MYSQL
параметры находятся в корне файла, как в Phase 1. JSON/SQLITE не требуют DB credentials.
MYSQL принимает настоящий process password, не HTTPS proxy placeholder. Default SSL проверяет
цепочку и hostname; настройте Java truststore своего CA. JDBC socketTimeout — 10000 ms, UTC.

`integrations.yml` version 1: `placeholderapi: true`; отсутствие PAPI безопасно.
Команды/default permissions объявлены в plugin.yml; узлы голосования переопределяются в voting.yml. Добавлена диагностическая подкоманда `storage`,
она не выполняет перенос онлайн и не делает блокирующего healthcheck.

## Локализация

Файлы языков остаются version 1. Шаблоны MiniMessage доверены администратору; dynamic значения
вставляются как plain Components. Действуют prefix, RU/EN, per-player язык, locale number formatting.
Новые `storage-*` ключи поставляются в обоих языках. Для старых lang-файлов недостающие новые
ключи берутся из bundled defaults без перезаписи custom messages; отсутствующие обязательные
старые ключи по-прежнему вызывают ошибку. Offline CLI использует bundled RU/EN.

## Изменения persistent state

Reload в этой фазе нет. Настройки целиком применяются после restart; backend активируется
только после успешного открытия/валидации. Смена type/пути/БД вручную **не переносит** данные:
используйте offline StorageAdmin. Изменение scope создаёт отдельный набор профилей, старый
сохраняется; выбранный язык общий по UUID. Смена default-language не меняет сохранённый выбор.
Кэши очищаются при shutdown, их лимиты не меняют persistent values. Не редактируйте version/checksum
миграций или pending marker вручную. Старые данные после успешного переноса не удаляются.

## Phase 2

Полная таблица `voting.yml` и правила `gui.yml` — [VOTING.md](VOTING.md). Отсутствующие файлы
создаются из defaults, существующие не переписываются. При обновлении Phase 1/1.1 новые
`vote-*`, `gui-*`, `reason-*` ключи подставляются из bundled RU/EN. Custom messages сохраняются.
Причины пользовательского администратора должны ссылаться на существующие переводы без
placeholders. Новые настройки валидируются до открытия backend. Нет online reload.
