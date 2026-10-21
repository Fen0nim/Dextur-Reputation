# Границы безопасности и проверка 1.0.0

SQL значения передаются prepared statements; динамические column/table/order fragments выбираются внутренними allowlists, identifiers schema проверяются конфигурацией. User search использует bound INSTR/LIKE, не подставляется в SQL. Transactions охватывают profile, precision, ledger, moderation, audit и cursor; внешние LP/health callbacks не удерживают transaction.

YAML SafeConstructor, duplicate/null/non-string mapping keys, collection aliases, bounded input и обязательные версии проверяются до READY. Parser diagnostics не печатают исходные строки. MiniMessage templates доверены администратору; player names/reasons/comments вставляются literal Components. Строгие UUID исключают нормализацию сокращённой формы Java UUID parser. Внешний предел command args применяется до lookup/join; field limits остаются отдельными.

Storage paths ограничены data folder; symlink targets и ancestors запрещены, temporary files открываются NOFOLLOW_LINKS с owner-only POSIX permissions. Это работа в доверенном admin-owned каталоге, не защита от администратора с правом произвольно менять файлы/SQL. Canonical SHA обнаруживает corruption, не подменяет authentication. Один exclusive storage owner обязателен.

Operation IDs привязаны к payload; конфликтующий replay отвергается. Bulk token случайный, ограничен actor/payload/expiry/одноразовостью; plan пересчитывается, каждый batch сохраняет cursor атомарно. GUI проверяет owner/session/permission/revision и очищает sessions при quit/disable. Обычный игрок не получает Trust/flags/private moderation через permissions или PAPI.

DB password читается из environment процесса. Failure logs печатают безопасный класс ошибки, не JDBC URL/SQL/credentials; YAML startup diagnostics сохраняют имя проблемного файла. Ошибка observer не меняет committed result, warning ограничен разом в минуту. JSON limit warning также ограничен разом в минуту, periodic growth warning — maintenance interval. Полные native dumps/приватные snapshots не включаются в Git/JAR. Внешний scan — [DEPENDENCIES](DEPENDENCIES.md).

При неопределённом commit сначала проверяйте durable состояние. Повторная console-команда создаёт новый operation ID; не повторяйте add вслепую. Для инцидента остановите writers, сохраните forensic copy и следуйте [backup/verify/repair](BACKUP_RESTORE.md).
