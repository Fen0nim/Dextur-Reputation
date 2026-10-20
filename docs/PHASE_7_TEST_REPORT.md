# Phase 7 — отчёт о проверке

Окончательная полная автоматизированная проверка: PASS после исправления подтверждённого mixed legacy/new review дефекта. Окончательная Paper-матрица: **PASS — 26 сценариев /77 запусков**, независимо сверенных по сохранённым native logs, маркерам, shutdown и SHA. **PHASE COMPLETE — VERIFIED**.

## Исходное состояние и среда

Репозиторий `Fen0nim/Dextur-Reputation`, ветка `work`. До изменений local HEAD и независимо прочитанный `origin/work` совпали с подтверждённой Phase 6: `35c7d5e57e9d170ba159569c01f88d65acdf17f6`. Working tree был чист. `main` оставлена на `19ed62dbcca403487b398f3166c52ffcf14360cc`.

Temurin Java **25+36**, Gradle Wrapper **9.7.0**, Paper **26.2 build 134**, Adventure/MiniMessage **5.2.0**, SQLite JDBC **3.51.1.0**, настоящие Docker MariaDB **11.4.8** и MySQL **8.4.7**. Полные PlaceholderAPI **2.12.3** и LuckPerms Bukkit **5.5.87 CI 1674** используются отдельно от compile-only APIs.

Проверены исходные Phase 6 команды/аудиты, voting/pending/Trust/flags, tiers/hysteresis/LP, recovery/provider receipts и policy/admin ledgers. Технический план представлен до изменения кода. Публичные API interfaces не расширялись чувствительными административными методами; private port и application capability не регистрируются как Bukkit services.

## Сохранность

Побайтово сохранены **53** прежних test source files, API sources, SQL **V1–V6**, SQLite historical migrations, dependency locks/checksums. Все **41** class-файл API Phase 6 совпадают с оригинальным main JAR SHA-256 `38e95c8e8965558d260c3f0339eb081f13c51432dec7db4f0a67495f3768087a`. Main JAR не содержит QA probe, PAPI API или LuckPerms API.

Source/test/QA/config/dependency identity зафиксирована для **234 файлов**, отдельно от документации. Предварительные identity records сохранены при исправлении обнаруженных review/QA дефектов; финальная матрица проверяет текущую неизменную identity перед каждым сценарием.

## Полная чистая сборка

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache --console=plain
```

**BUILD SUCCESSFUL, 36 tasks executed, 4m.** Затем отдельно выполнена `:reputation-paper:runtimeProbeJar --no-build-cache`: probe не входит в deployable main JAR. Gradle и Paper runs выполняются последовательно.

**600 PASS, 0 failures, 0 errors, 0 skipped**: все **423 прежних** случая плюс **177 новых**. Сборщик XML отдельно сопоставляет testcase classes со списком Phase 6 test sources.

| Suite | PASS | Failures / errors / skipped |
| --- | ---: | --- |
| Domain | 10 | 0 / 0 / 0 |
| Application | 58 | 0 / 0 / 0 |
| Paper | 65 | 0 / 0 / 0 |
| Local storage JSON/SQLite | 269 | 0 / 0 / 0 |
| Real MariaDB/MySQL | 198 | 0 / 0 / 0 |
| **Всего** | **600** | **0 / 0 / 0** |

## Автоматизированное покрытие

Общий contract выполняет 33 сценария на каждом из четырёх физических backends: inspect и exact contribution breakdown, активные/replaced/pending/legacy votes, invalidate/restore и illegal restore, idempotency/payload conflict, approve/reject/restart, Trust revisions/history, flag resolution, correlation-only relationships, filters/search, dry-run, fractional delta planning, stable bulk cursor/cancel/overlap и старый Phase 3 review → новая restoration.

Concurrency покрывает invalidate + replacement; restore + invalidate; approve + invalidate; set + reputation recalculation; recovery reward + full reconciliation; Trust recalculation + vote; bulk + normal vote; два invalidating moderators; overlapping bulk operations. Owner transactions и profile stripes задают commit order. Approval после зафиксированной invalidation отклоняется; несовпавший GUI revision отклоняется; invalidation старого заменённого голоса не затрагивает новый.

JSON fault points BEFORE_WRITE/BEFORE_RENAME/AFTER_RENAME оставляют целиком предыдущую либо новую generation с profile/moderation/audit/tier. Corrupted format-7 checksum блокирует startup. SQLite trigger и настоящие SQL constraints доказывают rollback mutation/audit/cursor; failed second batch получает PAUSED с прежним cursor и успешно продолжается после снятия fault. Завершённый batch и operation не повторяются.

SQL projection repair проверяется повреждением live profile projection в изолированном QA connection: authoritative weights/journal сохраняются, community/visible восстанавливаются без переписывания голосов. Неверный indexed revision и unsupported Trust model блокируют startup. Retention удаляет только expired DIAGNOSTIC и сохраняет старый important AUDIT. Bounded relations детерминированы и показывают incomplete при исчерпании бюджета.

Application tests проверяют token actor binding, expiry/invalid/single-use, изменившийся plan, isolated listener AssertionError, duplicate-free events и повтор завершённого bulk. Paper adapter tests проверяют отдельные mutation/read permissions, malformed IDs/control characters, inventory movement/spam/owner/session/permission guards, layout limits и matching RU/EN keys с literal MiniMessage substitutions. Новые recovery admin calls сохраняют UUID/CONSOLE вместо изменяемого sender name; прежние actor labels остаются в старых records.

Все шесть offline migration directions проверяются отдельно на настоящих MariaDB и MySQL, с полным mixed dataset: profiles/votes/pending/private Trust/precision/flags, tiers, recovery rewards/receipts/activity, policy/admin, invalidation/restoration audit, resolved false positive и RUNNING bulk metadata. Сравнивается полный snapshot.

## Финальные артефакты

| Файл | SHA-256 |
| --- | --- |
| DexturReputation.jar | `cee708319d3a336f8287056537448a699ec2b596c8072ae28aa9dc48cef59622` |
| DexturReputation-StorageAdmin.jar | `bf77d06824f53f0d144d26181b3cdc34d4feb3d6a49119774606400086362793` |
| DexturReputationProbe.jar — QA only | `67c4447f16115ead4fbd09a1a71d5a976c379fda5413a9d37caafef686b04e7b` |

Расположение: `reputation-paper/build/libs/`; API — `reputation-api/build/libs/reputation-api-0.7.0-SNAPSHOT.jar`. Только main JAR устанавливается в plugins/. CLI выполняется offline, probe используется только для QA.

## Paper runtime

Заключительная r3 матрица: **26 сценариев / 77 успешных запусков** на одинаковых финальных JAR. Результаты прежних r1/r2 не включены.

| Сценарий | Backends | Успешных запусков |
| --- | --- | ---: |
| Phase 7 + полный настоящий Phase 6 upgrade + full LP | JSON / SQLite / MariaDB / MySQL | 12 |
| Phase 7 без LP | JSON | 3 |
| Phase 6 policies/health + Phase 5 upgrade, с/без LP | четыре backends + JSON noLP | 15 |
| Recovery + полный Phase 4 upgrade и disabled recovery | четыре backends + disabled JSON | 15 |
| Tiers/display/full LP + Phase 3 upgrade | четыре backends | 12 |
| Protection + настоящий Phase 2 upgrade | JSON / SQLite / MariaDB | 9 |
| Voting | JSON / SQLite / MariaDB | 9 |
| Legacy MYSQL config v1 | MariaDB | 2 |
| **Всего** | | **77** |

Phase 7 использует три настоящих loopback protocol-776 клиента. Проверяет registered commands/permissions, Player moderator UUID и console actor, native cast → invalidate → restore, pending reject → restore pending → approve, exact −0.70 projection, flag false positive, all recalculation modes, unified audit/Trust history, dry-run без mutation и реальные confirmation/batch workflows. GUI проверяется настоящими inventory events: navigation всех десяти разделов, destructive confirmation, stale rejection, item movement, RU/EN inventories и shutdown cleanup. Known offline inspect/Trust recalculation также выполняются.

Перед рестартом сохраняется отдельный RUNNING bulk с cursor=0. Следующие JVM проверяют, что он не потерян и не возобновился автоматически. Audit IDs не дублируются после restart. Main/probe copies и input SHA сверяются; полный Phase 6 dataset остаётся неизменным, кроме разрешённого LP synchronization ownership/applied metadata.

Третий запуск использует полный PAPI parser. После moderation/recalculation проверяются score, policy и фактический owned health modifier. Реальный online effective LuckPerms group и foreign health modifier проверяются вместе с новыми операциями. Прежние recovery/voting/protection/tiers/health regressions выполняются отдельными scenarios.

Для обеих SQL engines standalone StorageAdmin запускает все шесть направлений отдельно для Phase 7 и прежнего policy workflow. Конечный SQL target открывается новым JVM и экспортируется для полного equality comparison. JSON source bytes и SQL/SQLite source rows сохраняются; backup directories остаются. QA harness удаляет только собственный disposable SQL container после exports, сохраняя filesystem snapshots/worlds/logs/backups.

## Предварительные прогоны и границы

Предварительный JSON pilot прошёл три запуска, PAPI/LP и полный Phase 6 upgrade. Он не заменяет заключительную матрицу финальных артефактов. Первая r1 матрица дополнительно прошла четыре Phase 7 backend cases (12 запусков и 12 standalone CLI directions), затем штатно остановлена между завершёнными scenarios для исправления полного вывода длинного комментария. После отдельного Unicode regression test снова выполнена полная clean build; матрица r2 использовала новые на тот момент SHA и впоследствии была остановлена для mixed-review исправления.

На ранних прогонах исправлены: null Instant serialization фильтров; преждевременные/неверные новые test expectations для SAME_VOTE и approval-after-invalidation; queued Trust timestamp; EPOCH metadata при первом review legacy vote в TIMESTAMP SQL; SQL fault constraint, ошибочно затрагивавший уже существующий audit; fractional dry-run rounding; restoration старых Phase 3 invalidations; полная paginated Trust history и audit before/after statuses. Изменения не ослабляли прежние 423 теста. QA helper schema namespace и GUI locale/offline checks проверены отдельно от production changes.

**NOT VERIFIED:** ручное pixel rendering всех Minecraft клиентов; online authentication в offline-mode loopback QA; универсальная совместимость со сторонними chat/TAB/attribute plugins; production TPS SLA; физический power-loss на произвольных FS; многосерверные writers/Folia/Velocity/Redis. Такие гарантии не заявляются.

Ограничения: single owner, JSON whole-snapshot/64 MiB, растущие private histories/indexed audit copies, configured caps и bounded queues/batches/graphs; durable bulk состоит из атомарных batches, а не общей неограниченной транзакции. External LP/health effects eventually reconciled; local events не durable queue. Исторические отсутствующие actor identities/Trust values не выдумываются. Полные semantics — [ADMINISTRATION](ADMINISTRATION.md), [INVESTIGATION](INVESTIGATION.md), [AUDIT](AUDIT.md).

Доказательства текущей среды находятся в `/workspace/.setup/phase7-verified-clean-build.log`, `phase7-verified-probe-build.log`, `phase7-final-test-counts.json`, `phase7-suite-verification.json`, `phase7-preservation-check.json`, `phase7-final-source-sha256.json`, `phase7-final-matrix.json` и `phase7-final-*/smoke-*.log`. Независимый collector сохранил `phase7-final-runtime-proof.json`: 77 PROBE_PASS, 15 administration, 15 GUI-locales и 15 admin-locales, 5 GUI mutation/offline/PAPI administration, 25 PAPI regressions, 36 online LP markers, 15 recovery/calendar, 12 tiers, 9 protection и 9 voting; PROBE_FAIL и shutdown deadline отсутствуют. Проверены сохранение всех dimensions и STOPPED во всех 77 логах. Все 24 основных standalone CLI direction checks прошли. Итоговый local/remote SHA и clean working tree фиксируются в `phase7-git-verification.json` и сообщаются при завершении задачи; SHA независимо читается через git ls-remote после push.

**STOP after Phase 7. Phase 8 не начиналась.**

Дополнительный контракт MIXED_REVIEW_RESTORE сначала воспроизвёл JSON/SQLite divergence (ACCEPTED против PENDING после old/new review chain). JSON legacy review теперь атомарно обновляет marker так же, как SQL. R2 матрица с прежним JAR штатно остановлена после 20 завершённых scenarios /60 запусков; это предварительные результаты. Финальная r3 матрица использует исправленные JAR.
