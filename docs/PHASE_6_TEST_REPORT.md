# Phase 6 — отчёт о проверке

Полная автоматизированная проверка и окончательная Paper-матрица: **PASS**. Все обязательные проверки Phase 6 завершены; границы и неподтверждённые внешние сценарии перечислены ниже.

## База, среда и сохранность

- Checkout `Fen0nim/Dextur-Reputation`, branch `work`; до изменений local HEAD и независимо прочитанный `origin/work` совпали: `1e0816bef59cd798ac7fcdd64ab165b485171d3b`. Working tree был чист. `main` (`19ed62dbcca403487b398f3166c52ffcf14360cc`) не изменялась.
- Java Temurin **25+36**, Gradle Wrapper **9.7.0**, Paper **26.2 build 134**, Adventure/MiniMessage **5.2.0**.
- SQLite JDBC **3.51.1.0**, реальные Docker MariaDB **11.4.8** и MySQL **8.4.7**.
- Full PlaceholderAPI **2.12.3**, SHA-256 `fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437`.
- Full LuckPerms Bukkit **5.5.87**, официальный CI 1674, SHA-256 `09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406`.
- Paper SHA-256 `3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b`.

Все **45** прежних test source files, все исторические SQL **V1–V5**/SQLite V2–V3, прежние API sources и dependency locks/checksums сохранены побайтово. Все **34** class-файла прежнего публичного API совпадают с оригинальным Phase 5 main JAR (`bf958c502e493870f4f6c8ad9382ebf96c872f52170da5557a8431943e436bda`). Основной JAR не содержит probe/PAPI API/LuckPerms API. Новые публичные типы добавлены отдельно.

## Заключительная чистая сборка

Выполнено успешно последовательно из checkout:

```sh
JAVA_HOME=/workspace/.setup/tools/jdk-25+36 \
GRADLE_USER_HOME=/workspace/.setup/gradle-home \
./gradlew clean build --no-build-cache
```

Лог использует также `--console=plain`, без изменения целей. **423 PASS, 0 failures, 0 errors, 0 skipped**; все **341** прежних случая и **82** новых. XML-сборщик сопоставляет testcase class names с исходными Phase 5 файлами и отдельно подтверждает все прежние случаи. `BUILD SUCCESSFUL`, **36 tasks executed**, 3m 14s. Затем отдельно собран `:reputation-paper:runtimeProbeJar`; он не входит в deployable plugin.

| Suite | PASS | Failures / errors / skipped |
| --- | ---: | --- |
| domain | 10 | 0 / 0 / 0 |
| application | 58 | 0 / 0 / 0 |
| paper | 48 | 0 / 0 / 0 |
| storage — JSON/SQLite/local | 183 | 0 / 0 / 0 |
| storage — real MariaDB/MySQL | 124 | 0 / 0 / 0 |
| **Всего** | **423** | **0 / 0 / 0** |

После сборки зафиксирована идентичность **207** source/test/QA/config/dependency files, отдельно от редактируемой документации. Изменение этих файлов или JAR требует пересмотра зависимых проверок. Gradle-прогоны против общих build directories не выполнялись параллельно; Paper-сценарии тоже последовательны.

## Что проверяют тесты

Policy configuration: threshold boundaries, deterministic explicit priority, tier/recovery/context conditions, limits/multipliers, unknown/disabled states, immutable metadata, duplicate/invalid definitions, bounds, emergency baseline и полный atomic reload с сохранением прежних rules при invalid candidate. Provider registration/connection lifecycle, collisions, двойной close старого handle после замены, отсутствие rewrite встроенных security policies и cache invalidation проверены отдельно.

Эквивалентные contracts выполняются на JSON, SQLite, MariaDB и MySQL: set positive/negative/zero, signed add, positive remove, точный public target при .35 community precision, сохранность votes/pending/Trust/recovery/languages, UUID/CONSOLE actors/reason, duplicate operation/payload conflict, overflow/bounds, voting lock/unlock/status, FORCE_ALLOW без обхода lock и non-overridable emergency. Recovery после admin mutation сохраняет независимую reward chain; admin после recovery сохраняет прежние награды. Прежний public systemScore остаётся совместимым aggregate non-community.

Concurrency: 24 competing adds, vote + set, reward + set, pending approval + set, lock + vote. Backend serialization и application profile locks определяют порядок commit; нет lost update или половины операции. Время queued запроса нормализуется не раньше committed profile/audit time, поэтому более ранний момент постановки в очередь не отклоняет корректную операцию. Повторный ID не повторяет audit/effect. No-op lock/override аудируется без повторного changed effect.

Faults: JSON BEFORE_WRITE/BEFORE_RENAME/AFTER_RENAME оставляют целиком прежнюю либо новую генерацию profile/admin/tier. Неопределённая запись poison-ит backend без fallback. SQLite trigger и MySQL/MariaDB constraint вызывают rollback profile/ledger/tier; corrupt indexed revision/payload блокирует startup. Snapshot validation проверяет admin audit model/цепочку/aggregate, profile projection и независимые recovery contribution checkpoints. Ошибка внешнего подписчика, включая AssertionError, не превращает committed mutation в failed future и не повреждает cache.

Health/адаптеры: tier target increase/decrease, no stacking, owned cleanup/disabled, foreign ADD_NUMBER/scalar/multiply preservation, безопасный current-health clamp и отказ от небезопасной композиции. Проверены отдельные permissions для каждой mutation и базовое `.use` для policy panel, inventory transfer/drag cancellation, cache-only PAPI и literal RU/EN substitutions без MiniMessage injection.

Storage migration contracts проверяют **все шесть направлений** JSON ↔ SQLITE ↔ MYSQL отдельно на реальных MariaDB и MySQL, включая полный mixed Phase 5 dataset + locks/admin/overrides. Equality охватывает весь portable snapshot, не только totals; прежние interruption/pending/backup/activation tests сохранены и прошли. Источники не удаляет StorageAdmin.

## Финальные артефакты

| Artifact | SHA-256 |
| --- | --- |
| DexturReputation.jar | `38e95c8e8965558d260c3f0339eb081f13c51432dec7db4f0a67495f3768087a` |
| DexturReputation-StorageAdmin.jar | `bea1aab62b64bcf365a05ad82f6ba0045454bd35cfad0d6af0463559dbaff573` |
| DexturReputationProbe.jar — QA only | `6ff24152ae2053d6e459c065723145634ab08c9f3889aa8a71c31897426a3349` |

Пути: `reputation-paper/build/libs/`; API — `reputation-api/build/libs/reputation-api-0.6.0-SNAPSHOT.jar`. Только основной JAR устанавливается в plugins/. CLI offline, probe QA only. Матрица сверяет неизменность SHA между сценариями.

Доказательства текущей среды: `/workspace/.setup/phase6-verified-clean-build.log`, `phase6-verified-probe-build.log`, `phase6-final-test-counts.json`, `phase6-suite-verification.json`, `phase6-preservation-check.json`, `phase6-final-source-sha256.json`, `phase6-final-matrix.json`, `phase6-final-runtime-proof.json`, `phase6-verified-runtime-inputs.json`, `phase6-verified-runtime.log` и `phase6-final-*-r2/smoke-*.log`.

## Paper runtime

Финальный прогон r2 завершён: **21 сценарий, 62 успешных запуска** на неизменных финальных артефактах. Независимый сборщик сверил SHA сохранённых plugin/probe copies, все PROBE_PASS, отсутствие PROBE_FAIL/shutdown deadline и штатное завершение сохранения миров.

| Сценарий | Хранилище | Успешных запусков |
| --- | --- | ---: |
| Phase 6 + реальный полный Phase 5 upgrade + full LuckPerms | JSON / SQLite / MariaDB / MySQL | 12 |
| Phase 6 без LuckPerms + Phase 5 upgrade | JSON | 3 |
| Recovery + полный Phase 4 upgrade | JSON / SQLite / MariaDB / MySQL | 12 |
| Disabled recovery | JSON | 3 |
| Tiers/display/LuckPerms + Phase 3 upgrade | JSON / SQLite / MariaDB / MySQL | 12 |
| Protection + настоящий Phase 2 upgrade | JSON / SQLite / MariaDB | 9 |
| Voting | JSON / SQLite / MariaDB | 9 |
| Legacy MYSQL config v1 | MariaDB | 2 |
| **Всего** | | **62** |

Phase 6 сценарии используют три настоящих loopback protocol-776 клиента и registered command handlers. Проверяются permission denial, native Player administrator UUID, console actor, set/add/remove, обе заблокированные команды like/dislike, unlock и обычный legitimate vote, concurrent vote/admin, duplicate adjustment, known offline lock, override/reset и невозможность FORCE_ALLOW обойти lock. Четыре history entries — настоящие cast/revoke, административные записи их не создают.

Реальные health attributes: foreign +4 сохраняется; отрицательный tier снижает max до 22 и безопасно ограничивает current health; положительный RESPECTED даёт owned +1/max25. Проверяются invalid reload retain-old, disable/remove-own, re-enable без stacking, join/restart, открытый policy GUI на disable и сохранность foreign modifier после disable. Policy GUI RU/EN проверяет server inventory content/title/transfer handlers; отдельный registered admin command capture проверяет обе локали и подстановки. Ручной pixel UI не заявляется.

Третий запуск каждого трёхзапускового сценария использует полный PAPI parser. Phase 6 проверяет policy_vote, policy_chat_send и **фактический** health_modifier; старые PAPI сохраняются. Full LuckPerms проверяет effective group у настоящего online player после administrative tier transition. Restart сверяет точные audit size/operation IDs, admin aggregate/lock и отсутствие replay событий.

Реальный Phase 5 fixture сохраняется полностью: прежние profiles, language, votes, pending, precision, private audit/Trust, recovery programs/rewards/receipts/activity/audit и tier history. С включённым LuckPerms допускается только штатное согласование appliedRevision/ownedGroups; без LP сравнение прежних tier records полное. Runtime CLI export проверяет полный новый portable snapshot.

Для каждого физического SQL engine отдельный QA helper выполняет все **шесть standalone StorageAdmin migrations**. SQL-источники подготавливаются в новых изолированных QA schemas/files; конечный SQL target дополнительно открывается и экспортируется **новым JVM process**, после чего сравнивается весь logical data. Проверяется сохранность исходных JSON bytes и SQL/SQLite source rows. Все source/target/config backups остаются; QA harness удаляет только собственный временный контейнер после exports. `phase6-six-cli-directions.json` содержит отдельное доказательство каждого направления. Обе SQL-матрицы успешно завершены: **12 основных standalone directions checks** (6 на MariaDB и 6 на MySQL), с дополнительными bootstrap/inspection процессами.

Итоговые marker counts: 62 PROBE_PASS; 15 PROBE_POLICIES_PASS; 15 PROBE_ADMIN_LOCALES_PASS; 20 PROBE_PAPI_PASS, включая 5 PROBE_POLICY_PAPI_PASS; 15 PROBE_RECOVERY_PASS/PROBE_RECOVERY_CALENDAR_PASS и 5 PROBE_RECOVERY_PAPI_PASS; 12 PROBE_TIERS_PASS и 4 PROBE_TIER_PAPI_PASS; 24 PROBE_LUCKPERMS_ONLINE_PASS, 12 PROBE_LUCKPERMS_PASS; по 9 PROBE_PROTECTION_PASS/PROBE_VOTING_PASS/PROBE_LEGACY_PHASE2_PASS. Recovery calendar regression использует контролируемые timestamps на изолированном QA sidecar, не физическое многодневное ожидание.

## Предварительные прогоны и ограничения

Они не включаются в финальные PASS. Первый Paper pilot остановился из-за QA reflection getDeclaredField: разрешение приватных типов потребовало отсутствующий optional PAPI class. До этого плагин достиг READY без PAPI. Используется internal method accessor. Второй pilot выявил реальные новые locale templates с неверным `<arg>` вместо существующего `{arg}`: исправлены и добавлен regression test literal RU/EN substitutions. Его history assertion также выявил преждевременный QA unlock: lock 08:30:38.482803, unlock 08:30:38.563862, queued vote commit 08:30:38.607242 UTC. Это голос после unlock; QA теперь держит lock до завершения всех accepted requests и проверяет нулевую history до unlock.

JSON pilot r3 прошёл три запуска. Начальная матрица r1 с предыдущими JAR была штатно остановлена между завершёнными сценариями для добавления callback-Error isolation regression. Уже выполненные старые-artifact cases не подменяют финальную r2 матрицу.

**NOT VERIFIED:** ручное pixel rendering всех клиентов; универсальная совместимость со сторонними attribute/chat/TAB плагинами; production TPS/load SLA; физический power-loss на всех файловых системах; действующий DexturChat adapter (чтение repository вернуло Repository not found); future auction/shop/rewards/support business logic. Эти внешние системы не реализуются в Phase 6. Локальный offline-mode smoke не проверяет online authentication; возможная недоступность Mojang publickeys из изолированной сети не означает сбой репутации.

Ограничения эксплуатации: один writer/owner, JSON whole-snapshot rewrite/64 MiB cap, SQL per-player history rewrite/growth, admin audit 10000 operations, bounded queues/caches/providers, local nondurable events, eventual main-thread health/LP reconciliation. Внешний потребитель делает fresh evaluate и собственные проверки, без распределённой транзакции с DexturReputation. Полные semantics/limits — [POLICIES](POLICIES.md), [ADMIN_ESSENTIALS](ADMIN_ESSENTIALS.md), [HEALTH](HEALTH.md).

Фаза ограничена **Phase 6**. Phase 7 investigation/admin GUI не начинались. Точный commit SHA, независимый remote SHA и clean working tree фиксируются в итоговом сообщении и локальном `/workspace/.setup/phase6-git-verification.json`.
