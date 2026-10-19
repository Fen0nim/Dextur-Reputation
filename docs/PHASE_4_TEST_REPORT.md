# Phase 4 — отчёт о проверке

Итоговая проверка кода и runtime: **PASS**. 257 автоматизированных тестов и 41 запуск Paper завершились успешно. Все runtime-сценарии использовали один и тот же основной JAR, указанный ниже.

## Среда и исходное состояние

- Репозиторий `Fen0nim/Dextur-Reputation`, ветка `work`.
- Подтверждённый предок Phase 3: `39fe60e1668169c540bab00005dfb45f0ff9a484`.
- Java Temurin **25+36**, Gradle Wrapper **9.7.0**, Paper **26.2 build 134**, Adventure/MiniMessage **5.2.0**.
- SQLite JDBC **3.51.1.0**; реальные Docker MariaDB **11.4.8** и MySQL **8.4.7**.
- Полная PlaceholderAPI **2.12.3**; SHA-256 `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437`.
- Полная LuckPerms Bukkit **5.5.87**, официальный CI build **1674**, API compile-only **5.4**; SHA-256 `09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406`.
- Paper JAR SHA-256 `3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b`.

Использован существующий checkout; изменения не сбрасывались. Все 22 исходных файла тестов Phase 3 сохранены байт-в-байт. SQL V1/V2/V3 и SQLite V2/V3 не изменены. Все 20 существовавших class-файлов публичного API совпадают побайтово со сборкой Phase 3; добавлены только новые типы tiers API. В основной JAR не попали QA probe, PlaceholderAPI API или LuckPerms API.

## Автоматизированная проверка

Успешно выполнено из корня репозитория:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache
```

Итого **257 тестов: 257 PASS, 0 failures, 0 errors, 0 skipped**. Включены все прежние **169** тестов и **88** новых проверок. Полный прогон: 36 исполненных Gradle tasks, `BUILD SUCCESSFUL`.

| Suite | PASS | Failures/errors/skipped |
| --- | ---: | ---: |
| domain | 5 | 0 / 0 / 0 |
| application | 47 | 0 / 0 / 0 |
| paper | 32 | 0 / 0 / 0 |
| storage — JSON/SQLite/local | 103 | 0 / 0 / 0 |
| storage — MariaDB/MySQL integration | 70 | 0 / 0 / 0 |
| **Всего** | **257** | **0 / 0 / 0** |

Проверены все десять положительных/отрицательных порогов, точные границы нейтральной области, большие скачки и смена знака, отключённые/ошибочные tiers, обе границы гистерезиса, перезапуск, смена политики, concurrent votes и дедупликация событий. Контрольная выборка 135 профилей с несколькими scope пересекает границы SQL-пакетов на SQLite/MySQL/MariaDB и проверяет пакетную проекцию JSON.

Vote/replacement/revoke и pending acceptance/invalidation сохраняют score и tier атомарно. Проверены JSON BEFORE_WRITE / BEFORE_RENAME / AFTER_RENAME: последний случай может означать совершённый commit с ошибкой подтверждения, но не половинчатое состояние. Проверены отказ SQL tier update с rollback журнала/профиля, rollback pending review на JSON/SQLite, повреждение tier records, строгие checksum/revision checks, восстановление JSON v4 с quarantine, отказ/повтор permission sync и CAS владения.

StorageAdmin сохраняет полное состояние во всех шести направлениях JSON ↔ SQLite ↔ MYSQL; тесты выполняют эту матрицу отдельно с MariaDB и MySQL. Включены Trust, pending, precision/audit, language, hysteresis/history и sync ownership. Прерывание после импорта сохраняет источник/backup и блокирует активацию целевого backend.

Проверены RU/EN completeness/fallback, literal substitutions против MiniMessage click/hover injection, старые и новые placeholders без синхронного IO, GUI navigation/click protection, старые пользовательские profile icons, прогресс к следующему уровню при удерживаемом tier, уведомления/каналы/cooldown/offline fallback. Отдельные регрессионные тесты воспроизвели и закрыли конфликт принятой другим плагином team прямо перед quit/disable, включая изменение visibility.

## Артефакты

| Файл | SHA-256 |
| --- | --- |
| DexturReputation.jar | `f22d17d992fb5a576a3ec542147ef3c7c8a3af4b1bb7e7a69117caabce599c41` |
| DexturReputation-StorageAdmin.jar | `273bec90a6058cddc3cbbf71637252099ab8d8695391ba3d6fefed13b201fad8` |
| DexturReputationProbe.jar — только QA | `7659ea02079516b220b7baf47b598d21366f203abf055b209eed7f0c97c3ad17` |

Сборка через повторяемый `/workspace/.setup/gradle` и проверенный install script даёт те же три JAR побайтово. Основной JAR устанавливается в Paper; StorageAdmin остаётся отдельным offline CLI.

## Paper runtime

Финальная матрица: **14 сценариев, 41 успешный запуск Paper**, 0 PROBE_FAIL.

| Сценарий | Хранилища / SQL engine | Запусков | Итог |
| --- | --- | ---: | --- |
| Phase 4 + полная LuckPerms | JSON, SQLite, MariaDB, MySQL | 12 | PASS |
| Phase 4 без LuckPerms | JSON, SQLite, MariaDB | 9 | PASS |
| Регрессии голосований Phase 2 | JSON, SQLite, MariaDB | 9 | PASS |
| Регрессии Trust/pending Phase 3 с реальным Phase 2 upgrade | JSON, SQLite, MariaDB | 9 | PASS |
| Прежний flat MYSQL config v1 | MariaDB | 2 | PASS |

Полная PAPI подключалась на третьем запуске каждого из 13 соответствующих сценариев: **13** запусков. Все девять обязательных и два дополнительных tier placeholders проверены через полный PAPI parser в **7** запусках Phase 4. Эффективная LP-группа онлайн и offline downgrade/upgrade проверены в **12** запусках; явная проверка отсутствующей LP — в **9**. В **7** сценариях сохранён полный реальный серверный QA-снимок Phase 3 и подтверждён offline CLI результат.

Локальные доказательства: `/workspace/.setup/phase4-final-build.log`, `phase4-final-test-counts.json`, `phase4-final-matrix.json`, `phase4-final-runtime-proof.json` и `phase4-final-<scenario>/smoke-<run>.log`. XML отчёты находятся в `reputation-*/build/test-results/`. Промежуточные исследовательские запуски не включены в итоговые 41.


Сценарии `--tiers` используют реальные локальные клиенты протокола 776, отдельные QA thresholds 2/4 с margin 1, сохранённую реальную Phase 3 format-3 базу с 37 историческими голосами и pending, а также последующий offline CLI export для SQL. Production thresholds отдельно проверяются автоматизированно. В runtime проверяются:

- positive/negative entry/exit, replacement/revoke, neutral recovery и сохранение удерживаемого tier после restart;
- after-commit events, отсутствие дубликатов при повторном ID/чтении/прогреве;
- foreign scoreboard team, отдельный custom viewer scoreboard, foreign TAB ownership, localized tier/progress GUI;
- player quit и shutdown cleanup, unregister всех трёх public services и PAPI;
- все девять обязательных PAPI значений и два дополнительных через полный PAPI parser;
- эффективная контекстная группа у онлайн-игрока, реальные offline downgrade/upgrade, повтор после отсутствовавшей группы, сохранение staff/donor/uncontextual same-group nodes и primary group;
- startup/голосования/отображение без LuckPerms;
- точное сохранение старых profiles/votes/preferences и private Trust/pairs/scores/assessments/audit/pending; scope metadata продолжает штатную последовательность новых операций.

Старые `--voting`, `--protection`, `--phase2-json-fixture` и `--legacy-mysql` сохраняются. Новый `--database-engine MYSQL|MARIADB` выбирает реальный SQL engine только в QA. Каждый сценарий владеет только собственным временным контейнером; finally удаляет его и его anonymous volume. Production StorageAdmin не удаляет старое хранилище. Логи, миры и storage/backup test data сохраняются; дубликаты pinned Paper runtime можно удалить после завершения сценария для экономии места.

Пример воспроизведения:

```sh
./gradlew :reputation-paper:runtimeProbeJar
JAVA_HOME=/path/to/jdk25 python scripts/paper-smoke.py \
  --accept-eula --tiers --storage-type SQLITE \
  --directory /tmp/dextur-phase4-new-empty \
  --paper-jar /path/to/paper-26.2-134.jar \
  --mojang-cache /path/to/mojang_26.2.jar \
  --placeholderapi-jar /path/to/PlaceholderAPI-2.12.3.jar \
  --luckperms-jar /path/to/LuckPerms-Bukkit-5.5.87.jar
```

EULA принимается только с явным разрешением владельца теста. В этой задаче разрешение пользователя уже было получено.

## Измерения и ограничения

Cached-format контроль: 1000 warm-up и 20 000 форматирований RU/EN; **567 725 071 ns**, среднее **28 386 ns** на форматирование в данном прогоне. Storage не вызывается. Это локальное измерение, не production TPS/SLA benchmark.

Отдельный контроль JSON на 500 профилях: до устранения повторного копирования списка — 398 ms первичная проекция и 212 ms language rewrite; после пакетного построения — 346 ms и 142 ms соответственно. Один прогон включает кодирование/checksum/fsync и не устанавливает статистически подтверждённую latency-гарантию. JSON по-прежнему целиком переписывается и ограничен 64 MiB по умолчанию. SQL startup/migration validation и history payload имеют описанные в STORAGE.md затраты.

Проверено на Linux amd64/local POSIX FS, offline-mode Paper 26.2 и локальных SQL без production TLS. Сообщение Mojang public-key DNS в изолированном offline-mode тесте не является ошибкой DexturReputation; online authentication этим прогоном не проверяется. В первом LP-сценарии отсутствие reputation group создаётся намеренно для проверки deferred retry.

Не заявляются универсальная совместимость со всеми TAB/packet plugins, ручная пиксельная проверка клиентского UI, Folia/Velocity, independent shared writers, production нагрузка/TLS, физическое power-loss поведение или гарантированная доставка notifications через crash/restart. При конфликте используйте PLACEHOLDER_ONLY/API. Managed groups допускают только косметические nodes; последующие произвольные изменения администраторами остаются их ответственностью.

Phase 5 не реализована. Точный Git SHA и независимый результат `git ls-remote origin refs/heads/work` приводятся в итоговом отчёте после commit/push.
