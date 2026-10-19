# Совместимость

Поддерживаемая цель: Paper **26.2**, JDK **25**, API **26.2.build.134-stable**,
Adventure/MiniMessage **5.2.0**. `plugin.yml` использует `api-version: '26.2'`.
Автоматические интеграционные тесты: MariaDB **11.4.8**, MySQL **8.4.7**,
MariaDB Connector/J **3.5.6**, HikariCP **6.3.0**. Сборка: Gradle **9.7.0**.
PAPI — необязательная интеграция **2.12.3**. Результаты runtime-проверок — [PHASE_4_TEST_REPORT.md](PHASE_4_TEST_REPORT.md).

Paper предоставляет Adventure/SLF4J; они исключены из shaded JAR. HikariCP/SnakeYAML
перемещены во внутренние packages, MariaDB JDBC включён с service descriptor.
JDBC-драйвер MySQL используется только readiness-проверками Testcontainers, в основной JAR
не включается. JDBC-доступ к обеим СУБД выполняется MariaDB-драйвером.

Другие версии Minecraft, Bukkit/Spigot, Folia, Velocity, legacy/1.12.2, DexturChat
и Discord не заявлены и не реализованы. LuckPerms поддерживается необязательно для косметических групп. API/domain отделены от платформы,
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

## Phase 2

ReputationService/ReputationSnapshot и PAPI placeholder names сохранены. Добавлен независимый
VotingService. JSON v1 и SQLite schema 1 обновляются без потери профилей/языков, MySQL V1
сохранена байт-в-байт и дополнена V2. Flat storage v1 остаётся MYSQL. Голоса входят в существующий
offline migration/recovery protocol. Администраторские config/lang файлы не перезаписываются.

Runtime --voting использует реальных offline-mode клиентов протокола 776 и native Player команды,
GUI events и cache checks на Paper 26.2. Это автоматизация серверного поведения; пиксельный
вид интерфейса/ручной клиент Minecraft не проверялся. Default eligibility/cooldowns/quota в этом
сценарии ослаблены только в изолированных QA-конфигах; границы production defaults тестируются
модульно.

## Phase 3

Target остаётся Paper 26.2 build 134 / Java 25. Публичные record-компоненты Phase 1/2 и
прежние методы сохранены; расширения VotingService — default. JSON v1/v2 читаются, V3
сохраняет private state; SQL V1/V2 неизменны, V3 additive. Переход назад требует backup.
Trust не становится новым PAPI placeholder, TAB/title/LuckPerms интеграции отсутствуют.
Runtime `--protection` использует три настоящих local клиента, native commands, .35 weights,
pending/review/CAS/restart и проверку отсутствия Trust в PAPI. Параметры QA изолированы;
правила production defaults дополнительно проверяются общими backend/domain suites.

## Phase 4

JSON format 4 / SQLite schema 4 / MySQL-MariaDB V4 сохраняют Phase 1–3 и все старые API-контракты.
PlaceholderAPI 2.12.3 остаётся необязательной; старый tier placeholder возвращает локализованное имя.
Полная LuckPerms 5.5.87 (официальный CI build 1674, API compile-only 5.4) проверяется как необязательный
адаптер контекстных косметических групп. API не упаковывается в основной JAR. Нет смены primary group.

TEAM_IF_FREE работает только на свободных командах scoreboard, DIRECT TAB уступает чужому оформлению.
Для другого владельца TAB/nameplate используйте PLACEHOLDER_ONLY и внешний PAPI renderer.
Не заявляется универсальная совместимость с любыми packet-плагинами, ручная проверка пикселей,
Folia/Velocity, production TPS/TLS/power-loss или гарантированная доставка событий после crash.
Все ограничения, владельцы и форматы описаны в DISPLAY.md и LUCKPERMS.md.
