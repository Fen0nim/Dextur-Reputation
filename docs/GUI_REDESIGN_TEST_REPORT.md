# Проверка переработки GUI и истории

Дата: 2026-11-07. Версия плагина остаётся 1.0.1. Публичный reputation-api, PlaceholderBridge, расчёты голосования/Trust, SQL-схемы, миграции и lock-файлы не изменены.

## Результат сборки

Выполнены `./gradlew clean build --no-build-cache`, полный `:reputation-storage:integrationTest` и финальный `build :reputation-paper:runtimeProbeJar --no-build-cache` после исправлений.

| Набор | Успешно | Ошибки / пропуски |
| --- | ---: | --- |
| domain | 10 | 0 / 0 |
| application | 95 | 0 / 0 |
| Paper unit | 126 | 0 / 0 |
| JSON/SQLite и общие storage | 348 | 0 / 0 |
| Полный MariaDB/MySQL | 232 | 0 / 0 |
| Всего уникальных тестовых случаев | **811** | **0 / 0** |

SQL-набор выполнен полностью. После исправления делегирования через StorageManager дополнительно повторены два SQL-контракта причин с MariaDB/MySQL; повторные запуски не прибавлены к общему числу уникальных случаев. Финальная обычная сборка содержит 579 тестов. QA probe собирается отдельным JAR и не входит в production JAR.

Добавлено **28 случаев**: 19 Paper, 7 local storage, 2 SQL. Существующие тесты сохранены; ожидания старой геометрии заменены на новый layout. Регрессии покрывают:

- 27 слотов главного меню; адаптивные размеры и отдельные экраны без чужой навигации;
- Back, страницы и сохранение фильтров; роли участников на второй странице аудита;
- имя + UUID администратора и игрока, console, offline cache hit/miss, служебный нулевой UUID, source marker PLAYER;
- UUID профиля без вымышленного ID аудита; полный Unicode-текст причины в технических подробностях;
- русские/английские подписи, канонический ID причины, положительные/отрицательные фильтры, пустую категорию read-модели и архивную причину;
- активные оценки отдельно от событий замены/отзыва/модерации, restart persistence и неизменность snapshot/Trust при чтении;
- повторную проверку прав после очереди, rapid clicks, reload и отсутствие приватных UUID/Trust в публичной истории;
- запрос истории через настоящий StorageManager на JSON/SQLite/MariaDB/MySQL.

## Paper

Финальный production JAR проверен на **Paper 26.2-134, Java 25**, JSON и SQLite, по **два успешных запуска** с рестартом (четыре запуска). Скрипт `scripts/admin-ux-smoke.py` использует три настоящих protocol-клиента: UxAdmin, UxVoter, Fen0nim. Все сценарии используют новый изолированный каталог с EULA consent.

Проверены выбранная голова/27 слотов `/repadmin gui Fen0nim`, отдельные разделы, компактные подтверждения и отмена, force/блокировки, модерация и pending, две страницы административной истории и аудита, имя + UUID участников и console. Публичная история проверена на сохранённых данных: реальные группировки, канонический reason ID, страницы 1/2, Back, RU/EN. Проверены личные recovery/policy экраны, отозванное право кнопки, reload, повторный вход после рестарта и cleanup. Сетевые chat-пакеты подтверждают доставку сообщений автору и получателю.

Оба финальных runtime-отчёта совпадают с SHA-256 production JAR:

`e48c5b9fba512cd16cb2e5f8b83f57b203a253396f1a6f9fb9f60a4bea48a7dc`

Проверка упаковки исключает test/probe классы, server worlds, storage data, конфигурации тестового сервера и случайные credentials из production артефактов. Build artifacts и сырые серверные данные не коммитятся.

Промежуточные неуспешные прогоны не включены в четыре успешных запуска. Они выявили неявную подпись console и пропуск делегирования read-проекции; оба исправлены и покрыты регрессиями. Сырые логи и JSON proofs сохранены вне checkout в `/workspace/.setup/gui-redesign/` (`full-suite.log`, `verified-build.log`, `manager-sql.log`, `paper-JSON-final/ux-proof.json`, `paper-SQLITE-final/ux-proof.json`, `artifact-audit.json`). Последующий clean удалил прежние Gradle XML, поэтому полный SQL-результат дополнительно зафиксирован в сохранённом полном логе.

## Ограничения

Это автоматическая проверка настоящего сервера, inventory events/components и сетевых пакетов. **Ручной визуальный осмотр нативным графическим Minecraft-клиентом не выполнялся.** Offline-имена берутся только из безопасного серверного кэша; при промахе остаётся локализованное неизвестное имя с UUID. Пользовательские тексты и сохранённые свободные причины не переписываются и не переводятся автоматически. JSON-группировка сканирует snapshot на IO worker; для больших историй предпочтителен SQL. Новых inbox, игровых механик, расчётов Trust или миграций нет.

[Полная структура меню, размеры, права и семантика истории](ADMIN_UX.md).

## Изменённые файлы

Всего: 38.

- `README.md`
- `docs/ADMINISTRATION.md`
- `docs/ADMIN_UX.md`
- `docs/AUDIT.md`
- `docs/GUI_REDESIGN_TEST_REPORT.md`
- `docs/VOTING.md`
- `reputation-application/src/main/java/org/dextur/reputation/application/VotingApplication.java`
- `reputation-domain/src/main/java/org/dextur/reputation/domain/ReasonHistory.java`
- `reputation-domain/src/main/java/org/dextur/reputation/domain/VotingRepository.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/AuditIdentity.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/DexturReputationPlugin.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/InvestigationGui.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/MenuGeometry.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/PlayerMessages.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/PolicyCommands.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/PolicyEffects.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/ReasonHistoryGui.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/RecoveryGui.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/VotingCommands.java`
- `reputation-paper/src/main/java/org/dextur/reputation/paper/VotingGui.java`
- `reputation-paper/src/main/resources/lang/en_US.yml`
- `reputation-paper/src/main/resources/lang/ru_RU.yml`
- `reputation-paper/src/runtimeProbe/java/org/dextur/reputation/probe/AdminUxRuntimeProbe.java`
- `reputation-paper/src/runtimeProbe/java/org/dextur/reputation/probe/RuntimeProbe.java`
- `reputation-paper/src/test/java/org/dextur/reputation/paper/AdminGuiUxTest.java`
- `reputation-paper/src/test/java/org/dextur/reputation/paper/AuditIdentityTest.java`
- `reputation-paper/src/test/java/org/dextur/reputation/paper/InvestigationAdaptersTest.java`
- `reputation-paper/src/test/java/org/dextur/reputation/paper/VotingGuiTest.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/InvestigationReports.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/JournalVoting.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/JsonStorage.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/SqlStorage.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/SqlVoting.java`
- `reputation-storage/src/main/java/org/dextur/reputation/storage/StorageManager.java`
- `reputation-storage/src/test/java/org/dextur/reputation/storage/DatabaseContract.java`
- `reputation-storage/src/test/java/org/dextur/reputation/storage/ReasonHistoryContract.java`
- `reputation-storage/src/test/java/org/dextur/reputation/storage/ReasonHistoryTest.java`
- `scripts/admin-ux-smoke.py`
