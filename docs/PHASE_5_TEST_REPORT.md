# Phase 5 — отчёт о проверке

Автоматизированная проверка и финальная runtime-матрица: **PASS**. Проверен только объём Phase 5; ограничения проверки перечислены ниже.

## Исходное состояние и среда

- Репозиторий `Fen0nim/Dextur-Reputation`, development branch `work` / `origin/work`.
- Перед изменениями независимо прочитан remote SHA `2663293824958d7f04950e28f45953d47c7ddc6d`; HEAD совпадал, working tree был чист. Main не использовалась как база и не изменялась.
- Java Temurin **25+36**, Gradle Wrapper **9.7.0**, Paper **26.2 build 134**, Adventure/MiniMessage **5.2.0**.
- SQLite JDBC **3.51.1.0**, реальные Docker MariaDB **11.4.8** и MySQL **8.4.7**.
- Full PlaceholderAPI **2.12.3**, SHA-256 `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437`.
- Full LuckPerms Bukkit **5.5.87**, официальный CI 1674, SHA-256 `09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406`.
- Paper SHA-256 `3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b`.

Сохранены побайтово все **34 прежних файла тестов**, все исторические SQL V1–V4/SQLite V2–V3 и все прежние API source files. **23 class-файла старого публичного API совпадают побайтово** с оригинальным Phase 4 plugin JAR, чей SHA проверен по опубликованному отчёту (`f22d17d...`). Основной JAR не включает QA probe, PlaceholderAPI API или LuckPerms API. Dependency locks/checksums не менялись. Новые классы API добавлены отдельно.

## Полный build

Выполнено успешно из корня checkout:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache
```

Фактический лог содержит дополнительный `--console=plain`: он не меняет цели проверки. Заключительная чистая сборка: **341 tests: 341 PASS, 0 failures, 0 errors, 0 skipped**; включены все **257** прежних и **84** новых. `BUILD SUCCESSFUL`, 36 выполненных Gradle tasks, 2m 44s. XML-сборщик отдельно сопоставил testcase classes с исходными Phase 4 test files и подтвердил все 257 прежних случаев. Затем отдельно собран `:reputation-paper:runtimeProbeJar`; он не попадает в основной плагин.

Ранний полный build с 337 PASS сохранён как историческое свидетельство. После него добавлены четыре enum-policy cases для NONE/PAUSE/RESET_STAGE/CANCEL; production-код и финальные runtime JAR не менялись. Полный follow-up suite с 341 PASS и заключительная чистая сборка с 341 PASS подтвердили итоговое дерево. Основной и CLI JAR заключительной сборки совпадают с runtime-артефактами побайтово; повтор runtime не требуется.

| Suite | PASS | Failures / errors / skipped |
| --- | ---: | --- |
| domain | 10 | 0 / 0 / 0 |
| application | 53 | 0 / 0 / 0 |
| paper | 36 | 0 / 0 / 0 |
| storage — JSON/SQLite/local | 148 | 0 / 0 / 0 |
| storage — real MariaDB/MySQL | 94 | 0 / 0 / 0 |
| **Всего** | **341** | **0 / 0 / 0** |

JSON/SQLite и оба SQL engine выполняют эквивалентные recovery contracts: enrollment, concurrent duplicate starts/rewards, pause/resume/lock, unavailable provider/duplicate/conflicting callback, day/session replay/expiry, frozen definitions, automatic/disabled recovery, completed и **незавершённый** progress через restart. Отключение при startup приостанавливает также offline ACTIVE records; re-enable требует resume.

Проверены точный score threshold, playtime/age boundaries, продолжение после -100 → -99, повторное вступление/cooldown, bounded program/lifetime rewards (в том числе несколько программ и нулевые rewards после исчерпания), Trust credit/reproduction/confirmed penalties, атомарный score/tier и сохранность обычного voting/language после credits. Отдельный тест исключает replay bootstrap tier transition при start/progress.

Activity tests проверяют connected vs eligible seconds, meaningful distance/телепорты, packet/signal spam, timeout, reconnect, monotonic clock regression. Предшествующая вступлению или паузе активность не переигрывается: пересекающий границу interval устанавливает baseline. Login days дедуплицируются; engine tests пересекают реальную границу Europe/Berlin при DST. WAIT использует сохранённые timestamps. Removed/reordered текущие stages не переписывают frozen program.

RU/EN completeness, additive fallback, literal substitutions против MiniMessage injection, permissions, immutable cache-only PAPI и отмена inventory transfer/drag проверены автоматически. Provider handle lifecycle, duplicate submissions, registration collisions, cancellation hint, after-commit events/reward extension, caller cancellation и bounded admission проверены на настоящих локальных backend.

## Отказы, integrity и перенос

- JSON BEFORE_WRITE / BEFORE_RENAME / AFTER_RENAME: диск содержит прежний либо целиком новый reward/profile/Trust/tier snapshot, никогда половину. При неопределённом write backend poisoned; требуется restart/явное recovery, без fallback.
- SQLite trigger и MySQL/MariaDB constraint вызывают rollback награды и полного состояния; повторное чтение равно исходному snapshot. Corrupt indexed revision/payload блокирует startup.
- Checksum, canonical payload, stable IDs, reward sums/caps/audit references, profile/system ledger и Trust credit проверяются при загрузке/переносе. Recomputed checksum не позволяет выдать неправильный progress либо поддельный COMPLETED с незавершёнными stages.
- Все **шесть направлений** JSON ↔ SQLITE ↔ MYSQL проверены отдельно на MariaDB и MySQL, плюс локальные направления JSON ↔ SQLite. Mixed fixtures содержат recovery progress/rewards/audit/receipts, Trust credits, новые version-2 votes с **pending**, tiers и язык. Verification сравнивает весь portable snapshot, а не только totals.
- Прежние тесты interruption/pending/backup/last-step activation сохранены и прошли. Старые данные не удаляет StorageAdmin; SQL-контейнеры QA удаляются тестовым harness отдельно от CLI после сохранения exports/backups.

## Финальные артефакты

| Artifact | SHA-256 |
| --- | --- |
| DexturReputation.jar | `bf958c502e493870f4f6c8ad9382ebf96c872f52170da5557a8431943e436bda` |
| DexturReputation-StorageAdmin.jar | `91184859bcaf876ee838e19734e3dba6da645cd6e6b5749350f189fbedd5bd43` |
| DexturReputationProbe.jar — только QA | `03c016dcb4bcaa03aa09873081eaedbf8fa777a51cf5cdbce264087f7a67eec1` |

Матрица проверяет неизменность этих SHA между сценариями. Основной JAR находится в `reputation-paper/build/libs/DexturReputation.jar`; CLI и QA probe — рядом. Runtime logs, XML counts и доказательство сохранности: `/workspace/.setup/phase5-final-clean-build.log`, `phase5-complete-suite.log`, `phase5-final-test-counts.json`, `phase5-suite-verification.json`, `phase5-preservation-check.json`, `phase5-final-matrix.json`, `phase5-final-runtime-proof.json` и `phase5-final-*-r2/smoke-*.log`. Ранний build: `phase5-verified-build.log`. Предварительные неудачные/исследовательские запуски не включаются в итоговые PASS.

## Paper runtime

Финальная матрица: **16 сценариев, 47 успешных запусков**, последовательно на одних и тех же финальных JAR. Независимый сборщик повторно проверил SHA сохранённых plugin/probe copies, все 47 `PROBE_PASS`, отсутствие `PROBE_FAIL`, снятие сервисов и завершение сохранения миров при shutdown.

| Сценарий | Физическое хранилище | Запуски | Результат |
| --- | --- | ---: | --- |
| Recovery + полный Phase 4 upgrade | JSON | 3 | PASS |
| Recovery + полный Phase 4 upgrade | SQLite | 3 | PASS |
| Recovery + полный Phase 4 upgrade | MariaDB | 3 | PASS |
| Recovery + полный Phase 4 upgrade | MySQL | 3 | PASS |
| Disabled recovery + Phase 4 preservation | JSON | 3 | PASS |
| Phase 4 tiers/display/full LuckPerms + Phase 3 upgrade | JSON / SQLite / MariaDB / MySQL | 12 | PASS |
| Phase 3 protection + настоящий Phase 2 upgrade | JSON / SQLite / MariaDB | 9 | PASS |
| Phase 2 voting | JSON / SQLite / MariaDB | 9 | PASS |
| Неизменённый legacy MYSQL config v1 | MariaDB | 2 | PASS |
| **Всего** | | **47** | **PASS** |

В каждом трёхзапусковом сценарии первые два запуска выполняются без PAPI, третий — с полным PlaceholderAPI: **15** проверок parser/registration, включая **5** проверок всех шести recovery values (четыре физических backend и disabled). Full LuckPerms реально применяет online/offline группы в **12** tiers-запусках; recovery-MySQL также загружает его, сохраняя исходный Phase 4 integration config с выключенной синхронизацией. Подтверждены 12 `PROBE_LUCKPERMS_ONLINE_PASS`, 12 `PROBE_LUCKPERMS_PASS`, 12 `PROBE_TIERS_PASS`, 9 `PROBE_PROTECTION_PASS`, 9 `PROBE_VOTING_PASS`.

Recovery использует настоящих loopback protocol-776 клиентов. Один двигается реальными пакетами; неподвижный участник не получает active-time rewards, затем cancellation проверяет cooldown. Выполняются настоящие Player commands, RU/EN GUI handlers, provider pause/resume, concurrent callback, bounded system rewards, закрытая проверка Trust credits, public voting/tier consistency, restart и полный PAPI parser. QA только в отдельном каталоге уменьшает time thresholds; production defaults проверены отдельно. Принимается уже разрешённая пользователем EULA для локального сервера.

`RecoveryCalendarRuntime` внутри Paper отдельно проверяет два календарных дня, same-day deduplication, backend close/reopen и WAIT resume на изолированном JSON QA-хранилище. Используются контролируемые authoritative timestamps и входные session/activity facts; системные часы и live данные не меняются. Настоящие многосуточные ожидания не выполнялись. На выбранном live backend проверяется реальный login day и active/connected time; на всех четырёх backend календарная модель дополнительно проверена автоматизированно. Full CLI переносит основной recovery dataset и полный реальный Phase 4 снимок с историческими votes/pending/precision/Trust/tier history/hysteresis/ownership/языками.

Календарная проверка имеет 15 PASS markers, включая последующее чтение устойчивого completed state после рестартов. Во всех пяти recovery/disabled сценариях CLI export и сравнение полного прежнего снимка завершились `PHASE4_UPGRADE_PASS` + `PHASE5_CLI_PASS`. Runtime проверяет native inventory handlers, содержимое/локализованные заголовки, permission denial, передачу предметов и безопасный async переход языка; ручная оценка пикселей клиента не выполнялась.

Предварительный runtime r1 остановился на гонке QA: тест открывал GUI до завершения асинхронной смены языка. Probe теперь ожидает committed cached language. Production-код для этой гонки не менялся; вся финальная матрица r2 выполнена заново на одном исправленном probe. Ранее пересекшиеся Gradle-прогоны вызвали конфликт файлов отчёта; окончательные build/test задачи выполнялись последовательно. Эти предварительные сбои не включены в PASS.

## Пределы проверки

**NOT VERIFIED:** ручное pixel rendering всех клиентских GUI/nameplates/TAB; универсальная совместимость с любым сторонним плагином; production TPS/load SLA; физический power loss на всех файловых системах; автоматическое распознавание всех ботов; существующие внешние trade/help/contract/economy/Discord integrations (они не реализуются в этой фазе). Это не утверждения реализованной функциональности.

Локальные offline-mode серверы могут логировать недоступность Mojang publickeys из изолированной сети; online authentication не заявляется проверенной. Серверы слушают только loopback; production deployment не выполнялся.

Ограничения эксплуатации: JSON whole-snapshot rewrite/size cap; SQL per-player recovery history rewrite/growth; bounded receipt/day/tracker/queue limits; возможная потеря несохранённого activity interval при аварии; conservative enrollment/resume boundary; доверенные wall clocks для downtime; local events/extensions без durable delivery. Полные значения и процедуры — [RECOVERY.md](RECOVERY.md), API — [RECOVERY_API.md](RECOVERY_API.md).

Фаза ограничена **Phase 5**. Phase 6, economy и полная Phase 7 не реализованы. Commit/push/независимый remote SHA подтверждаются после финальной проверки в итоговом сообщении и отдельном локальном git-verification artifact.
