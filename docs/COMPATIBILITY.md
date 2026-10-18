# Совместимость

Поддерживаемая цель: Paper **26.2**, JDK **25**, API **26.2.build.134-stable**,
Adventure/MiniMessage **5.2.0**. `plugin.yml` использует `api-version: '26.2'`.
Автоматические интеграционные тесты: MariaDB **11.4.8**, MySQL **8.4.7**,
MariaDB Connector/J **3.5.6**, HikariCP **6.3.0**. Сборка: Gradle **9.7.0**.
PAPI — необязательная интеграция **2.12.3**. Результаты runtime-проверок — TEST_REPORT.md.

Paper предоставляет Adventure/SLF4J; они исключены из shaded JAR. HikariCP/SnakeYAML
перемещены во внутренние packages, MariaDB JDBC включён с service descriptor.
JDBC-драйвер MySQL используется только readiness-проверками Testcontainers, в основной JAR
не включается. JDBC-доступ к обеим СУБД выполняется MariaDB-драйвером.

Другие версии Minecraft, Bukkit/Spigot, Folia, Velocity, legacy/1.12.2, DexturChat,
LuckPerms и Discord не заявлены и не реализованы. API/domain отделены от платформы,
чтобы будущие адаптеры не требовали переписывать модель.
Нет Redis, DexturCore, сетевой синхронизации кэшей и интеграций несуществующих плагинов.

Runtime smoke использует локальный offline-mode сервер, тестовую MariaDB без TLS и
консоль/QA-плагин. Это не проверка Mojang-аутентификации, клиентского UI, production TLS,
нагрузки или аварийного отключения питания. Для production нужны online-mode, TLS и свой
резервный backup-процесс; эти проверки не подменяются локальным smoke.

## Phase 1.1

Новая установка JSON; SQLite JDBC 3.51.1.0 необязателен по выбору storage.type. MySQL/MariaDB
и исходный SQL V1 совместимы с Phase 1; flat config v1 без selector остаётся MYSQL. Новый
config storage version 2. SQLite/native и atomic file persistence проверяются на Linux amd64.
JSON требует local POSIX FS с atomic rename/directory fsync. Один владелец физического
хранилища/базы; multi-server shared writer topology не заявлена. MySQL использует N+1
соединений (request pool + dedicated ownership session). Migration CLI — отдельный fat JAR.
Актуальные результаты в [PHASE_1_1_TEST_REPORT.md](PHASE_1_1_TEST_REPORT.md); раздел выше
сохранён как история исходного Phase 1 target.
