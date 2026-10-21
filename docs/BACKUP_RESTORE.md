# Резервное копирование и восстановление

Сначала остановите **всех** writers. Запишите версию плагина, storage.type, schema/format, scope и SHA-256 копии. Храните копии вне data folder на отдельном носителе с ограниченным доступом: приватный Trust, аудит и UUID не являются публичными данными. YAML/обе локали и LuckPerms сохраняйте вместе по времени, но независимыми копиями. Не публикуйте credentials или dump.

## JSON

После штатного shutdown копируйте весь storage/json, включая state.json, state.json.bak и оставшиеся recovery/temp файлы. Не выбирайте «самый новый» файл по mtime: generation, canonical payload и SHA проверяет StorageAdmin. Копия одной записи из середины файла непригодна.

Для восстановления подготовьте пустой каталог, верните YAML и полную проверенную копию. Сохраните заменяемую базу отдельно. `verify` читает и валидирует; явный `recover-json` проверяет допустимое поколение и оставляет forensic copies. Команды и обязательный stopped token — [STORAGE](STORAGE.md). Запустите плагин, проверьте данные и рестарт. Checksum обнаруживает случайное повреждение, не защищает от администратора, который переписал и данные, и checksum.

## SQLite

Простой безопасный путь — штатно остановить владельца и проверить завершение WAL checkpoint. Копируйте database вместе с непустыми `-wal`/`-shm`, если checkpoint не подтверждён; один main-файл при незаписанном WAL теряет committed данные. Альтернатива для управляемого обслуживания — SQLite backup API/`.backup`; не делайте произвольный live `cp`. При `PRAGMA wal_checkpoint(TRUNCATE)` результат busy должен быть 0, WAL пустым, writers остановлены.

Восстанавливайте в новый путь, затем `verify` и plugin restart. Не смешивайте main DB из одной копии с WAL из другой. Сохраните старую базу; не редактируйте user_version/checksum вручную.

## MySQL и MariaDB

Проверяйте отдельно соответствующим native клиентом: `mysqldump`/`mysql` или `mariadb-dump`/`mariadb`. При остановленных writers используйте `--single-transaction --skip-lock-tables --no-tablespaces`. Сохраните **все dr_* таблицы и dr_schema_history**, не только profiles. Параллельные DDL недопустимы. Пароль передавайте через защищённый client defaults file или безопасное окружение процесса, не аргументом командной строки. Production TLS должен проверять сервер.

Восстановите dump в **новую пустую БД**, предоставьте приложению минимально нужные права, проверьте schema metadata и полный portable snapshot через StorageAdmin. Затем явно переключите конфигурацию на восстановленную БД, запустите плагин, проверьте данные и повторный restart. Нативная копия переносится между совместимыми версиями конкретного движка; между JSON/SQLite/MYSQL используйте проверяемый offline migrate, а не импорт произвольного SQL.

## Проверка и ремонт

Путь: VERIFY → REPORT → DRY RUN → явное CONFIRM → REPAIR. Offline `verify` проверяет canonical payload, SHA, revisions, journal/active index, вклад и ссылки аудита; он не является произвольным repair. Online `/repadmin inspect`, `/repadmin vote`, `/repadmin audit operation` показывают источники оценки. `/repadmin recalculate reputation|trust|tier|all Player reason` — явное изменение с аудитом. Для массового ремонта используйте ограниченный selector, dry-run и confirm. Recalculate не исправляет повреждённые байты или отсутствующий ledger; в таком случае нужен проверенный backup.

Успешный backup без пробного restore недостаточен. Фактически проверенные tooling/версии и результаты — [отчёт](PHASE_8_TEST_REPORT.md). Power-loss и долговечность носителя не эквивалентны моделируемому сбою JVM.
