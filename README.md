Ну кароче Placeholders теперь нормальные, плагин наипиздатейший

# DexturReputation 1.0.1

Плагин репутации для **Paper 26.2 / Java 25**. Игроки оставляют оценки с причинами; Trust и anti-abuse определяют допустимость и вес голосов. Репутация задаёт уровни, оформление и настраиваемые политики. Администраторы получают расследования, аудит и безопасные массовые операции.

- Положительные/отрицательные голоса, замена, отзыв, история, pending moderation.
- Приватные Trust и сигналы anti-abuse; признаки корреляции сами по себе не доказывают нарушение.
- Уровни с гистерезисом, GUI, RU/EN, необязательные PlaceholderAPI и LuckPerms.
- Программы восстановления с учётом активности и защитой от повторной выдачи наград.
- Политики ограничений/преимуществ, необязательный адаптер здоровья, admin set/add/remove и voting locks.
- JSON по умолчанию, SQLite или MySQL/MariaDB; offline StorageAdmin для переноса и восстановления.
- Асинхронный публичный API и чтение готовых снимков из кэша.

## Установка

1. Подготовьте Paper 26.2 build 134 и Java 25.
2. Поместите **только `DexturReputation.jar`** в `plugins/` и запустите сервер.
3. Плагин создаст конфигурацию в `plugins/DexturReputation/`. Новая установка использует JSON и русский язык. Существующий flat storage.yml v1 сохраняет MySQL.
4. Настройте правила и права; сделайте backup перед изменением хранилища. Изменения конфигурации требуют штатного перезапуска, кроме `/repadmin reload`, который перечитывает только `features.yml`.

`DexturReputation-StorageAdmin.jar` — отдельный **offline CLI**, не Paper-плагин. `DexturReputationProbe.jar` предназначен исключительно для QA и не устанавливается в production.

JSON подходит для небольших баз: он переписывает полный снимок, имеет стандартный предел 64 MiB и требует локальную файловую систему с атомарной заменой файлов. SQLite удобен для одного сервера с растущей историей. MySQL/MariaDB подходят для внешней БД с управляемыми резервными копиями. Одновременно допускается один владелец хранилища; общей записи с нескольких серверов нет. При ошибке выбранного backend автоматического переключения нет.

## Команды и интеграции

Игрокам: `/rep`, `/like Player reason [comment]`, `/dislike Player reason [comment]`, `/rep history`, `/rep gui`, `/rep recovery`, `/rep policies`, `/rep language en_US`.

Администраторам: `/repadmin help`, `inspect`, `set/add/remove`, `voting`, `pending`, `trust`, `flags`, `audit`, `recalculate`, `bulk` и `gui`. Изменяющие операции имеют отдельные права, причины и аудит. [Полный справочник](docs/COMMANDS.md), [права](docs/PERMISSIONS.md).

PlaceholderAPI и LuckPerms необязательны. PAPI читает готовый кэш, не обращается к БД синхронно и не раскрывает Trust. LuckPerms синхронизирует принадлежащие плагину косметические группы, не меняя primary group. При внешнем владельце TAB используйте PLACEHOLDER_ONLY. DexturCore не требуется. DexturChat-side интеграция не заявлена: предоставлен Reputation-side policy contract.

Для чата используйте plain-text PAPI значения: `%dexturreputation_score%` → `100`, `%dexturreputation_score_signed%` → `+100`, `%dexturreputation_score_bracketed%` → `(+100)`. `%dexturreputation_score_color%` возвращает `green` / `gray` / `red` для положительного / нулевого / отрицательного значения. В DexturChat цвет можно выбирать динамически:

```text
<%dexturreputation_score_color%>(%dexturreputation_score_signed%)</%dexturreputation_score_color%>
```

Эти значения не содержат тегов или цветовых кодов. `reputation_formatted` сохраняет MiniMessage-вывод; `%dexturreputation_reputation%` не зарегистрирован. [Полный список placeholders](docs/DISPLAY.md#placeholderapi--chat).

Админское меню `/repadmin gui <игрок>` занимает 27 слотов и открывает отдельные компактные разделы без повторной навигации. В аудите отображаются роли участников, имена и UUID; публичная история группируется по каноническим причинам с отдельными счётчиками действующих оценок и событий. `/repadmin force <игрок> [on|off|status]` снимает только начальные требования по времени игры/возрасту на сервере; лимиты, блокировки и anti-abuse продолжают действовать. [Меню, права и уведомления](docs/ADMIN_UX.md).

## Документация

[Установка](docs/INSTALLATION.md) · [Конфигурация](docs/CONFIGURATION.md) · [Хранилища и миграции](docs/STORAGE.md) · [Обновление](docs/UPGRADE.md) · [Backup/restore](docs/BACKUP_RESTORE.md) · [Troubleshooting](docs/TROUBLESHOOTING.md)

[Голосование](docs/VOTING.md) · [Trust](docs/TRUST.md) · [Anti-abuse](docs/ANTI_ABUSE.md) · [Уровни](docs/TIERS.md) · [Display](docs/DISPLAY.md) · [LuckPerms](docs/LUCKPERMS.md) · [Recovery](docs/RECOVERY.md) · [Политики](docs/POLICIES.md) · [Здоровье](docs/HEALTH.md)

[Администрирование](docs/ADMINISTRATION.md) · [Расследования](docs/INVESTIGATION.md) · [Аудит](docs/AUDIT.md) · [API](docs/API_DOCUMENTATION.md) · [Recovery API](docs/RECOVERY_API.md) · [Совместимость и ограничения](docs/COMPATIBILITY.md)

## Разработка и выпуск

```sh
./gradlew clean build --no-build-cache
```

Нужны Java 25 и Gradle Wrapper из репозитория; глобальная установка Gradle не нужна. Обычный build выполняет все локальные JSON/SQLite, application и Paper-тесты **без Docker**. Полная проверка выпуска: `./gradlew releaseCheck --no-build-cache`; она дополнительно требует Docker и запускает все тесты MariaDB/MySQL. На Windows используйте `gradlew.bat`. Gradle wrapper, зависимости и verification metadata закреплены. Основные JAR находятся в `reputation-paper/build/libs/`. Публичные 1.x API сохраняют совместимость; операции возвращают CompletionStage, Bukkit-эффекты выполняются на основном потоке.

[Release checklist](docs/RELEASE_CHECKLIST.md) · [Результаты Phase 8](docs/PHASE_8_TEST_REPORT.md) · [Сборка на macOS/Windows/Linux](docs/BUILDING.md) · [Отчёт hotfix 1.0.1](docs/1.0.1_PORTABILITY_TEST_REPORT.md) · [Release notes 1.0.1](docs/RELEASE_NOTES_1.0.1.md). Старые PHASE_*_TEST_REPORT — исторические отчёты, не описание текущей версии. Merge в main, tag и GitHub Release выполняются отдельно после явного разрешения владельца.
