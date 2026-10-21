# Хранилища, резервные копии и перенос — 1.0.1

## Выбор и совместимость

В `storage.yml` v2 только **один** selector:

```yaml
config-version: 2
storage:
  type: JSON # JSON | SQLITE | MYSQL
  json:
    directory: storage/json
    maximum-file-bytes: 67108864
  sqlite:
    file: storage/reputation.sqlite
    busy-timeout-ms: 5000
  mysql:
    host: localhost
    port: 3306
    database: dexturreputation
    username: dexturreputation
    password-environment: DEXTURREPUTATION_DB_PASSWORD
    ssl-mode: verify-full
    maximum-pool-size: 2
    connection-timeout-ms: 5000
    max-lifetime-ms: 1800000
```

Новый конфиг выбирает JSON. В старом плоском `config-version: 1` отсутствие `storage.type`
означает MYSQL; файл не переписывается. Если администратор добавляет в v1 `storage: {type: JSON}`
или SQLITE, это явное переключение с сохранением прочих legacy-полей. Для обычного переноса
предпочтителен полный целевой конфиг v2. Независимых enable-booleans нет.

Все объявленные параметры валидируются до регистрации API. Для MYSQL дополнительно нужен
реальный пароль в указанной переменной окружения процесса; для файловых backend он не читается.
Никакой ошибки типа/данных/подключения не приводит к автоматическому fallback.
Относительные пути должны оставаться в каноническом каталоге плагина. Существующие предки явно доверенного data root канонизируются без OS-specific исключений. Ссылка/junction внутри этого root разрешена только при цели внутри той же канонической границы; выход наружу и подмена корня отвергаются. Низкоуровневые операции принимают уже разрешённый канонический путь и сохраняют строгие проверки. Для явно выбранного внешнего backup directory администратор доверяет его существующей ancestry; symlink самого backup root отвергается. Backup внутри data root разрешается по границе data root и не может ссылкой выйти наружу. См. SECURITY.md.

## JSON

`storage/json/state.json` содержит formatVersion (поддерживаются 1–7), generation, profiles, preferences, votes и SHA-256.
Формат 1 Phase 1.1 читается без потери данных; запись выбирает формат 2–7 по реально сохранённым разделам.
Сохраняются **все scope, оценки, tier, revision, UUID, timestamps профилей и выбора языка**.
Снимок канонически сортируется; дубликаты ключей/профилей/языков, некорректные UUID/enum/числа,
неподдерживаемый формат и неверная checksum отвергаются. Hash защищает от повреждения,
не является подписью/защитой от владельца файловой системы.

Каждая мутация сериализована под lock и подтверждается только после:

1. Проверки, что текущий файл не повреждён/не изменён вне владельца.
2. Атомарной замены предыдущей версии в `state.json.bak`.
3. Записи полного нового `state.json.tmp` и `FileChannel.force(true)`.
4. `ATOMIC_MOVE` в `state.json` на той же файловой системе и fsync каталога на поддерживаемой платформе (Linux).

На Linux создание новых каталогов также синхронизирует родительские записи. На macOS/Windows directory sync имеет описанное ниже ограничение. Новые snapshot/backup
файлы создаются с POSIX mode 0600 либо приватным ACL владельца на Windows. Нет неатомарного обходного пути. Ошибка/неопределённый
результат записи переводит открытый backend в fail-closed до перезапуска/проверки.
Если rename успел завершиться до ошибки fsync/падения, после рестарта может существовать новая
полная версия, хотя caller не получил успех. Повторные getOrCreate/выбор того же языка идемпотентны; голос повторяйте с тем же operationId.

Повреждённый/пропавший primary **не** вызывает автоматического отката или создания пустой БД,
если остались backup/tmp. Корректный primary загружается; незавершённый tmp не активируется.
Backup — предыдущая версия, не бесконечный журнал. Возможна потеря последних изменений при
явном восстановлении старого backup. Все варианты сохраняются в quarantine перед восстановлением.

Ограничения: полная сериализация и fsync на каждую мутацию (O(N)), ограничение размера файла
по умолчанию 64 MiB, полный snapshot в памяти, один процесс-владелец. Нет SQL-запросов,
построчного журнала или произвольных многооперационных транзакций. Подходит для небольших
установок; для роста выбирайте SQLite/MySQL. Выполнены проверки Linux и синтетических OS-границ; нативные macOS/APFS и Windows/NTFS добавлены в CI, их фактическое удалённое исполнение отмечается отдельно в отчёте 1.0.1. На macOS Java 25 и Windows нет directory fsync через этот NIO backend: file force и atomic replacement сохраняются, metadata durability при физическом отключении питания не заявляется. NFS/FUSE и нестандартные файловые системы не проверены. Неподдерживаемые
операции должны завершаться ошибкой, а не ослаблять гарантию. Настоящая потеря питания не тестировалась.

## SQLite

Локальный JDBC, WAL, `synchronous=FULL`, `foreign_keys=ON`, busy timeout 250–30000 ms
(по умолчанию 5000). Один владелец файла и контролируемая последовательность операций внутри
процесса; параметры SQL передаются prepared statements. Создание профиля, выбор языка и bulk
import используют транзакции, ошибки делают ROLLBACK. При старте выполняются integrity_check,
проверка schema user_version и полная валидация сохранённых записей.

Таблицы содержат те же логические данные, timestamps — ISO UTC с микросекундами.
Не копируйте только главный `.sqlite` во время работы: актуальные данные могут быть в `-wal`.
Остановите всех писателей, либо используйте штатный SQLite backup API/CLI отдельным инструментом.
Не открывайте SQLite на сетевой файловой системе; concurrent внешний SQL writer не является
поддерживаемым способом администрирования.

## MySQL/MariaDB

Сохраняются Phase 1 `dr_profiles`, `dr_player_preferences`, `dr_schema_history`, UUID/scope PK,
UTC TIMESTAMP(6), HikariCP, MariaDB Connector/J и неизменённая V1/checksum. Аддитивная таблица
`dr_storage_control` хранит формат метаданных и запрет активации незавершённого импорта.
Пароль берётся только из окружения, не записывается в snapshot/receipt/config и не выводится CLI.
Для production `ssl-mode: verify-full`; `disable` только для изолированных тестов.

На одну физическую базу допускается один владелец DexturReputation. Отдельная JDBC-сессия
удерживает advisory lock; она добавляется **к** настроенному request pool (учтите N+1 соединений).
Перед операциями проверяется сохранение владения. Потеря сессии не вызывает автоматического
перехвата: операции завершаются ошибкой до явного рестарта. Pool/ownership очищаются вместе.
Существующие SQL-транзакции по-прежнему атомарны; внешние ручные writers/старые версии плагина
не соблюдают этот протокол, поэтому их необходимо остановить до переноса.

## Offline перенос

Инструмент: `DexturReputation-StorageAdmin.jar`, **не** основной plugin JAR.
Требуются Java 25, файловые права и (для MYSQL) credential/environment выбранных баз.

1. Полностью остановите Paper **и все другие писатели исходной/целевой БД**. Консольная
   команда статуса не является остановкой. Сохраните обычную внешнюю резервную копию.
2. Подготовьте отдельный полный `storage-target.yml` по примеру выше с нужным type/путями.
   Назначение должно быть **пустым**. Не выбирайте старую непустую JSON/SQLite/SQL базу.
   При обратном переносе используйте новый путь/новую БД: исходные данные сохраняются.
3. Для MYSQL задайте пароль безопасно в окружении; если у баз разные пароли, настройте
   разные `password-environment`. Не передавайте пароль аргументом командной строки.
4. Выполните явно:

```sh
java -jar DexturReputation-StorageAdmin.jar migrate \
  --data-directory /srv/minecraft/plugins/DexturReputation \
  --target-config /srv/minecraft/storage-target.yml \
  --backups /srv/backups/dextur-reputation \
  --confirm STOPPED-ALL-WRITERS \
  --language ru_RU
```

Для английского отчёта — `--language en_US`. Без точного confirm инструмент не меняет данные.

Процедура берёт `.storage.lock`, backend ownership обоих хранилищ и проверяет, что identities
различаются. Создаёт уникальную папку backup с `source.json`, `target-before.json`,
`storage-before.yml`, `storage-target.yml`. Все backup перечитываются и проверяются.
Назначение помечается pending, выполняется атомарный bulk import (JSON snapshot / SQL transaction).
Затем сравниваются полные source/target snapshots, а не только counts: timestamps, языки,
оценки, scope, revision и полный журнал голосов должны совпасть. Создаётся проверенный `VERIFIED.json`.
**Только после этого** снимается pending и атомарно заменяется активный `storage.yml`.
Конфигурация содержит один selector, поэтому два активных backend не появляются.

При ошибке до подтверждённой активации прежний config сохраняется/восстанавливается, а
назначение остаётся pending. Плагин откажется его запускать. Не снимайте marker руками и
не делайте downgrade до Phase 1, которая не знает marker. Для повтора выбирайте новое пустое
назначение, сохранив неудачный результат и backups для анализа. При сбое во время финальной
замены проверьте config и VERIFIED receipt перед запуском. Никогда не считайте один exit code
после аварийного завершения доказательством того, какой backend активен.

5. Проверьте отчёт/backup, запустите Paper, выполните `/dexturreputation storage` и `/rep`.
   Старое хранилище **не удаляется**. Если новая система уже принимала записи, возврат старого
   config откатит эти записи: безопасный rollback требует нового проверенного обратного переноса.

Протокол не может остановить произвольные внешние SQL-клиенты или writers старой версии.
Подтверждение STOPPED-ALL-WRITERS — реальная эксплуатационная предпосылка, а не её автоматическая
проверка для всех машин. Соблюдающие протокол владельцы на этой версии защищены locks.

## Явное JSON recovery

Остановите сервер. Выберите `backup` (предыдущая полная версия) или `temporary`
(возможная незавершённая запись, которая могла не быть подтверждена вызывающему):

```sh
java -jar DexturReputation-StorageAdmin.jar recover-json \
  --data-directory /srv/minecraft/plugins/DexturReputation \
  --candidate backup \
  --confirm STOPPED-ALL-WRITERS \
  --language ru_RU
```

Кандидат сначала проверяется по формату/checksum/данным. Текущий primary, backup и tmp
сохраняются в `recovery-<UUID>/`, затем primary заменяется атомарно. Invalid candidate не
активируется. Recovery не снимает pending миграции. После него проверьте данные до старта.

## Регулярные резервные копии

- JSON: остановить владельца и копировать **весь** JSON-каталог (включая primary/bak/tmp),
  конфиги и lang; проверять checksum через восстановление в отдельном изолированном каталоге.
- SQLite: остановить всех владельцев и копировать DB вместе с оставшимися WAL/SHM, либо
  использовать официальный SQLite backup API. Не удалять WAL вручную.
- MYSQL: согласованный дамп/backup средствами СУБД с безопасными client credentials;
  `--single-transaction` для InnoDB, без concurrent DDL. Дополнительно сохранить конфиги/lang.
- Portable snapshots миграции содержат все данные завершённых Phase 1–7, но не произвольные таблицы других
  плагинов и не являются заменой полного DBA backup. Храните внешние копии и проверяйте restore.

## Сохраняемые данные и рост

Журнал votes/active pairs, request hashes, exact precision baseline/current units и lifetime ceilings; preferences; private Trust/assessments/signals/reviews; tiers/hysteresis/LP desired/applied metadata; recovery definitions/activity/progress/rewards/receipts; admin contribution/voting locks/policy overrides; flags/moderation/audit/bulk cursor — единый проверяемый portable snapshot. SQL использует additive V1–V7, SQLite user_version до 7. Исторические migrations и их checksums не изменены. Hotfix 1.0.1 не меняет формат и не добавляет V8.

Invalidation/restore не удаляет journal, не пересчитывает старый вес и не стирает confirmed evidence. Bulk состоит из bounded atomic batches, а не одной глобальной transaction. Cursor/audit фиксируются с batch. После restart требуется явное resume/cancel; volatile confirmation tokens не сохраняются.

Деструктивный аудит, moderation markers, receipts и bulk metadata автоматически не удаляются. Только низкорисковые DIAGNOSTIC records имеют retention. Hard caps (например admin ledger 10 000 операций на UUID/scope, investigation maximum-records) fail closed; это не автоматическая архивация. JSON переписывает всю базу, SQL — затронутые payloads и indexed rows. Plan migration до достижения cap, сохраняйте проверяемый архив.

Обычные SQL profile/history/GUI reads не загружают всю базу в Java. Startup validation, offline verify, backup и migration намеренно читают полный snapshot и требуют соответствующей памяти/времени обслуживания. Глубокий OFFSET всё ещё имеет стоимость обхода индекса; не обещается константное время arbitrary page.

[Backup/restore](BACKUP_RESTORE.md) · [Upgrade](UPGRADE.md) · [Audit](AUDIT.md) · [Troubleshooting](TROUBLESHOOTING.md).

## Offline verification 1.0.1

```sh
java -jar DexturReputation-StorageAdmin.jar verify \
  --data-directory /srv/paper/plugins/DexturReputation \
  --confirm STOPPED-ALL-WRITERS --language ru_RU
```

Открывает выбранный backend с exclusive ownership, выполняет startup/schema validation и проверку полного canonical portable snapshot: profiles/journal/contributions/Trust/tier/recovery/admin/audit references. Печатает только counts и SHA-256; не исправляет оценки и не пересчитывает Trust. На старой схеме возможна обычная startup migration, поэтому backup обязателен до первого открытия новой версией. Полный snapshot загружается в память: это offline maintenance, не запрос публичного API. Публичные runtime reads/history/GUI SQL используют bounded queries. Ошибка verification возвращает ненулевой exit code; backend не заменяется.

## Персональный начальный допуск

`/repadmin force` сохраняется в существующем UUID/scope administration ledger, включая аудит. Форматы и SQL-схемы не меняются. Полный backup и штатная миграция включают состояние bypass. [Точные ограничения bypass](ADMIN_UX.md).
